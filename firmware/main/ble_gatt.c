#include "ble_gatt.h"
#include "frame.h"
#include "esp_log.h"
#include "nvs_flash.h"
#include "freertos/FreeRTOS.h"
#include "freertos/queue.h"
#include "freertos/semphr.h"

/* NimBLE includes */
#include "host/ble_hs.h"
#include "host/ble_gap.h"
#include "host/ble_gatt.h"
#include "host/util/util.h"
#include "services/gap/ble_svc_gap.h"
#include "services/gatt/ble_svc_gatt.h"
#include "nimble/nimble_port.h"
#include "nimble/nimble_port_freertos.h"
#include "store/config/ble_store_config.h"

extern void ble_store_config_init(void);

static const char *TAG = "ble_gatt";

/* ----- UUIDs ----- */
/* NOTE: NimBLE BLE_UUID128_INIT expects UUID bytes in little-endian order
 * (i.e., reversed compared to the standard UUID string representation).
 * Contract service UUID: a0e1c000-5b9d-4c3a-9a39-2f6d8e1b7c00
 * Reversed bytes used below: 00 7c 1b 8e 6d 2f 39 9a 3a 4c 9d 5b 00 c0 e1 a0
 */

static const ble_uuid128_t gatt_svc_uuid =
    BLE_UUID128_INIT(0x00, 0x7C, 0x1B, 0x8E, 0x6D, 0x2F, 0x39, 0x9A,
                     0x3A, 0x4C, 0x9D, 0x5B, 0x00, 0xC0, 0xE1, 0xA0);
static const ble_uuid128_t gatt_ctrl_uuid =
    BLE_UUID128_INIT(0x00, 0x7C, 0x1B, 0x8E, 0x6D, 0x2F, 0x39, 0x9A,
                     0x3A, 0x4C, 0x9D, 0x5B, 0x01, 0xC0, 0xE1, 0xA0);
static const ble_uuid128_t gatt_data_uuid =
    BLE_UUID128_INIT(0x00, 0x7C, 0x1B, 0x8E, 0x6D, 0x2F, 0x39, 0x9A,
                     0x3A, 0x4C, 0x9D, 0x5B, 0x02, 0xC0, 0xE1, 0xA0);
static const ble_uuid128_t gatt_status_uuid =
    BLE_UUID128_INIT(0x00, 0x7C, 0x1B, 0x8E, 0x6D, 0x2F, 0x39, 0x9A,
                     0x3A, 0x4C, 0x9D, 0x5B, 0x03, 0xC0, 0xE1, 0xA0);

/* ----- GATT handles (filled via registration callback) ----- */
static uint16_t ctrl_val_handle;
static uint16_t data_val_handle;
static uint16_t status_val_handle;
static uint16_t conn_handle_current;

/* ----- Shared frame state (protected by semaphore) ----- */
static frame_state_t frame;
static SemaphoreHandle_t frame_mutex;

/* ----- Display task queue ----- */
static QueueHandle_t cmd_queue;

/* ----- STATUS notify state ----- */
static bool status_notify_enabled;

/* ----- Advertising handle ----- */
static uint8_t own_addr_type;

/* ----- File-static converted RAM buffers (BUG A fix) ----- */
static uint8_t s_bw_ram[FRAME_PLANE_SIZE];
static uint8_t s_red_ram[FRAME_PLANE_SIZE];
static uint32_t data_write_count;
static uint32_t data_byte_count;

/* ----- Persisted background frame (REDRAW_BG / COMMIT_BG) ----- */
static uint8_t s_bg_frame[FRAME_TOTAL_SIZE];
static bool s_bg_valid;
#define BG_NVS_NAMESPACE "bg"
#define BG_NVS_KEY       "frame"

/* ----- Skip-if-unchanged: identity of what the panel currently shows.
 * A full 3-color refresh is expensive (~15 s + flashing) and the panel is
 * BWR, so no shortened waveform is allowed (see ARCHITECTURE.md "Design
 * constraints"). Skipping a re-render when the incoming frame is byte-
 * identical to what's already on the panel is safe and free.
 * Guarded by frame_mutex. */
typedef enum {
    LAST_RENDER_NONE = 0,   /* unknown / after boot / after render failure */
    LAST_RENDER_FRAME,      /* s_last_render_crc holds the frame's CRC-32  */
    LAST_RENDER_WHITE,      /* panel is all white (last op was CLEAR)      */
} last_render_kind_t;

static last_render_kind_t s_last_render_kind;
static uint32_t           s_last_render_crc;
static last_render_kind_t s_pending_render_kind;
static uint32_t           s_pending_render_crc;

/* ----- Forward declarations ----- */
static void ble_gatt_set_status(uint8_t code);
static int gap_event_cb(struct ble_gap_event *event, void *arg);
static void start_advertising(void);
static void log_frame_fingerprint(void);
static void handle_commit_success(bool persist_bg, uint32_t frame_crc);
static void ble_gatt_save_bg(void);

/* ================================================================ */
/*  Status helpers                                                   */
/* ================================================================ */

static void ble_gatt_set_status(uint8_t code)
{
    if (status_notify_enabled && status_val_handle != 0) {
        struct os_mbuf *om = ble_hs_mbuf_from_flat(&code, 1);
        if (om) {
            ble_gatts_notify_custom(conn_handle_current, status_val_handle, om);
        }
    }
}

static void ble_gatt_reply_busy(void)
{
    ble_gatt_set_status(FRAME_STATUS_BUSY);
}

/* ================================================================ */
/*  Const accessors for converted RAM buffers                        */
/* ================================================================ */

const uint8_t *ble_gatt_bw_ram(void)
{
    return s_bw_ram;
}

const uint8_t *ble_gatt_red_ram(void)
{
    return s_red_ram;
}

/* ================================================================ */
/*  Persisted background frame (NVS)                                 */
/* ================================================================ */

/* Save s_bg_frame to NVS. Best-effort: logs and returns on failure,
   same as every other error path in this file (no connection teardown). */
static void ble_gatt_save_bg(void)
{
    nvs_handle_t h;
    esp_err_t err = nvs_open(BG_NVS_NAMESPACE, NVS_READWRITE, &h);
    if (err != ESP_OK) {
        ESP_LOGE(TAG, "nvs_open(%s) failed: %s", BG_NVS_NAMESPACE, esp_err_to_name(err));
        return;
    }

    err = nvs_set_blob(h, BG_NVS_KEY, s_bg_frame, sizeof(s_bg_frame));
    if (err != ESP_OK) {
        ESP_LOGE(TAG, "nvs_set_blob failed: %s", esp_err_to_name(err));
    } else {
        err = nvs_commit(h);
        if (err != ESP_OK) {
            ESP_LOGE(TAG, "nvs_commit failed: %s", esp_err_to_name(err));
        } else {
            ESP_LOGI(TAG, "Background frame persisted to NVS");
        }
    }

    nvs_close(h);
}

void ble_gatt_load_bg(void)
{
    nvs_handle_t h;
    esp_err_t err = nvs_open(BG_NVS_NAMESPACE, NVS_READONLY, &h);
    if (err == ESP_ERR_NVS_NOT_FOUND) {
        ESP_LOGI(TAG, "No '%s' NVS namespace yet, no background stored", BG_NVS_NAMESPACE);
        return;
    }
    if (err != ESP_OK) {
        ESP_LOGE(TAG, "nvs_open(%s) failed: %s", BG_NVS_NAMESPACE, esp_err_to_name(err));
        return;
    }

    size_t len = sizeof(s_bg_frame);
    err = nvs_get_blob(h, BG_NVS_KEY, s_bg_frame, &len);
    nvs_close(h);

    if (err == ESP_ERR_NVS_NOT_FOUND) {
        ESP_LOGI(TAG, "No stored background frame yet");
    } else if (err != ESP_OK || len != sizeof(s_bg_frame)) {
        ESP_LOGW(TAG, "Failed to load background frame: %s len=%u",
                 esp_err_to_name(err), (unsigned)len);
    } else {
        s_bg_valid = true;
        ESP_LOGI(TAG, "Loaded background frame from NVS");
    }
}

/* ================================================================ */
/*  BUG B fix: called by display task after epd_render returns       */
/* ================================================================ */

void ble_gatt_render_done(bool ok)
{
    xSemaphoreTake(frame_mutex, portMAX_DELAY);
    frame.busy = false;
    if (ok) {
        s_last_render_kind = s_pending_render_kind;
        s_last_render_crc  = s_pending_render_crc;
    } else {
        /* Panel state after a failed refresh is undefined; force next
         * incoming frame to render, whatever its CRC. */
        s_last_render_kind = LAST_RENDER_NONE;
    }
    xSemaphoreGive(frame_mutex);

    uint8_t code = ok ? FRAME_STATUS_OK : FRAME_STATUS_DISPLAY_ERR;
    ble_gatt_set_status(code);
}

/* ================================================================ */
/*  Shared post-commit path: convert + (optionally) persist as       */
/*  background + dispatch to display task. Called with frame_mutex   */
/*  held; releases it before returning. Used by COMMIT, COMMIT_BG    */
/*  and REDRAW_BG (all of which need "convert whatever is now in     */
/*  frame.data and render it").                                      */
/* ================================================================ */

static void handle_commit_success(bool persist_bg, uint32_t frame_crc)
{
    /* Convert into file-static buffers while holding mutex,
     * before marking busy and posting to display task. */
    frame_convert(&frame, s_bw_ram, s_red_ram);

    if (persist_bg) {
        memcpy(s_bg_frame, frame.data, sizeof(s_bg_frame));
        s_bg_valid = true;
        ble_gatt_save_bg();
    }

    frame.busy = true;
    s_pending_render_kind = LAST_RENDER_FRAME;
    s_pending_render_crc  = frame_crc;

    xSemaphoreGive(frame_mutex);

    ble_gatt_set_status(FRAME_STATUS_BUSY);

    ble_cmd_msg_t msg;
    msg.type = BLE_CMD_RENDER;

    if (xQueueSend(cmd_queue, &msg, pdMS_TO_TICKS(100)) != pdTRUE) {
        ESP_LOGE(TAG, "Queue full, dropping render");
        xSemaphoreTake(frame_mutex, portMAX_DELAY);
        frame.busy = false;
        s_pending_render_kind = LAST_RENDER_NONE;
        xSemaphoreGive(frame_mutex);
        ble_gatt_set_status(FRAME_STATUS_DISPLAY_ERR);
    }
}

/* ================================================================ */
/*  CTRL characteristic: 0x01 BEGIN, 0x02 COMMIT, 0x03 CLEAR,        */
/*  0x04 REDRAW_BG, 0x05 COMMIT_BG                                   */
/* ================================================================ */

static void handle_ctrl_write(struct os_mbuf *om)
{
    uint16_t om_len = OS_MBUF_PKTLEN(om);

    if (om_len < 1) return;

    uint8_t cmd;
    os_mbuf_copydata(om, 0, 1, &cmd);

    xSemaphoreTake(frame_mutex, portMAX_DELAY);

    if (frame.busy) {
        xSemaphoreGive(frame_mutex);
        ble_gatt_reply_busy();
        return;
    }

    int status = FRAME_STATUS_OK;

    switch (cmd) {
    case 0x01: /* BEGIN */
        if (om_len >= 5) {
            uint8_t buf[4];
            os_mbuf_copydata(om, 1, 4, buf);
            uint32_t total_len = (uint32_t)buf[0] |
                                 ((uint32_t)buf[1] << 8) |
                                 ((uint32_t)buf[2] << 16) |
                                 ((uint32_t)buf[3] << 24);
            status = frame_begin(&frame, total_len);
            data_write_count = 0;
            data_byte_count = 0;
            ESP_LOGI(TAG, "BEGIN len=%lu status=%02x",
                     (unsigned long)total_len, (unsigned int)status);
        } else {
            status = FRAME_STATUS_BAD_LEN;
            ESP_LOGW(TAG, "BEGIN write too short (%u bytes, need 5)",
                     (unsigned int)om_len);
        }
        break;

    case 0x02: /* COMMIT */
        if (om_len >= 5) {
            uint8_t buf[4];
            os_mbuf_copydata(om, 1, 4, buf);
            uint32_t crc = (uint32_t)buf[0] |
                          ((uint32_t)buf[1] << 8) |
                          ((uint32_t)buf[2] << 16) |
                          ((uint32_t)buf[3] << 24);
            status = frame_commit(&frame, crc);
            ESP_LOGI(TAG, "COMMIT crc=%08lx status=%02x writes=%lu bytes=%lu",
                     (unsigned long)crc, (unsigned int)status,
                     (unsigned long)data_write_count,
                     (unsigned long)data_byte_count);
            log_frame_fingerprint();
            if (status == FRAME_STATUS_CRC_ERR) {
                uint32_t calc = frame_crc32(frame.data, frame.total_len);
                ESP_LOGE(TAG, "CRC mismatch: app=%08lx device=%08lx",
                         (unsigned long)crc, (unsigned long)calc);
            }

            if (status == FRAME_STATUS_OK) {
                if (s_last_render_kind == LAST_RENDER_FRAME &&
                    s_last_render_crc == crc) {
                    ESP_LOGI(TAG, "COMMIT skipped: frame unchanged (crc=%08lx)",
                             (unsigned long)crc);
                    xSemaphoreGive(frame_mutex);
                    ble_gatt_set_status(FRAME_STATUS_OK);
                    return;
                }
                handle_commit_success(false, crc);
                return;
            }
        } else {
            status = FRAME_STATUS_BAD_LEN;
            ESP_LOGW(TAG, "COMMIT write too short (%u bytes, need 5)",
                     (unsigned int)om_len);
        }
        break;

    case 0x05: /* COMMIT_BG: same as COMMIT, plus persists as background */
        if (om_len >= 5) {
            uint8_t buf[4];
            os_mbuf_copydata(om, 1, 4, buf);
            uint32_t crc = (uint32_t)buf[0] |
                          ((uint32_t)buf[1] << 8) |
                          ((uint32_t)buf[2] << 16) |
                          ((uint32_t)buf[3] << 24);
            status = frame_commit(&frame, crc);
            ESP_LOGI(TAG, "COMMIT_BG crc=%08lx status=%02x writes=%lu bytes=%lu",
                     (unsigned long)crc, (unsigned int)status,
                     (unsigned long)data_write_count,
                     (unsigned long)data_byte_count);
            log_frame_fingerprint();
            if (status == FRAME_STATUS_CRC_ERR) {
                uint32_t calc = frame_crc32(frame.data, frame.total_len);
                ESP_LOGE(TAG, "CRC mismatch: app=%08lx device=%08lx",
                         (unsigned long)crc, (unsigned long)calc);
            }

            if (status == FRAME_STATUS_OK) {
                if (s_last_render_kind == LAST_RENDER_FRAME &&
                    s_last_render_crc == crc) {
                    ESP_LOGI(TAG, "COMMIT_BG skipped: frame unchanged (crc=%08lx), persisting bg only",
                             (unsigned long)crc);
                    /* Panel already shows this frame; just persist it as bg. */
                    memcpy(s_bg_frame, frame.data, sizeof(s_bg_frame));
                    s_bg_valid = true;
                    ble_gatt_save_bg();
                    xSemaphoreGive(frame_mutex);
                    ble_gatt_set_status(FRAME_STATUS_OK);
                    return;
                }
                handle_commit_success(true, crc);
                return;
            }
        } else {
            status = FRAME_STATUS_BAD_LEN;
            ESP_LOGW(TAG, "COMMIT_BG write too short (%u bytes, need 5)",
                     (unsigned int)om_len);
        }
        break;

    case 0x04: /* REDRAW_BG: no payload, re-render the persisted background */
        if (!s_bg_valid) {
            ESP_LOGW(TAG, "REDRAW_BG: no background stored, ignoring");
            break;
        }
        ESP_LOGI(TAG, "REDRAW_BG");
        memcpy(frame.data, s_bg_frame, sizeof(s_bg_frame));
        {
            uint32_t bg_crc = frame_crc32(s_bg_frame, sizeof(s_bg_frame));
            if (s_last_render_kind == LAST_RENDER_FRAME &&
                s_last_render_crc == bg_crc) {
                ESP_LOGI(TAG, "REDRAW_BG skipped: bg already on panel (crc=%08lx)",
                         (unsigned long)bg_crc);
                xSemaphoreGive(frame_mutex);
                ble_gatt_set_status(FRAME_STATUS_OK);
                return;
            }
            handle_commit_success(false, bg_crc);
        }
        return;

    case 0x03: /* CLEAR */
        ESP_LOGI(TAG, "CLEAR");
        {
            if (s_last_render_kind == LAST_RENDER_WHITE) {
                ESP_LOGI(TAG, "CLEAR skipped: panel already white");
                xSemaphoreGive(frame_mutex);
                ble_gatt_set_status(FRAME_STATUS_OK);
                return;
            }

            /* Fill both HAT (B) RAM planes with white. */
            memset(s_bw_ram, 0xFF, sizeof(s_bw_ram));
            memset(s_red_ram, 0xFF, sizeof(s_red_ram));

            frame.busy = true;
            s_pending_render_kind = LAST_RENDER_WHITE;
            s_pending_render_crc  = 0;

            xSemaphoreGive(frame_mutex);

            ble_gatt_set_status(FRAME_STATUS_BUSY);

            ble_cmd_msg_t msg;
            msg.type = BLE_CMD_CLEAR;

            if (xQueueSend(cmd_queue, &msg, pdMS_TO_TICKS(100)) != pdTRUE) {
                ESP_LOGE(TAG, "Queue full, dropping clear");
                xSemaphoreTake(frame_mutex, portMAX_DELAY);
                frame.busy = false;
                s_pending_render_kind = LAST_RENDER_NONE;
                xSemaphoreGive(frame_mutex);
                ble_gatt_set_status(FRAME_STATUS_DISPLAY_ERR);
            }
            return;
        }
        break;

    default:
        status = FRAME_STATUS_OK;
        break;
    }

    xSemaphoreGive(frame_mutex);

    if (status != FRAME_STATUS_OK && status != FRAME_STATUS_BUSY) {
        ble_gatt_set_status((uint8_t)status);
    }
}

/* ================================================================ */
/*  DATA characteristic                                              */
/* ================================================================ */

static void handle_data_write(struct os_mbuf *om)
{
    uint16_t om_len = OS_MBUF_PKTLEN(om);

    if (om_len < 2) return;

    xSemaphoreTake(frame_mutex, portMAX_DELAY);

    if (frame.busy) {
        xSemaphoreGive(frame_mutex);
        ble_gatt_reply_busy();
        return;
    }

    /* Read u16 LE offset */
    uint8_t offbuf[2];
    os_mbuf_copydata(om, 0, 2, offbuf);
    uint16_t offset = (uint16_t)offbuf[0] | ((uint16_t)offbuf[1] << 8);

    uint16_t payload_len = om_len - 2;

    /* Copy payload directly into frame buffer after bounds check */
    if (!frame.begun) {
        xSemaphoreGive(frame_mutex);
        ble_gatt_set_status(FRAME_STATUS_NOT_BEGUN);
        return;
    }
    if ((uint32_t)offset + payload_len > frame.total_len) {
        xSemaphoreGive(frame_mutex);
        ble_gatt_set_status(FRAME_STATUS_BAD_OFF);
        return;
    }
    os_mbuf_copydata(om, 2, payload_len, &frame.data[offset]);
    memset(&frame.received[offset], 1, payload_len);
    data_write_count++;
    data_byte_count += payload_len;

    xSemaphoreGive(frame_mutex);
}

static void log_frame_fingerprint(void)
{
    uint32_t sum = 0;
    for (uint32_t i = 0; i < frame.total_len; ++i) {
        sum += frame.data[i];
    }

    ESP_LOGI(TAG,
             "Frame fingerprint: device_crc=%08lx sum=%08lx first=%02x%02x%02x%02x%02x%02x%02x%02x last=%02x%02x%02x%02x%02x%02x%02x%02x",
             (unsigned long)frame_crc32(frame.data, frame.total_len),
             (unsigned long)sum,
             frame.data[0], frame.data[1], frame.data[2], frame.data[3],
             frame.data[4], frame.data[5], frame.data[6], frame.data[7],
             frame.data[frame.total_len - 8], frame.data[frame.total_len - 7],
             frame.data[frame.total_len - 6], frame.data[frame.total_len - 5],
             frame.data[frame.total_len - 4], frame.data[frame.total_len - 3],
             frame.data[frame.total_len - 2], frame.data[frame.total_len - 1]);
}

/* ================================================================ */
/*  STATUS characteristic                                            */
/* ================================================================ */

static void handle_status_read(struct os_mbuf *om)
{
    uint8_t code;
    xSemaphoreTake(frame_mutex, portMAX_DELAY);
    if (frame.busy) {
        code = FRAME_STATUS_BUSY;
    } else {
        code = FRAME_STATUS_OK;
    }
    xSemaphoreGive(frame_mutex);

    int rc = os_mbuf_append(om, &code, 1);
    if (rc != 0) {
        ESP_LOGE(TAG, "Failed to append status byte to mbuf");
    }
}

/* ================================================================ */
/*  GATT access callback (dispatch by handle)                        */
/* ================================================================ */

static int gatt_svc_access(uint16_t conn_handle, uint16_t attr_handle,
                           struct ble_gatt_access_ctxt *ctxt, void *arg)
{
    (void)arg;

    if (ctxt->op == BLE_GATT_ACCESS_OP_WRITE_CHR) {
        if (attr_handle == ctrl_val_handle) {
            handle_ctrl_write(ctxt->om);
        } else if (attr_handle == data_val_handle) {
            handle_data_write(ctxt->om);
        }
    } else if (ctxt->op == BLE_GATT_ACCESS_OP_READ_CHR) {
        if (attr_handle == status_val_handle) {
            handle_status_read(ctxt->om);
        }
    }

    return 0;
}

/* ================================================================ */
/*  GATT register callback (filled after registration)               */
/* ================================================================ */

static void gatt_register_cb(struct ble_gatt_register_ctxt *ctxt, void *arg)
{
    (void)arg;
    switch (ctxt->op) {
    case BLE_GATT_REGISTER_OP_SVC:
        ESP_LOGI(TAG, "Registered service handle=%d", ctxt->svc.handle);
        break;
    case BLE_GATT_REGISTER_OP_CHR:
        ESP_LOGI(TAG, "Registered characteristic handle=%d", ctxt->chr.def_handle);
        break;
    default:
        break;
    }
}

/* ================================================================ */
/*  GAP event callback                                               */
/* ================================================================ */

static int gap_event_cb(struct ble_gap_event *event, void *arg)
{
    (void)arg;

    switch (event->type) {
    case BLE_GAP_EVENT_CONNECT:
        ESP_LOGI(TAG, "Connected, conn_handle=%d", event->connect.conn_handle);
        if (event->connect.status == 0) {
            conn_handle_current = event->connect.conn_handle;
        } else {
            /* Connection failed, re-advertise */
            start_advertising();
        }
        return 0;

    case BLE_GAP_EVENT_DISCONNECT:
        ESP_LOGI(TAG, "Disconnected, reason=%d", event->disconnect.reason);
        /* Reset state on disconnect */
        xSemaphoreTake(frame_mutex, portMAX_DELAY);
        frame_init(&frame);
        xSemaphoreGive(frame_mutex);
        status_notify_enabled = false;
        conn_handle_current = 0;
        /* Re-advertise */
        start_advertising();
        return 0;

    case BLE_GAP_EVENT_SUBSCRIBE:
        ESP_LOGI(TAG, "Subscribe event; attr_handle=%d cur_notify=%d",
                 event->subscribe.attr_handle,
                 event->subscribe.cur_notify);
        if (event->subscribe.attr_handle == status_val_handle) {
            status_notify_enabled = (event->subscribe.cur_notify != 0);
        }
        return 0;

    case BLE_GAP_EVENT_MTU:
        ESP_LOGI(TAG, "MTU updated: conn_handle=%d mtu=%d",
                 event->mtu.conn_handle, event->mtu.value);
        return 0;

    case BLE_GAP_EVENT_REPEAT_PAIRING:
        /* Delete old bond */
        ble_store_util_delete_oldest_peer();
        return 0;

    default:
        return 0;
    }
}

/* ================================================================ */
/*  Start advertising                                                */
/* ================================================================ */

static void start_advertising(void)
{
    struct ble_gap_adv_params adv_params;
    struct ble_hs_adv_fields fields;
    struct ble_hs_adv_fields rsp_fields;
    const char *name;
    int rc;

    memset(&fields, 0, sizeof(fields));
    memset(&rsp_fields, 0, sizeof(rsp_fields));

    /* Flags: general discoverable, BR/EDR not supported */
    fields.flags = BLE_HS_ADV_F_DISC_GEN | BLE_HS_ADV_F_BREDR_UNSUP;

    /* Keep the 128-bit service UUID in the primary advertisement so Android can
     * scan by UUID. The complete name is too large to fit in the same legacy
     * advertising packet, so it goes in the scan response below. */
    fields.uuids128 = &gatt_svc_uuid;
    fields.num_uuids128 = 1;
    fields.uuids128_is_complete = 1;

    name = ble_svc_gap_device_name();
    rsp_fields.name = (void *)name;
    rsp_fields.name_len = strlen(name);
    rsp_fields.name_is_complete = 1;

    rc = ble_gap_adv_set_fields(&fields);
    if (rc != 0) {
        ESP_LOGE(TAG, "ble_gap_adv_set_fields failed: rc=%d", rc);
        return;
    }

    rc = ble_gap_adv_rsp_set_fields(&rsp_fields);
    if (rc != 0) {
        ESP_LOGE(TAG, "ble_gap_adv_rsp_set_fields failed: rc=%d", rc);
        return;
    }

    memset(&adv_params, 0, sizeof(adv_params));
    adv_params.conn_mode = BLE_GAP_CONN_MODE_UND;
    adv_params.disc_mode = BLE_GAP_DISC_MODE_GEN;

    rc = ble_gap_adv_start(own_addr_type, NULL, BLE_HS_FOREVER,
                           &adv_params, gap_event_cb, NULL);
    if (rc != 0) {
        ESP_LOGE(TAG, "ble_gap_adv_start failed: rc=%d", rc);
        return;
    }
    ESP_LOGI(TAG, "Advertising started as '%s'", name);
}

/* ================================================================ */
/*  NimBLE host-sync callback                                        */
/* ================================================================ */
/* ================================================================ */

static void on_sync(void)
{
    int rc;

    /* Determine address type */
    rc = ble_hs_util_ensure_addr(0);
    assert(rc == 0);
    rc = ble_hs_id_infer_auto(0, &own_addr_type);
    assert(rc == 0);

    /* Start advertising */
    start_advertising();
}

/* ================================================================ */
/*  NimBLE host task                                                 */
/* ================================================================ */

static void nimble_host_task(void *param)
{
    (void)param;
    ESP_LOGI(TAG, "NimBLE host task started");
    nimble_port_run();
    ESP_LOGI(TAG, "NimBLE host task ended");
}

/* ================================================================ */
/*  Init GATT service array                                          */
/* ================================================================ */

static const struct ble_gatt_svc_def gatt_svcs[] = {
    {
        .type = BLE_GATT_SVC_TYPE_PRIMARY,
        .uuid = &gatt_svc_uuid.u,
        .characteristics = (struct ble_gatt_chr_def[]) {
            {
                .uuid = &gatt_ctrl_uuid.u,
                .access_cb = gatt_svc_access,
                .flags = BLE_GATT_CHR_F_WRITE,
                .val_handle = &ctrl_val_handle,
            },
            {
                .uuid = &gatt_data_uuid.u,
                .access_cb = gatt_svc_access,
                .flags = BLE_GATT_CHR_F_WRITE,
                .val_handle = &data_val_handle,
            },
            {
                .uuid = &gatt_status_uuid.u,
                .access_cb = gatt_svc_access,
                .flags = BLE_GATT_CHR_F_READ | BLE_GATT_CHR_F_NOTIFY,
                .val_handle = &status_val_handle,
            },
            { 0 }
        },
    },
    { 0 }
};

/* ================================================================ */
/*  Public: initialise everything, return the display-task queue     */
/* ================================================================ */

void *ble_gatt_init(void)
{
    int rc;

    ESP_LOGI(TAG, "Initialising BLE GATT");

    /* Create frame mutex */
    frame_mutex = xSemaphoreCreateMutex();
    assert(frame_mutex != NULL);

    /* Create command queue for display task (BUG A fix: sizeof(ble_cmd_msg_t) is now just the type) */
    cmd_queue = xQueueCreate(4, sizeof(ble_cmd_msg_t));
    assert(cmd_queue != NULL);

    /* Init frame state */
    frame_init(&frame);
    status_notify_enabled = false;
    conn_handle_current = 0;

    /* Initialise NimBLE */
    nimble_port_init();

    /* Configure NimBLE host */
    ble_hs_cfg.sync_cb = on_sync;
    ble_hs_cfg.gatts_register_cb = gatt_register_cb;
    ble_hs_cfg.store_status_cb = ble_store_util_status_rr;

    /* Set preferred MTU */
    ble_att_set_preferred_mtu(247);

    /* Init GAP and GATT services */
    ble_svc_gap_init();
    ble_svc_gatt_init();

    /* Set device name */
    rc = ble_svc_gap_device_name_set("EPD-Keychain");
    assert(rc == 0);

    /* Register our custom GATT service */
    rc = ble_gatts_count_cfg(gatt_svcs);
    assert(rc == 0);

    rc = ble_gatts_add_svcs(gatt_svcs);
    assert(rc == 0);

    /* Init BLE store config */
    ble_store_config_init();

    ESP_LOGI(TAG, "BLE GATT initialisation complete");

    /* Start NimBLE host task */
    nimble_port_freertos_init(nimble_host_task);

    return (void *)cmd_queue;
}

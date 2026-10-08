#ifndef BLE_GATT_H
#define BLE_GATT_H

#include <stdint.h>
#include <stdbool.h>

/* Queue message types for the display task */
typedef enum {
    BLE_CMD_RENDER,  /* render the current frame */
    BLE_CMD_CLEAR,   /* render all-white */
} ble_cmd_type_t;

/* Message posted from GATT handlers to the display task */
typedef struct {
    ble_cmd_type_t type;
} ble_cmd_msg_t;

/* Initialise NimBLE stack, GATT service, start advertising.
   Returns the FreeRTOS queue handle for the display task. */
void *ble_gatt_init(void);

/* Called by display task after epd_render returns.
   Clears busy flag and notifies status. */
void ble_gatt_render_done(bool ok);

/* Const accessors for the file-static converted RAM buffers.
   Valid until the next render/clear command. */
const uint8_t *ble_gatt_bw_ram(void);
const uint8_t *ble_gatt_red_ram(void);

/* Preload the persisted background frame (if any) from NVS into the
   file-static s_bg_frame buffer. Call once at boot, near nvs_flash_init().
   Leaves the background unset (not an error) if none was ever saved. */
void ble_gatt_load_bg(void);

#endif /* BLE_GATT_H */
#include <stdio.h>
#include "freertos/FreeRTOS.h"
#include "freertos/task.h"
#include "freertos/queue.h"
#include "esp_log.h"
#include "nvs_flash.h"
#include "esp_system.h"

#include "ble_gatt.h"
#include "epd.h"
#include "frame.h"

static const char *TAG = "main";

/* ================================================================ */
/*  Display task: consumes render/clear commands from BLE            */
/* ================================================================ */

static void display_task(void *param)
{
    QueueHandle_t queue = (QueueHandle_t)param;
    ESP_LOGI(TAG, "Display task started");

    while (1) {
        ble_cmd_msg_t msg;
        if (xQueueReceive(queue, &msg, portMAX_DELAY) == pdTRUE) {
            bool success = false;

            ESP_LOGI(TAG, "Display: got cmd type=%d", (int)msg.type);

            /* Both RENDER and CLEAR use epd_render with the
             * file-static buffers exposed via const accessors. */
            success = epd_render(ble_gatt_bw_ram(), ble_gatt_red_ram());

            ESP_LOGI(TAG, "Display: render %s", success ? "OK" : "FAILED");

            /* BUG B fix: clear busy + notify status */
            ble_gatt_render_done(success);
        }
    }
}

/* ================================================================ */
/*  App main                                                         */
/* ================================================================ */

void app_main(void)
{
    ESP_LOGI(TAG, "EPD Keychain firmware starting");

    /* Init NVS */
    esp_err_t ret = nvs_flash_init();
    if (ret == ESP_ERR_NVS_NO_FREE_PAGES ||
        ret == ESP_ERR_NVS_NEW_VERSION_FOUND) {
        ESP_ERROR_CHECK(nvs_flash_erase());
        ret = nvs_flash_init();
    }
    ESP_ERROR_CHECK(ret);

    /* Preload persisted background frame (if any) for REDRAW_BG */
    ble_gatt_load_bg();

    /* Init display hardware */
    epd_init();

    /* Init BLE (returns display task queue) */
    QueueHandle_t queue = (QueueHandle_t)ble_gatt_init();

    /* Start display task */
    xTaskCreate(display_task, "display", 8192,
                (void *)queue, 5, NULL);

    ESP_LOGI(TAG, "BLE photo receiver ready");

    /* Main task has nothing more to do */
    while (1) {
        vTaskDelay(pdMS_TO_TICKS(10000));
    }
}

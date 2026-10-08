#include "epd.h"
#include "frame.h"

#include <string.h>

#include "driver/gpio.h"
#include "driver/spi_master.h"
#include "esp_err.h"
#include "esp_log.h"
#include "freertos/FreeRTOS.h"
#include "freertos/task.h"

static const char *TAG = "epd";

/* XIAO ESP32-S3 labels, matching the verified Arduino V4 sketch:
 * DIN=D10/GPIO9, CLK=D9/GPIO8, CS=D8/GPIO7, DC=D4/GPIO5,
 * RST=D3/GPIO4, BUSY=D2/GPIO3 (active HIGH while the panel is busy). */
#define EPD_MOSI       GPIO_NUM_9
#define EPD_SCLK       GPIO_NUM_8
#define EPD_CS         GPIO_NUM_7
#define EPD_DC         GPIO_NUM_5
#define EPD_RST        GPIO_NUM_4
#define EPD_BUSY       GPIO_NUM_3
#define EPD_SPI_HOST   SPI2_HOST
#define EPD_SPI_HZ     2000000
#define EPD_TIMEOUT_MS 40000 /* matches ARCHITECTURE.md's documented refresh budget (~15-20s typical, 40s error cutoff); a cold panel refreshes slower, so this must not be tighter than the doc's promised margin */

static spi_device_handle_t s_spi;

static bool epd_wait_until_idle(const char *stage, uint32_t timeout_ms)
{
    const TickType_t started = xTaskGetTickCount();

    while (gpio_get_level(EPD_BUSY) == 1) {
        if ((xTaskGetTickCount() - started) >= pdMS_TO_TICKS(timeout_ms)) {
            ESP_LOGE(TAG, "BUSY timeout during %s", stage);
            return false;
        }
        vTaskDelay(pdMS_TO_TICKS(50));
    }

    /* The V4 reference waits briefly after BUSY falls before the next command. */
    vTaskDelay(pdMS_TO_TICKS(200));
    ESP_LOGI(TAG, "Ready after %s (%lu ms)", stage,
             (unsigned long)((xTaskGetTickCount() - started) * portTICK_PERIOD_MS));
    return true;
}

static bool epd_send_byte(bool is_data, uint8_t value)
{
    spi_transaction_t transaction = {
        .length = 8,
        .tx_buffer = &value,
    };

    gpio_set_level(EPD_DC, is_data ? 1 : 0);
    /* One transaction per byte deliberately matches the working Arduino
     * sequence, including a CS high interval between every transferred byte. */
    esp_err_t err = spi_device_transmit(s_spi, &transaction);
    if (err != ESP_OK) {
        ESP_LOGE(TAG, "SPI transfer failed: %s", esp_err_to_name(err));
        return false;
    }
    return true;
}

static bool epd_send_cmd(uint8_t command)
{
    return epd_send_byte(false, command);
}

static bool epd_send_data(uint8_t data)
{
    return epd_send_byte(true, data);
}

static bool epd_send_buffer(const uint8_t *buffer, size_t length)
{
    for (size_t i = 0; i < length; ++i) {
        if (!epd_send_data(buffer[i])) {
            return false;
        }
    }
    return true;
}

static bool epd_reset(void)
{
    gpio_set_level(EPD_RST, 1);
    vTaskDelay(pdMS_TO_TICKS(20));
    gpio_set_level(EPD_RST, 0);
    vTaskDelay(pdMS_TO_TICKS(2));
    gpio_set_level(EPD_RST, 1);
    vTaskDelay(pdMS_TO_TICKS(20));

    ESP_LOGI(TAG, "BUSY after reset: %d", gpio_get_level(EPD_BUSY));
    return epd_wait_until_idle("hardware reset", EPD_TIMEOUT_MS);
}

void epd_init(void)
{
    const gpio_config_t output_config = {
        .pin_bit_mask = (1ULL << EPD_DC) | (1ULL << EPD_RST),
        .mode = GPIO_MODE_OUTPUT,
        .pull_up_en = GPIO_PULLUP_DISABLE,
        .pull_down_en = GPIO_PULLDOWN_DISABLE,
        .intr_type = GPIO_INTR_DISABLE,
    };
    const gpio_config_t busy_config = {
        .pin_bit_mask = 1ULL << EPD_BUSY,
        .mode = GPIO_MODE_INPUT,
        .pull_up_en = GPIO_PULLUP_DISABLE,
        .pull_down_en = GPIO_PULLDOWN_DISABLE,
        .intr_type = GPIO_INTR_DISABLE,
    };
    const spi_bus_config_t bus_config = {
        .mosi_io_num = EPD_MOSI,
        .miso_io_num = -1,
        .sclk_io_num = EPD_SCLK,
        .quadwp_io_num = -1,
        .quadhd_io_num = -1,
        .max_transfer_sz = 1,
    };
    const spi_device_interface_config_t device_config = {
        .clock_speed_hz = EPD_SPI_HZ,
        .mode = 0,
        .spics_io_num = EPD_CS,
        .queue_size = 1,
    };

    ESP_LOGI(TAG, "Initialising V4 EPD: 122x250, SPI mode 0 at 2 MHz");
    ESP_ERROR_CHECK(gpio_config(&output_config));
    ESP_ERROR_CHECK(gpio_config(&busy_config));
    gpio_set_level(EPD_DC, 0);
    gpio_set_level(EPD_RST, 1);

    ESP_ERROR_CHECK(spi_bus_initialize(EPD_SPI_HOST, &bus_config, SPI_DMA_DISABLED));
    ESP_ERROR_CHECK(spi_bus_add_device(EPD_SPI_HOST, &device_config, &s_spi));
}

bool epd_render(const uint8_t *bw_ram, const uint8_t *red_ram)
{
    if (bw_ram == NULL || red_ram == NULL) {
        ESP_LOGE(TAG, "Refusing to render a null frame buffer");
        return false;
    }

    ESP_LOGI(TAG, "Starting V4 render sequence");
    if (!epd_reset()) {
        return false;
    }

    if (!epd_send_cmd(0x12) || !epd_wait_until_idle("software reset", EPD_TIMEOUT_MS) ||
        !epd_send_cmd(0x01) || !epd_send_data(0xF9) || !epd_send_data(0x00) ||
        !epd_send_data(0x00) || !epd_send_cmd(0x11) || !epd_send_data(0x03) ||
        !epd_send_cmd(0x44) || !epd_send_data(0x00) || !epd_send_data(0x0F) ||
        !epd_send_cmd(0x45) || !epd_send_data(0x00) || !epd_send_data(0x00) ||
        !epd_send_data(0xF9) || !epd_send_data(0x00) || !epd_send_cmd(0x4E) ||
        !epd_send_data(0x00) || !epd_send_cmd(0x4F) || !epd_send_data(0x00) ||
        !epd_send_data(0x00) || !epd_send_cmd(0x3C) || !epd_send_data(0x05) ||
        !epd_send_cmd(0x18) || !epd_send_data(0x80) || !epd_send_cmd(0x21) ||
        !epd_send_data(0x80) || !epd_send_data(0x80) ||
        !epd_wait_until_idle("initialization", EPD_TIMEOUT_MS)) {
        return false;
    }

    /* Write the same two RAM planes as the verified V4 sketch. */
    if (!epd_send_cmd(0x24) || !epd_send_buffer(bw_ram, FRAME_PLANE_SIZE) ||
        !epd_send_cmd(0x26) || !epd_send_buffer(red_ram, FRAME_PLANE_SIZE) ||
        !epd_send_cmd(0x20)) {
        return false;
    }
    if (!epd_wait_until_idle("refresh", EPD_TIMEOUT_MS)) {
        return false;
    }

    if (!epd_send_cmd(0x10) || !epd_send_data(0x01)) {
        return false;
    }
    vTaskDelay(pdMS_TO_TICKS(100));
    ESP_LOGI(TAG, "Render complete");
    return true;
}

bool epd_clear(void)
{
    static uint8_t white_bw[FRAME_PLANE_SIZE];
    static uint8_t no_red[FRAME_PLANE_SIZE];

    memset(white_bw, 0xFF, sizeof(white_bw));
    memset(no_red, 0xFF, sizeof(no_red));
    return epd_render(white_bw, no_red);
}

void epd_sleep(void)
{
    /* epd_render already puts the V4 into deep sleep after every refresh. */
}

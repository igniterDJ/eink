#ifndef EPD_HDR_H
#define EPD_HDR_H

#include <stdint.h>
#include <stdbool.h>

/* Waveshare 2.13-inch e-Paper HAT (B) V4: 122 x 250 pixels. */
#define EPD_W  122
#define EPD_H  250

/* Initialise the display hardware (SPI, GPIOs). Call once at boot. */
void epd_init(void);

/* Render a full frame (two 4000-byte black/red planes).
   Returns true on success, false on timeout/error. */
bool epd_render(const uint8_t *bw_ram, const uint8_t *red_ram);

/* Render an all-white screen. Returns true on success. */
bool epd_clear(void);

/* Put the display into deep sleep (called after every render). */
void epd_sleep(void);

#endif /* EPD_HDR_H */

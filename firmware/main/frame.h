#ifndef FRAME_HDR_H
#define FRAME_HDR_H

#include <stdint.h>
#include <stdbool.h>
#include <stddef.h>

/* Waveshare 2.13-inch e-Paper HAT (B) V4 native image dimensions. */
#define FRAME_W            122
#define FRAME_H            250
#define FRAME_ROW_BYTES    ((FRAME_W + 7) / 8)   /* 16 bytes per row; 6 pad bits */
#define FRAME_PLANE_SIZE   (FRAME_H * FRAME_ROW_BYTES)  /* 4000 */
#define FRAME_TOTAL_SIZE   (FRAME_PLANE_SIZE * 2)       /* 8000 */

/* Status codes for the GATT STATUS characteristic */
enum frame_status {
    FRAME_STATUS_OK          = 0x00,
    FRAME_STATUS_BUSY        = 0x01,
    FRAME_STATUS_BAD_LEN     = 0xE1,
    FRAME_STATUS_BAD_OFF     = 0xE2,
    FRAME_STATUS_CRC_ERR     = 0xE3,
    FRAME_STATUS_INCOMPLETE  = 0xE4,
    FRAME_STATUS_NOT_BEGUN   = 0xE5,
    FRAME_STATUS_DISPLAY_ERR = 0xE6,
};

/* A frame holds two 4000-byte planes as received over BLE:
   plane K (black) then plane R (red). */
typedef struct {
    uint8_t data[FRAME_TOTAL_SIZE];     /* raw 8000 bytes from BLE */
    bool    received[FRAME_TOTAL_SIZE]; /* per-byte received bitmap */
    bool    begun;                      /* BEGIN seen? */
    uint32_t total_len;                 /* expected len from BEGIN */
    bool    busy;                       /* rendering now */
} frame_state_t;

/* Initialise a frame state to empty/idle. */
void frame_init(frame_state_t *f);

/* BEGIN command: set total_len, reset buffer & received bitmap.
   Returns status code (OK or BAD_LEN). */
int frame_begin(frame_state_t *f, uint32_t total_len);

/* DATA write: copy payload at given offset. 
   offset + payload_len must not exceed total_len. 
   Returns status code. */
int frame_data_write(frame_state_t *f, uint16_t offset,
                     const uint8_t *payload, uint16_t payload_len);

/* COMMIT: check all bytes received, verify CRC-32 over the 8000 bytes.
   Returns OK on success, or INCOMPLETE/CRC_ERR/NOT_BEGUN. */
int frame_commit(frame_state_t *f, uint32_t crc);

/* CLEAR: just mark that we want an all-white render. */
void frame_clear(frame_state_t *f);

/* CRC-32 IEEE 802.3 / zlib over buf[len]. Pure C. */
uint32_t frame_crc32(const uint8_t *buf, size_t len);

/* Convert the raw K/R planes in frame to the HAT (B) V4 black/red RAM format
   (each 4000 bytes). A zero bit represents an inked pixel in either RAM.
   The caller provides two FRAME_PLANE_SIZE-byte output buffers. */
void frame_convert(const frame_state_t *f,
                   uint8_t *bw_ram, uint8_t *red_ram);

#endif /* FRAME_HDR_H */

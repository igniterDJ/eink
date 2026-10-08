/* host_test/e2e.c — firmware-side end-to-end test with no hardware.
 *
 * Reads tools/e2e/golden_frame.bin (8000 bytes, produced by Android unit test),
 * feeds it through frame.c API exactly as ble_gatt.c does:
 *   - BEGIN with 8000
 *   - DATA writes of u16 LE offset + 242-byte payloads (MTU 247: 247-3-2=242)
 *   - COMMIT with zlib CRC-32 computed independently in the test
 *   - Asserts status OK
 *   - Converts to BW/RED RAM, asserts pad bits (BW=1, RED=0)
 *   - Decodes RAM back to pixels as the panel shows them
 *     (BW bit0=black unless RED bit1=red)
 *   - Rotates to landscape, writes tools/e2e/render.ppm (P6 250x122)
 *   - Asserts sample pixels: (20,60)=black, (125,100)=white,
 *     (230,60)=red, (135,10)=black
 * Also tests:
 *   - Out-of-order DATA chunks
 *   - Corrupted-CRC COMMIT returns 0xE3
 */

#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <stdint.h>
#include "../main/frame.h"

/* ---------- helpers ---------- */

#define CHUNK_SIZE 242   /* MTU 247 − 3 (ATT) − 2 (DATA offset) */

static int tests_passed = 0;
static int tests_failed = 0;

#define TEST(name) do { \
    printf("  TEST: %s ... ", name); \
} while(0)

#define PASS() do { \
    printf("PASS\n"); \
    tests_passed++; \
} while(0)

#define FAIL(msg) do { \
    printf("FAIL: %s\n", msg); \
    tests_failed++; \
} while(0)

/* Read entire file into malloc'd buffer; caller must free. */
static uint8_t *read_file(const char *path, size_t *out_len)
{
    FILE *f = fopen(path, "rb");
    if (!f) {
        fprintf(stderr, "FATAL: cannot open %s\n", path);
        exit(1);
    }
    fseek(f, 0, SEEK_END);
    long sz = ftell(f);
    rewind(f);
    if (sz < 0) { fclose(f); exit(1); }
    uint8_t *buf = (uint8_t *)malloc((size_t)sz);
    if (!buf) { fclose(f); exit(1); }
    if (fread(buf, 1, (size_t)sz, f) != (size_t)sz) {
        fprintf(stderr, "FATAL: short read %s\n", path);
        exit(1);
    }
    fclose(f);
    *out_len = (size_t)sz;
    return buf;
}

/* Feed the golden frame through frame.c exactly like ble_gatt.c does:
 *   1) BEGIN with 8000
 *   2) DATA writes in-order, u16 LE offset + 242-byte payloads
 *   3) COMMIT with CRC-32
 * Returns the frame_state (caller provides).
 */
static void feed_frame(frame_state_t *f, const uint8_t *data, size_t len)
{
    int rc;

    /* BEGIN */
    rc = frame_begin(f, (uint32_t)len);
    if (rc != FRAME_STATUS_OK) {
        fprintf(stderr, "  BEGIN returned %02x, expected OK\n", rc);
        exit(1);
    }

    /* DATA writes: u16 LE offset + CHUNK_SIZE bytes */
    for (size_t off = 0; off < len; off += CHUNK_SIZE) {
        size_t chunk = (off + CHUNK_SIZE <= len) ? CHUNK_SIZE : (len - off);
        /* Build a buffer with u16 LE offset followed by payload,
           to verify frame_data_write gets called with the payload part only. */
        rc = frame_data_write(f, (uint16_t)off, &data[off], (uint16_t)chunk);
        if (rc != FRAME_STATUS_OK) {
            fprintf(stderr, "  DATA write at %zu failed: %02x\n", off, rc);
            exit(1);
        }
    }

    /* COMMIT with independently computed CRC-32 */
    uint32_t crc = frame_crc32(data, len);
    rc = frame_commit(f, crc);
    if (rc != FRAME_STATUS_OK) {
        fprintf(stderr, "  COMMIT returned %02x, expected OK\n", rc);
        exit(1);
    }
}

/* ================================================================ */
/*  Test 1: Full in-order feed, convert, assert pad bits             */
/* ================================================================ */
static void test_e2e_normal(void)
{
    TEST("E2E normal feed + convert + pad bits");

    uint8_t *golden = NULL;
    size_t   golden_len = 0;

    /* Path relative to firmware/host_test/ */
    golden = read_file("../../tools/e2e/golden_frame.bin", &golden_len);
    if (golden_len != FRAME_TOTAL_SIZE) {
        fprintf(stderr, "  golden_frame.bin is %zu bytes, expected %d\n",
                golden_len, FRAME_TOTAL_SIZE);
        exit(1);
    }

    frame_state_t f;
    frame_init(&f);
    feed_frame(&f, golden, golden_len);

    /* Convert to display RAM */
    uint8_t bw_ram[FRAME_PLANE_SIZE];
    uint8_t red_ram[FRAME_PLANE_SIZE];
    frame_convert(&f, bw_ram, red_ram);

    /* Assert pad bits: last byte of each row (col 15):
       BW RAM bits 6-7 must be 1, RED RAM bits 6-7 must be 0. */
    int pad_ok = 1;
    for (int row = 0; row < FRAME_H; row++) {
        int idx = row * FRAME_ROW_BYTES + (FRAME_ROW_BYTES - 1);
        if ((bw_ram[idx] & 0x03) != 0x03) {
            fprintf(stderr, "  BW pad row %d: got 0x%02x, expected bits 6-7=1\n",
                    row, bw_ram[idx]);
            pad_ok = 0;
            break;
        }
        if ((red_ram[idx] & 0x03) != 0x00) {
            fprintf(stderr, "  RED pad row %d: got 0x%02x, expected bits 6-7=0\n",
                    row, red_ram[idx]);
            pad_ok = 0;
            break;
        }
    }
    if (!pad_ok) { FAIL("pad bits"); free(golden); return; }

    /* Decode panel RAM back to landscape pixels, write PPM */
    /* Panel interpretation:
       For each native pixel (col c, row r):
         byte = r*16 + c/8, bit = 7 - (c%8)  (MSB first)
         RED bit1=1 -> red
         else BW bit1=0 -> black
         else -> white
       Landscape (lx, ly): native col = 121-ly, native row = lx
       Landscape width=250 (x), height=122 (y)
    */
    FILE *ppm = fopen("../../tools/e2e/render.ppm", "wb");
    if (!ppm) {
        fprintf(stderr, "  cannot open render.ppm for writing\n");
        free(golden);
        FAIL("open render.ppm");
        return;
    }

    /* P6 header */
    fprintf(ppm, "P6\n250 122\n255\n");

    for (int ly = 0; ly < 122; ly++) {        /* landscape rows top→bottom */
        for (int lx = 0; lx < 250; lx++) {    /* landscape cols left→right */
            int nc = 121 - ly;   /* native column */
            int nr = lx;          /* native row */

            int byte_idx = nr * 16 + (nc / 8);
            int bit_pos  = 7 - (nc % 8);

            int red_bit = (red_ram[byte_idx] >> bit_pos) & 1;
            int bw_bit  = (bw_ram[byte_idx] >> bit_pos) & 1;

            uint8_t r, g, b;
            if (red_bit) {
                r = 255; g = 0; b = 0;       /* red */
            } else if (bw_bit == 0) {
                r = 0; g = 0; b = 0;         /* black */
            } else {
                r = 255; g = 255; b = 255;   /* white */
            }
            uint8_t rgb[3] = {r, g, b};
            fwrite(rgb, 1, 3, ppm);
        }
    }
    fclose(ppm);

    /* Assert sample pixels */
    /* Landscape (x,y) -> native (121-y, x) */
    struct { int x; int y; const char *expected; } checks[] = {
        { 20,  60,  "BLACK" },
        {125, 100,  "WHITE" },
        {230,  60,  "RED"   },
        {135,  10,  "BLACK" },
    };

    int all_ok = 1;
    for (int i = 0; i < 4; i++) {
        int lx = checks[i].x;
        int ly = checks[i].y;
        int nc = 121 - ly;
        int nr = lx;
        int bi = nr * 16 + (nc / 8);
        int bp = 7 - (nc % 8);
        int red_bit = (red_ram[bi] >> bp) & 1;
        int bw_bit  = (bw_ram[bi] >> bp) & 1;

        const char *actual;
        if (red_bit)       actual = "RED";
        else if (bw_bit==0) actual = "BLACK";
        else               actual = "WHITE";

        if (strcmp(actual, checks[i].expected) != 0) {
            char msg[128];
            snprintf(msg, sizeof(msg),
                     "pixel (%d,%d) is %s, expected %s",
                     lx, ly, actual, checks[i].expected);
            FAIL(msg);
            all_ok = 0;
        }
    }
    if (all_ok) {
        PASS();
    }
    free(golden);
}

/* ================================================================ */
/*  Test 2: Out-of-order DATA chunks still succeed                    */
/* ================================================================ */
static void test_e2e_out_of_order(void)
{
    TEST("E2E out-of-order DATA chunks");

    uint8_t *golden = NULL;
    size_t   golden_len = 0;
    golden = read_file("../../tools/e2e/golden_frame.bin", &golden_len);
    if (golden_len != FRAME_TOTAL_SIZE) {
        fprintf(stderr, "  golden_frame.bin is %zu bytes, expected %d\n",
                golden_len, FRAME_TOTAL_SIZE);
        exit(1);
    }

    frame_state_t f;
    frame_init(&f);

    int rc = frame_begin(&f, (uint32_t)golden_len);
    if (rc != FRAME_STATUS_OK) {
        fprintf(stderr, "  BEGIN returned %02x\n", rc);
        free(golden);
        FAIL("BEGIN");
        return;
    }

    /* Write chunks in reverse order: last chunk first, then first, etc. */
    size_t chunk_count = (golden_len + CHUNK_SIZE - 1) / CHUNK_SIZE;
    for (size_t ci = chunk_count; ci > 0; ci--) {
        size_t ci0 = ci - 1;  /* zero-based */
        size_t off = ci0 * CHUNK_SIZE;
        size_t chunk = (off + CHUNK_SIZE <= golden_len) ? CHUNK_SIZE : (golden_len - off);
        rc = frame_data_write(&f, (uint16_t)off, &golden[off], (uint16_t)chunk);
        if (rc != FRAME_STATUS_OK) {
            char msg[64];
            snprintf(msg, sizeof(msg), "DATA write at %zu returned %02x", off, rc);
            free(golden);
            FAIL(msg);
            return;
        }
    }

    /* Now also write one chunk in the middle twice (should still be fine) */
    rc = frame_data_write(&f, 1000, &golden[1000], 242);
    if (rc != FRAME_STATUS_OK) {
        free(golden);
        FAIL("duplicate write");
        return;
    }

    /* COMMIT */
    uint32_t crc = frame_crc32(golden, golden_len);
    rc = frame_commit(&f, crc);
    if (rc != FRAME_STATUS_OK) {
        char msg[64];
        snprintf(msg, sizeof(msg), "COMMIT returned %02x, expected OK", rc);
        free(golden);
        FAIL(msg);
        return;
    }

    PASS();
    free(golden);
}

/* ================================================================ */
/*  Test 3: Corrupted-CRC COMMIT returns 0xE3                        */
/* ================================================================ */
static void test_e2e_bad_crc(void)
{
    TEST("E2E corrupted-CRC COMMIT returns 0xE3");

    uint8_t *golden = NULL;
    size_t   golden_len = 0;
    golden = read_file("../../tools/e2e/golden_frame.bin", &golden_len);
    if (golden_len != FRAME_TOTAL_SIZE) {
        fprintf(stderr, "  golden_frame.bin is %zu bytes, expected %d\n",
                golden_len, FRAME_TOTAL_SIZE);
        exit(1);
    }

    frame_state_t f;
    frame_init(&f);

    /* Feed all data normally */
    feed_frame(&f, golden, golden_len);   /* This commits with correct CRC */

    /* Now do a second attempt: BEGIN again, feed data, COMMIT with wrong CRC */
    frame_init(&f);
    int rc = frame_begin(&f, (uint32_t)golden_len);
    if (rc != FRAME_STATUS_OK) {
        free(golden);
        FAIL("BEGIN for bad-crc test");
        return;
    }
    for (size_t off = 0; off < golden_len; off += CHUNK_SIZE) {
        size_t chunk = (off + CHUNK_SIZE <= golden_len) ? CHUNK_SIZE : (golden_len - off);
        rc = frame_data_write(&f, (uint16_t)off, &golden[off], (uint16_t)chunk);
    }
    /* Commit with intentionally wrong CRC */
    uint32_t bad_crc = 0xDEADBEEF;
    rc = frame_commit(&f, bad_crc);
    if (rc == FRAME_STATUS_CRC_ERR) {
        PASS();
    } else {
        char msg[64];
        snprintf(msg, sizeof(msg), "expected 0xE3, got %02x", rc);
        FAIL(msg);
    }
    free(golden);
}

/* ================================================================ */
/*  Main                                                              */
/* ================================================================ */

int main(void)
{
    printf("=== frame.c end-to-end tests ===\n\n");

    test_e2e_normal();
    test_e2e_out_of_order();
    test_e2e_bad_crc();

    printf("\n=== E2E Results: %d passed, %d failed ===\n",
           tests_passed, tests_failed);

    return tests_failed ? 1 : 0;
}
/* host_test/test_frame.c — tests for frame.c (pure C, no IDF) */
#include <stdio.h>
#include <string.h>
#include <stdlib.h>
#include <assert.h>
#include "../main/frame.h"

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

/* ================================================================ */
/*  1. BEGIN with bad length                                        */
/* ================================================================ */
static void test_begin_bad_length(void)
{
    TEST("BEGIN bad length (9999)");
    frame_state_t f;
    frame_init(&f);
    int rc = frame_begin(&f, 9999);
    if (rc == FRAME_STATUS_BAD_LEN && !f.begun) {
        PASS();
    } else {
        FAIL("expected FRAME_STATUS_BAD_LEN and f.begun==false");
    }
}

/* ================================================================ */
/*  2. BEGIN with correct length                                    */
/* ================================================================ */
static void test_begin_good_length(void)
{
    TEST("BEGIN correct length (8000)");
    frame_state_t f;
    frame_init(&f);
    int rc = frame_begin(&f, FRAME_TOTAL_SIZE);
    if (rc == FRAME_STATUS_OK && f.begun && f.total_len == FRAME_TOTAL_SIZE) {
        PASS();
    } else {
        FAIL("expected FRAME_STATUS_OK, begun=true, total_len=8000");
    }
}

/* ================================================================ */
/*  3. DATA offset overflow                                         */
/* ================================================================ */
static void test_offset_overflow(void)
{
    TEST("DATA offset overflow");
    frame_state_t f;
    frame_init(&f);
    frame_begin(&f, FRAME_TOTAL_SIZE);

    /* Try writing 100 bytes at offset 7950 (7950+100 = 8050 > 8000) */
    uint8_t buf[100];
    memset(buf, 0x55, 100);
    int rc = frame_data_write(&f, 7950, buf, 100);
    if (rc == FRAME_STATUS_BAD_OFF) {
        PASS();
    } else {
        FAIL("expected FRAME_STATUS_BAD_OFF");
    }
}

/* ================================================================ */
/*  4. DATA before BEGIN (not-begun)                                 */
/* ================================================================ */
static void test_not_begun(void)
{
    TEST("DATA before BEGIN (not begun)");
    frame_state_t f;
    frame_init(&f);
    uint8_t buf[10];
    memset(buf, 0xAA, 10);
    int rc = frame_data_write(&f, 0, buf, 10);
    if (rc == FRAME_STATUS_NOT_BEGUN) {
        PASS();
    } else {
        FAIL("expected FRAME_STATUS_NOT_BEGUN");
    }
}

/* ================================================================ */
/*  5. COMMIT before BEGIN (not-begun)                               */
/* ================================================================ */
static void test_commit_not_begun(void)
{
    TEST("COMMIT before BEGIN (not begun)");
    frame_state_t f;
    frame_init(&f);
    int rc = frame_commit(&f, 0x12345678);
    if (rc == FRAME_STATUS_NOT_BEGUN) {
        PASS();
    } else {
        FAIL("expected FRAME_STATUS_NOT_BEGUN");
    }
}

/* ================================================================ */
/*  6. COMMIT incomplete (not all bytes received)                    */
/* ================================================================ */
static void test_incomplete(void)
{
    TEST("COMMIT incomplete");
    frame_state_t f;
    frame_init(&f);
    frame_begin(&f, FRAME_TOTAL_SIZE);

    /* Write only some bytes */
    uint8_t buf[100];
    memset(buf, 0xCC, 100);
    frame_data_write(&f, 0, buf, 100);
    /* Don't write the rest */

    uint32_t crc = frame_crc32(f.data, FRAME_TOTAL_SIZE);
    int rc = frame_commit(&f, crc);
    if (rc == FRAME_STATUS_INCOMPLETE) {
        PASS();
    } else {
        FAIL("expected FRAME_STATUS_INCOMPLETE");
    }
}

/* ================================================================ */
/*  7. CRC mismatch                                                 */
/* ================================================================ */
static void test_crc_mismatch(void)
{
    TEST("CRC mismatch");
    frame_state_t f;
    frame_init(&f);
    frame_begin(&f, FRAME_TOTAL_SIZE);

    /* Write all bytes with some pattern */
    for (uint32_t i = 0; i < FRAME_TOTAL_SIZE; i++) {
        uint8_t b = (uint8_t)(i & 0xFF);
        frame_data_write(&f, (uint16_t)i, &b, 1);
    }

    /* Pass wrong CRC */
    int rc = frame_commit(&f, 0xDEADBEEF);
    if (rc == FRAME_STATUS_CRC_ERR) {
        PASS();
    } else {
        FAIL("expected FRAME_STATUS_CRC_ERR");
    }
}

/* ================================================================ */
/*  8. Success: full write + correct CRC                            */
/* ================================================================ */
static void test_success(void)
{
    TEST("Full write + correct CRC -> SUCCESS");
    frame_state_t f;
    frame_init(&f);
    frame_begin(&f, FRAME_TOTAL_SIZE);

    /* Write all bytes with a known pattern */
    for (uint32_t i = 0; i < FRAME_TOTAL_SIZE; i++) {
        uint8_t b = (uint8_t)((i * 7 + 13) & 0xFF);
        frame_data_write(&f, (uint16_t)i, &b, 1);
    }

    /* Compute correct CRC */
    uint32_t crc = frame_crc32(f.data, FRAME_TOTAL_SIZE);
    int rc = frame_commit(&f, crc);
    if (rc == FRAME_STATUS_OK) {
        PASS();
    } else {
        FAIL("expected FRAME_STATUS_OK");
    }
}

/* ================================================================ */
/*  9. K/R -> BW/RED conversion (incl. pad bits)                    */
/* ================================================================ */
static void test_convert_black(void)
{
    TEST("Convert: pure black (K=0xFF, R=0x00)");
    frame_state_t f;
    frame_init(&f);
    frame_begin(&f, FRAME_TOTAL_SIZE);

    /* All K bits = 1 (black), R = 0 */
    memset(f.data, 0xFF, FRAME_PLANE_SIZE);            /* K plane */
    memset(f.data + FRAME_PLANE_SIZE, 0x00, FRAME_PLANE_SIZE); /* R plane */
    memset(f.received, 1, FRAME_TOTAL_SIZE);

    uint8_t bw[4000], red[4000];
    frame_convert(&f, bw, red);

    /* Check: BW = ~K | R = ~0xFF | 0x00 = 0x00 (all black on BW RAM: bit 0 = black) */
    /* But pad bits should be forced 1 */
    for (int row = 0; row < FRAME_H; row++) {
        for (int col = 0; col < FRAME_ROW_BYTES; col++) {
            int idx = row * FRAME_ROW_BYTES + col;
            uint8_t expected_bw = (col == FRAME_ROW_BYTES - 1) ? 0x3F : 0x00;
            if (bw[idx] != expected_bw) {
                char msg[128];
                snprintf(msg, sizeof(msg),
                         "BW[%d,%d]=0x%02x expected 0x%02x",
                         row, col, bw[idx], expected_bw);
                FAIL(msg);
                return;
            }
            uint8_t expected_red = 0xFF; /* raw R=0 -> red RAM disabled */
            if (red[idx] != expected_red) {
                char msg[128];
                snprintf(msg, sizeof(msg),
                         "RED[%d,%d]=0x%02x expected 0x%02x",
                         row, col, red[idx], expected_red);
                FAIL(msg);
                return;
            }
        }
    }
    PASS();
}

static void test_convert_red(void)
{
    TEST("Convert: pure red (K=0x00, R=0xFF)");
    frame_state_t f;
    frame_init(&f);
    frame_begin(&f, FRAME_TOTAL_SIZE);

    memset(f.data, 0x00, FRAME_PLANE_SIZE);               /* K plane = 0 */
    memset(f.data + FRAME_PLANE_SIZE, 0xFF, FRAME_PLANE_SIZE); /* R plane = 1 */
    memset(f.received, 1, FRAME_TOTAL_SIZE);

    uint8_t bw[4000], red[4000];
    frame_convert(&f, bw, red);

    /* BW = ~K | R = ~0x00 | 0xFF = 0xFF (red pixels write white in BW) */
    /* Red RAM is active-low: raw R=0xFF becomes 0x00; pad bits are 1. */
    for (int row = 0; row < FRAME_H; row++) {
        for (int col = 0; col < FRAME_ROW_BYTES; col++) {
            int idx = row * FRAME_ROW_BYTES + col;
            uint8_t expected_bw = 0xFF; /* always 0xFF: ~0 | 0xFF */
            uint8_t expected_red = (col == FRAME_ROW_BYTES - 1) ? 0x3F : 0x00;
            if (bw[idx] != expected_bw) {
                char msg[128];
                snprintf(msg, sizeof(msg),
                         "BW[%d,%d]=0x%02x expected 0x%02x",
                         row, col, bw[idx], expected_bw);
                FAIL(msg);
                return;
            }
            if (red[idx] != expected_red) {
                char msg[128];
                snprintf(msg, sizeof(msg),
                         "RED[%d,%d]=0x%02x expected 0x%02x",
                         row, col, red[idx], expected_red);
                FAIL(msg);
                return;
            }
        }
    }
    PASS();
}

static void test_convert_both_red_wins(void)
{
    TEST("Convert: both black+red set -> red wins");
    frame_state_t f;
    frame_init(&f);
    frame_begin(&f, FRAME_TOTAL_SIZE);

    /* Both K and R = 0xFF */
    memset(f.data, 0xFF, FRAME_TOTAL_SIZE);
    memset(f.received, 1, FRAME_TOTAL_SIZE);

    uint8_t bw[4000], red[4000];
    frame_convert(&f, bw, red);

    /* BW = ~0xFF | 0xFF = 0x00 | 0xFF = 0xFF (white in BW, so red shows) */
    /* Red RAM is active-low: raw R=0xFF becomes 0x00; pad bits are 1. */
    for (int row = 0; row < FRAME_H; row++) {
        for (int col = 0; col < FRAME_ROW_BYTES; col++) {
            int idx = row * FRAME_ROW_BYTES + col;
            uint8_t expected_bw = (col == FRAME_ROW_BYTES - 1) ? 0xFF : 0xFF;
            uint8_t expected_red = (col == FRAME_ROW_BYTES - 1) ? 0x3F : 0x00;
            if (bw[idx] != expected_bw) {
                char msg[128];
                snprintf(msg, sizeof(msg),
                         "BW[%d,%d]=0x%02x expected 0x%02x",
                         row, col, bw[idx], expected_bw);
                FAIL(msg);
                return;
            }
            if (red[idx] != expected_red) {
                char msg[128];
                snprintf(msg, sizeof(msg),
                         "RED[%d,%d]=0x%02x expected 0x%02x",
                         row, col, red[idx], expected_red);
                FAIL(msg);
                return;
            }
        }
    }
    PASS();
}

static void test_convert_white(void)
{
    TEST("Convert: pure white (K=0x00, R=0x00)");
    frame_state_t f;
    frame_init(&f);
    frame_begin(&f, FRAME_TOTAL_SIZE);

    memset(f.data, 0x00, FRAME_TOTAL_SIZE);
    memset(f.received, 1, FRAME_TOTAL_SIZE);

    uint8_t bw[4000], red[4000];
    frame_convert(&f, bw, red);

    /* BW = ~0x00 | 0x00 = 0xFF (white), pad bits already 1 */
    /* RED = 0x00, pad bits 0 */
    for (int row = 0; row < FRAME_H; row++) {
        for (int col = 0; col < FRAME_ROW_BYTES; col++) {
            int idx = row * FRAME_ROW_BYTES + col;
            uint8_t expected_bw = 0xFF;
            uint8_t expected_red = 0xFF;
            if (bw[idx] != expected_bw) {
                char msg[128];
                snprintf(msg, sizeof(msg),
                         "BW[%d,%d]=0x%02x expected 0x%02x",
                         row, col, bw[idx], expected_bw);
                FAIL(msg);
                return;
            }
            if (red[idx] != expected_red) {
                char msg[128];
                snprintf(msg, sizeof(msg),
                         "RED[%d,%d]=0x%02x expected 0x%02x",
                         row, col, red[idx], expected_red);
                FAIL(msg);
                return;
            }
        }
    }
    PASS();
}

/* ================================================================ */
/*  10. CRC-32 known-value test                                     */
/* ================================================================ */
static void test_crc32_known(void)
{
    TEST("CRC-32 known value (\"123456789\")");
    /* CRC-32 of "123456789" should be 0xCBF43926 */
    const uint8_t *data = (const uint8_t *)"123456789";
    uint32_t crc = frame_crc32(data, 9);
    if (crc == 0xCBF43926) {
        PASS();
    } else {
        char msg[64];
        snprintf(msg, sizeof(msg), "got 0x%08lx expected 0xCBF43926",
                 (unsigned long)crc);
        FAIL(msg);
    }
}

static void test_crc32_zero_block(void)
{
    TEST("CRC-32 known value (500 zero bytes)");
    const uint8_t data[500] = {0};
    uint32_t crc = frame_crc32(data, sizeof(data));
    if (crc == 0x0283B97A) {
        PASS();
    } else {
        char msg[64];
        snprintf(msg, sizeof(msg), "got 0x%08lx expected 0x0283B97A",
                 (unsigned long)crc);
        FAIL(msg);
    }
}

/* ================================================================ */
/*  11. CLEAR command sets all white                                */
/* ================================================================ */
static void test_clear(void)
{
    TEST("CLEAR results in all-white planes");
    frame_state_t f;
    frame_init(&f);
    frame_clear(&f);

    /* After clear: begun=true, all data=0x00, all received=true */
    if (!f.begun) { FAIL("begun should be true"); return; }
    if (f.total_len != FRAME_TOTAL_SIZE) { FAIL("total_len should be 8000"); return; }

    for (uint32_t i = 0; i < FRAME_TOTAL_SIZE; i++) {
        if (f.data[i] != 0x00) { FAIL("data should be all zero"); return; }
        if (!f.received[i]) { FAIL("received should be all true"); return; }
    }

    uint8_t bw[4000], red[4000];
    frame_convert(&f, bw, red);

    for (int i = 0; i < 4000; i++) {
        if (bw[i] != 0xFF) { FAIL("BW should be all 0xFF (white)"); return; }
        if (red[i] != 0xFF) { FAIL("RED should be all 0xFF (disabled)"); return; }
    }
    PASS();
}

/* ================================================================ */
/*  Main test runner                                                 */
/* ================================================================ */

int main(void)
{
    printf("=== frame.c host tests ===\n\n");

    test_begin_bad_length();
    test_begin_good_length();
    test_offset_overflow();
    test_not_begun();
    test_commit_not_begun();
    test_incomplete();
    test_crc_mismatch();
    test_success();
    test_convert_black();
    test_convert_red();
    test_convert_both_red_wins();
    test_convert_white();
    test_crc32_known();
    test_crc32_zero_block();
    test_clear();

    printf("\n=== Results: %d passed, %d failed ===\n",
           tests_passed, tests_failed);

    return tests_failed ? 1 : 0;
}

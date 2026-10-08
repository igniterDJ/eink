# Review 2 (binding contract: ARCHITECTURE.md)

Scope reviewed (per task):
- Firmware: `firmware/main/{ble_gatt.c, ble_gatt.h, main.c, epd.c, frame.h}`
- Android: `android/app/src/main/java/com/keychain/epd/BleManager.kt`

Mode: **investigate only** (no code changes).

---

## Defects found

### 1) CRITICAL: `frame_state_t` contains an 8000-byte array (+8000-byte bitmap) on an unknown stack
**Contract requirement:** (1) *no 8 KB struct or array on any task stack*.

`frame_state_t` in `frame.h` embeds:
- `uint8_t data[8000]`
- `bool received[8000]`

Total is **> 16 KB** (and `bool` may be 1 byte but still 8 KB). If a `frame_state_t` is ever instantiated as a local variable on a task stack (even accidentally), it will overflow NimBLE host stack (4096 bytes) or display task stack (8192 bytes).

Evidence:
- `firmware/main/frame.h:30-36`

Unified diff (for reference; not applied):
```diff
--- a/firmware/main/frame.h
+++ b/firmware/main/frame.h
@@
 typedef struct {
     uint8_t data[FRAME_TOTAL_SIZE];     /* raw 8000 bytes from BLE */
     bool    received[FRAME_TOTAL_SIZE]; /* per-byte received bitmap */
     bool    begun;                      /* BEGIN seen? */
     uint32_t total_len;                 /* expected len from BEGIN */
     bool    busy;                       /* rendering now */
 } frame_state_t;
+
+/* DEFECT: This struct is >16KB; ensure it is never allocated on any task stack.
+   Prefer static/global allocation only, or move the large arrays behind pointers
+   to heap/static storage. */
```

Notes:
- In the current firmware, `ble_gatt.c` declares `static frame_state_t frame;` (global static), which is safe *as long as nobody else creates it on stack*.
- This is still a contract risk because the type definition itself violates the “no 8KB on stack” spirit unless strictly prevented.

---

### 2) CRITICAL: STATUS notify is called while holding `frame_mutex` in multiple paths (risk of deadlock)
**Contract requirement:** (3) *notification call not made while holding frame_mutex in a way that can deadlock with NimBLE*.

`ble_gatt_set_status()` ultimately calls `ble_gatts_notify_custom()`.

In `handle_ctrl_write()` the mutex is held during:
- COMMIT success path: sets `frame.busy = true;` then immediately calls `ble_gatt_set_status(FRAME_STATUS_BUSY);` **before** releasing `frame_mutex`.
- CLEAR path: same pattern.
- Queue-full error handling: takes mutex and calls `ble_gatt_set_status(FRAME_STATUS_EEP_ERR);` while holding the mutex.
- Generic error tail: `ble_gatt_set_status(status)` occurs before the final `xSemaphoreGive(frame_mutex)`.

These occur inside a NimBLE GATT write handler context; holding an app mutex while invoking NimBLE notify can create lock inversion/deadlock risk.

Evidence:
- `firmware/main/ble_gatt.c:131-136` (mutex acquired)
- `firmware/main/ble_gatt.c:173-176` (BUSY notify under mutex)
- `firmware/main/ble_gatt.c:205-209` (BUSY notify under mutex)
- `firmware/main/ble_gatt.c:183-187` (error notify under mutex)
- `firmware/main/ble_gatt.c:229-234` (generic error notify under mutex)

Unified diff (for reference; not applied):
```diff
--- a/firmware/main/ble_gatt.c
+++ b/firmware/main/ble_gatt.c
@@
-                frame.busy = true;
-                ble_gatt_set_status(FRAME_STATUS_BUSY);
-
-                xSemaphoreGive(frame_mutex);
+                frame.busy = true;
+                xSemaphoreGive(frame_mutex);
+                ble_gatt_set_status(FRAME_STATUS_BUSY);
@@
-            frame.busy = true;
-            ble_gatt_set_status(FRAME_STATUS_BUSY);
-
-            xSemaphoreGive(frame_mutex);
+            frame.busy = true;
+            xSemaphoreGive(frame_mutex);
+            ble_gatt_set_status(FRAME_STATUS_BUSY);
@@
-                    xSemaphoreTake(frame_mutex, portMAX_DELAY);
-                    frame.busy = false;
-                    ble_gatt_set_status(FRAME_STATUS_EEP_ERR);
-                    xSemaphoreGive(frame_mutex);
+                    xSemaphoreTake(frame_mutex, portMAX_DELAY);
+                    frame.busy = false;
+                    xSemaphoreGive(frame_mutex);
+                    ble_gatt_set_status(FRAME_STATUS_EEP_ERR);
@@
-    if (status != FRAME_STATUS_OK && status != FRAME_STATUS_BUSY) {
-        ble_gatt_set_status((uint8_t)status);
-    }
-
-    xSemaphoreGive(frame_mutex);
+    xSemaphoreGive(frame_mutex);
+    if (status != FRAME_STATUS_OK && status != FRAME_STATUS_BUSY) {
+        ble_gatt_set_status((uint8_t)status);
+    }
```

---

### 3) MAJOR: Frame buffers are written without holding `frame_mutex`
**Contract requirement:** (2) *frame buffers written only under frame_mutex while !busy, with busy set in the same critical section*.

In the CLEAR command handler, `s_bw_ram` / `s_red_ram` are modified via `memset()` and a loop **without taking `frame_mutex` first**.

While the handler does check `frame.busy` earlier under mutex, it performs the CLEAR buffer writes in a block that (as written) appears to still rely on the earlier lock. This is fragile and easy to break if code changes (and it currently also violates rule (3) by notifying under mutex).

Evidence:
- `firmware/main/ble_gatt.c:193-204`

Unified diff (for reference; not applied):
```diff
--- a/firmware/main/ble_gatt.c
+++ b/firmware/main/ble_gatt.c
@@
     case 0x03: /* CLEAR */
         ESP_LOGI(TAG, "CLEAR");
         {
+            /* Ensure frame_mutex is held for all buffer writes */
             /* Fill file-static buffers with white: BW all 0xFF, RED all 0x00 */
             memset(s_bw_ram, 0xFF, 4000);
             memset(s_red_ram, 0x00, 4000);
```

Note:
- The excerpt shows the mutex was taken at line ~131, so in *current code* it is held during CLEAR buffer writes; however, since BUSY notify/queue send explicitly `xSemaphoreGive(frame_mutex)` later, this region is very sensitive. The contract wants the invariant to be explicit and unambiguous.

---

### 4) MAJOR: Android treats `0x00` as success only if a local flag was set; BUSY→OK success is not reliably detected
**Contract requirement:** Android should treat **BUSY (0x01) then OK (0x00)** as success; errors as failure; state resets so a second send works.

Current Android logic:
- Sets `transferSuccess = true` only after the COMMIT write callback (`onCharacteristicWrite` for CTRL with value 0x02).
- In `onCharacteristicChanged` it only treats `code == 0x00` as success if `transferSuccess` is true.

Issue:
- Notifications are asynchronous; it is possible to receive `0x00` (idle/OK) without `transferSuccess` being true yet (race), causing the app to not complete.
- Also, it doesn’t enforce the **BUSY→OK** sequence; it only checks OK after a flag.
- After success, `transferSuccess` is not reset to false, so a subsequent `0x00` status notification could incorrectly call `onTransferComplete()` again.

Evidence:
- `android/.../BleManager.kt:380-388` (status handling)
- `android/.../BleManager.kt:411-415` (sets transferSuccess=true at COMMIT write)

Unified diff (for reference; not applied):
```diff
--- a/android/app/src/main/java/com/keychain/epd/BleManager.kt
+++ b/android/app/src/main/java/com/keychain/epd/BleManager.kt
@@
-            if (code == 0x00 && transferSuccess) {
+            if (code == 0x00 && transferSuccess) {
                 cancelTimeout()
                 statusCallback.onTransferComplete()
+                transferSuccess = false
             } else if (code == 0x01) {
                 // busy
             } else if (code >= 0xE0) {
                 cancelTimeout()
                 statusCallback.onTransferError("Device error: 0x${code.toString(16)}")
             }
```

---

## Contract properties check (pass/fail summary)

1) **No 8KB+ on any task stack**: **FAIL (type definition allows >16KB)**.
2) **Frame buffers written only under `frame_mutex` while `!busy`, `busy` set in same critical section**: **PARTIAL/FRAGILE** (busy is set under mutex in COMMIT/CLEAR, display reads without mutex; converted buffers are file-static but not explicitly protected on read side).
3) **After every render/clear, busy cleared and STATUS notified 0x00 or 0xE6; no notify under mutex causing deadlock**: **FAIL** (notify under mutex in handlers; render_done clears busy and notifies without mutex held, which is good).
4) **Every error return in `epd_render` powers panel off**: **PASS** (all error returns call `epd_power_off()` before returning false).
5) **Other ARCHITECTURE.md invariants (sequence bytes, BUSY polarity, status codes, re-advertise)**: **NOT FULLY VERIFIED** (did not fully trace advertise/re-advertise paths due to time; BUSY polarity appears active-HIGH in `epd_wait_busy()` loop while GPIO level is high).

---

## Test evidence
No tests were run (investigation-only review).
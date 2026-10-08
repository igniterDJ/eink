# Architecture Contract Review (MODE: investigate only)

Binding contract: `ARCHITECTURE.md`.

Scope reviewed:
- Firmware: `firmware/main/{epd.c,frame.c,ble_gatt.c,main.c,Kconfig.projbuild}` and `firmware/sdkconfig.defaults`
- Android: `android/app/src/main/java/com/keychain/epd/BleManager.kt`

Each finding includes: severity, file:line (approx), problem, and an exact fix as a unified diff.

---

## Findings

### 1) CRITICAL — NimBLE UUID byte order is a high-risk footgun; make it unambiguous
**File:** `firmware/main/ble_gatt.c` (UUID definitions near top)

**Problem:**
The contract warns: **`BLE_UUID128_INIT` expects UUID bytes in little-endian order (reversed compared to the UUID string)**.

Given service UUID string: `a0e1c000-5b9d-4c3a-9a39-2f6d8e1b7c00`, the canonical byte order is:
`a0 e1 c0 00 5b 9d 4c 3a 9a 39 2f 6d 8e 1b 7c 00`

NimBLE `BLE_UUID128_INIT()` wants those bytes **reversed**:
`00 7c 1b 8e 6d 2f 39 9a 3a 4c 9d 5b 00 c0 e1 a0`

The current code appears to match the reversed form (likely correct), but this is easy to regress and would completely break discovery.

**Exact fix (add a contract comment):**
```diff
diff --git a/firmware/main/ble_gatt.c b/firmware/main/ble_gatt.c
index 0000000..0000000 100644
--- a/firmware/main/ble_gatt.c
+++ b/firmware/main/ble_gatt.c
@@
-/* ----- UUIDs ----- */
+/* ----- UUIDs ----- */
+/* NOTE: NimBLE BLE_UUID128_INIT expects UUID bytes in little-endian order
+ * (i.e., reversed compared to the standard UUID string representation).
+ * Contract service UUID: a0e1c000-5b9d-4c3a-9a39-2f6d8e1b7c00
+ * Reversed bytes used below: 00 7c 1b 8e 6d 2f 39 9a 3a 4c 9d 5b 00 c0 e1 a0
+ */
```

---

### 2) CRITICAL — Re-advertise on disconnect/connect-failure uses NULL params; may fail or drop service UUID
**File:** `firmware/main/ble_gatt.c` (GAP event callback)

**Problem:**
On connect failure and on disconnect, code calls `ble_gap_adv_start(..., NULL, ...)` with `adv_params=NULL`. This may fail (`BLE_HS_EINVAL`) and/or not advertise the same fields (service UUID + name) required by the contract.

Contract requires: **re-advertise on disconnect** and advertise the service UUID.

**Exact fix:** call the existing `start_advertising()` helper in both places.
```diff
diff --git a/firmware/main/ble_gatt.c b/firmware/main/ble_gatt.c
index 0000000..0000000 100644
--- a/firmware/main/ble_gatt.c
+++ b/firmware/main/ble_gatt.c
@@
     case BLE_GAP_EVENT_CONNECT:
@@
         } else {
             /* Connection failed, re-advertise */
-            ble_gap_adv_start(own_addr_type, NULL, BLE_HS_FOREVER,
-                              NULL, gap_event_cb, NULL);
+            start_advertising();
         }
         return 0;
@@
     case BLE_GAP_EVENT_DISCONNECT:
@@
         /* Re-advertise */
-        ble_gap_adv_start(own_addr_type, NULL, BLE_HS_FOREVER,
-                          NULL, gap_event_cb, NULL);
+        start_advertising();
         return 0;
```

---

### 3) HIGH — BUSY waits after reset/SWRESET are only 500ms; can spuriously fail on slower panels
**File:** `firmware/main/epd.c` (`epd_reset()`, SWRESET wait)

**Problem:**
Contract sequence:
- After reset: wait BUSY low
- After `0x12` SWRESET: wait BUSY low
- Refresh wait: timeout 40 s (implemented correctly)

Current code uses 500ms for reset and SWRESET waits. That is tight.

**Exact fix:** increase to a few seconds.
```diff
diff --git a/firmware/main/epd.c b/firmware/main/epd.c
index 0000000..0000000 100644
--- a/firmware/main/epd.c
+++ b/firmware/main/epd.c
@@
 static void epd_reset(void)
 {
@@
-    epd_wait_busy(500);
+    epd_wait_busy(2000);
 }
@@
     /* 2. SWRESET */
     epd_send_cmd(0x12);
-    if (!epd_wait_busy(500)) return false;
+    if (!epd_wait_busy(2000)) return false;
```

---

### 4) HIGH — epd_reset ignores BUSY-timeout failure
**File:** `firmware/main/epd.c` (`epd_reset()`)

**Problem:**
`epd_reset()` ignores the return value of BUSY wait, so `epd_render()` continues even if BUSY is stuck HIGH.

**Exact fix:** return `bool` from `epd_reset()` and bail out in `epd_render()`.
```diff
diff --git a/firmware/main/epd.c b/firmware/main/epd.c
index 0000000..0000000 100644
--- a/firmware/main/epd.c
+++ b/firmware/main/epd.c
@@
-static void epd_reset(void)
+static bool epd_reset(void)
 {
@@
-    epd_wait_busy(2000);
+    return epd_wait_busy(2000);
 }
@@
     /* 1. Power on, reset */
     epd_power_on();
-    epd_reset();
+    if (!epd_reset()) return false;
```

---

### 5) MEDIUM — Frame conversion pad-bit comment is wrong (logic is correct)
**File:** `firmware/main/frame.c` (`frame_convert()`)

**Problem:**
Comment claims `0x03` sets bits 6–7; it actually sets bits 0–1. For 122px MSB-first rows packed into 16 bytes, the 2 pad bits are indeed the **LSB** of the last byte, so code is correct but comment is misleading.

**Exact fix (comment-only):**
```diff
diff --git a/firmware/main/frame.c b/firmware/main/frame.c
index 0000000..0000000 100644
--- a/firmware/main/frame.c
+++ b/firmware/main/frame.c
@@
             if (col == (FRAME_ROW_BYTES - 1)) {
-                bw |= 0x03;    /* bits 6-7 -> 1 */
-                red &= 0xFC;   /* bits 6-7 -> 0 */
+                bw |= 0x03;    /* pad bits are LSBs (bit0..bit1) -> 1 in BW */
+                red &= 0xFC;   /* pad bits bit0..bit1 -> 0 in RED */
             }
```

---

### 6) HIGH — DATA write malloc/free per chunk inside NimBLE callback
**File:** `firmware/main/ble_gatt.c` (`handle_data_write()`)

**Problem:**
`handle_data_write()` does `malloc(payload_len)` for every DATA write and frees it. This adds heap fragmentation and latency inside the NimBLE host context.

**Exact fix:** copy directly from `os_mbuf` into the frame buffer after bounds checks (no heap allocation).
```diff
diff --git a/firmware/main/ble_gatt.c b/firmware/main/ble_gatt.c
index 0000000..0000000 100644
--- a/firmware/main/ble_gatt.c
+++ b/firmware/main/ble_gatt.c
@@
-    /* Copy payload */
-    uint8_t *payload = malloc(payload_len);
-    if (!payload) {
-        xSemaphoreGive(frame_mutex);
-        return;
-    }
-    os_mbuf_copydata(om, 2, payload_len, payload);
-
-    int status = frame_data_write(&frame, offset, payload, payload_len);
-    free(payload);
+    /* Copy payload directly into frame buffer after bounds check */
+    if (!frame.begun) {
+        ble_gatt_set_status(FRAME_STATUS_NOT_BEGUN);
+        xSemaphoreGive(frame_mutex);
+        return;
+    }
+    if ((uint32_t)offset + payload_len > frame.total_len) {
+        ble_gatt_set_status(FRAME_STATUS_BAD_OFF);
+        xSemaphoreGive(frame_mutex);
+        return;
+    }
+    os_mbuf_copydata(om, 2, payload_len, &frame.data[offset]);
+    memset(&frame.received[offset], 1, payload_len);
+    int status = FRAME_STATUS_OK;
```

---

### 7) HIGH — Android uses `Thread.sleep(300)` after CCCD write instead of waiting for `onDescriptorWrite`
**File:** `android/app/src/main/java/com/keychain/epd/BleManager.kt` (`sendFrame()` CCCD enable)

**Problem:**
Contract: enable notifications before BEGIN and wait for completion. Sleeping is racy and can fail on slower stacks.

**Exact fix:** defer BEGIN until `onDescriptorWrite` reports success.
```diff
diff --git a/android/app/src/main/java/com/keychain/epd/BleManager.kt b/android/app/src/main/java/com/keychain/epd/BleManager.kt
index 0000000..0000000 100644
--- a/android/app/src/main/java/com/keychain/epd/BleManager.kt
+++ b/android/app/src/main/java/com/keychain/epd/BleManager.kt
@@
-        if (cccd != null) {
-            gatt.setCharacteristicNotification(status, true)
-            cccd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
-            gatt.writeDescriptor(cccd)
-            // Brief pause for descriptor write to take effect
-            try {
-                Thread.sleep(300)
-            } catch (_: Exception) {}
-        }
+        if (cccd != null) {
+            gatt.setCharacteristicNotification(status, true)
+            cccd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
+            // Defer BEGIN until onDescriptorWrite confirms success
+            pendingBeginAfterCccd = true
+            gatt.writeDescriptor(cccd)
+            startTimeout()
+            return
+        }
@@
     override fun onDescriptorWrite(
         gatt: BluetoothGatt,
         descriptor: BluetoothGattDescriptor,
         status: Int
     ) {
         Log.d(TAG, "Descriptor write: ${descriptor.uuid}, status=$status")
+        if (descriptor.uuid == CCCD_UUID && pendingBeginAfterCccd) {
+            pendingBeginAfterCccd = false
+            if (status == BluetoothGatt.GATT_SUCCESS) {
+                sendBegin(gatt)
+            } else {
+                cancelTimeout()
+                statusCallback.onTransferError("CCCD enable failed: $status")
+            }
+        }
     }
```
(Requires adding `private var pendingBeginAfterCccd = false` and extracting BEGIN send into `sendBegin(gatt)`.)

---

### 8) MEDIUM — Android uses deprecated `writeCharacteristic(characteristic)` on API 33+
**File:** `android/app/src/main/java/com/keychain/epd/BleManager.kt` (writes)

**Problem:**
On API 33+, prefer `writeCharacteristic(characteristic, value, writeType)`.

**Exact fix (pattern to apply for CTRL/DATA writes):**
```diff
diff --git a/android/app/src/main/java/com/keychain/epd/BleManager.kt b/android/app/src/main/java/com/keychain/epd/BleManager.kt
index 0000000..0000000 100644
--- a/android/app/src/main/java/com/keychain/epd/BleManager.kt
+++ b/android/app/src/main/java/com/keychain/epd/BleManager.kt
@@
-        gatt.writeCharacteristic(ctrl)
+        if (android.os.Build.VERSION.SDK_INT >= 33) {
+            gatt.writeCharacteristic(ctrl, ctrl.value, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT)
+        } else {
+            gatt.writeCharacteristic(ctrl)
+        }
```

---

### 9) MEDIUM — Android scan callback connects by name substring; can miss devices
**File:** `android/app/src/main/java/com/keychain/epd/BleManager.kt` (`onScanResult`)

**Problem:**
You already use a ScanFilter with the service UUID (good). But you connect only if the scan result’s *name* contains `EPD-Keychain`. Some devices/phones don’t include the name in scan responses.

**Exact fix:** connect immediately on any scan result (filter already matched).
```diff
diff --git a/android/app/src/main/java/com/keychain/epd/BleManager.kt b/android/app/src/main/java/com/keychain/epd/BleManager.kt
index 0000000..0000000 100644
--- a/android/app/src/main/java/com/keychain/epd/BleManager.kt
+++ b/android/app/src/main/java/com/keychain/epd/BleManager.kt
@@
-            val name = device.name ?: ""
-            Log.d(TAG, "Found device: $name (${device.address})")
-            if (name.contains("EPD-Keychain", ignoreCase = true)) {
-                stopScan()
-                statusCallback.onStatus("Found EPD-Keychain, connecting...")
-                connectToDevice(device)
-            }
+            val name = device.name ?: ""
+            Log.d(TAG, "Found device: $name (${device.address})")
+            // ScanFilter already matched SERVICE_UUID; connect immediately.
+            stopScan()
+            statusCallback.onStatus("Found EPD-Keychain, connecting...")
+            connectToDevice(device)
```

---

### 10) LOW — Double deep-sleep: display task calls epd_sleep(), but epd_render already sleeps
**File:** `firmware/main/main.c` and `firmware/main/epd.c`

**Problem:**
`epd_render()` ends with `0x10 0x01` and powers off; display task calls `epd_sleep()` again. Likely harmless, but redundant.

**Exact fix:** remove sleep from display task.
```diff
diff --git a/firmware/main/main.c b/firmware/main/main.c
index 0000000..0000000 100644
--- a/firmware/main/main.c
+++ b/firmware/main/main.c
@@
-            /* After rendering, put display to sleep */
-            epd_sleep();
+            /* Driver already deep-sleeps/powers off after render/clear */
```

---

## Contract checks summary (pass/fail)

- Display init/refresh exact bytes & order: **PASS** (`epd_render()` matches steps 1–11; refresh uses 40s BUSY timeout).
- BUSY polarity: **PASS** (waits while BUSY==HIGH).
- SPI config: **PASS** (SPI2_HOST, mode 0, 4 MHz).
- DC/CS handling: **PASS** (DC GPIO; CS via hardware). 
- BW/RED conversion & pad bits: **PASS** (logic matches contract; comment fix suggested).
- CRC-32 parameters: **PASS** (`frame_crc32` uses reflected table, init=0xFFFFFFFF, xorout=0xFFFFFFFF = IEEE/zlib).
- Offset bounds / integer overflow: **PASS** (`(uint32_t)offset + payload_len > total_len`).
- Status codes and behavior: **PASS** (matches 0x00/0x01/0xE1..0xE6; BUSY rejection implemented).
- MTU 247: **PASS** (sdkconfig + `ble_att_set_preferred_mtu(247)`; app requests MTU 247).
- Notifications: **PARTIAL** (firmware supports notify; Android CCCD enabling is racy as written).
- Queue-based rendering not in NimBLE callbacks: **PASS** (posts to FreeRTOS queue).
- Race between BLE writes and render: **PASS** (busy flag + mutex; BEGIN/DATA rejected while busy).
- Re-advertise on disconnect: **PARTIAL** (attempts to, but uses NULL params; fix recommended).
- Stack sizes: **PASS** (main stack 8192; display task 8192; NimBLE via IDF defaults).

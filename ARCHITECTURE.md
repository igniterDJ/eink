# E-Ink BLE Photo Keychain — Architecture Contract (binding for all workers)

Two deliverables: `android/` (Kotlin app) and `firmware/` (ESP-IDF 5.5.1). Do not deviate from this contract; if something here is impossible, stop and report.

## Design constraints (binding)

**No ghosting. No long-term panel degradation.** The Waveshare 2.13" HAT (B) V4 is a 3-color (B/W/R) panel. Every visible refresh MUST run the manufacturer's full 3-color waveform (the ~15 s flashing cycle in `epd.c` `epd_render`). This is non-negotiable and takes priority over refresh-speed optimisations. In particular:
- Do NOT ship a custom/shortened LUT via `0x32` on this panel.
- Do NOT ship a "BW-only fast update" path that skips the red-plane waveform on a BWR panel — over many updates it will strand red pigment and cause permanent ghosting / colour shift.
- Partial refresh is not supported on this panel; do not add one.
- Speed-ups that DO NOT touch the waveform are fine and encouraged: skipping the whole render when the incoming frame is byte-identical to the last-rendered frame, batching BLE writes, sleeping the MCU between updates, etc.

If a future feature seems to require a shortened waveform on the BWR panel, stop and ask — do not just do it.

## Hardware
- MCU: **XIAO ESP32-S3** (target `esp32s3`). (Was previously documented as ESP32-S3-DevKitC-1 with a different pin table; `epd.c` has always targeted the XIAO pinout — this doc was stale, not the code.)
- Display: **Waveshare 2.13inch e-Paper HAT (B) V4**, 3-color (black/white/red), 122 x 250 px native, SSD1680-class controller.
- Wiring (HAT pin → ESP32-S3 GPIO), hardcoded as C `#define`s in `epd.c`:

| HAT | GPIO |
|---|---|
| VCC | 3V3 (always on — no software power-enable pin) |
| GND | GND |
| DIN (MOSI) | 9 |
| CLK (SCLK) | 8 |
| CS | 7 |
| DC | 5 |
| RST | 4 |
| BUSY | 3 |

  `Kconfig.projbuild` also declares `EPD_PIN_*` menuconfig options (with mostly-matching defaults, except BUSY defaults to 6 there), but `epd.c` never reads `CONFIG_EPD_PIN_*` — those options are currently dead/unwired. Treat the table above as authoritative until that's fixed.
- SPI2_HOST, mode 0, **2 MHz** (`EPD_SPI_HZ`), MSB first. BUSY is **active HIGH** (panel busy while HIGH).

## Display init/refresh sequence (`epd.c` `epd_render`, verified against a known-working Arduino V4 sketch)
1. RST: high 20ms → low 2ms → high 20ms. Wait BUSY low (stage "hardware reset").
2. `0x12` SWRESET, wait BUSY low (stage "software reset").
3. `0x01` data `0xF9 0x00 0x00` (driver output control, 250 lines — 3 data bytes).
4. `0x11` data `0x03` (data entry: X inc, Y inc).
5. Window: `0x44` data `0x00 0x0F` (X 0..15 bytes), `0x45` data `0x00 0x00 0xF9 0x00` (Y 0..249).
6. Cursor: `0x4E 0x00`, `0x4F 0x00 0x00`.
7. `0x3C` data `0x05` (border), `0x18` data `0x80` (internal temp sensor), `0x21` data `0x80 0x80`. Wait BUSY low (stage "initialization").
8. `0x24` + 4000 bytes BW RAM (**bit 1 = white, 0 = black**) — cursor is not reset again here; still at the origin set in step 6.
9. `0x26` + 4000 bytes RED RAM (**active-low: bit 0 = red, 1 = not red**) — same, no cursor reset before this write.
10. `0x20` (activate display update — the code does not send `0x22`/display-update-control-2 first; the panel runs on its POR default), wait BUSY low (stage "refresh"; ≈15–20 s typical, `EPD_TIMEOUT_MS` = 40 s → error).
11. `0x10` data `0x01` (deep sleep). No PWR pin to drive low (see Hardware note above).

## Image format (the "frame")
- Native orientation: width W=122, height H=250, row-major top→bottom, each row = 16 bytes (ceil(122/8)), MSB first = leftmost pixel, 2 pad bits at row end = 0.
- Plane size 250*16 = **4000 bytes**. Frame = **plane K (4000) ‖ plane R (4000) = 8000 bytes**.
- Wire semantics: plane K bit 1 = **black**; plane R bit 1 = **red**. If both set, red wins.
- Firmware converts: BW RAM byte = `~K[i] | R[i]` (red pixels written white in BW RAM), RED RAM byte = `~R[i]` (active-low — the raw R plane bit is inverted; frame.c's `frame_convert` and its host tests call this out explicitly), pad bits forced to 1 in both BW RAM and RED RAM (a padding bit must never read as "red" under the active-low convention, so it is forced to the inactive/1 value in RED RAM too, not 0).

## BLE GATT (firmware = peripheral, NimBLE; app = central)
- Advertised name: `EPD-Keychain`, advertises the service UUID.
- Service `a0e1c000-5b9d-4c3a-9a39-2f6d8e1b7c00`
  - CTRL `a0e1c001-5b9d-4c3a-9a39-2f6d8e1b7c00` — Write (with response)
    - `0x01 BEGIN` + u32 LE total_len (must equal 8000; else status 0xE1). Resets rx buffer & received bitmap. A write shorter than 5 bytes (can't hold the u32) is also rejected with 0xE1.
    - `0x02 COMMIT` + u32 LE CRC-32 (IEEE 802.3 / zlib, over the 8000-byte frame). Checks: begun (0xE5), every byte received (0xE4), CRC (0xE3). A write shorter than 5 bytes is rejected with 0xE1. On success → status 0x01 BUSY, render on display task, then 0x00 OK (or 0xE6 display error/timeout).
    - `0x03 CLEAR` — renders all white. Same BUSY/OK statuses.
  - DATA `a0e1c002-5b9d-4c3a-9a39-2f6d8e1b7c00` — Write (with response)
    - u16 LE offset + payload bytes. offset+len > total_len → status 0xE2. Before BEGIN → 0xE5.
  - STATUS `a0e1c003-5b9d-4c3a-9a39-2f6d8e1b7c00` — Read + Notify, 1 byte code: `0x00 OK/idle`, `0x01 BUSY`, `0xE1 bad length`, `0xE2 bad offset`, `0xE3 CRC mismatch`, `0xE4 incomplete`, `0xE5 not begun`, `0xE6 display error`.
- Firmware: preferred MTU 247. Commands received while BUSY → notify 0x01 and ignore. Rendering never runs inside a NimBLE callback: GATT handlers post to a FreeRTOS queue consumed by a display task. The frame buffer is copied/locked so a new BEGIN cannot corrupt a frame mid-render (reject with 0x01 while busy).
- Re-advertise on disconnect.
- App: requestMtu(247); chunk payload = negotiated MTU − 3 − 2. Send BEGIN, DATA chunks sequentially (wait for each `onCharacteristicWrite`), COMMIT; enable STATUS notifications (CCCD 0x2902) before BEGIN; show status text; treat OK after BUSY as success, 60 s timeout.

## Firmware layout (`firmware/`)
- `main/main.c` (init NVS, BLE, display task), `main/ble_gatt.c/.h`, `main/epd.c/.h` (driver per sequence above; pins are hardcoded there, not Kconfig-driven), `main/frame.c/.h` (**pure C, no IDF includes**: rx buffer, bitmap, offset checks, CRC-32, K/R → BW/RED RAM conversion), `main/Kconfig.projbuild` (declares `EPD_PIN_*` options, currently unused — see Hardware section), `sdkconfig.defaults` (target esp32s3, BT + NimBLE, BLE-only).
- `firmware/host_test/` — plain gcc test of `frame.c` (`make test`), no IDF.

## Android layout (`android/`)
- Kotlin, Views (no Compose), single `MainActivity`, minSdk 26, compile/target 35, AGP 8.7.3, Gradle wrapper 8.10.2, JDK 21 on PATH. Package `com.keychain.epd`.
- Features, all converging on the same dither → 8000-byte frame → BLE-send pipeline: **Photo** (capture/upload, crop/adjust, send), **Text** (compose + preview, send), **Gallery/History** (browse, star, delete, resend past sends), **Now Playing** (generic — any app with an active `MediaSession`, not app-specific; see below).
- UI (`MainActivity`): buttons **Take Photo** (`ActivityResultContracts.TakePicture` + FileProvider), **Upload Photo** (`PickVisualMedia`), **Add Text**, **Gallery**, **Now Playing** (toggle) + **Now Playing Style** (picker), **Scan & Connect**, **Send**, **Clear Display**; preview ImageView showing the 3-color dithered result (rotated back to landscape), status text.
- Permissions: API 31+: BLUETOOTH_SCAN (neverForLocation), BLUETOOTH_CONNECT; API ≤30: BLUETOOTH, BLUETOOTH_ADMIN, ACCESS_FINE_LOCATION. CAMERA declared. `INTERNET` is also still declared but currently unused (leftover from a removed Spotify OAuth integration — no code path makes a network call anymore). Now Playing additionally needs Notification Listener access — a special-access grant via `Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS`, not a manifest permission — checked with `NotificationManagerCompat.getEnabledListenerPackages`.
- Now Playing (`MediaSessionListenerService.kt`, `MediaSessionNowPlaying.kt`): a minimal `NotificationListenerService` plus `MediaSessionManager.getActiveSessions()` reads the currently-playing track from *any* app that publishes a MediaSession (YouTube Music, Spotify, etc.) — no OAuth, no network calls, local-phone playback only (won't see e.g. Spotify Connect playback on another device). Picks the most-recently-updated session when several are playing simultaneously. Downsamples large embedded album art before rendering. `requestRebind()` is called each time Now Playing is enabled, working around a platform quirk where a freshly-granted permission doesn't take effect until rebind or app restart. Feeds `NowPlayingRenderer` (`NowPlayingStyles.kt` / `NowPlayingStyleActivity.kt`) the same title/artist/art shape regardless of source app.
- Files: `ImageProcessor.kt` (**pure Kotlin/JVM, no android.* imports**, operates on IntArray ARGB): input any-size ARGB image with EXIF rotation already applied → center-crop to 250:122 landscape → bilinear/area scale to 250x122 → rotate 90° clockwise to 122x250 native → Floyd–Steinberg dither to palette {white (255,255,255), black (0,0,0), red (255,0,0)} using nearest color by squared RGB distance → pack K and R planes per "Image format" → 8000-byte frame. Also `crc32(ByteArray)` (use java.util.zip.CRC32), `chunk(frame, payloadSize)` → list of DATA writes. `BleManager.kt` (scan/connect/protocol). `MainActivity.kt` (UI wiring, bitmap decode with EXIF via androidx.exifinterface, downsample large images). `PhotoAdjustActivity.kt` / `CropFrameView.kt` (pinch-zoom/drag/rotate crop before `ImageProcessor`). `TextAdjustActivity.kt` / `TextCanvasView.kt` / `TextGeometry.kt` (text compose + layout). `GalleryActivity.kt` / `HistoryStore.kt` (past-send browsing and starring; on-disk PNG+frame persistence with no retention cap yet — grows unbounded). `MediaSessionListenerService.kt` / `MediaSessionNowPlaying.kt` / `NowPlayingStyles.kt` / `NowPlayingStyleActivity.kt` (Now Playing, see above). `BitmapUtils.kt` (shared bitmap helpers).
- JVM unit tests in `app/src/test/` for ImageProcessor (sizes, pure-color images map to correct planes, orientation of a marker pixel, chunk offsets cover frame exactly, CRC matches known value).

### UI safe area (notch)

Phones with display cutouts (notches) will cover top-aligned content unless the UI applies system-bar insets.

- Every activity layout root should have `@+id/root`.
- In each Activity `onCreate()`, apply `WindowInsetsCompat.Type.systemBars()` as top padding on that root.

If text/buttons are under the notch, the screen is missing this.

## Cross-check (end-to-end, no hardware)
- `tools/e2e/`: a JVM test writes a golden frame for a known synthetic image to `tools/e2e/golden_frame.bin`; the firmware host test ingests it through frame.c exactly as BLE writes (BEGIN, chunked DATA with offsets for MTU 247, COMMIT with CRC), converts to BW/RED RAM, and renders a PNG/PPM (`tools/e2e/render.ppm`) as the panel would show it, rotated to landscape, plus asserts on marker pixels.

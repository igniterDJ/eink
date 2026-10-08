# Features — current state (2026-09-17)

Verified against the actual source in
`android/app/src/main/java/com/keychain/epd/` (18 files listed below), plus
`ARCHITECTURE.md`, `PLAN_calendar_and_scheduled_text.md`, and
`PLAN_generic_now_playing.md`. This is what exists in code today, not a copy of
either plan.

## Files present

`BitmapUtils.kt`, `BleManager.kt`, `CalendarRenderer.kt`, `CalendarSource.kt`,
`CropFrameView.kt`, `GalleryActivity.kt`, `HistoryStore.kt`, `ImageProcessor.kt`,
`MainActivity.kt`, `MediaSessionListenerService.kt`, `MediaSessionNowPlaying.kt`,
`NowPlayingService.kt`, `NowPlayingStyleActivity.kt`, `NowPlayingStyles.kt`,
`PhotoAdjustActivity.kt`, `TextAdjustActivity.kt`, `TextCanvasView.kt`,
`TextGeometry.kt`.

All features funnel into the same pipeline: build an ARGB bitmap →
`ImageProcessor.process()` (crop/scale/rotate/dither to the 3-color 122x250
e-ink frame) → `BleManager` sends it over BLE to the keychain.

## Implemented

**Photo** — `PhotoAdjustActivity.kt` + `CropFrameView.kt`. Take a photo
(`ActivityResultContracts.TakePicture`) or pick one from the gallery
(`PickVisualMedia`), pinch-zoom/drag/rotate crop, then process and send.
Wired from `MainActivity.kt`.

**Text** — `TextAdjustActivity.kt` + `TextCanvasView.kt` + `TextGeometry.kt`.
Compose and lay out one or more text items on a canvas, preview, then process
and send. `TextGeometry.kt` (angle/distance/rotated hit-testing) is pure JVM
and already unit-tested (`TextGeometryTest.kt`).

**Gallery / History** — `GalleryActivity.kt` + `HistoryStore.kt`. Browse past
sends, star/delete them, resend a stored frame. Persists PNG + raw 8000-byte
frame per entry on disk under `filesDir`, no retention cap (grows unbounded —
noted as a known gap in `ARCHITECTURE.md`, not fixed here).

**Now Playing — generic MediaSession only.** `NowPlayingService.kt` (a
foreground `Service` owning the single BLE connection + a 10s poll loop) +
`MediaSessionNowPlaying.kt` (reads the active session via
`MediaSessionManager.getActiveSessions()`, needs Notification Listener access)
+ `MediaSessionListenerService.kt` (the trivial listener stub that access is
granted against) + `NowPlayingStyles.kt` / `NowPlayingStyleActivity.kt`
(render styles: art+text, text-only, etc., and the style picker).

Note: `PLAN_generic_now_playing.md` proposed *adding* this as a second source
**alongside** an existing Spotify OAuth integration, keeping both selectable.
That Spotify integration is not present in the code: there is no
`SpotifyAuth.kt` / `SpotifyClient.kt`, no OAuth flow, and no source toggle —
`grep -ri spotify` over the package only turns up a comment in
`MediaSessionNowPlaying.kt` listing it as an example app the generic path
covers. `ARCHITECTURE.md` confirms this directly: the `INTERNET` permission
is "still declared but currently unused (leftover from a removed Spotify OAuth
integration — no code path makes a network call anymore)". So today there is
exactly one Now Playing source (generic MediaSession, any app), not two.

**Calendar** — just implemented. `CalendarSource.kt` (queries
`CalendarContract.Instances` for the soonest event across visible calendars in
a lookahead window, on-device only, no network) + `CalendarRenderer.kt`
(renders title + formatted time, "No upcoming events" placeholder when empty)
+ a `btnCalendar` button wired in `MainActivity.kt` (`onCalendarClicked()` /
`renderNextCalendarEvent()`), `READ_CALENDAR` permission requested at runtime
and declared in `AndroidManifest.xml`. On-demand render (button tap), not a
background poll — matches the plan's decision. This is the "Calendar" half of
`PLAN_calendar_and_scheduled_text.md` section 1.

## Planned but not implemented

**Scheduled Text** — `PLAN_calendar_and_scheduled_text.md` section 2 (push
composed text at a future time via `AlarmManager`, unattended, with a
foreground service + boot receiver to re-arm alarms). None of its listed files
exist: no `ScheduledTextStore.kt`, `AlarmScheduler.kt`,
`ScheduledTextAlarmReceiver.kt`, `ScheduledTextSendService.kt`, or
`BootCompletedReceiver.kt`. The manifest also has none of its permissions
(`SCHEDULE_EXACT_ALARM`, `RECEIVE_BOOT_COMPLETED`) — `POST_NOTIFICATIONS`,
`FOREGROUND_SERVICE`, and `FOREGROUND_SERVICE_CONNECTED_DEVICE` are present,
but those are already needed by the implemented `NowPlayingService.kt`, not
evidence of Scheduled Text work. Status: planning only, as the plan doc itself
states.

**Spotify Now Playing** — was apparently implemented at some point (the
`INTERNET` permission is the leftover), then removed in favor of the generic
MediaSession path. Not currently planned to come back; `ARCHITECTURE.md`
documents its removal as settled, not as an open item.

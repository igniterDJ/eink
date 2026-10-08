# Plan: Generic "Now Playing" (adds YouTube Music, keeps Spotify)

Status: **planning only, not implemented.** Written by Opus (main session).

## Problem

Now Playing only works with Spotify because [SpotifyAuth.kt](android/app/src/main/java/com/keychain/epd/SpotifyAuth.kt) +
[SpotifyClient.kt](android/app/src/main/java/com/keychain/epd/SpotifyClient.kt) poll Spotify's Web API (OAuth,
`GET /v1/me/player/currently-playing`). YouTube Music has no public API for this — official or
otherwise usable from Android. That path is a dead end for YT Music specifically.

## Decision

Add a second, app-agnostic source: Android's `NotificationListenerService` +
`MediaSessionManager.getActiveSessions()`. Any app playing audio (YouTube Music, YouTube, Amazon
Music, Spotify's own local playback) publishes a `MediaSession` with title/artist/album-art/playback
state — readable generically once the user grants "Notification access" (one-time Settings toggle,
not a runtime dialog). One implementation covers YouTube Music and everything else, for free —
no API keys, no OAuth, no per-app work.

**Keep the existing Spotify OAuth path rather than replacing it.** It's the only source that can see
Spotify Connect playback on a *different* device (TV, speaker, desktop) — MediaSession only sees what's
playing locally on the phone. Both become selectable "sources"; default to the new generic one since
it covers more apps.

## Why this is the lazy option too

[NowPlayingStyleActivity.kt](android/app/src/main/java/com/keychain/epd/NowPlayingStyleActivity.kt)
and `NowPlayingRenderer.render(style, art: Bitmap, title: String, artist: String)` already take plain
primitives, not a Spotify-specific type. The renderer needs **zero changes** — both sources just need
to produce a `Bitmap?` + two strings before calling the same render path MainActivity already calls at
[MainActivity.kt:472](android/app/src/main/java/com/keychain/epd/MainActivity.kt#L472).

## New pieces

1. **`MediaSessionListenerService.kt`** (new) — trivial `NotificationListenerService` subclass, no
   logic. Its only purpose is to exist so `MediaSessionManager.getActiveSessions(componentName)` has
   something to authorize against. Declared in `AndroidManifest.xml` with
   `android:permission="android.permission.BIND_NOTIFICATION_LISTENER_SERVICE"` and the matching
   intent-filter. No new dangerous permission — this is a special-access grant via
   `Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS`, checked at runtime with
   `NotificationManagerCompat.getEnabledListenerPackages(context).contains(packageName)`.

2. **`MediaSessionNowPlaying.kt`** (new) — pure logic, JVM-testable where possible:
   - Calls `getActiveSessions()`, filters to sessions with `playbackState.state == STATE_PLAYING`.
   - If more than one is playing (e.g. YT Music + a podcast app), picks by most-recent
     `playbackState.lastPositionUpdateTime` — needs a documented tie-break rule, this is the one
     genuinely tricky bit and worth a unit test.
   - Reads `MediaMetadata`: `METADATA_KEY_TITLE`, `METADATA_KEY_ARTIST`, and album art — prefer
     `METADATA_KEY_ALBUM_ART` (Bitmap) if present, else resolve `METADATA_KEY_ALBUM_ART_URI` via
     `ContentResolver` (some apps only supply the URI form).
   - Returns `null` cleanly when nothing is playing or permission isn't granted — same "idle" shape
     `pollSpotifyNowPlaying()` already handles for Spotify's 204 case.

3. **`MainActivity.kt`** (modify) — add a source toggle ("This device" / "Spotify"), persisted next to
   the existing `selectedStyle` SharedPreference. Branch `pollSpotifyNowPlaying()`'s timer callback on
   source instead of writing a second poll loop; both branches converge on the same
   `NowPlayingRenderer.render(...)` → dither → BLE send call. When source = "This device" and
   permission isn't granted, show status text and launch the Settings intent — mirrors how
   `onNowPlayingClicked()` already kicks off the Spotify OAuth intent today.

4. **`AndroidManifest.xml`** (modify) — register the listener service.

No changes needed to `SpotifyClient.kt`, `SpotifyAuth.kt`, `NowPlayingRenderer`, or the BLE/frame
pipeline. Nothing about the existing Spotify flow is touched.

## Edge cases the implementer must handle

- Multiple simultaneous playing sessions → tie-break rule above, not "first in list" (order isn't
  guaranteed across OEMs).
- Permission revoked mid-poll (user turns off notification access while app is running) → same
  "nothing playing" idle state, not a crash.
- Album art as URI-only, not Bitmap (some apps) → `ContentResolver` fallback.
- OEM battery optimization (Samsung/Xiaomi) can kill background listener services → not fixable in
  app code; worth a one-line in-app note if art goes stale, not a blocking issue.

## Implementation-phase routing (for when this is approved)

Per the engineering-team routing rules — none of this is ISR/DMA/RTOS/math, so it doesn't need Opus
to implement, only to have designed it and to do the final review:

| Piece | Worker | Reason |
|---|---|---|
| `MediaSessionListenerService.kt`, `MediaSessionNowPlaying.kt`, `MainActivity.kt` wiring, manifest change | Sonnet 5 | Standard multi-file Android feature work, matches "application logic / mid-difficulty" row — not firmware, not math. |
| Unit test for the multi-session tie-break rule | Sonnet 5 (same worker, same file set) | Keeps "one file, one worker" simple; it's a small addition to the same PR. |
| Final review of the permission/lifecycle handling | Opus (you) | The one genuinely subtle part — a background-service lifecycle bug here fails silently (stale art, no crash), which is exactly the class of bug worth a personal pass rather than trusting "it built." |

No free-worker (GPT-5.2/DeepSeek) step here: no math to verify, and this isn't bulk/full-stack volume
work — it's a handful of tightly-coupled Android files best done by one agentic worker with repo access.

## Open questions for the CEO

1. Keep Spotify OAuth as a second source (recommended), or retire it now that the generic path covers
   Spotify's local playback too? Recommendation: keep it — it's the only source that spans devices via
   Spotify Connect, and it's already-working code (no reason to delete it).
2. Default source for new installs: "This device" (broader app coverage) or "Spotify" (matches current
   behavior, less surprising for existing users)? Recommendation: default to "This device," Spotify
   stays one tap away via the source toggle.

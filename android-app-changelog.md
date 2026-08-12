# SlideTV Player — Android App Changelog

Tracks changes to the Android player app (`eu.slidetv.player`).

- **[Unreleased]** = changes already on `main` but **not yet in a published Google
  Play production version**. They will ship in the next release.
- When a version is **published in Play Console**, its changes move under a dated
  version heading below. (The user tells the assistant when a version goes out.)

---

## [Unreleased]

### Added
- **Device log shipping is back.** The player again posts lifecycle events to
  `POST /api/device/log` (it stopped after build 0.33). It ships only its own
  native events — start, pairing, remote reload, sleep/wake (remote and
  scheduled), cache clears, watchdog reloads, and network up/down — never
  playback detail. Network trouble is logged on state change only (one line on
  the first failed poll, one on recovery), so a long outage can't flood the
  buffer with repeats. A "player stopping" line is recorded on teardown but is
  best-effort: the in-memory buffer usually dies with the process before the
  next flush. Off by default: nothing is sent unless a screen has `logs_enabled`
  turned on in the SaaS. Lines are held in a ~500-line in-memory ring buffer (no
  disk), flushed every 60s or at 50 lines (max 200 per request), capped at 60
  requests/hour, with network errors silent and the lines kept for the next
  attempt. The web player will report to the same endpoint independently (rows
  tagged with a `web-` version prefix), so native does not relay web logs.

### Changed
- **Admin panel typography** — Wix Madefor Display for headings, Montserrat for
  body/controls (bundled variable fonts, no runtime download).
- **Nav items** are cleaner — dropped the redundant SYSTEM / ON-OFF / STORAGE
  sub-labels under each tab name.

### Fixed
- **TV overscan** no longer clips the panel — the device card at the bottom of
  the sidebar was cut off on TVs. The panel now sits further inside the screen.
- **Информация tab opens at the top.** Content scroll is reset on every tab
  switch, so the tab no longer lands mid-scroll on the "Разкачи устройство"
  button with the version/cache/URL rows scrolled out of view.

---

## [1.0.4] — versionCode 13 — released to Play production 2026-07-29

### Added
- **Media cache no longer grows without limit.** The player caches images and
  videos on disk so it keeps playing without internet, but nothing ever removed
  them: files went away only on the manual "Изчисти кеша" button or the remote
  clear-cache command. On an 8-16 GB box a video playlist eventually filled the
  storage. The cache now has a ceiling (default 2 GB) and evicts the least
  recently *shown* files once it is exceeded, so whatever is in the current
  rotation stays cached and retired media ages out. Partial downloads are never
  evicted mid-flight. A sweep runs at startup and after each new download, on a
  low-priority background thread. (`dea4c30`, `0014005`)
- **Cache limit is a per-device setting** — 1 / 2 / 4 / 8 GB in the Кеш tab of
  the admin panel, shown next to the current cache size, so a small box can be
  dialled down without a new build. The cache size readout now formats GB.
  (`ca19740`)

### Changed
- **Redesigned the hidden admin panel** — glassmorphic look with a left sidebar,
  live connection status (online + Wi-Fi/Ethernet), and a clear focus ring on
  every control for D-pad / remote navigation. Fixes the panel scaling oversized
  on TVs and the content not scrolling to the end (clamped panel + flex layout).
  The ~900-line panel was extracted from `MainActivity.kt` into its own
  `AdminPanel.kt` (MainActivity dropped from 1738 → 742 lines). Glass surfaces
  use a dark, mostly-opaque frosted fill so text stays readable; the brand mark
  is the bare app icon (no frame); the background is soft, blurred radial colour
  glows instead of two hard circles.

### Internal / CI
- Unit tests now run in CI on every push and pull request; the repo previously
  had only the two manual APK/AAB build workflows. (`52835d1`)

---

## [1.0.3] — versionCode 12 — released to Play production 2026-07-17 (in review/rollout)

### Added
- **Kiosk touch bridge.** The shell now forwards every native touch (and key) to the
  web player via `window.__slidetvKioskActivity()`. Touches inside a cross-origin
  kiosk iframe never reach the page's JS (browser security), so the WebView shell is
  the only reliable place to detect them. This lets an interacted-with Kiosk slide
  keep its "don't auto-advance while in use" promise — each touch resets the slide's
  idle-grace countdown. Throttled (400 ms) so ACTION_MOVE streams don't flood
  `evaluateJavascript`. Verified on-device via ADB: continuous taps froze a kiosk
  slide's rotation (53s+ vs ~15s untouched) and rotation resumed once taps stopped.
  (`07fd66d`)

_Also carries forward the 1.0.2 fixes below (1.0.2 was never published)._

---

## [1.0.2] — versionCode 11 — **REJECTED** by Play (metadata/screenshots), never published

### Fixed
- **Manual Sleep/Wake now overrides the schedule.** A manual Wake Up during the
  sleep window no longer reverts to sleep after ~10s. The manual command holds
  until the schedule naturally reaches the same state, then auto-scheduling
  resumes. (`b09a7d2`)
- **No replay of historical remote commands on first poll.** After pairing or an
  app restart the command timestamps are baselined to the server's current
  values, so old sleep/wake/reload commands don't all fire at once. This fixed a
  case where a spurious wake command blocked the schedule from ever sleeping.
  (`6987162`)
- **Remote wake brings the player to the foreground** and dismisses the keyguard,
  so a wake command reliably shows content on a locked/backgrounded device.
  (`f27d2f6`)

### Internal / CI
- Added a `workflow_dispatch` "Build Test APK" workflow that produces a signed
  release APK artifact for `adb` testing (no GitHub release, no OTA). (`2c6e153`)
- Added this changelog. (`af6b664`)

### Store listing (not app code)
- Screenshots reshot to satisfy the metadata policy: no third-party brands, and
  each shot shows real app functionality (web dashboard, pairing, paired-idle,
  a menu-board content example, and the two admin-panel tabs).

---

## [1.0.1] — versionCode 10 — **REJECTED** by Play review (never published)

**Rejection reason:** Metadata policy — the store listing screenshots were
considered unclear/generic and contained unnecessary third-party brands. The APK
itself was not the problem; the listing was. Superseded by 1.0.2.

### Added
- **Official Play identity:** `applicationId = eu.slidetv.player`; `versionCode` /
  `versionName` are driven by CI env (run number / release tag).
- **Android TV support:** in-APK `android:banner` (320×180) + leanback launcher,
  so the app qualifies for the Android TV form factor.

### Fixed
- **Single screen identity.** The native shell now reuses the embedded web
  player's `slidetv_device_token` cookie instead of registering its own device
  via `/api/device/init`. Previously the shell polled a separate, unpaired
  screen, so the remote sleep/wake schedule and commands never reached the
  screen the user actually sees. Also removed the broken init call that crashed
  parsing the float `expires_in`. (`6545827`)

### Build
- Disabled the Gradle configuration cache (it broke KSP on CI with a fresh
  configuration). (`95f5a4a`)
- AAB build workflow takes a `version_name` input instead of a hardcoded value.

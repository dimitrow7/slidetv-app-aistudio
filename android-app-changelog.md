# SlideTV Player — Android App Changelog

Tracks changes to the Android player app (`eu.slidetv.player`).

- **[Unreleased]** = changes already on `main` but **not yet in a published Google
  Play production version**. They will ship in the next release.
- When a version is **published in Play Console**, its changes move under a dated
  version heading below. (The user tells the assistant when a version goes out.)

---

## [Unreleased]

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

### Internal / CI
- Added a `workflow_dispatch` "Build Test APK" workflow that produces a signed
  release APK artifact for `adb` testing (no GitHub release, no OTA). (`2c6e153`)

---

## [1.0.1] — versionCode 10 — submitted to Play production review (pending publish)

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

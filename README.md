# Repose

Pause a selected work profile and fully reboot an Android phone into **Before
First Unlock (BFU)**. Root enforcement runs independently of the settings app.

- Configurable time since the last unlock: always on, scheduled, or both.
- Independent, 24/7 failed-unlock threshold.
- Optional lock-screen panic PIN/password through Vector/LSPosed.
- SMS and HTTPS commands: plain passphrase, signed one-use commands, or both.
- Native Material settings, authenticated notification/Quick Settings controls,
  and credential-redacted diagnostic export.

## Install

Requires Android 13+, ARM64, Magisk or KernelSU, and a device screen lock.
Download **Repose.apk** and **Repose-module.zip** from the same
[release](https://github.com/doccstat/repose/releases/latest), install both, then
open Repose and select **Start root engine**. Configure the monitored user, optional
work profile and desired rules. All triggers start disabled.

For credential-failure monitoring, activate the app's device-admin receiver. It
requests only `watch-login`, not device-owner access or data-wipe permission.
For another user's failures, install and activate the matching APK in that user.
For authenticated-only unlock timing, biometric failures or a panic credential,
enable Repose's **System UI** scope in Vector/LSPosed and restart the framework.
The app reports whether the required hook contracts are available.

A full reboot clears temporary root. Enforcement becomes available again when
root and the module service are restored. A framework-only soft restart is **not
BFU** and is not a reboot action in Repose.

## Rules and controls

**Any unlock** includes trusted-device unlocks. Credential/biometric-only options
require the hook plus an actual lock-screen dismissal. Screen wakes and storage
unlock restoration never restart a timer. A fresh installation has no timestamp
until it observes a qualifying unlock; framework/module restarts preserve the timer.

Each timer has an explicit duration and “wait until locked” option. Scheduled
windows use the selected IANA time zone or the system zone, and selected start
days (Monday = 1). Overnight windows belong to their start day; equal start/end
means 24 hours. Android/Java zone rules resolve DST gaps forward and overlaps to
the earlier offset.

**Snooze** affects both timers. **Skip** affects only the current or next scheduled
window. Failed-unlock and panic triggers bypass both. Profile-pause failure can
abort the flow or continue to reboot, as selected. A failed/incomplete action is
latched until acknowledged; acknowledgment can immediately rearm an overdue timer.

Panic credentials must differ from the real credential and fit the lock-screen
input form: at least four PIN digits or printable-ASCII password characters.
Passphrases received remotely support Unicode. Input is never trimmed, renamed
or normalized. Blank credential fields preserve the saved value; **Clear**
explicitly removes it after disabling its dependent modes.

Enable SMS permission only when using SMS commands. The app does not become the
default SMS app or delete received messages. A plain passphrase can be replayed by
anyone who obtains it; signed commands have persistent one-use protection.
See [remote commands](docs/remote.md) for the sender and LastSignal inbox adapter.

## Diagnostics and builds

Use **Export diagnostics** in the app. Reports include version, platform,
configuration, hook capabilities, errors and operation results; credentials,
command bodies, device identifiers and inbox URLs are excluded. Export is local
and never uploaded automatically. Root startup failures are also recorded in
`/data/adb/repose/startup.log`.

[Build and test](docs/build.md) · [Language support](docs/i18n.md)

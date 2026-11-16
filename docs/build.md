# Build and test

Use JDK 21, Android platform/build-tools 36, an ARM64-capable Android NDK and
Python 3. The Gradle 8.13 wrapper checks its distribution checksum. Material
Components 1.14.0 provides the native UI; Xposed API 82 is compile-only.

Download `https://api.xposed.info/de/robv/android/xposed/api/82/api-82.jar` into a
build cache. Its SHA-256 must be
`f48c635f1c7469fdec0e00ad2ea0b7a6b2f5b55065784a35b7ca3a84615e8e25`.

```sh
export JAVA_HOME=/path/to/jdk-21
export ANDROID_HOME=/path/to/android-sdk
export ANDROID_NDK_HOME=/path/to/android-ndk
export XPOSED_API_JAR=/path/to/api-82.jar
python3 build.py --test
python3 build.py --lint
python3 build.py --debug
```

Default output is `~/.cache/repose/`. `--out` selects another directory outside
source, or exactly `dist/`. `ANDROID_AAPT2` can select a host-compatible aapt2;
`ANDROID_CC` can select an Android ARM64 compiler. Keep generated outputs out of
source control. Tests use no root or phone; OpenSSL and a host C compiler are needed
for sender and alarm syscall-contract tests. The Android build compiles the small
`observer/` adapter into an isolated DEX asset. Its Android/SystemUI stubs are
compile-only; the runtime adapter inherits the device's actual callback class.
Tests check both Android 13/15 Binder contracts, the newer enumeration signature,
quiet-mode caller/identity ordering, constructor-erased callback compatibility and
exclusion of platform stubs from the packaged adapter.

On ARM64 Linux, `tools/ndk_qemu.py` runs an unmodified Linux x86-64 NDK through
QEMU without installing binfmt rules. Set `ANDROID_CC` to that executable,
`ANDROID_NDK_HOME` to the extracted NDK, `QEMU_X86_64` to qemu-x86_64-static,
`QEMU_LD_PREFIX` to an x86-64 loader/library tree, and optionally
`QEMU_EXTRA_LIBRARY_PATH` for x86-64 libz. It executes the compiler's planned
frontend/linker commands without a shell. NDK r27c was used for the initial ARM64
build; the official Linux archive SHA-1 is
`090e8083a715fdb1a3e402d0763c388abb03fb4e`. Native libraries use 16 KB load alignment.

`--debug` creates an installable development-signed APK and matching module ZIP.
Without it, the APK is unsigned unless existing `SIGNING_KEYSTORE`,
`SIGNING_KEY_ALIAS`, `SIGNING_STORE_PASSWORD` and `SIGNING_KEY_PASSWORD` are
supplied. The builder never generates a release key. Keep permanent signing
credentials outside source and disposable caches. Both outputs have SHA-256
files; the module embeds the exact same APK bytes as the installed companion.
Android enforces signer continuity on updates.

## Device acceptance

On a test device, install the matched pair with all rules disabled. Confirm root
engine connection, profile identities, watch-login activation and hook capability
reports. On each ROM, check that the quiet-mode worker's attributed Binder request
succeeds and each biometric failure produces exactly one event after callback
registration. Also check that a matching panic credential returns a rejected
Android authentication result while dispatching the security action.
Exercise a short locked timer through suspend, a real unlock just before
the deadline, each failure/reset mode, panic PIN/password and both SMS modes.
Confirm the selected work profile stops and the device actually fully reboots;
then confirm primary CE storage stays locked until the first credential entry.

Also exercise missing/stale profiles, denied pause, root removal, rejected replay,
expired command, wrong device/key, HTTP failure, clock changes, snooze/skip,
framework restart, module-service replay, notification/tile authentication,
TalkBack, large fonts, RTL pseudolocale and process recreation. Device operations
are intentional reboot-class tests, not part of the host build command.

## Runtime state

The module keeps root-only state under `/data/adb/repose/`, separate from APK
preferences. Atomic writes preserve policy, kernel-boot identity, elapsed deadlines,
failure counters, action latches and the signed-command replay ledger. One file
lock owns the engine. A dead framework Binder requests a clean engine restart;
service-stage replay cannot create another policy engine.

Uninstalling the module retains this directory, including replay protection and
configuration. Deliberate permanent removal can delete it after disabling the
module; re-pair remote credentials before later reuse. Never remove only the
replay ledger while leaving the same remote public key enabled.

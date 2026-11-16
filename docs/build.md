# Build and test

Use JDK 21, Android platform/build-tools 36, Android NDK r27c (`27.2.12479018`)
and Python 3. The Gradle 8.13 wrapper checks its distribution checksum. Material
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
for sender and alarm syscall-contract tests; Bash and jq run the mocked release
publication checks. The Android build compiles the small `observer/` adapter into an isolated DEX asset. Its Android/SystemUI stubs are
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
The default build is unsigned unless signing credentials are supplied. `--release`
requires existing `SIGNING_KEYSTORE`, `SIGNING_KEY_ALIAS`, `SIGNING_STORE_PASSWORD`,
`SIGNING_KEY_PASSWORD` and `SIGNING_CERT_SHA256`. It verifies the APK signature and
exact certificate pin before packaging. The builder never generates a release key.
Keep permanent signing credentials outside source, output and disposable caches.
Both outputs have SHA-256 files; the module embeds the exact signed APK bytes.
Android enforces signer continuity on updates.

## GitHub releases

The `Android build` workflow runs tests/lint and uploads the unsigned APK/module
pair on main pushes, pull requests and manual dispatch. `Signed release` runs on
main pushes and manual dispatch from `main` in `doccstat/repose`. After tests and
lint, it builds the permanently signed pair and publishes a normal, latest GitHub
release. Both workflows upload test/lint reports as downloadable Actions artifacts.

Set up the repository's **release** environment for the `main` branch, without
required reviewers for automatic publication. Configure:

| Name | Environment setting |
| --- | --- |
| `ANDROID_KEYSTORE_BASE64` | Secret: base64 of the permanent keystore |
| `ANDROID_KEYSTORE_PASSWORD` | Secret: keystore password |
| `ANDROID_KEY_PASSWORD` | Secret: key password |
| `ANDROID_KEY_ALIAS` | Secret: key alias |
| `ANDROID_SIGNING_CERT_SHA256` | Variable: certificate SHA-256 fingerprint |

Keep the permanent key and its recovery material in durable private storage.
The workflow decodes a temporary runner copy with private permissions and removes
it on exit. Publication uses the repository's `GITHUB_TOKEN` with Contents write.

Set `versionCode` and `versionName` in `AndroidManifest.xml` and the matching values
in `module/module.prop`. Gradle and diagnostic versions derive from the manifest;
`python3 build.py --version` checks the pair and prints `versionCode-versionName`.
The initial tag is `1-1.0.0`, with release title `1.0.0`. Versions remain numeric,
without status suffixes. CI never invents or increments them.

A new main push at the same version replaces that tag's APK, module ZIP, checksums
and release notes. Bump both version fields deliberately for a new release. An old
run checks current `main` and skips publication if superseded. Releases are always
published with `draft=false`, `prerelease=false` and latest status.

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

# B-box's interface on Throne

> Status 2026-11-03: current. `Capabilities.kt` is the source of truth for what exists; `tools/diagnostics/channel_audit.py` (wired into CI) enforces the Dart/Kotlin contract on every push.

The app runs **B-box's own Flutter interface** over Throne's backend. `flutter/` starts as a copy of
`giturass/B-box`'s `flutter/` module (FlClash 0.9.2 presentation, M3 Expressive) — 99 files vendored
unchanged and 33 adapted, all recorded with hashes in `flclash-manifest.json`. On top of that the
port carries its own Dart code (the manifest's `ownFiles` partition: the channel client, the pages
Throne adds — routes, settings, logs — and the capability-gated shells), plus the Android host that
answers the channels the screens call. Claims of "nothing on the Dart side was rewritten" describe
the vendored subset only.

> **Build status.** The debug AAR builds and links. `flutter build aar --debug` produced
> `flutter/build/host/outputs/repo` (this workspace also copied the `io.flutter` engine artifacts from the Gradle
> cache into that repo, so the host app resolves them offline). Building it on a constrained network needs
> `FLUTTER_STORAGE_BASE_URL=https://storage.flutter-io.cn`, `kotlin.incremental=false` +
> `kotlin.incremental.useClasspathSnapshot=false` (the plugin's Pub cache lives on another drive than the build
> tree), and `kotlin.compiler.execution.strategy=in-process`. See *Build* below.

## Why a channel host instead of a rewrite

B-box's screens talk to Android through two channels and nothing else:

| Channel | Direction | Used for |
| --- | --- | --- |
| `io.ericlee.bfa/box` | Dart → Android | `ready`, `service.start/stop/reload`, `mode.set`, `groups.select/test`, `profiles.*`, `preferences.set`, `settings.get/set/patch`, `url.open`, … |
| `io.ericlee.bfa/events` | Android → Dart | `dashboard`, `rates`, `profiles`, `groups`, `settings`, `preferences`, `error`, `applyRequired`, … |

`BoxController` (`flutter/lib/box.dart`) is the only consumer, so implementing those two channels is what makes the
whole interface work — dashboard, proxies, profiles, tools and settings included. That is what
`app/src/main/java/io/throne/bbox/flutter/ThroneBoxBridge.kt` does.

## Layout

```
flutter/                     B-box's Flutter module (assets, shaders, 189 Dart files)
  pubspec.yaml               module: androidPackage io.ericlee.bbox_ui
  build/host/outputs/repo/   the local Maven repo `flutter build aar` writes

app/src/main/java/io/throne/bbox/
  bridge/                    the state and action layer (see docs/bbox-bridge.md)
  flutter/ThroneBoxBridge.kt the channel host: state out, actions in
  flutter/BboxFlutterActivity.kt  FlutterFragmentActivity + engine registration
```

`BboxFlutterActivity` is the launcher. It registers `ThroneBoxBridge` in `configureFlutterEngine`, which runs before
the first frame, so `BoxController.initialize()` gets a real `ready` answer instead of a missing plugin.

## Build

```bash
cd flutter
flutter create --template=module --project-name b_box_ui --org io.ericlee .   # once: writes .android/
flutter pub get
FLUTTER_STORAGE_BASE_URL=https://storage.flutter-io.cn flutter build aar      # debug + release
```

Things that bite, and their fixes:

* The module ships without an `android/` (or `.android/`) project, so `flutter create --template=module` has to run
  once first; without it Gradle walks up and finds the host app's `settings.gradle.kts` and refuses the build.
* `flutter build aar` must run where no parent `settings.gradle.kts` shadows it. Building a copy outside the repo and
  copying `build/host/outputs/repo` back works.
* The Gradle wrapper pulls `gradle-9.3.1-all.zip` from `services.gradle.org`, which redirects to GitHub; where GitHub
  is unreachable, pre-seed `$GRADLE_USER_HOME/wrapper/dists/gradle-9.3.1-all/<hash>/` from a mirror instead.
* Engine artifacts (`io.flutter:flutter_embedding_*`, `io.flutter:<abi>_<mode>`) come from
  `https://storage.flutter-io.cn/download.flutter.io` (or `storage.googleapis.com/download.flutter.io`). On a slow
  network set `FLUTTER_STORAGE_BASE_URL=https://storage.flutter-io.cn` before building. If the consuming app must
  resolve offline, copy those artifacts from the Gradle cache into the local repo's `io/flutter/` tree — the POM of
  `flutter_debug`/`flutter_release` depends on them.
* On Windows, if the plugin's Pub cache (e.g. `mobile_scanner`) is on a different drive than the build tree, Kotlin
  incremental compilation fails with `this and base files have different roots`; set `kotlin.incremental=false` and
  `kotlin.incremental.useClasspathSnapshot=false` in the module's `gradle.properties`. If the Kotlin daemon then
  hangs ("No compile task"), also set `kotlin.compiler.execution.strategy=in-process`.
* Network-bound Gradle dependency resolution is much faster with Aliyun mirrors (`maven.aliyun.com/repository/…`)
  in the module's `.android/settings.gradle`.

The AAR lands in `flutter/build/host/outputs/repo`; `repositories.gradle.kts` already points Gradle at it (plus the
engine fallback mirrors). `app/build.gradle.kts` depends on `io.ericlee.bbox_ui:flutter_release` — a plain
`implementation`, not a `debugImplementation`/`releaseImplementation` pair. Every variant links the release AAR,
because the debug one ships the JIT engine and Dart as interpreted kernel, which is ~150 MB of APK buying hot reload
that this host never exercises (the Dart lives in the module and is developed there). No `flutter` command is needed
to build the app afterwards, only after the Dart side changes.

> **`--release` from now on.** Only the release AAR is consumed, so `flutter build aar` has produced one artifact too
> many. Keeping the debug copy published costs nothing at build time, but nothing needs it either.

> **Always `clean` after changing this dependency.** Switching which AAR is linked leaves Gradle's incremental
> packaging producing APKs padded with tens of MB of zero bytes between entries — 96.86 MB reported where the real
> content was 60.38 MB. `stat` and the sum of ZIP entry sizes disagreeing is the tell; `:app:clean` clears it.

### Version constraints between the module and the host

The Flutter toolchain and Throne's are pinned differently, and both sides have to give way where they can:

| Where | Constraint |
| --- | --- |
| `flutter/.android/settings.gradle` | Flutter's Gradle plugin requires AGP >= 8.11.1 and KGP >= 2.2.20 (`DependencyVersionChecker`), so the module builds with AGP 9.1.0 / Kotlin 2.2.20 — Kotlin 2.4.0 from the template would also work but produces newer metadata than needed. |
| `app` (`buildSrc/…/Helpers.kt`) | `compileSdk = 36` because every AAR published above declares `minCompileSdk=36`; `targetSdk` stays 35. |
| `app` (`buildSrc/…/Helpers.kt`) | `-Xskip-metadata-version-check`, because Kotlin 2.0.21 must load AARs whose metadata is 2.2. Raising the whole toolchain to Kotlin 2.2 would ripple through KSP and Room, so relaxing this one check is the smaller change. |
| `app/build.gradle.kts` | CameraX forced to 1.5.3 and Kotlin artifacts to the app's own versions; see the comments there. |

Put together, that means a rebuild after touching anything under `flutter/lib/` looks like:

```bash
FLUTTER_STORAGE_BASE_URL=https://storage.flutter-io.cn flutter build aar --debug   # inside the module copy
cp -r <module copy>/build/host/outputs/repo/. throne-src/flutter/build/host/outputs/repo/
# then re-publish io/flutter engine artifacts from ~/.gradle if the local repo was wiped
```

## What the host maps

| Channel call | Throne |
| --- | --- |
| `ready` | `BboxBridge.state` + `BboxCatalog` → the bootstrap map the UI expects |
| `service.start` / `service.stop` | `BboxCommands.toggleService()` / `SagerNet.stopService()` |
| `service.reload` | `SagerNet.reloadService()` |
| `mode.set` | `BboxCommands.setMode` (the routing-profile rewrite in `BboxMode`) |
| `groups.select` | `BboxCommands.selectProxy`, which broadcasts `Action.SWITCH_PROFILE` for a hot switch |
| `groups.test` | `TestSessionClient` with `TestSpec(KIND_URL, …)` |
| `profiles.select` / `profiles.delete` | `GroupRepo.setCurrent` / `GroupRepo.delete` |
| `preferences.set` | a `bbox_ui` `SharedPreferences` file, as B-box keeps its own |
| `settings.get/set/patch` | `DataStore.proxyApps`, `DataStore.bypass`, … |
| `url.open` | `ACTION_VIEW` |

Events are pushed by diffing `BboxBridge.state`: `dashboard` and `rates` when the status, totals or speed change,
`profiles` and `groups` when the catalog changes. The UI's own throttling (30 samples, `sampleRevision`) is untouched.

## Not wired yet

These answer with an `unsupported` error, which the UI shows as a snackbar. All are B-box features
Throne has no counterpart for; `Capabilities.kt` is the single source of truth and prunes the
navigation accordingly:

`terminal`, `capture.*` (request debugging), `remote.*`, `reports`, the `stun` / `quality` tools,
`endpoints` (tailscale / openvpn / openconnect / usbip), `taildrop`, `perAppManaged` (no
Shizuku-managed package list), `rootQuery` (packages come from PackageManager only), the `dnsObject`
editor, and `warp` as a dedicated page (WARP is configured through the core). `apps.permission`
refuses with an explanation rather than being absent: the list needs no Shizuku grant.

Everything else answers: `webdav.*`, `backup.*`, `apps.list` / `apps.icon`, `connections.*`,
`logs.*`, `updates.check`, `dashboard.probe.*` and the rest are all wired.

## Television entry

`BboxFlutterActivity` carries both launcher filters — `LAUNCHER` for handhelds and `LEANBACK_LAUNCHER` for Android TV —
so a television boots into the Flutter interface rather than the old native shell. D-pad navigation needs no port:
Flutter routes those keys through its own focus tree, which `BboxFlutterActivity` leaves alone.

One habit did have to come across by hand, because a television has neither a notification shade nor a quick-settings
tile: the remote's play/pause button still toggles the proxy. It is caught in `dispatchKeyEvent` (before Flutter's key
channel can swallow it) and forwarded to `BboxCommands.toggleService()`, exactly as `MainActivity` used to do.
`MainActivity` keeps its backup-import and quick-settings-tile intent filters and nothing else.

## Packaging: dropping the Vulkan validation layer

The debug engine ships `libVkLayer_khronos_validation.so` — 237 MB uncompressed, ~65 MB inside the APK, second only to
`libflutter.so` itself. It is a graphics-debugging layer with no runtime role, and the release engine jar has zero
`VkLayer` entries, so nothing can depend on it. `app/build.gradle.kts` excludes it:

```kotlin
packaging {
    jniLibs {
        useLegacyPackaging = true
        excludes += setOf("**/libVkLayer_khronos_validation.so")
    }
}
```

What remains of the bulk has nothing to do with Flutter either — see below.

## Why the native libraries were enormous: nothing was being stripped

For a while this app produced a 272 MB debug APK, and `libflutter.so` alone accounted for 377 MB uncompressed.
Neither figure is Flutter's fault. Both come from every native library shipping with its full debugging symbols,
because the strip task silently gave up:

```
Unable to strip the following libraries, packaging them as they are:
libbarhopper_v3.so, libflutter.so, libimage_processing_util_jni.so,
libsurface_util_jni.so, libthrone.so
```

This app has no C/C++ of its own, so nothing had ever set `ndkVersion` — and without it the strip task cannot locate
`llvm-strip`. Setting `ndkVersion` in `buildSrc/Helpers.kt` was the whole fix:

| | before | after |
| --- | --- | --- |
| `libflutter.so` | 377.53 MB (327.57 MB of it `.debug_*`) | 37.05 MB, zero symbol sections |
| APK (arm64-v8a) | 272.42 MB | 96.66 MB |

Note that the *release* engine jar is 157 MB for the same reason — 139 MB of it is `.debug_*`, leaving 6 MB of actual
`.text`. The engine sizes quoted earlier in this file were both measured on unstripped libraries; stripped, the debug
engine is 37 MB. Any Flutter app that forgets `ndkVersion` pays this, whichever build type it uses.

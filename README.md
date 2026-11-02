# Flrone (Throne for Android)

An Android GUI proxy utility on the [sing-box](https://github.com/SagerNet/sing-box) core: Throne's engine with a
fully rebuilt Flutter interface in the B-box / FlClash style. Supports Android 7.0 (API 24) and newer, including
Android TV.

## What this repository is

A downstream distribution of [throneproj/ThroneForAndroid](https://github.com/throneproj/ThroneForAndroid): the
core (sing-box + Xray, protocol and configuration surface) is pinned per release to Throne's own (`THRONE_CORE_REF`
in `nb4a.properties`) and built by CI, while everything above it — the interface, the bridge layer, the release
line — is owned here.

- **One interface**: the Flutter UI is the app's only screen as of 3.0.0; the legacy Fragment interface retired
  entirely (the VPN notification, share/open intents and the quick settings tile all land in it).
- **Engine follows upstream**: core upgrades ride upstream releases; this repository does not carry core patches.
- Upstream's own releases, docs ([throneproj.github.io](https://throneproj.github.io)) and donation options live in
  the [upstream repository](https://github.com/throneproj/ThroneForAndroid).

### Install and update

- Releases ship three ABI splits (arm64-v8a, armeabi-v7a, x86_64) with SHA256SUMS from
  [GitHub Releases](https://github.com/yuedaochangmendian/throne-flclash-ui/releases); the in-app update check
  verifies size and SHA-256 before offering the installer.
- Releases from v2.0.0 on are signed with a key that Android refuses to install over a 1.x release (signature
  conflict): uninstall the old release first and import subscriptions again.

## Features

- Groups and subscriptions that work like Throne desktop: per-group subscription options (filters, clean-up, URL test after an update), front / landing proxy, HWID headers, scheduled updates with change reports
- Backups in the desktop's `.thrbackup` format, restorable on either side: save, share or sync over WebDAV
- Route profiles shared with desktop, preloaded from [routeprofiles](https://github.com/throneproj/routeprofiles), plus Android-only rule conditions: apps, Wi-Fi network (SSID / BSSID), network type and metered networks
- Auto Selector profiles that keep the best server of one or more groups ranked and switch automatically
- Cloudflare WARP: registration (WireGuard or MASQUE identity) and a built-in WARP mode for routing
- URL, IP / country and speed tests, with a bulk-test panel (live progress, latency histogram, fastest servers, sort / remove unavailable / connect to fastest)
- The sing-box dashboard built in (live traffic, connections, logs)
- The home status card shows a vector country flag cropped to its animated badge; tap to show the country code, or long-press to refresh the exit IP
- Core logs and Logcat support level filters, search, pause, clear and redacted export; cancelling the save dialog leaves the logs untouched; known-benign connection-reset errors fold behind a counter, and uncaught app-layer exceptions keep their own seven-day record exportable as one file
- JSON editor with sing-box schema checking, completion and formatting
- Home-screen widgets, a quick settings tile, and server switching from the notification
- Android TV: launcher banner, D-pad navigation, previous / next server

## Supported protocols

- SOCKS
- HTTP(S)
- Shadowsocks
- Trojan
- VMess
- VLESS
- TUIC
- Hysteria
- Hysteria2
- AnyTLS
- Mieru
- Snell
- NaïveProxy
- Juicity
- TrustTunnel
- ShadowTLS
- Wireguard
- AmneziaWG
- MASQUE
- SSH
- Xray VLESS
- OpenVPN/OpenConnect
- Custom Outbound (Both Sing-box and Xray)
- Custom Config (Both Sing-box and Xray)
- Chaining outbounds

## Subscription Formats

Various formats are supported, including share links, various JSON representation of Sing-box configs, and v2rayN link format as well as limited support for Shadowsocks and Clash formats. Only the outbounds (servers) of a subscription are imported; its routing rules are ignored.

Deeplinks are also supported, read the [documentation](https://throneproj.github.io/advanced/deeplinks/) for more information.

## Development

- **CI** (`ci.yml`, every push): Kotlin unit tests + debug build (with the ThroneCore AAR built from the pinned
  upstream ref), the Flutter module's `analyze --fatal-infos` + widget tests, a
  [channel contract audit](tools/diagnostics/channel_audit.py) that fails when a bridge method or event loses an
  end, and a scan proving the freshly built Flutter AAR only calls APIs the pinned kotlin-stdlib/coroutines
  provide.
- **Releases** (`release.yml`, manual): tag = `VERSION_NAME` from `nb4a.properties`, notes from the matching
  `CHANGELOG.md` section.
- **Specs**: `openspec/specs/` holds the governing specifications; changes go through `openspec/changes/`.
- **Design documents**: [docs/bbox-bridge.md](docs/bbox-bridge.md) (the bridge layer),
  [docs/bbox-flutter.md](docs/bbox-flutter.md) (the Flutter module), [docs/device-checklist.md](docs/device-checklist.md)
  (the pre-release device gate), [docs/roadmap.md](docs/roadmap.md) (where the project is heading).
- `Capabilities.kt` is the single source of truth for which B-box-only features exist; the bridge documents
  every method and event it serves.

## Credits

- [throneproj/sing-box](https://github.com/throneproj/sing-box), a fork of [SagerNet/sing-box](https://github.com/SagerNet/sing-box)
- [throneproj/Xray-core](https://github.com/throneproj/Xray-core), a fork of [XTLS/Xray-core](https://github.com/XTLS/Xray-core)
- [throneproj/ThroneForAndroid](https://github.com/throneproj/ThroneForAndroid), the engine and data model this project follows

Android GUI:

- [MatsuriDayo/NekoBoxForAndroid](https://github.com/MatsuriDayo/NekoBoxForAndroid)
- [SagerNet/SagerNet](https://github.com/SagerNet/SagerNet)
- [shadowsocks/shadowsocks-android](https://github.com/shadowsocks/shadowsocks-android)
- [giturass/B-box](https://github.com/giturass/B-box), the interface this UI was adapted from

## FAQ

**How does this differ from Throne or NekoBox for Android?** <br/>
It started as a NekoBox for Android fork; v2.0.0 replaced its core and data model with Throne desktop's. This
distribution then replaced Throne's own interface with the Flutter one described above — same engine, same
profile types, share links, route profiles and backup format, one modern screen.

**Where are the downloadable route profiles/rulesets coming from?**<br/>
They are located at the [routeprofiles](https://github.com/throneproj/routeprofiles) repository, the same one Throne desktop uses.

**Why are some servers skipped when I import a link or a subscription?**<br/>
As on desktop, Extra Core, Chain and Auto Selector profiles are never imported from links, subscriptions or shared route profiles; create them in the app instead. Nodes the app cannot run are skipped too, and the import reports what was left out.

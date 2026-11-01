# The B-box bridge

The ported interface (`:bboxui`) renders one immutable snapshot and sends every gesture through one object. Throne's
backend does not work that way: its state lives in four places and the old fragments read each of them directly. This
document describes the layer in `app/src/main/java/io/throne/bbox/bridge` that reconciles the two, replacing the
fragment-owned service connections of the old UI.

## Why a bridge at all

B-box's screens (FlClash's) are written against a controller that exposes

* a snapshot — `dashboard`, `groups`, `profiles`, `preferences`, traffic history, logs, connections;
* an action bus — `box.act('mode.set', {...})`, `box.act('service.start')`, `box.act('proxy.select', {...})`;
* change notifications — one flow per aspect, so a screen rebuilds only what changed.

Throne instead has `ISagerNetService` (a bound AIDL service with speed/state callbacks), `ProfileManager` and
`GroupRepo` (Room repositories with in-process listeners), `DataStore` (a preference store), and `RouteManager`
(the routing profiles). Reproducing B-box's screens faithfully means giving them the first shape; the bridge is that
translation, not a set of adapters bolted onto each screen.

## Shape

```
        ┌──────────────────────────── BboxBridge (singleton) ───────────────────────────┐
        │  state: StateFlow<BboxUiState>        meter: BboxTrafficMeter                  │
        │  one SagerConnection   GroupRepo.Listener   ProfileManager.Listener           │
        └───────▲───────────────────────┬───────────────────────────┬───────────────────┘
                │                       │                           │
     SagerConnection callbacks     BboxCatalog.load()          BboxEgress.probe()
     (state, speed, selector)      (groups, profiles)         (mixed inbound)
                │                       │                           │
        ┌───────┴───────────────────────┴───────────────────────────┴───────────────────┐
        │  BboxCommands : BboxActions — the write half                                  │
        └───────────────────────────────────────────────────────────────────────────────┘
```

| File | Role |
| --- | --- |
| `BboxBridge.kt` | Owns the single service connection and both repository listeners, mirrors everything into `BboxUiState`, debounces bursts of change notifications, ticks the run time and memory once a second. |
| `BboxCatalog.kt` | Maps the catalog: a Throne group is a B-box group, its members are the nodes, the selected profile is the selection; a subscription group is also a B-box profile card with its quota and expiry. |
| `BboxTrafficMeter.kt` | Turns `SpeedDisplayData` into the dashboard's rates, totals and the two 30 point sparklines. |
| `BboxMode.kt` | The outbound mode switch, implemented as a routing-profile rewrite (see below). |
| `BboxEgress.kt` | The outbound IP, country and organisation, probed through the running mixed inbound. |
| `BboxCommands.kt` | Every write: start/stop, mode, selection, tests, subscription refresh, delete, open URL. |
| `BboxRuntime.kt` | Installs the bridge from `SagerNet.onCreate` in the main process and hands `CoreConfigs` the mode-aware routing input. |
| `app/.../flutter/ThroneBoxBridge.kt` | The host of B-box's own channels: turns this state into the `dashboard` / `rates` / `profiles` / `groups` events and the actions into the calls the Flutter screens make. |

## Data mapping

| B-box | Throne | Note |
| --- | --- | --- |
| `dashboard.status` | `DataStore.serviceState` + the service's `profileName` | `startedAt` is stamped when the state first becomes `Connected`, since Throne keeps no start time. |
| `dashboard.modes` / `mode` | `BboxMode` (stored in the configuration store) | Rule / Global / Direct, see below. |
| `dashboard.groups[]` | `GroupRepo.all()` + `ProfileManager.members(gid)` | `selected` is `DataStore.selectedProxy`. |
| node `delay` / `testedAt` | `ProxyEntity.latency` / `latencyAt` | Seconds in the row, milliseconds in the model. |
| `dashboard.profiles[]` | subscription groups (`ProxyGroup.isSubscription`) | `traffic` / `expiry` come from `SubUserInfo` (`groups.sub_metadata_json`). |
| `dashboard.egress` | `BboxEgress` | IP, country, organisation, ASN and address family. |
| `dashboard.memory` | `Runtime` | B-box shows the core's RSS; Throne has no such figure, so the process heap is shown instead. |
| `dashboard.intranet` | — | Throne exposes no LAN addresses; the panel stays hidden. |

## Actions

| Action | Implementation |
| --- | --- |
| `toggleService` | `SagerNet.startService()` / `SagerNet.stopService()`; a start with nothing selected is refused and logged. |
| `setMode` | `BboxMode.set` then `SagerNet.reloadService()` when running. |
| `selectProxy(group, tag)` | Resolve the profile by name, write `selectedProxy` + `currentProfile`, broadcast `Action.SWITCH_PROFILE` so a running core switches instead of restarting, then reload the rows. |
| `testGroup` / `testProxy` | `TestSessionClient.start(TestSpec(KIND_URL, ...))`; the results are persisted by `:bg` and arrive back through `ProfileManager.Listener`. |
| `selectProfile` | `GroupRepo.setCurrent`; when stopped, the group's first member becomes the selection. |
| `updateSubscription` | `SubscriptionQueue.refreshGroup(gid, showDiff = true)`. |
| `deleteProfile` | `GroupRepo.delete` (its profiles go by the foreign key cascade). |
| `openUrl` | `ACTION_VIEW` on the application context. |

## Outbound mode

Throne has no `route.final` switch; a route profile's default outbound and rule list decide where traffic goes. The
switch therefore rewrites the profile it hands to the config generator, in `CoreConfigs.buildMain`:

* **Rule** — the profile exactly as saved (no allocation, the common path).
* **Global** — the profile's rules are dropped and the default outbound becomes `proxy` (the started profile).
* **Direct** — the same, with `direct` as the default outbound.

The `hijack-dns` rule survives every mode: without it the core's own DNS requests would leave unhandled. The rewrite
happens on a copy, so the stored profile and the desktop's `.thrbackup` files are never modified.

## Lifecycle

`SagerNet.onCreate` installs the bridge in the main process only. That is earlier than any activity, so the first
frame already has the service state and the catalog; it also survives rotation, which the old per-fragment connection
did not. `BboxFlutterActivity` therefore holds no backend state of its own — it registers the channel host and lets the
Flutter screens read the flow.

The `:bg` process is untouched: it still builds configs, runs the core and persists test results. `BboxMode` is read
in `:bg` when a config is built, from the same shared preference table, so a start triggered by the tile or a shortcut
honours the mode as well.

## Not wired yet

* The connections and logs screens: the models exist (`ConnectionInfo`, `logs`), but the sources are the sing-box
  clash API and `CoreLog`'s buffer, which need their own readers.
* `addProfile`: importing is an activity flow (`ProfileSelectActivity`, the scanner, file pickers); the card opens it
  once that flow is ported.
* The per-node IP / quality probe of the status card: B-box asks libbox for a routed probe; only the egress identity is
  read here.

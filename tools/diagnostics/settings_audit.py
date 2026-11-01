"""settings audit diagnostic for the B-box UI settings surface.

Three-way diff of what Throne stores, what the bridge exposes and what the UI edits:
  - store keys: SettingsRegistry.kt (`bool|int|long|string|oneOf|stringList("key" ...)`) and the
    `Key` object in Constants.kt (the camelCase keys DataStore binds through ktx delegates)
  - bridge keys: SettingsSchema.kt (`boolEntry|intEntry|textEntry|listEntry("key" ...)` and
    `Entry(bridgeKey = "key" ...)`) plus the RESET_ROW_ALIASES store-row map
  - UI keys: flutter/lib/pages/throne.dart (`Setting*(<title>, 'key' ...)`) and the keys each
    page's restoreDefaultsAction resets

A store key is covered when it is the row of some schema entry (camelToSnake(key), the key
itself, or an alias). A bridge key is covered when a Setting* control edits it. A UI key is
covered when a schema entry backs it.

The keys no column claims fall in three non-actionable books, kept apart so the diff only names
what batch work remains: NON_SETTING (runtime state, managed by code), SHELL_OWNED (a user
setting the B-box shell edits through its own store or flow, not the Throne settings surface) and
RESTORE_ONLY (a key no settings page on either side ever edits; it travels through restores).

Run from the repository root:
    uv run tools/diagnostics/settings_audit.py [--summary]

Exit code 0 when every column is covered, 1 when any gap is listed.
"""

from __future__ import annotations

import argparse
import re
import sys
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parents[2]
REGISTRY = REPO_ROOT / "app/src/main/java/io/nekohasekai/sagernet/database/SettingsRegistry.kt"
CONSTANTS = REPO_ROOT / "app/src/main/java/io/nekohasekai/sagernet/Constants.kt"
SCHEMA = REPO_ROOT / "app/src/main/java/io/throne/bbox/bridge/SettingsSchema.kt"
THRONE_DART = REPO_ROOT / "flutter/lib/pages/throne.dart"

STORE_CALL = re.compile(r'(?:bool|int|long|string|oneOf|stringList)\(\s*"([A-Za-z0-9_]+)"')
KEY_CONST = re.compile(r'const val [A-Z0-9_]+ = "([A-Za-z0-9_]+)"')
BRIDGE_KEY = re.compile(
    r'(?:boolEntry|intEntry|textEntry|listEntry)\(\s*"([A-Za-z0-9_]+)"'
    r'|bridgeKey = "([A-Za-z0-9_]+)"'
    r'|Entry\(\s*"([A-Za-z0-9_]+)"'
)
ALIAS_ROW = re.compile(r'"([A-Za-z0-9_]+)"\s+to\s+"([A-Za-z0-9_]+)"')
_Q = r"[\"']"
UI_KEY = re.compile(r"Setting(?:Toggle|Text|Number|Lines|DnsServer|Json)\(\s*" + _Q + r"[^\"']*" + _Q + r"\s*,\s*" + _Q + r"([A-Za-z0-9_]+)" + _Q)
UI_CHOICE = re.compile(r"SettingChoice<[^>]*>\(\s*" + _Q + r"[^\"']*" + _Q + r"\s*,\s*" + _Q + r"([A-Za-z0-9_]+)" + _Q)
# settings.dart's own switch primitive, which reads box.settings and writes box.setSetting.
UI_NATIVE = re.compile(r"NativeToggle\(\s*" + _Q + r"[^\"']*" + _Q + r"\s*,\s*" + _Q + r"([A-Za-z0-9_]+)" + _Q)
UI_SET = re.compile(r"setSetting\(\s*" + _Q + r"([A-Za-z0-9_]+)" + _Q)
UI_READ = re.compile(r"settings\[\s*" + _Q + r"([A-Za-z0-9_]+)" + _Q + r"\s*\]")
RESTORE_KEYS = re.compile(r"restoreDefaultsAction\(\s*context\s*,\s*const\s*\[([^\]]*)\]")
RESTORE_KEY_ITEM = re.compile(r"[\"']([A-Za-z0-9_]+)[\"']")

# Store keys that are runtime state rather than user settings: they carry a selection, a
# history or a service status and are managed by code, never edited on a settings page.
# They are listed here so the diff only names actionable gaps.
NON_SETTING = {
    "current_group",            # GroupRepo's selected group
    "current_route_id",         # the route profile in use
    "direct_dns_history",       # DnsServerHistory input log
    "remote_dns_history",
    "route_auto_update_last",   # update timestamps
    "sub_auto_update_last",
    "profileId",                # DataStore.selectedProxy: the started node
    "profileCurrent",           # service bookkeeping
    "profileDirty",
    "profileGroup",
    "runningProfiles",
    "runningRoute",
    "serviceError",             # last start failure, shown as status
    "serviceErrorDns",
    "serviceErrorGeo",
    "serviceErrorRuleSets",
    "deviceId",                 # device identity for subscriptions
    "deviceName",
    "hwidFallback",
    "batteryPromptShown",       # one-shot prompts
    "previewHintDismissedVersion",
    "wifiPermissionAsked",
    "updateSkippedVersionCode",
    "resumeAfterUpdate",
    "logExportRedact",          # the log export dialog's checkbox state
    "subscriptionLink",         # share-sheet scratch
    "groupName",                # group form scratch
    "groupType",
    "warp_amnezia_packets",     # generated with the WARP identity, never edited
    "warp_masque_ifc_addrs",    # written by the MASQUE registration, never edited
    "proxy",                    # Key names shared with intents, not settings
    "vpn",
    "serverConfig",
}

# UI settings the shell keeps in its own SETTINGS_KEY JSON overlay (applySettings' else branch)
# instead of ThroneSettings: they are B-box presentation keys Throne has no counterpart for.
OVERLAY_SETTING = {
    "fontFamily",
    "fontFollowTheme",
    "fontPath",
    "fontSize",
}

# User settings the B-box shell edits through its own store or its own flow rather than the
# Throne settings surface: the appearance page keeps its own theme and language, the B-box list
# presentation replaces the old layout options, and the backup page owns the WebDAV account
# through the webdav.* channels.
SHELL_OWNED = {
    "appTheme",              # appearance.dart
    "useSystemTheme",        # appearance.dart
    "nightTheme",            # appearance.dart
    "amoledTheme",           # appearance.dart
    "appLanguage",           # the Flutter l10n picks zh/en from the system locale
    "groupLayoutMode",       # the B-box group presentation
    "compactProfileRows",    # the B-box profile list presentation
    "compactGroupCards",     # the B-box group card presentation
    "logFontSize",           # the B-box log page keeps its own text size
    "traffic_graph_hidden",  # the B-box dashboard's sparklines are not configurable
    "traffic_graph_hidden_dirs",
    "traffic_graph_range",
    "traffic_graph_smooth",
    "traffic_graph_markers",
    "webdavServer",          # webdav.* channels, edited on the backup page
    "webdavUsername",
    "webdavPassword",
    "webdavPath",
}

# Keys no settings page on either side ever edits: they travel through .thrbackup restores and
# desktop sync, and the code reads them where it needs them.
RESTORE_ONLY = {
    "appTLSVersion",         # Nets.kt restricts the app's own TLS; no page surfaces it
    "notificationActions",   # ServiceNotification picks its buttons from it
    "show_config_security",  # ProfileRowHolder reads it for its display flags
    "xray_vless_preference", # SettingsMapper reads it when the importer builds a VLESS node
    "xray_geoip_url",        # XrayGeoAssets reads it when a config needs geoip.dat
    "xray_geosite_url",      # XrayGeoAssets reads it when a config needs geosite.dat
}

# Every book the three-way diff consults before it names a store key as a gap.
NON_ACTIONABLE = NON_SETTING | SHELL_OWNED | RESTORE_ONLY


def camel_to_snake(name: str) -> str:
    return re.sub(r"(?<!^)(?=[A-Z])", "_", name).lower()


def read(path: Path) -> str:
    return path.read_text(encoding="utf-8")


def parse_store_keys() -> tuple[set[str], set[str]]:
    registry = set(STORE_CALL.findall(read(REGISTRY)))
    key_object = read(CONSTANTS)
    key_block = key_object[key_object.index("object Key {"):]
    # Stop at the object's closing brace; later top-level `const val` lines are other constants.
    end = key_block.index("\n}")
    key_block = key_block[:end]
    constants = set(KEY_CONST.findall(key_block))
    return registry, constants


def parse_schema() -> tuple[set[str], dict[str, set[str]]]:
    text = read(SCHEMA)
    keys = {a or b or c for a, b, c in BRIDGE_KEY.findall(text)}
    keys.discard("")
    alias_block = text[text.index("RESET_ROW_ALIASES"):]
    alias_block = alias_block[: alias_block.index(")")]
    aliases: dict[str, set[str]] = {}
    for key, row in ALIAS_ROW.findall(alias_block):
        aliases.setdefault(key, set()).add(row)
    return keys, aliases


def parse_ui() -> tuple[set[str], set[str]]:
    # Consumption is spread over the pages: the Setting* primitives edit by key, and the
    # custom sheets (access, settings) call setSetting / read settings by key directly.
    controls: set[str] = set()
    restored: set[str] = set()
    for path in (REPO_ROOT / "flutter/lib").rglob("*.dart"):
        text = path.read_text(encoding="utf-8")
        controls |= set(UI_KEY.findall(text))
        controls |= set(UI_CHOICE.findall(text))
        controls |= set(UI_NATIVE.findall(text))
        controls |= set(UI_SET.findall(text))
        controls |= set(UI_READ.findall(text))
        for block in RESTORE_KEYS.findall(text):
            restored |= set(RESTORE_KEY_ITEM.findall(block))
    return controls, restored


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--summary", action="store_true", help="counts only")
    args = parser.parse_args()

    registry, constants = parse_store_keys()
    bridge, aliases = parse_schema()
    ui_controls, ui_restored = parse_ui()

    # Every store row a schema entry covers, however the row is named.
    covered_rows: set[str] = set()
    for key in bridge:
        covered_rows.add(key)
        covered_rows.add(camel_to_snake(key))
        covered_rows |= aliases.get(key, set())

    registry_missing = sorted(key for key in registry if key not in covered_rows and key not in NON_ACTIONABLE)
    constants_missing = sorted(key for key in constants if key not in covered_rows and key not in NON_ACTIONABLE)
    bridge_missing_ui = sorted(key for key in bridge if key not in ui_controls and key not in NON_SETTING)
    ui_missing_bridge = sorted(
        key for key in ui_controls if key not in bridge and key not in NON_SETTING and key not in OVERLAY_SETTING)
    restored_missing_control = sorted(key for key in ui_restored if key not in ui_controls)

    gaps = {
        "registry store keys with no schema entry": registry_missing,
        "Key constants with no schema entry": constants_missing,
        "schema bridge keys with no Setting* control": bridge_missing_ui,
        "Setting* keys with no schema entry": ui_missing_bridge,
        "restoreDefaults keys with no control on the page": restored_missing_control,
    }

    if args.summary:
        for title, items in gaps.items():
            print(f"{len(items):4d}  {title}")
    else:
        for title, items in gaps.items():
            print(f"## {title} ({len(items)})")
            for item in items:
                print(f"  - {item}")
            print()
    return 1 if any(gaps.values()) else 0


if __name__ == "__main__":
    sys.exit(main())

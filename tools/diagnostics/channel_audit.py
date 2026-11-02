"""MethodChannel contract audit for the B-box bridge.

Cross-checks, statically, the two ends of the bridge that `flutter analyze` and
the Kotlin compiler cannot see (the contract lives in string literals):

  - Dart -> Kotlin: every method the UI invokes (`box.call('…')` / `box.act('…')`,
    dotted or bare inside BoxController) must have a `when(call.method)` handler
    in ThroneBoxBridge.kt — unless the method belongs to a capability that is
    switched off in Capabilities.kt (those pages ship unreachable by design;
    flipping a capability to true without adding its handlers fails this audit)
  - Kotlin -> Dart: every event the host emits (`emit("…", …)`) must be consumed
    by a `case '…'` in the Dart event dispatcher (flutter/lib/box.dart)
  - dispatch sites whose method is not a literal (ternaries, widget arguments)
    are held to a declared allowlist in DYNAMIC_SITES; a new site fails the audit
  - handlers with no caller are listed (with --verbose) as dead-weight candidates

Run from the repository root:
    uv run tools/diagnostics/channel_audit.py [--verbose]

Exit code 0 when every call has a handler and every event a consumer, 1 otherwise.
"""

from __future__ import annotations

import argparse
import re
import sys
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parents[2]
FLUTTER_LIB = REPO_ROOT / "flutter" / "lib"
BRIDGE = REPO_ROOT / "app" / "src" / "main" / "java" / "io" / "throne" / "bbox" / "flutter" / "ThroneBoxBridge.kt"
CAPABILITIES = REPO_ROOT / "app" / "src" / "main" / "java" / "io" / "throne" / "bbox" / "bridge" / "Capabilities.kt"
DART_DISPATCH = FLUTTER_LIB / "box.dart"

# Bridge dispatches only: the receiver is a box-ish identifier (`box`, `_box!`,
# `context.box`), so an unrelated `.call(` on a callback field never matches.
# `?.call(` on a Dart Callable is not a dispatch and the `?` lookbehind keeps it
# out. The bare form (`call('…')` inside BoxController) is matched separately.
RECEIVER = r"\w*box\s*!?\??\s*\.\s*(?:call|act)"
CALL_RE = re.compile(rf"(?<!\?)(?:{RECEIVER}|(?<![\w?.])(?:call|act))\(\s*'([a-zA-Z][a-zA-Z0-9_.:]*)'", re.IGNORECASE)
DYNAMIC_CALL_RE = re.compile(rf"(?<!\?)(?:{RECEIVER}|(?<![\w?.])(?:call|act))\(\s*(?!['\s)])", re.IGNORECASE)
KOTLIN_METHOD_RE = re.compile(r'"([a-zA-Z][a-zA-Z0-9_.:]*)"\s*->')
EMIT_RE = re.compile(r'\bemit\(\s*"([a-zA-Z][a-zA-Z0-9_]*)"')
DART_CASE_RE = re.compile(r"case\s+'([a-zA-Z][a-zA-Z0-9_]*)'")
CAPABILITY_RE = re.compile(r'"([a-zA-Z]+)"\s+to\s+(true|false)')

# Handlers are dotted (`word.word…`) with the single historical exception of
# `ready`; anything else a `"…" ->` match picks up inside the when block (nested
# data mappings like "Stopping", "directDns") is not a handler.
METHOD_NAME_RE = re.compile(r"^[a-z][a-zA-Z0-9_]*(\.[a-zA-Z0-9_.]+)+$|^ready$")

# Methods invoked through a disabled capability's UI. Key = Capabilities.kt key,
# values = the bridge-method prefixes that belong to it. The vendored B-box pages
# behind `false` call these, the bridge answers none of them — expected absence.
# Turning a capability on without landing its handlers fails this audit.
CAPABILITY_PREFIXES: dict[str, set[str]] = {
    "terminal": {"ssh.", "terminal."},
    "capture": {"capture."},
    "remote": {"remote."},
    "reports": {"reports."},
    "stun": {"stun."},
    "quality": {"quality."},
    "taildrop": {"taildrop.", "share."},
    "endpoints": {"tailscale.", "usb."},
}

# Dynamic dispatch sites (ternaries, widget-driven methods) with their literals;
# a prefix ending in `.` matches every method under it. Keep in sync with the
# code — a new dynamic site anywhere else fails the audit.
DYNAMIC_SITES: dict[str, set[str]] = {
    "flutter/lib/box.dart": {"dashboard.memory.visible", "logs.logcat.visible", "groups.testSession.visible", "logs.visible"},
    "flutter/lib/components/capture_poll.dart": {"capture."},  # widget.method, capability-gated page
    "flutter/lib/components/status_panel.dart": {"service.start", "service.stop"},
    "flutter/lib/pages/backup.dart": {"webdav.read", "backup.read"},
    "flutter/lib/pages/diagnostics.dart": {"quality.start", "quality.stop", "quality.confirm", "quality.dismiss", "stun.start", "stun.stop"},
    "flutter/lib/pages/logs.dart": {"logs.clear", "logs.logcat.clear"},
    "flutter/lib/pages/requests.dart": {"capture."},  # passthrough, capability-gated page
    "flutter/lib/pages/taildrop.dart": {"taildrop.dismissSend", "taildrop.cancelSend"},
}

# The transport hub (the call/act plumbing itself) and generated code are not
# dispatch sites; only their literal calls count.
DYNAMIC_EXEMPT = ("flutter/lib/box.dart", "flutter/lib/ui/generated/")


def dart_calls() -> tuple[dict[str, list[str]], list[str]]:
    callers: dict[str, list[str]] = {}
    dynamic: list[str] = []
    for path in sorted(FLUTTER_LIB.rglob("*.dart")):
        rel = path.relative_to(REPO_ROOT).as_posix()
        text = path.read_text(encoding="utf-8")
        for match in CALL_RE.finditer(text):
            callers.setdefault(match.group(1), []).append(rel)
        if rel.startswith(DYNAMIC_EXEMPT) or rel in DYNAMIC_EXEMPT:
            continue
        for match in DYNAMIC_CALL_RE.finditer(text):
            line = text.count("\n", 0, match.start()) + 1
            dynamic.append(f"{rel}:{line}")
    return callers, dynamic


def kotlin_handlers() -> set[str]:
    text = BRIDGE.read_text(encoding="utf-8")
    start = text.find("when (call.method)")
    if start < 0:
        return set()
    depth = 0
    end = start
    for index in range(text.find("{", start), len(text)):
        if text[index] == "{":
            depth += 1
        elif text[index] == "}":
            depth -= 1
            if depth == 0:
                end = index
                break
    return {name for name in KOTLIN_METHOD_RE.findall(text[start:end]) if METHOD_NAME_RE.match(name)}


def disabled_capabilities() -> set[str]:
    text = CAPABILITIES.read_text(encoding="utf-8")
    return {key for key, value in CAPABILITY_RE.findall(text) if value == "false"}


def expected_absent(disabled: set[str]) -> set[str]:
    absent: set[str] = set()
    for capability in disabled:
        absent |= CAPABILITY_PREFIXES.get(capability, set())
    return absent


def emitted_events() -> set[str]:
    return set(EMIT_RE.findall(BRIDGE.read_text(encoding="utf-8")))


def consumed_events() -> set[str]:
    return set(DART_CASE_RE.findall(DART_DISPATCH.read_text(encoding="utf-8")))


def first_argument(text: str, open_paren: int) -> str:
    depth = 0
    for index in range(open_paren, len(text)):
        char = text[index]
        if char in "([{":
            depth += 1
        elif char in ")]}":
            if depth == 0:
                return text[open_paren:index]
            depth -= 1
        elif char == "," and depth == 0:
            return text[open_paren:index]
    return text[open_paren:]


def site_allowed(site: str, absent_prefixes: set[str]) -> bool:
    file, _, line = site.partition(":")
    literals = DYNAMIC_SITES.get(Path(file).as_posix())
    if literals is None:
        return False
    text = (REPO_ROOT / file).read_text(encoding="utf-8")
    for match in DYNAMIC_CALL_RE.finditer(text):
        if text.count("\n", 0, match.start()) + 1 != int(line):
            continue
        first = first_argument(text, match.end())
        # A conditional dispatch offers its branch results as the method names;
        # anything else on the line (map keys, etc.) is not a method literal.
        branches = [a or b for a, b in re.findall(r"\?\s*'([^']+)'|:\s*'([^']+)'", first)]
        if not branches:
            return True  # widget-driven (widget.method): gated by its page's capability
        return all(
            method in literals or any(method.startswith(prefix) for prefix in literals | absent_prefixes)
            for method in branches
        )
    return False


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--verbose", action="store_true", help="also list handlers with no Dart caller")
    args = parser.parse_args()

    calls, dynamic = dart_calls()
    handlers = kotlin_handlers()
    disabled = disabled_capabilities()
    absent_prefixes = expected_absent(disabled)
    emits = emitted_events()
    consumers = consumed_events()

    missing_handlers = sorted(
        method for method in set(calls) - handlers
        if not any(method.startswith(prefix) for prefix in absent_prefixes)
    )
    missing_consumers = sorted(emits - consumers)
    unknown_dynamic = [site for site in dynamic if not site_allowed(site, absent_prefixes)]
    dynamic_known = {method for literals in DYNAMIC_SITES.values() for method in literals if not method.endswith(".")}
    uncalled = sorted(
        handler for handler in handlers - set(calls) - dynamic_known
        if not any(handler.startswith(prefix) for prefix in absent_prefixes)
    )

    print(f"bridge methods: {len(handlers)} handlers, {len(calls)} distinct Dart callers, "
          f"{len(disabled)} capabilities off")
    print(f"events: {len(emits)} emitted, {len(consumers)} consumed")

    failed = False
    if missing_handlers:
        failed = True
        print("\nFAIL — Dart calls without a Kotlin handler:")
        for method in missing_handlers:
            print(f"  {method}  (called from {', '.join(sorted(set(calls[method])))})")
    if missing_consumers:
        failed = True
        print("\nFAIL — Kotlin events without a Dart consumer (flutter/lib/box.dart):")
        for event in missing_consumers:
            print(f"  {event}")
    if unknown_dynamic:
        failed = True
        print("\nFAIL — dynamic dispatch sites outside DYNAMIC_SITES (declare them in the script):")
        for site in unknown_dynamic:
            print(f"  {site}")
    if args.verbose and uncalled:
        print("\nnote — Kotlin handlers with no Dart caller found:")
        for method in uncalled:
            print(f"  {method}")

    if not failed:
        print("\ncontract OK: every live call has a handler, every event a consumer")
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())

# Hardening round (batches M + N of docs/roadmap.md)

## Why

Post-parity hardening: the migration finished with the whole surface shipped but the guard
rails thin — no channel contract enforcement, lint debt, benign core noise shown as errors,
crashes only surviving in a rotating log, no documented device gate.

## Changes

- CI gains three gates: the Dart/Kotlin channel contract audit
  (`tools/diagnostics/channel_audit.py`), a scan of the freshly built Flutter AAR for API
  references the pinned kotlin-stdlib/coroutines do not back, and `flutter analyze --fatal-infos`
  (the module's ~167 stale infos were cleared first).
- The core log folds the sing-box lazy handshake's known-benign connection-reset errors behind
  a counting chip; long log lines wrap inside their tile (a real overflow, not just lint).
- Uncaught exceptions persist to a seven-day store (`CrashStore`) beside the rotating core.log,
  exportable as one redacted file from the logs page; the update integrity gate became a plain
  JVM surface with rejection tests.
- README/docs describe the distribution on its own terms; `docs/device-checklist.md` is the
  pre-release gate; `docs/roadmap.md` carries the plan.

## Spec deltas

- `diagnostics-logging`: noise folding and the crash record store are additive behavior.
- `repository-governance`: the three CI gates are MUST clauses going forward.

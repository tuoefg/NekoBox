# B-box full parity (the Flutter migration)

Archived 2026-11-02 at the 3.0.0 milestone: every batch (0.5, A, B, C, D, E1, E2, F, G, H, I, J, K, L) executed and verified.

- The task ledger and the gap matrix live beside this file (`tasks.md`, `gap-matrix.md`); the spec deltas were merged into `openspec/specs/*` as the batches landed.
- The old Fragment UI retired in batch L: the Flutter host is the app's only screen, MainActivity is a headless shell forwarding share/view/tile payloads, and the protocol activities with their preference XMLs are gone.
- The Kotlin `ui.json.engine` survives because the core layers (Validation, ProfileImport, XrayStreamSetting) parse through it; the old editor wrappers on top of it were removed.
- CI runs the Android unit tests + debug build plus the Flutter module tests (engine suite, i18n coverage gate) on every push.

# Settings Storage Specification

## Purpose

Defines how application settings are declared, stored, read, and kept consistent across processes: the settings registry contract, typed access through the data store, per-profile versus global setting precedence, and settings-driven UI screens.

## Requirements

### Requirement: Typed settings access
The app SHALL provide typed, named accessors for all settings backed by persistent storage, with defaults applied when a value is unset, and all reads SHALL reflect the latest persisted value across UI and service processes.

#### Scenario: Setting visible in both processes
- **WHEN** the user changes a setting in the UI while a connection service runs
- **THEN** the service observes the new value on its next read

### Requirement: Settings registry contract
Each supported setting SHALL be registered with its key, type, and default; unrecognized keys encountered during import or migration SHALL be rejected or ignored without corrupting existing settings.

#### Scenario: Unknown key on restore
- **WHEN** a settings import contains a key that is not in the registry
- **THEN** the app does not crash and keeps existing settings intact

### Requirement: Per-profile overrides
The app SHALL let individual profiles override selected global settings; a profile's effective configuration SHALL prefer its override over the global value when the override is set.

#### Scenario: Profile-level override
- **WHEN** a profile overrides a global setting and is started
- **THEN** the running configuration uses the profile's value; other profiles keep the global value

### Requirement: Settings categories
Settings SHALL be organized into user-facing categories including general, appearance, network (TUN/DNS/inbound), routing, core, subscriptions, and testing, and each category screen SHALL only expose settings of that category.

#### Scenario: Open a category
- **WHEN** the user opens a settings category
- **THEN** only that category's settings are shown with their current values

### Requirement: Restart-required hints
For settings that cannot take effect on a running connection, the app SHALL indicate that a reconnect is required for the change to apply.

#### Scenario: Change TUN setting while connected
- **WHEN** the user changes a connection-affecting setting while the VPN runs
- **THEN** the app signals that the change applies after the connection restarts

### Requirement: Settings surface parity
Every key the registry stores SHALL be reachable from exactly one surface: a bridge schema entry edited through the Flutter settings pages, a shell-owned store or flow (the B-box appearance and layout preferences, the backup page's WebDAV account through the `webdav.*` channels), or a non-actionable book (runtime state managed by code, restore-only keys no settings page edits). The three-way audit SHALL keep the store, bridge and UI columns closed.

#### Scenario: Audit gate
- **WHEN** `uv run tools/diagnostics/settings_audit.py` runs on the tree
- **THEN** every column reports zero gaps and the exit code is 0

#### Scenario: New setting lands on all three columns
- **WHEN** a registry key gains a bridge schema entry
- **THEN** a `Setting*` control (or a `NativeToggle` the audit recognises) edits it on the owning page, and the page's restore-defaults list carries it

#### Scenario: Shell-owned key stays out of the schema
- **WHEN** a key the B-box shell edits through its own store or flow (theme, layout, WebDAV account) is added
- **THEN** it is registered in the audit's SHELL_OWNED book with a note naming the surface that owns it, and gains no duplicate schema entry

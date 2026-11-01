# Profile Management Specification

## Purpose

Defines management of proxy profiles and groups: creating, editing, ordering and deleting configurations, organizing them into groups and subscriptions, and importing from or sharing to share links.

## Requirements

### Requirement: Profile persistence
The app SHALL persist proxy profiles in a local database, each with a group membership, display order, traffic statistics, and status markers, and SHALL keep statistics across app restarts.

#### Scenario: Create and restart
- **WHEN** the user creates a profile and restarts the app
- **THEN** the profile is still listed with its saved configuration

### Requirement: Group model
The app SHALL organize profiles into ordered groups; a group is a subscription if and only if it has a subscription URL. Groups SHALL support per-group options including skip-auto-update, auto-clear-unavailable, test/traffic sorting, front and landing proxy chaining, and display order for tab arrangement.

#### Scenario: Create a subscription group
- **WHEN** the user creates a group with a subscription URL
- **THEN** the group is treated as a subscription and can be updated by fetching that URL

### Requirement: Group settings surface
The ported UI SHALL expose every group field the native editor manages through one settings screen — name, subscription URL, front/landing proxy chaining, routing profile with subscription import, skip-auto-update, auto-clear-unavailable, the subscription options (user agent, TLS/HTTP version overrides, send-HWID mode with device fields, update interval, server-interval handling, the after-update pruning and test options, name filters, info card), and the kept sort — with the native editor's validation: a subscription URL must be http(s) (or content://), name filters must compile as regular expressions, the update interval must be empty or at least 30 minutes, and header values must be single lines under the length cap.

#### Scenario: Edit the routing profile of the running group
- **WHEN** the user saves a group whose routing profile changed while that group's profile is running
- **THEN** the service reloads so the running configuration follows the new routing profile

#### Scenario: Validation blocks an invalid save
- **WHEN** the user saves with an interval below 30 minutes, or a name filter that does not compile
- **THEN** the save is refused and the field is marked with the reason

### Requirement: Subscription update
The app SHALL fetch a subscription URL and replace the group's profiles with the parsed result, preserving per-profile pins/selection where applicable, recording the last update time and the raw Subscription-UserInfo header.

#### Scenario: Update a subscription
- **WHEN** the user triggers a subscription update and the fetch succeeds
- **THEN** the group's profiles reflect the remote content and the last-update timestamp is refreshed

#### Scenario: Subscription fetch failure
- **WHEN** the fetch or parse fails
- **THEN** existing profiles are kept unchanged and an error is shown

### Requirement: Subscription change report
A manual subscription update SHALL report its changes to the user as a dismissable, selectable change window with a copy action; update errors and untitled status lines SHALL surface as transient messages; and a refresh that re-pointed the running configuration's routing SHALL raise the apply hint the dashboard shows.

#### Scenario: Review the changes of an update
- **WHEN** the user updates a subscription and the fetch reports changes
- **THEN** a change window opens with the added/removed/renamed summary and the text can be copied

### Requirement: Import and share
The app SHALL import profiles from share links and raw configs (URL schemes, clipboard, QR scan, file), and SHALL export a profile as a share link or config text.

#### Scenario: Import a share link
- **WHEN** the user imports a supported share link
- **THEN** a profile is created with the decoded parameters and placed in the chosen group

### Requirement: Ordering and pinning
The app SHALL let the user reorder profiles within a group (manual order and sorted orders such as by test result or traffic) and pin profiles to the top.

#### Scenario: Pin a profile
- **WHEN** the user pins a profile
- **THEN** it stays at the top of its group list regardless of other sort orders

### Requirement: Default group
When the app's database is empty, the app SHALL create a Default group automatically on first access, so the profile list is never groupless.

#### Scenario: First access on an empty database

- **WHEN** the app opens the profile list with an empty groups table
- **THEN** a Default group exists and newly imported or created profiles land in it

# Connectivity Testing Specification

## Purpose

Defines connectivity testing behavior: on-demand URL tests and TCP pings against one or many profiles, how results are measured, stored, and displayed, and how tests integrate with sorting and the auto-selector.

## Requirements

### Requirement: Single profile URL test
The user SHALL be able to run a URL test for the currently active or any selected profile; the app SHALL report the measured latency or a timeout/failure state.

#### Scenario: Successful test
- **WHEN** the user runs a URL test on a reachable profile
- **THEN** the measured latency is displayed and stored as the profile's latest result

#### Scenario: Unreachable profile
- **WHEN** the tested profile cannot reach the test URL within the timeout
- **THEN** the profile is shown as unavailable/timeout rather than a latency value

### Requirement: Batch testing
The user SHALL be able to trigger URL tests for all profiles in a group; results SHALL update per profile as each test completes, without blocking the UI.

#### Scenario: Test a whole group
- **WHEN** the user runs a group test
- **THEN** each profile in the group shows its own result as it arrives

### Requirement: Batch test panel
The ported UI SHALL offer a batch test panel over one session at a time: it SHALL show live progress (done, ok, failed, aborted counts and the testing slot), a latency histogram, the ranked best results, and the speed test's live curve while a speed session runs — and, once a session finishes, the actions the native panel offers: sort the tested group by the session's metric, remove the unavailable profiles, and connect through a ranked result. Stopping a session reports the remaining profiles as aborted.

#### Scenario: Review and act on a finished group test
- **WHEN** the user runs a group test in the panel and waits for it to finish
- **THEN** the panel lists the fastest results and offers the sort, remove-unavailable and connect actions

### Requirement: TCP ping testing
The app SHALL support TCP ping tests measuring connection setup time to a target, as an alternative to URL tests, with results stored and displayed the same way.

#### Scenario: TCP ping a profile
- **WHEN** the user runs a TCP ping on a profile
- **THEN** the connection-time result is stored and displayed for that profile

### Requirement: Result-driven sorting
The user SHALL be able to sort a group's profiles by their latest test result or traffic statistics, and the chosen sort SHALL persist per group.

#### Scenario: Sort by latency
- **WHEN** the user sorts a tested group by latency
- **THEN** profiles are listed from fastest to slowest, with unavailable profiles last

### Requirement: Test configuration
The user SHALL be able to configure test concurrency, timeout, and the test URL in app settings, and tests SHALL respect those values.

#### Scenario: Custom test URL
- **WHEN** the user sets a custom test URL and runs tests
- **THEN** the tests use the configured URL

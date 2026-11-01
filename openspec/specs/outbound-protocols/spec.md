# Outbound Protocols Specification

## Purpose

Defines the configuration contract and behavior of the supported outbound protocol types: which protocols the app can represent as profiles, how their settings are edited, and how they are serialized for the core and for import/export.

## Requirements

### Requirement: Supported protocol set
The app SHALL support configuration profiles for, at minimum: Shadowsocks, VMess, VLESS (standard and Xray), Trojan, ShadowTLS, SOCKS, HTTP, SSH, Snell, Hysteria/Hysteria2, TUIC, Juicity, Mieru, NaiveProxy, AnyTLS, TrustTunnel, MASQUE, WireGuard, OpenVPN, OpenConnect, Direct, Chain, Custom, and AutoSelector.

#### Scenario: Create each protocol profile
- **WHEN** the user creates a profile of each supported protocol type
- **THEN** a type-specific settings editor is shown and the saved profile keeps its type

### Requirement: Type-specific settings contract
Each protocol profile SHALL persist its settings in a typed, named field set, validated on save; protocol settings SHALL be independent of other profiles and preserved on edit.

#### Scenario: Edit protocol settings
- **WHEN** the user edits protocol-specific fields and saves
- **THEN** only that profile's settings change and invalid values are rejected with a visible error

### Requirement: Typed settings forms in the ported UI
The ported UI SHALL offer a typed settings form per protocol, starting with Shadowsocks, VMess, VLESS, Trojan, TUIC, Hysteria (v1/v2) and WireGuard: the fields the native editors bind, at the configuration's own paths, with the shared TLS, transport, multiplex and QUIC blocks and their native visibility rules, and the same save gate — required address and credentials, the port range, and the model's own validation run over the parsed document before anything is stored. A protocol without a typed form yet keeps the raw editor.

#### Scenario: Edit a Shadowsocks node through the form
- **WHEN** the user opens the typed form of a Shadowsocks node, changes the method and saves
- **THEN** the stored configuration carries the new method and the other fields are unchanged

#### Scenario: Save refused by the model validation
- **WHEN** the user saves a form whose document the outbound model rejects (a missing credential, an out-of-range port)
- **THEN** nothing is stored and the error names the field

### Requirement: Config serialization
The app SHALL serialize each profile into the core's configuration format when starting a connection, including required credentials, transport, TLS/Reality and multiplex options where applicable; serialization failures SHALL prevent the start and report the profile as misconfigured.

#### Scenario: Start with a broken profile
- **WHEN** the user starts a profile whose serialization fails validation
- **THEN** the connection does not start and the user sees a config error naming the profile

### Requirement: Chained outbounds
The app SHALL support chain profiles that route traffic through a front proxy and/or landing proxy, referencing other profiles by id, and SHALL report an error if a referenced profile is missing or would create a cycle.

#### Scenario: Chain through a front proxy
- **WHEN** a group's front proxy is set and a profile in that group is started
- **THEN** the generated configuration tunnels the profile through the front proxy

### Requirement: Custom and raw configs
The app SHALL support a Custom profile type holding user-provided raw configuration for the core, editable via a JSON editor, without semantic validation of its content beyond core acceptance.

#### Scenario: Start a custom profile
- **WHEN** the user starts a custom profile with valid raw config
- **THEN** the core runs with that configuration unchanged

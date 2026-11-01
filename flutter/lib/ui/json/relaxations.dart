// Per-editor schema relaxations (Kotlin JsonRelaxations.kt / ProfileJson.kt): the desktop's
// AllowExtraType plus what Throne's own stored forms need. The Dart side carries the tables; the
// Outbound-bound helpers (exportToJson, xray/endpoint roots) stay bridge-side, where the typed
// objects live.

export 'engine/json_formatter.dart';
export 'engine/json_tree.dart';
export 'engine/schema_store.dart';
export 'engine/schema_validator.dart';

import 'engine/json_formatter.dart';
import 'engine/json_tree.dart';
import 'engine/schema_store.dart';
import 'engine/schema_validator.dart';

/// Keys Throne writes that the sing-box schema does not know.
class ExtraKey {
  final String pointer;
  final String key;
  const ExtraKey(this.pointer, this.key);
}

/// An extra accepted type for a property name anywhere in the document (desktop AllowExtraType).
class ExtraType {
  final String key;
  final JsonType type;
  const ExtraType(this.key, this.type);
}

class JsonRelaxations {
  final List<ExtraKey> extraKeys;
  final List<ExtraType> extraTypes;

  /// Throne type names of the root object mapped to the schema's (`openvpn` -> `openvpn-client`).
  final Map<String, String> typeAliases;
  final bool unknownKeysAsWarnings;
  const JsonRelaxations({
    this.extraKeys = const [],
    this.extraTypes = const [],
    this.typeAliases = const {},
    this.unknownKeysAsWarnings = false,
  });

  void applyTo(SchemaValidator validator) {
    for (final extra in extraKeys) {
      validator.allowExtraKey(extra.pointer, extra.key);
    }
    for (final extra in extraTypes) {
      validator.allowExtraType(extra.key, extra.type);
    }
    typeAliases.forEach(validator.aliasType);
    validator.unknownKeysAsWarnings = unknownKeysAsWarnings;
  }
}

/// "Edit as JSON" of a typed profile, as far as Dart goes: the stored form's formatting, the
/// relaxation tables keyed by the configuration's type. The root refs and the relaxations of a
/// concrete node arrive from the bridge (the xray/endpoint flags are the typed object's knowledge).
class ProfileJsonTables {
  static const _tlsKeys = [ExtraKey('/tls', 'spoof_enabled'), ExtraKey('/tls/ech', 'resolver')];

  /// Root keys of the stored forms (the types' exportToJson) that sing-box does not have.
  static const _throneKeys = <String, List<ExtraKey>>{
    'hysteria': [ExtraKey('', 'recv_window_conn'), ExtraKey('', 'recv_window'), ExtraKey('', 'disable_mtu_discovery')],
    'socks': [ExtraKey('', 'uot')],
    'openconnect': [
      ExtraKey('', 'only_advertised_routes'), ExtraKey('', 'otp_profile_id'),
      ExtraKey('', 'server_path'), ExtraKey('', 'tunnel_dns'),
    ],
    'openvpn': [ExtraKey('', 'only_advertised_routes'), ExtraKey('', 'otp_profile_id'), ExtraKey('', 'tunnel_dns')],
  };

  /// Pretty-prints the stored form (compact JSON in, formatted out); empty stays empty.
  static String text(String compactJson) {
    if (compactJson.isEmpty) return '';
    return JsonFormatter.format(compactJson) ?? compactJson;
  }

  static List<String> schemaRoots({required bool xray, required bool endpoint}) => switch ((xray, endpoint)) {
    (true, _) => const [],
    (_, true) => [SchemaStore.endpoint],
    _ => [SchemaStore.outbound],
  };

  static JsonRelaxations relaxations(String type) => JsonRelaxations(
    extraKeys: [..._tlsKeys, ..._throneKeys[type] ?? const []],
    typeAliases: const {'openvpn': 'openvpn-client'},
    unknownKeysAsWarnings: true,
  );
}

// The tree types are re-exported so editor code needs one import.

// The bundled sing-box schema (assets/schema/sing-box.json), loaded once (Kotlin SchemaStore.kt).
// A missing or broken asset leaves the editors with syntax checking only. Dart's asset reads are
// async, so the load completes before the editors ask for the document.

import 'package:flutter/services.dart' show rootBundle;

import 'json_tree.dart';
import 'schema_document.dart';
import 'schema_validator.dart';

class SchemaStore {
  static const config = '';
  static const outbound = r'#/$defs/Outbound';
  static const endpoint = r'#/$defs/Endpoint';

  static const asset = 'schema/sing-box.json';

  // The core's stub files register these without the build tags, but the Android core cannot run them.
  static const _unsupported = <String, Set<String>>{
    'Endpoint': {'tailscale'},
    'DNSServer': {'dhcp', 'tailscale'},
    'Service': {'derp'},
  };

  static bool _loaded = false;
  static SchemaDocument? _document;
  static String? loadError;

  /// Loads the asset once; later calls return the cached document. Null when unavailable.
  static Future<SchemaDocument?> get() async {
    if (_loaded) return _document;
    _document = await load();
    _loaded = true;
    return _document;
  }

  static Future<SchemaDocument?> load() async {
    try {
      final text = await rootBundle.loadString(asset);
      return parse(text);
    } catch (error) {
      loadError = error.toString();
      return null;
    }
  }

  /// Testable parse: builds the document from schema text.
  static SchemaDocument? parse(String text) {
    final parsed = JsonTree.parse(text);
    final root = parsed.root;
    if (root == null) {
      loadError = parsed.error;
      return null;
    }
    final plain = SchemaDocument.obj(JsonTree.toPlain(root));
    if (plain == null || plain.isEmpty) return null;
    strip(plain);
    return SchemaDocument(plain);
  }

  /// A list of roots becomes a oneOf union (SchemaStore.cpp:37-43); no roots means no schema.
  static SchemaValidator? validator(SchemaDocument schema, List<String> roots) => switch (roots.length) {
    0 => null,
    1 => SchemaValidator.create(schema, roots[0]),
    _ => SchemaValidator.createForNode(schema, {
        'oneOf': [for (final root in roots) {r'$ref': root}],
      }),
  };

  static void strip(Map<String, dynamic> root) {
    final defs = SchemaDocument.obj(root[r'$defs']);
    if (defs == null) return;
    _unsupported.forEach((name, types) {
      final def = SchemaDocument.obj(defs[name]);
      if (def != null) removeVariants(def, types);
    });
  }

  static void removeVariants(Map<String, dynamic> union, Set<String> types) {
    for (final key in ['oneOf', 'anyOf']) {
      final branches = SchemaDocument.list(union[key]);
      if (branches.isEmpty) continue;
      final kept = <Object?>[];
      for (final branch in branches) {
        final node = SchemaDocument.obj(branch);
        if (node == null) {
          kept.add(branch);
          continue;
        }
        if (node.length == 1 && (node.containsKey('oneOf') || node.containsKey('anyOf'))) {
          removeVariants(node, types);
          kept.add(node);
          continue;
        }
        final type = SchemaDocument.obj(SchemaDocument.obj(node['properties'])?['type']);
        final constant = type?['const'];
        if (constant is String && types.contains(constant)) continue;
        kept.add(node);
      }
      union[key] = kept;
    }
  }
}

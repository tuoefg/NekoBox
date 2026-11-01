// A parsed JSON Schema document (plain values of JsonTree.toPlain) with the lookups of
// JsonSchemaValidator.cpp (Kotlin SchemaDocument.kt).


class SchemaDocument {
  final Map<String, dynamic> root;
  SchemaDocument(this.root);

  Map<String, dynamic>? lookup(String pointer) {
    var path = pointer;
    if (path.startsWith('#')) path = path.substring(1);
    if (path.isEmpty || path == '/') return root;
    if (!path.startsWith('/')) return null;
    var current = root as Object?;
    for (final rawToken in path.substring(1).split('/')) {
      final token = rawToken.replaceAll('~1', '/').replaceAll('~0', '~');
      if (current is Map) {
        if (!current.containsKey(token)) return null;
        current = current[token];
      } else if (current is List) {
        final index = int.tryParse(token);
        if (index == null || index < 0 || index >= current.length) return null;
        current = current[index];
      } else {
        return null;
      }
    }
    return obj(current);
  }

  Map<String, dynamic> deref(Map<String, dynamic> node, [int depth = 0]) {
    if (depth > 8) return node;
    final ref = node[r'$ref'];
    if (ref is! String || ref.isEmpty) return node;
    final target = lookup(ref);
    if (target == null) return node;
    return deref(target, depth + 1);
  }

  /// The property rule of [key] in a branch read through its allOf/$ref composition.
  Map<String, dynamic>? branchProperty(Map<String, dynamic> branch, String key, [int level = 0]) {
    if (level > 6) return null;
    final node = deref(branch);
    final properties = obj(node['properties']);
    if (properties != null && properties.containsKey(key)) {
      return deref(obj(properties[key]) ?? SchemaDocument.empty);
    }
    for (final sub in list(node['allOf'])) {
      final own = obj(sub);
      if (own == null) continue;
      final found = branchProperty(own, key, level + 1);
      if (found != null) return found;
    }
    return null;
  }

  bool branchRequires(Map<String, dynamic> branch, String key, [int level = 0]) {
    if (level > 6) return false;
    final node = deref(branch);
    if (list(node['required']).any((entry) => entry == key)) return true;
    for (final sub in list(node['allOf'])) {
      final own = obj(sub);
      if (own == null) continue;
      if (branchRequires(own, key, level + 1)) return true;
    }
    return false;
  }

  /// Property names of a branch; [sorted] follows QJsonObject::keys() (the validator's discriminator search).
  List<String> branchKeys(Map<String, dynamic> branch, bool sorted, [int level = 0]) {
    if (level > 6) return const [];
    final node = deref(branch);
    final own = obj(node['properties'])?.keys ?? const Iterable<String>.empty();
    final keys = List<String>.of(sorted ? (own.toList()..sort()) : own);
    for (final sub in list(node['allOf'])) {
      final ownSub = obj(sub);
      if (ownSub == null) continue;
      for (final key in branchKeys(ownSub, sorted, level + 1)) {
        if (!keys.contains(key)) keys.add(key);
      }
    }
    return keys;
  }

  List<String> branchRequired(Map<String, dynamic> branch, [int level = 0]) {
    if (level > 6) return const [];
    final node = deref(branch);
    final required = <String>[];
    for (final entry in list(node['required'])) {
      if (entry is String && !required.contains(entry)) required.add(entry);
    }
    for (final sub in list(node['allOf'])) {
      final ownSub = obj(sub);
      if (ownSub == null) continue;
      for (final key in branchRequired(ownSub, level + 1)) {
        if (!required.contains(key)) required.add(key);
      }
    }
    return required;
  }

  /// A branch that is itself a bare union contributes its own branches (JsonSchemaValidator.cpp:414-426).
  List<Map<String, dynamic>> flattenUnion(List<Object?> branches) {
    final resolved = <Map<String, dynamic>>[];
    void flatten(Map<String, dynamic> node, int level) {
      final branch = deref(node);
      if (level < 3 && branch.length == 1) {
        for (final key in unionKeys) {
          if (!branch.containsKey(key)) continue;
          for (final sub in list(branch[key])) {
            final own = obj(sub);
            if (own == null) continue;
            flatten(own, level + 1);
          }
          return;
        }
      }
      resolved.add(branch);
    }
    for (final branch in branches) {
      final own = obj(branch);
      if (own == null) continue;
      flatten(own, 0);
    }
    return resolved;
  }

  // ------------------------------------------------------------------------------------------------ helpers

  static const empty = <String, dynamic>{};
  static const _unionKeys = ['oneOf', 'anyOf'];

  static Map<String, dynamic>? obj(Object? value) {
    if (value is Map<String, dynamic>) return value;
    // The JVM cast is unchecked: any string-keyed map is the schema's object shape.
    if (value is Map && value.keys.every((key) => key is String)) {
      return Map<String, dynamic>.from(value);
    }
    return null;
  }

  static List<Object?> list(Object? value) => value is List<Object?> ? value : const [];

  static List<String> types(Object? type) {
    if (type is String) return [type];
    if (type is List) return [for (final entry in type) entry is String ? entry : ''];
    return const [];
  }

  static List<Object?> pinnedValues(Map<Object?, Object?> rule) {
    if (rule.containsKey('const')) return [rule['const']];
    if (rule.containsKey('enum')) return list(rule['enum']);
    return const [];
  }

  /// qFuzzyCompare(a + 1, b + 1), as the desktop compares numbers.
  static bool sameNumber(double a, double b) {
    final x = a + 1.0;
    final y = b + 1.0;
    return (x - y).abs() * 1e12 <= (x.abs() < y.abs() ? x.abs() : y.abs());
  }

  static String formatNumber(double value) {
    if (value == value.roundToDouble() && value.abs() < 1e15) {
      // Kotlin toLong() truncates toward zero; roundToDouble on integral values is the same.
      return value.truncate().toString();
    }
    return value.toString();
  }

  static String scalarText(Object? json) => switch (json) {
    String() => json,
    bool() => json ? 'true' : 'false',
    double() => formatNumber(json),
    int() => formatNumber(json.toDouble()),
    null => 'null',
    _ => '',
  };

  static const unionKeys = _unionKeys;
}

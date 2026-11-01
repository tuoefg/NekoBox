// The JSON Schema subset of JsonSchemaValidator.cpp with its messages (Kotlin SchemaValidator.kt).
// A oneOf is resolved through its discriminating constant (type/action), so errors name the variant.
// Relaxations are per editor: allowExtraType (desktop AllowExtraType), allowExtraKey for keys only
// Throne writes, aliasType for a root `type` Throne names differently and unknownKeysAsWarnings.

import 'json_tree.dart';
import 'schema_document.dart';

enum Severity { error, warning }

class JsonIssue {
  final Severity severity;
  final String message;
  final String pointer;
  final JsonSpan span;

  /// Set when a union rejected the value outright: the wrong variant, not a bad field.
  final bool variantMismatch;
  const JsonIssue(this.severity, this.message, this.pointer, this.span, {this.variantMismatch = false});
}

class SchemaValidator {
  final SchemaDocument _doc;
  final Map<String, dynamic> _root;

  final _extraTypes = <String, Set<JsonType>>{};
  final _extraKeys = <(List<String>, String)>[];
  final _typeAliases = <String, String>{};
  final _patterns = <String, RegExp?>{};

  bool unknownKeysAsWarnings = false;

  SchemaValidator._(this._doc, this._root);

  void allowExtraType(String propertyName, JsonType type) {
    _extraTypes.putIfAbsent(propertyName, () => {}).add(type);
  }

  /// [pointer] addresses the instance object: '' is the root, '/tls' its TLS object; a `*` segment matches any.
  void allowExtraKey(String pointer, String key) {
    _extraKeys.add((segments(pointer), key));
  }

  void aliasType(String type, String schemaType) {
    _typeAliases[type] = schemaType;
  }

  List<JsonIssue> validate(JsonValue root) {
    final issues = <JsonIssue>[];
    _validate(_root, _aliased(root), '', 0, issues, null);
    // Kotlin sortedBy is stable; Dart's sort is not, so ties keep insertion order by index.
    final order = List<int>.generate(issues.length, (i) => i);
    order.sort((a, b) {
      final byOffset = issues[a].span.offset.compareTo(issues[b].span.offset);
      return byOffset != 0 ? byOffset : a.compareTo(b);
    });
    return [for (final i in order) issues[i]];
  }

  JsonValue _aliased(JsonValue value) {
    if (_typeAliases.isEmpty || value.type != JsonType.object_) return value;
    final index = value.indexOfKey('type');
    if (index < 0) return value;
    final type = value.members[index];
    if (type.type != JsonType.string) return value;
    final alias = _typeAliases[type.string];
    if (alias == null) return value;
    final copy = JsonValue(JsonType.object_)
      ..span = value.span
      ..keys.addAll(value.keys)
      ..keySpans.addAll(value.keySpans)
      ..members.addAll(value.members);
    copy.members[index] = (JsonValue(JsonType.string)
      ..span = type.span
      ..string = alias);
    return copy;
  }

  bool _relaxed(String key, JsonType type) => _extraTypes[key]?.contains(type) == true;

  bool _extraKey(String pointer, String key) {
    if (_extraKeys.isEmpty) return false;
    final at = segments(pointer);
    for (final (path, name) in _extraKeys) {
      if (name != key || path.length != at.length) continue;
      var all = true;
      for (var i = 0; i < path.length; i++) {
        if (path[i] != '*' && path[i] != at[i]) { all = false; break; }
      }
      if (all) return true;
    }
    return false;
  }

  JsonIssue _unknownField(String key, String pointer, JsonSpan span) => JsonIssue(
    unknownKeysAsWarnings ? Severity.warning : Severity.error,
    'Unknown field "$key"', pointer, span,
  );

  void _validate(
    Map<String, dynamic> schema,
    JsonValue value,
    String pointer,
    int depth,
    List<JsonIssue> issues,
    Set<String>? evaluated,
  ) {
    if (depth > maxSchemaDepth || issues.length >= maxIssues) return;

    final local = <String>{};
    void propagate() {
      evaluated?.addAll(local);
    }

    final ref = schema[r'$ref'];
    if (ref is String && ref.isNotEmpty) {
      final target = _doc.lookup(ref);
      if (target != null) _validate(target, value, pointer, depth + 1, issues, local);
    }

    if (schema.containsKey('type')) {
      final types = SchemaDocument.types(schema['type']);
      if (!types.any((name) => matchesType(name, value))) {
        issues.add(JsonIssue(
          Severity.error,
          'Expected ${types.join(' or ')}, got ${JsonTree.typeName(value.type)}',
          pointer, value.span,
        ));
        propagate();
        return;
      }
    }

    if (schema.containsKey('const')) {
      final expected = schema['const'];
      if (!equalsJson(value, expected)) {
        issues.add(JsonIssue(Severity.error, 'Expected ${SchemaDocument.scalarText(expected)} here', pointer, value.span));
        propagate();
        return;
      }
    }

    if (schema.containsKey('enum')) {
      final allowed = SchemaDocument.list(schema['enum']);
      if (!allowed.any((entry) => equalsJson(value, entry))) {
        issues.add(JsonIssue(
          Severity.error,
          '${describeValue(value)} is not valid here (expected: ${_joinScalars(allowed)})',
          pointer, value.span,
        ));
        propagate();
        return;
      }
    }

    if (schema['deprecated'] == true) {
      issues.add(JsonIssue(Severity.warning, 'This option is deprecated', pointer, value.span));
    }

    if (value.type == JsonType.string && schema.containsKey('pattern')) {
      final pattern = _pattern(schema['pattern'] is String ? schema['pattern'] as String : null);
      if (pattern != null && !pattern.hasMatch(value.string)) {
        issues.add(JsonIssue(
          Severity.error, '"${value.string}" is not in the expected format', pointer, value.span,
        ));
      }
    }

    if (value.type == JsonType.number) {
      final minimum = (schema['minimum'] as num?)?.toDouble();
      if (minimum != null && value.number < minimum) {
        issues.add(JsonIssue(
          Severity.error, 'Value must be at least ${SchemaDocument.formatNumber(minimum)}', pointer, value.span,
        ));
      }
      final maximum = (schema['maximum'] as num?)?.toDouble();
      if (maximum != null && value.number > maximum) {
        issues.add(JsonIssue(
          Severity.error, 'Value must be at most ${SchemaDocument.formatNumber(maximum)}', pointer, value.span,
        ));
      }
    }

    if (value.type == JsonType.array && schema.containsKey('items')) {
      final items = SchemaDocument.obj(schema['items']) ?? SchemaDocument.empty;
      for (var i = 0; i < value.elements.length; i++) {
        _validate(items, value.elements[i], '$pointer/$i', depth + 1, issues, null);
      }
    }

    if (value.type == JsonType.object_) _validateObject(schema, value, pointer, depth, issues, local);

    for (final branch in SchemaDocument.list(schema['allOf'])) {
      final own = SchemaDocument.obj(branch);
      if (own == null) continue;
      _validate(own, value, pointer, depth + 1, issues, local);
    }
    if (schema.containsKey('anyOf')) {
      _validateUnion(SchemaDocument.list(schema['anyOf']), false, value, pointer, depth, issues, local);
    }
    if (schema.containsKey('oneOf')) {
      _validateUnion(SchemaDocument.list(schema['oneOf']), true, value, pointer, depth, issues, local);
    }

    if (value.type == JsonType.object_ && schema['unevaluatedProperties'] == false) {
      for (var i = 0; i < value.keys.length; i++) {
        final key = value.keys[i];
        if (local.contains(key) || _relaxed(key, value.members[i].type) || _extraKey(pointer, key)) continue;
        issues.add(_unknownField(key, pointer, value.keySpans[i]));
      }
    }

    propagate();
  }

  void _validateObject(
    Map<String, dynamic> schema,
    JsonValue value,
    String pointer,
    int depth,
    List<JsonIssue> issues,
    Set<String> evaluated,
  ) {
    final properties = SchemaDocument.obj(schema['properties']) ?? SchemaDocument.empty;
    final additional = schema['additionalProperties'];
    final propertyNames = SchemaDocument.obj(schema['propertyNames']);

    for (var i = 0; i < value.keys.length; i++) {
      final key = value.keys[i];
      final member = value.members[i];

      if (_relaxed(key, member.type) || _extraKey(pointer, key)) {
        evaluated.add(key);
        continue;
      }

      if (propertyNames != null) {
        final name = JsonValue(JsonType.string)
          ..string = key
          ..span = value.keySpans[i];
        _validate(propertyNames, name, pointer, depth + 1, issues, null);
      }

      if (properties.containsKey(key)) {
        evaluated.add(key);
        _validate(
          SchemaDocument.obj(properties[key]) ?? SchemaDocument.empty,
          member, '$pointer/$key', depth + 1, issues, null,
        );
        continue;
      }
      if (additional is Map) {
        evaluated.add(key);
        _validate(
          SchemaDocument.obj(additional) ?? SchemaDocument.empty,
          member, '$pointer/$key', depth + 1, issues, null,
        );
        continue;
      }
      if (additional == false) issues.add(_unknownField(key, pointer, value.keySpans[i]));
    }

    for (final entry in SchemaDocument.list(schema['required'])) {
      if (entry is! String) continue;
      if (value.indexOfKey(entry) < 0) {
        issues.add(JsonIssue(
          Severity.error, 'Missing required field "$entry"', pointer, JsonSpan(value.span.offset, 1),
        ));
      }
    }
  }

  void _validateUnion(
    List<Object?> branches,
    bool exclusive,
    JsonValue value,
    String pointer,
    int depth,
    List<JsonIssue> issues,
    Set<String> evaluated,
  ) {
    if (branches.isEmpty) return;

    final resolved = _doc.flattenUnion(branches);
    if (resolved.isEmpty) return;
    var candidates = List<int>.generate(resolved.length, (i) => i);

    // Narrow repeatedly by the constants branches pin their properties to: "type" first, then variants of it.
    if (value.type == JsonType.object_) {
      final tried = <String>{};
      var round = 0;
      while (round < 4 && candidates.length > 1) {
        round++;
        var key = '';
        var allowedPerBranch = <List<Object?>>[];
        for (final name in _doc.branchKeys(resolved[candidates.first], true)) {
          if (tried.contains(name)) continue;
          final allowed = <List<Object?>>[];
          var usable = true;
          for (final index in candidates) {
            final rule = _doc.branchProperty(resolved[index], name);
            final pinned = rule == null ? null : SchemaDocument.pinnedValues(rule);
            if (pinned == null || pinned.isEmpty) {
              usable = false;
              break;
            }
            allowed.add(pinned);
          }
          if (usable) {
            key = name;
            allowedPerBranch = allowed;
            break;
          }
        }
        if (key.isEmpty) break;
        tried.add(key);

        final narrowed = <int>[];
        final keyIndex = value.indexOfKey(key);
        if (keyIndex >= 0) {
          final given = value.members[keyIndex];
          for (var n = 0; n < candidates.length; n++) {
            // the empty string marks "may be omitted", not a variant name
            if (allowedPerBranch[n].any((option) =>
                SchemaDocument.scalarText(option).isNotEmpty && equalsJson(given, option))) {
              narrowed.add(candidates[n]);
            }
          }
          if (narrowed.isEmpty) {
            final names = <Object?>[];
            final seen = <String>{};
            for (final options in allowedPerBranch) {
              for (final option in options) {
                final text = SchemaDocument.scalarText(option);
                if (text.isEmpty || !seen.add(text)) continue;
                names.add(option);
              }
            }
            evaluated.add(key);
            issues.add(JsonIssue(
              Severity.error,
              'Unknown $key ${describeValue(given)} (expected: ${_joinScalars(names)})',
              pointer, given.span, variantMismatch: true,
            ));
            return;
          }
        } else {
          for (final index in candidates) {
            if (!_doc.branchRequires(resolved[index], key)) narrowed.add(index);
          }
          if (narrowed.isEmpty) break;
        }
        candidates = narrowed;
      }
    }

    if (candidates.length == 1) {
      _validate(resolved[candidates.first], value, pointer, depth + 1, issues, evaluated);
      return;
    }

    var bestIndex = -1;
    var bestScore = 0;
    var bestIssues = <JsonIssue>[];
    var bestEvaluated = <String>{};
    for (final index in candidates) {
      final trial = <JsonIssue>[];
      final trialEvaluated = <String>{};
      _validate(resolved[index], value, pointer, depth + 1, trial, trialEvaluated);
      final errors = trial.where((issue) => issue.severity == Severity.error).length;
      if (errors == 0) {
        evaluated.addAll(trialEvaluated);
        issues.addAll(trial);
        return;
      }
      final score = errors + (trial.any((issue) => issue.variantMismatch) ? variantPenalty : 0);
      if (bestIndex < 0 || score < bestScore) {
        bestIndex = index;
        bestScore = score;
        bestIssues = trial;
        bestEvaluated = trialEvaluated;
      }
    }

    if (exclusive && bestIssues.isNotEmpty) {
      evaluated.addAll(bestEvaluated);
      issues.addAll(bestIssues.take(maxUnionIssues));
      return;
    }

    final forms = <String>[];
    for (final branch in resolved) {
      for (final name in SchemaDocument.types(branch['type'])) {
        if (name.isNotEmpty && !forms.contains(name)) forms.add(name);
      }
    }
    if (forms.isEmpty) {
      issues.add(JsonIssue(Severity.error, 'Value does not match any accepted form', pointer, value.span));
    } else {
      issues.add(JsonIssue(
        Severity.error, 'Expected ${forms.join(' or ')}, got ${JsonTree.typeName(value.type)}',
        pointer, value.span,
      ));
    }
  }

  RegExp? _pattern(String? source) {
    if (source == null) return null;
    if (_patterns.containsKey(source)) return _patterns[source];
    RegExp? compiled;
    try {
      compiled = RegExp(source);
    } catch (_) {
      compiled = null;
    }
    _patterns[source] = compiled;
    return compiled;
  }

  // ------------------------------------------------------------------------------------------------ construction

  /// [rootRef] is a JSON pointer into the schema ('#/\$defs/RouteOptions'); empty is the document root.
  static SchemaValidator? create(SchemaDocument schema, [String rootRef = '']) {
    if (schema.root.isEmpty) return null;
    final root = rootRef.isEmpty ? schema.root : schema.lookup(rootRef);
    if (root == null) return null;
    return SchemaValidator._(schema, root);
  }

  static SchemaValidator createForNode(SchemaDocument schema, Map<String, dynamic> root) =>
      SchemaValidator._(schema, root);

  static List<String> segments(String pointer) {
    if (pointer.isEmpty || pointer == '/') return const [];
    final body = pointer.startsWith('/') ? pointer.substring(1) : pointer;
    return body.split('/');
  }

  static bool matchesType(String name, JsonValue value) => switch (name) {
    'object' => value.type == JsonType.object_,
    'array' => value.type == JsonType.array,
    'string' => value.type == JsonType.string,
    'boolean' => value.type == JsonType.bool_,
    'null' => value.type == JsonType.null_,
    'number' => value.type == JsonType.number,
    'integer' => value.type == JsonType.number && value.integral,
    _ => true,
  };

  static bool equalsJson(JsonValue value, Object? json) => switch (value.type) {
    JsonType.string => json is String && json == value.string,
    JsonType.bool_ => json is bool && json == value.bool_,
    JsonType.number => json is num && SchemaDocument.sameNumber(json.toDouble(), value.number),
    JsonType.null_ => json == null,
    _ => false,
  };

  static String describeValue(JsonValue value) => switch (value.type) {
    JsonType.string => '"${value.string}"',
    JsonType.bool_ => value.bool_ ? 'true' : 'false',
    JsonType.number => SchemaDocument.formatNumber(value.number),
    JsonType.null_ => 'null',
    _ => JsonTree.typeName(value.type),
  };

  static String _joinScalars(List<Object?> values) {
    final text = <String>[];
    for (final value in values) {
      if (text.length >= maxListed) {
        text.add('...');
        break;
      }
      text.add(SchemaDocument.scalarText(value));
    }
    return text.join(', ');
  }

  static const maxSchemaDepth = 64;
  static const maxIssues = 200;
  static const maxListed = 10;
  static const maxUnionIssues = 6;

  // a branch the value simply is not (wrong "type"/"action") ranks below one it almost matches
  static const variantPenalty = 1000;
}

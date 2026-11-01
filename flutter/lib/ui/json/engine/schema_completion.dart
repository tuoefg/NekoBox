// Schema-driven completion (Kotlin SchemaCompletion.kt; the desktop has only the raw-route outbound
// completer, RawRouteItem.cpp:27-102): the caret context comes from a tolerant scan of the whole
// text, the schema node from the path of containers around it with unions narrowed by the constants
// the document already holds (type, action, ...).

import 'json_formatter.dart';
import 'json_tree.dart';
import 'schema_document.dart';

/// One completion: [insert] replaces the range [caret - before, caret + after) of the text it was
/// computed for; the selection afterwards is [selectStart, selectEnd) relative to the start of [insert].
class Suggestion {
  final String label;
  final String detail;
  final String insert;
  final int before;
  final int after;
  final int selectStart;
  final int selectEnd;

  /// Value suggestions are worth asking for right after this key was inserted.
  final bool chain;
  final bool isKey;
  const Suggestion(this.label, this.detail, this.insert, this.before, this.after,
      this.selectStart, this.selectEnd, this.chain, this.isKey);
}

enum _State { key, colon, value, afterValue }

class _Frame {
  final bool isObject;
  final String? segment;
  final _Frame? parent;
  _State state;
  String? pendingKey;
  int index = 0;
  final keys = <String>{};
  final scalars = <String, Object?>{};
  final values = <String>{};
  _Frame(this.isObject, this.segment, this.parent) : state = isObject ? _State.key : _State.value;
}

class _Capture {
  final List<_Frame> frames;
  final _State? state;
  final String? pendingKey;
  final int start;
  final int end;
  final bool inString;
  final String prefix;
  _Capture(this.frames, this.state, this.pendingKey, this.start, this.end, this.inString, this.prefix);
}

class _Scan {
  final _Capture? capture;
  final Map<String, List<String>> tags;
  const _Scan(this.capture, this.tags);
}

class _Choice {
  final String label;
  final String detail;
  final Object? value;
  final String? snippet;
  const _Choice(this.label, this.detail, this.value, [this.snippet]);
}

class _Template {
  final String text;
  final int selectStart;
  final int selectEnd;
  final bool chain;
  const _Template(this.text, this.selectStart, this.selectEnd, this.chain);
}

class SchemaCompletion {
  final SchemaDocument doc;
  final List<String> roots;
  final Map<String, String> typeAliases;
  final Map<String, List<String>> builtinTags;
  SchemaCompletion(this.doc, this.roots, {this.typeAliases = const {}, this.builtinTags = const {}});

  List<Suggestion> suggest(String text, int caret) {
    if (caret < 0 || caret > text.length) return const [];
    final scan = _scan(text, caret);
    final capture = scan.capture;

    if (capture == null) return const [];
    final top = capture.frames.isEmpty ? null : capture.frames.last;
    if (top == null) return const [];
    final typed = capture.inString || capture.prefix.isNotEmpty;
    List<Suggestion> suggestions;
    if (top.isObject && (capture.state == _State.key || capture.state == _State.afterValue)) {
      if (!typed) return const [];
      suggestions = _keySuggestions(text, capture, top);
    } else if (top.isObject && capture.state == _State.value) {
      final key = capture.pendingKey;
      if (key == null) return const [];
      final schemas = _propertySchemas(capture.frames, key);
      suggestions = _valueSuggestions(text, capture, schemas, top.values, scan.tags);
    } else if (!top.isObject && capture.state == _State.value) {
      if (!typed) return const [];
      final schemas = _elementSchemas(capture.frames);
      suggestions = _valueSuggestions(text, capture, schemas, top.values, scan.tags);
    } else {
      return const [];
    }
    return _filter(suggestions, capture.prefix);
  }

  // ---- scanning

  _Scan _scan(String text, int caret) {
    final stack = <_Frame>[];
    final tags = <String, List<String>>{};
    _Capture? capture;
    var editing = -1;
    final n = text.length;

    _Frame? top() => stack.isEmpty ? null : stack.last;

    void doCapture(int start, int end, bool inString, String prefix) {
      final frame = top();
      capture = _Capture(List.of(stack), frame?.state, frame?.pendingKey, start, end, inString, prefix);
      editing = start;
    }

    void collectTag(_Frame frame) {
      final parent = frame.parent;
      if (parent == null) return;
      if (!frame.isObject || parent.isObject) return;
      final kind = definitionArrays[parent.segment];
      if (kind == null) return;
      final tag = frame.scalars['tag'];
      if (tag is String && tag.isNotEmpty) {
        tags.putIfAbsent(kind, () => []).add(tag);
      }
    }

    void onScalar(Object? value, int start, bool isString, String raw) {
      final frame = top();
      if (frame == null) return;
      if (frame.isObject) {
        switch (frame.state) {
          case _State.key || _State.afterValue:
            if (isString && start != editing) frame.keys.add(value as String);
            frame.pendingKey = isString ? value as String : raw;
            frame.state = _State.colon;
          case _State.colon:
            frame.state = _State.afterValue;
          case _State.value:
            final key = frame.pendingKey;
            if (key != null && start != editing) frame.scalars[key] = value;
            frame.state = _State.afterValue;
        }
      } else {
        if (isString && start != editing) frame.values.add(value as String);
        frame.state = _State.afterValue;
      }
    }

    var i = 0;
    while (i < n) {
      if (capture == null && i >= caret) doCapture(caret, caret, false, '');
      final c = text[i];
      if (c == ' ' || c == '\t' || c == '\n' || c == '\r') {
        i++;
      } else if (c == '/' && i + 1 < n && text[i + 1] == '/') {
        var end = i + 2;
        while (end < n && text[end] != '\n') {
          end++;
        }
        if (capture == null && caret >= i + 1 && caret <= end) return _Scan(null, tags);
        i = end;
      } else if (c == '/' && i + 1 < n && text[i + 1] == '*') {
        final close = text.indexOf('*/', i + 2);
        final end = close < 0 ? n : close + 2;
        if (capture == null && caret > i && caret < end) return _Scan(null, tags);
        i = end;
      } else if (c == '"') {
        var end = i + 1;
        var terminated = false;
        while (end < n) {
          final ch = text[end];
          if (ch == r'\' && end + 1 < n && text[end + 1] != '\n') {
            end += 2;
            continue;
          }
          if (ch == '\n') break;
          end++;
          if (ch == '"') {
            terminated = true;
            break;
          }
        }
        end = end < n ? end : n;
        final contentEnd = terminated ? end - 1 : end;
        if (capture == null && caret > i && caret <= contentEnd) {
          // an unclosed string runs to the line end; replace only the word being typed
          var replaceEnd = end;
          if (!terminated) {
            replaceEnd = caret;
            while (replaceEnd < contentEnd && _isWordChar(text[replaceEnd])) {
              replaceEnd++;
            }
          }
          doCapture(i, replaceEnd, true, text.substring(i + 1, caret));
        }
        final raw = text.substring(i + 1, contentEnd);
        final decoded = !raw.contains(r'\') ? raw : (JsonTree.parse('"$raw"').root?.string ?? raw);
        onScalar(decoded, i, true, decoded);
        i = end;
      } else if (c == '{' || c == '[') {
        final parent = top();
        final segment = parent == null
            ? null
            : parent.isObject ? (parent.pendingKey ?? '') : '${parent.index}';
        stack.add(_Frame(c == '{', segment, parent));
        i++;
      } else if (c == '}' || c == ']') {
        if (stack.isNotEmpty) {
          collectTag(stack.removeLast());
          top()?.state = _State.afterValue;
        }
        i++;
      } else if (c == ':') {
        final frame = top();
        if (frame != null && frame.isObject && frame.state == _State.colon) frame.state = _State.value;
        i++;
      } else if (c == ',') {
        final frame = top();
        if (frame != null) {
          if (frame.isObject) {
            frame.state = _State.key;
            frame.pendingKey = null;
          } else {
            frame.index++;
            frame.state = _State.value;
          }
        }
        i++;
      } else if (_isWordChar(c)) {
        var end = i + 1;
        while (end < n && _isWordChar(text[end])) {
          end++;
        }
        if (capture == null && caret > i && caret <= end) doCapture(i, end, false, text.substring(i, caret));
        final word = text.substring(i, end);
        final Object? value = switch (word) {
          'true' => true,
          'false' => false,
          'null' => null,
          _ => double.tryParse(word) ?? word,
        };
        onScalar(value, i, false, word);
        i = end;
      } else {
        i++;
      }
    }
    capture ??= _Capture(List.of(stack), null, null, caret, caret, false, '');
    for (final frame in stack.reversed) {
      collectTag(frame);
    }
    return _Scan(capture, tags);
  }

  // ---- schema resolution

  /// Every way an instance can satisfy [node]: lists of schema parts whose direct keywords apply together.
  List<List<Map<String, dynamic>>> _alternatives(Map<String, dynamic> node, [int depth = 0]) {
    final n = doc.deref(node);
    if (depth > 8) return [[n]];
    var result = <List<Map<String, dynamic>>>[[n]];
    for (final sub in SchemaDocument.list(n['allOf'])) {
      final own = SchemaDocument.obj(sub);
      if (own == null) continue;
      result = _product(result, _alternatives(own, depth + 1));
    }
    final union = [...SchemaDocument.list(n['oneOf']), ...SchemaDocument.list(n['anyOf'])];
    if (union.isNotEmpty) {
      result = _product(
        result,
        [
          for (final it in union)
            ..._alternatives(SchemaDocument.obj(it) ?? SchemaDocument.empty, depth + 1),
        ],
      );
    }
    return result;
  }

  List<List<Map<String, dynamic>>> _product(
    List<List<Map<String, dynamic>>> a,
    List<List<Map<String, dynamic>>> b,
  ) {
    if (b.isEmpty) return a;
    final out = <List<Map<String, dynamic>>>[];
    for (final x in a) {
      for (final y in b) {
        if (out.length >= maxAlternatives) return out;
        out.add([...x, ...y]);
      }
    }
    return out;
  }

  List<List<Map<String, dynamic>>> _alternativesOf(List<Map<String, dynamic>> schemas) =>
      [for (final schema in schemas) ..._alternatives(schema)];

  Map<String, Map<String, dynamic>> _properties(List<Map<String, dynamic>> alt) {
    final out = <String, Map<String, dynamic>>{};
    for (final part in alt) {
      final props = SchemaDocument.obj(part['properties']);
      if (props == null) continue;
      props.forEach((key, rule) {
        if (!out.containsKey(key)) out[key] = doc.deref(SchemaDocument.obj(rule) ?? SchemaDocument.empty);
      });
    }
    return out;
  }

  Set<String> _required(List<Map<String, dynamic>> alt) {
    final out = <String>{};
    for (final part in alt) {
      for (final entry in SchemaDocument.list(part['required'])) {
        if (entry is String) out.add(entry);
      }
    }
    return out;
  }

  Set<String> _types(List<Map<String, dynamic>> alt) {
    final out = <String>{};
    for (final part in alt) {
      out.addAll(SchemaDocument.types(part['type']));
    }
    return out;
  }

  bool _isObjectShape(List<Map<String, dynamic>> alt) {
    final types = _types(alt);
    if (types.isNotEmpty) return types.contains('object');
    return alt.any((part) => part.containsKey('properties') || part.containsKey('additionalProperties'));
  }

  bool _isArrayShape(List<Map<String, dynamic>> alt) {
    final types = _types(alt);
    if (types.isNotEmpty) return types.contains('array');
    return alt.any((part) => part.containsKey('items'));
  }

  /// Narrows object alternatives by the scalars the instance holds (desktop union narrowing, tolerant).
  List<List<Map<String, dynamic>>> _discriminate(
    List<List<Map<String, dynamic>>> alts,
    _Frame frame,
    String? exclude,
  ) {
    var candidates = alts.where(_isObjectShape).toList();
    if (candidates.isEmpty) return candidates;
    final tried = <String>{};
    for (var round = 0; round < 4; round++) {
      if (candidates.length <= 1) break;
      final key = _discriminator(candidates, tried);
      if (key == null) break;
      tried.add(key);
      if (key == exclude) continue;
      final narrowed = frame.scalars.containsKey(key)
          ? (() {
              final given = _instanceScalar(frame, key);
              return candidates.where((alt) {
                final rule = _properties(alt)[key];
                if (rule == null) return false;
                return SchemaDocument.pinnedValues(rule).any((option) =>
                    SchemaDocument.scalarText(option).isNotEmpty && _samePlain(option, given));
              }).toList();
            })()
          : candidates.where((alt) => !_required(alt).contains(key)).toList();
      if (narrowed.isNotEmpty) candidates = narrowed;
    }
    return candidates;
  }

  String? _discriminator(List<List<Map<String, dynamic>>> candidates, Set<String> tried) {
    final first = _properties(candidates.first);
    final ordered = [...preferredDiscriminators.where(first.containsKey), ...first.keys];
    for (final key in ordered) {
      if (tried.contains(key)) continue;
      if (candidates.every((alt) {
        final rule = _properties(alt)[key];
        return rule != null && SchemaDocument.pinnedValues(rule).isNotEmpty;
      })) {
        return key;
      }
    }
    return null;
  }

  Object? _instanceScalar(_Frame frame, String key) {
    final value = frame.scalars[key];
    if (frame.parent == null && key == 'type' && value is String) return typeAliases[value] ?? value;
    return value;
  }

  /// Schemas of the container [frames] ends with.
  List<Map<String, dynamic>> _containerSchemas(List<_Frame> frames) {
    var schemas = [for (final root in roots) if (doc.lookup(root) != null) doc.lookup(root)!];
    for (var i = 0; i < frames.length - 1; i++) {
      final frame = frames[i];
      final segment = frames[i + 1].segment;
      if (segment == null) return const [];
      schemas = frame.isObject ? _childOfObject(schemas, frame, segment, null) : _childOfArray(schemas);
      if (schemas.isEmpty) return schemas;
    }
    return schemas;
  }

  List<Map<String, dynamic>> _childOfObject(
    List<Map<String, dynamic>> schemas,
    _Frame frame,
    String key,
    String? exclude,
  ) {
    // Dedup by identity, like the Kotlin IdentityHashMap: the same rule object once.
    final out = <Map<String, dynamic>>[];
    final seen = <Map<String, dynamic>>{};
    void add(Map<String, dynamic> rule) {
      if (seen.add(rule)) out.add(rule);
    }
    final alts = _discriminate(_alternativesOf(schemas), frame, exclude);
    for (final alt in alts) {
      final rule = _properties(alt)[key];
      if (rule != null) {
        add(rule);
        continue;
      }
      for (final part in alt) {
        final additional = SchemaDocument.obj(part['additionalProperties']);
        if (additional != null) add(doc.deref(additional));
      }
    }
    return out;
  }

  List<Map<String, dynamic>> _childOfArray(List<Map<String, dynamic>> schemas) {
    final out = <Map<String, dynamic>>[];
    final seen = <Map<String, dynamic>>{};
    for (final alt in _alternativesOf(schemas)) {
      if (!_isArrayShape(alt)) continue;
      for (final part in alt) {
        final items = SchemaDocument.obj(part['items']);
        if (items != null && seen.add(items)) out.add(doc.deref(items));
      }
    }
    return out;
  }

  List<Map<String, dynamic>> _propertySchemas(List<_Frame> frames, String key) =>
      _childOfObject(_containerSchemas(frames), frames.last, key, key);

  List<Map<String, dynamic>> _elementSchemas(List<_Frame> frames) => _childOfArray(_containerSchemas(frames));

  // ---- suggestions

  List<Suggestion> _keySuggestions(String text, _Capture capture, _Frame frame) {
    final alts = _discriminate(_alternativesOf(_containerSchemas(capture.frames)), frame, null);
    if (alts.isEmpty) return const [];

    var keys = <String>{};
    final required = <String>{};
    final rules = <String, List<Map<String, dynamic>>>{};
    for (final alt in alts) {
      _properties(alt).forEach((key, rule) {
        keys.add(key);
        rules.putIfAbsent(key, () => []).add(rule);
      });
    }
    final discriminator = alts.length > 1 ? _discriminator(alts, {}) : null;
    if (discriminator != null && !frame.scalars.containsKey(discriminator)) {
      // no variant chosen yet: the discriminator first, then what every variant accepts
      final common = keys.where((key) => alts.every((alt) => _properties(alt).containsKey(key))).toSet();
      keys = {discriminator, ...common};
      required.add(discriminator);
    }
    for (final alt in alts) {
      required.addAll(_required(alt).where(keys.contains));
    }

    final caseInsensitive = _caseInsensitive();
    final ordered = [
      ...required.where(keys.contains),
      ...keys.where((key) => !required.contains(key)).toList()
        ..sort((a, b) => caseInsensitive(a, b)),
    ];
    final following = _nextSignificant(text, capture.end);
    final hasColon = following == ':';
    final needsComma = following == '"';
    final out = <Suggestion>[];
    for (final key in ordered) {
      if (frame.keys.contains(key)) continue;
      final keyRules = rules[key] ?? const <Map<String, dynamic>>[];
      final quoted = JsonFormatter.quote(key);
      final detail = _describe(keyRules) + (required.contains(key) ? ' *' : '');
      if (hasColon) {
        out.add(_suggestion(key, detail, quoted, capture, quoted.length, quoted.length, false, true));
        continue;
      }
      final template = _template(keyRules);
      final insert = '$quoted: ${template.text}${needsComma ? ',' : ''}';
      final base = quoted.length + 2;
      out.add(_suggestion(
        key, detail, insert, capture,
        base + template.selectStart, base + template.selectEnd, template.chain, true,
      ));
    }
    return out;
  }

  List<Suggestion> _valueSuggestions(
    String text,
    _Capture capture,
    List<Map<String, dynamic>> schemas,
    Set<String> present,
    Map<String, List<String>> documentTags,
  ) {
    final choices = _choices(schemas, documentTags);
    final needsComma = _nextSignificant(text, capture.end) == '"';
    final comma = needsComma ? ',' : '';
    final out = <Suggestion>[];
    final seen = <String>{};
    for (final choice in choices) {
      final value = choice.value;
      if (capture.inString && value is! String) continue;
      if (value is String && present.contains(value)) continue;
      final insert = choice.snippet != null
          ? choice.snippet!
          : value is String ? JsonFormatter.quote(value) : SchemaDocument.scalarText(value);
      if (!seen.add(insert)) continue;
      final caretAt = choice.snippet != null ? 1 : insert.length;
      out.add(_suggestion(choice.label, choice.detail, insert + comma, capture, caretAt, caretAt, false, false));
    }
    return out;
  }

  List<_Choice> _choices(List<Map<String, dynamic>> schemas, Map<String, List<String>> documentTags) {
    final out = <_Choice>[];
    var objects = false;
    var arrays = false;
    for (final alt in _alternativesOf(schemas)) {
      for (final part in alt) {
        if (part.containsKey('const')) {
          out.add(_Choice(SchemaDocument.scalarText(part['const']), 'const', part['const']));
        }
        for (final value in SchemaDocument.list(part['enum'])) {
          out.add(_Choice(SchemaDocument.scalarText(value), 'enum', value));
        }
        final types = SchemaDocument.types(part['type']);
        if (types.contains('boolean')) {
          out.add(const _Choice('true', 'boolean', true));
          out.add(const _Choice('false', 'boolean', false));
        }
        if (types.contains('null')) out.add(const _Choice('null', 'null', null));
        if (types.contains('object')) objects = true;
        if (types.contains('array')) arrays = true;
        for (final value in SchemaDocument.list(part['examples'])) {
          out.add(_Choice(SchemaDocument.scalarText(value), 'example', value));
        }
        final kind = part['x-tag-reference'];
        if (kind is String) {
          final tags = <String>{};
          tags.addAll(builtinTags[kind] ?? const []);
          tags.addAll(documentTags[kind] ?? const []);
          for (final tag in tags) {
            out.add(_Choice(tag, 'tag', tag));
          }
        }
      }
    }
    if (objects) out.add(const _Choice('{}', 'object', null, '{}'));
    if (arrays) out.add(const _Choice('[]', 'array', null, '[]'));
    return out;
  }

  _Template _template(List<Map<String, dynamic>> rules) {
    final alts = _alternativesOf(rules);
    String? first;
    for (final alt in alts) {
      for (final part in alt) {
        first = switch (part) {
          {'const': Object? value} => _kindOf(value),
          {'enum': Object? value} => SchemaDocument.list(value).isEmpty
              ? null
              : _kindOf(SchemaDocument.list(value).first),
          _ => SchemaDocument.types(part['type']).isEmpty ? null : SchemaDocument.types(part['type']).first,
        };
        if (first != null) break;
      }
      if (first != null) break;
    }
    return switch (first) {
      'string' => _Template('""', 1, 1, _choices(rules, const {}).any((choice) => choice.value is String)),
      'integer' || 'number' => const _Template('0', 0, 1, false),
      'boolean' => const _Template('false', 0, 5, false),
      'object' => const _Template('{}', 1, 1, false),
      'array' => const _Template('[]', 1, 1, false),
      'null' => const _Template('null', 0, 4, false),
      _ => const _Template('', 0, 0, false),
    };
  }

  String _kindOf(Object? value) => switch (value) {
    String() => 'string',
    bool() => 'boolean',
    num() => 'number',
    null => 'null',
    _ => 'string',
  };

  String _describe(List<Map<String, dynamic>> rules) {
    final names = <String>{};
    for (final alt in _alternativesOf(rules)) {
      for (final part in alt) {
        if (part.containsKey('const') || part.containsKey('enum')) {
          names.add('enum');
        } else {
          names.addAll(SchemaDocument.types(part['type']));
        }
      }
    }
    return names.join(' | ');
  }

  Suggestion _suggestion(String label, String detail, String insert, _Capture capture,
      int selectStart, int selectEnd, bool chain, bool isKey) {
    final caret = capture.start + (capture.inString ? 1 : 0) + capture.prefix.length;
    return Suggestion(
      label, detail, insert,
      caret - capture.start,
      capture.end - caret,
      selectStart, selectEnd, chain, isKey,
    );
  }

  /// As built while nothing is typed; alphabetical once a prefix narrows the list, prefix matches first.
  List<Suggestion> _filter(List<Suggestion> items, String prefix) {
    if (prefix.isEmpty) return items.take(maxSuggestions).toList();
    final caseInsensitive = _caseInsensitive();
    int byLabel(Suggestion a, Suggestion b) => caseInsensitive(a.label, b.label);
    bool startsWith(Suggestion item) =>
        item.label.toLowerCase().startsWith(prefix.toLowerCase());
    bool contains(Suggestion item) =>
        item.label.toLowerCase().contains(prefix.toLowerCase());
    final starts = items.where(startsWith).toList()..sort(byLabel);
    final containsPrefix = items.where((item) => !startsWith(item) && contains(item)).toList()..sort(byLabel);
    return [...starts, ...containsPrefix].take(maxSuggestions).toList();
  }

  String? _nextSignificant(String text, int from) {
    var i = from;
    while (i < text.length) {
      final c = text[i];
      if (c != ' ' && c != '\t' && c != '\r' && c != '\n') return c;
      i++;
    }
    return null;
  }

  bool _samePlain(Object? option, Object? given) => switch (option) {
    String() => given is String && option == given,
    bool() => given is bool && option == given,
    num() => given is num && SchemaDocument.sameNumber(option.toDouble(), given.toDouble()),
    null => given == null,
    _ => false,
  };

  static bool _isWordChar(String c) {
    final code = c.codeUnitAt(0);
    return (code >= 0x30 && code <= 0x39) ||
        (code >= 0x41 && code <= 0x5a) ||
        (code >= 0x61 && code <= 0x7a) ||
        code == 0x5f || code == 0x2d || code == 0x2b || code == 0x2e ||
        // letters beyond ASCII count too (Kotlin isLetterOrDigit)
        code > 0x7f;
  }

  static int Function(String, String) _caseInsensitive() =>
      (a, b) => a.toLowerCase().compareTo(b.toLowerCase());

  // ------------------------------------------------------------------------------------------------

  static const maxAlternatives = 256;
  static const maxSuggestions = 200;

  static const preferredDiscriminators = ['type', 'action'];

  /// Arrays whose object elements define tags, by the x-tag-reference kind the tags answer.
  static const definitionArrays = <String, String>{
    'outbounds': 'outbound',
    'endpoints': 'outbound',
    'inbounds': 'inbound',
    'servers': 'dns_server',
    'rule_set': 'rule_set',
    'certificate_providers': 'certificate_provider',
    'http_clients': 'http_client',
    'network_namespaces': 'network_namespace',
  };
}

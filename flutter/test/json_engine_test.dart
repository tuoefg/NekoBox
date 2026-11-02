// Behavioral tests for the Dart JSON engine, against the semantics of the Kotlin engine it ports
// (JsonTree/JsonFormatter/SchemaDocument/SchemaValidator/SchemaCompletion/SchemaStore). The schema
// exercised here is the real generated sing-box schema, the same asset the app bundles.

import 'dart:convert' as convert;
import 'dart:io';

import 'package:flutter_test/flutter_test.dart';
import 'package:b_box_ui/ui/json/engine/schema_completion.dart';
import 'package:b_box_ui/ui/json/engine/schema_document.dart';
import 'package:b_box_ui/ui/json/relaxations.dart';

SchemaDocument? _schema;

SchemaDocument schema() {
  final cached = _schema;
  if (cached != null) return cached;
  final text = File('assets/schema/sing-box.json').readAsStringSync();
  final document = SchemaStore.parse(text);
  expect(document, isNotNull, reason: 'the bundled schema must parse');
  return _schema = document!;
}

JsonParseResult parse(String text) => JsonTree.parse(text);

void main() {
  group('JsonTree', () {
    test('parses scalars with spans', () {
      final result = parse(' { "a" : true , "n": -1.5e2, "s": "x\\u0041", "nil": null } ');
      expect(result.ok, isTrue);
      final root = result.root!;
      expect(root.type, JsonType.object_);
      expect(root.keys, ['a', 'n', 's', 'nil']);
      expect(root.members[0].type, JsonType.bool_);
      expect(root.members[0].bool_, isTrue);
      expect(root.members[1].number, -150.0);
      expect(root.members[1].integral, isFalse);
      expect(root.members[2].string, 'xA');
      expect(root.members[3].type, JsonType.null_);
      // The member spans point at the values in the source.
      expect(root.members[0].span.offset, ' { "a" : '.length);
      expect(root.keySpans[0].offset, 3);
    });

    test('keeps the last duplicate key', () {
      final root = parse('{"k": 1, "k": 2}').root!;
      expect(root.indexOfKey('k'), 1);
      expect(root.member('k')!.number, 2.0);
    });

    test('comments are trivia', () {
      final root = parse('''
        // line comment
        { /* block
           comment */ "a": 1 }
      ''').root!;
      expect(root.keys, ['a']);
    });

    test('first error stops the parse with a span', () {
      final result = parse('{"a": 1,}');
      expect(result.ok, isFalse);
      expect(result.error, 'Trailing comma');
      expect(result.errorSpan.offset, '{"a": 1,'.length - 1);

      final unterminated = parse('{"a": "unclosed');
      expect(unterminated.error, 'Unterminated string');
      expect(unterminated.errorSpan.offset, 6);

      final trailing = parse('{} extra');
      expect(trailing.error, 'Unexpected content after the end of the document');
    });

    test('depth limit', () {
      final result = parse('[${'[' * 200}${']' * 200}]');
      expect(result.ok, isFalse);
      expect(result.error, 'The document is nested too deeply');
    });

    test('the scanner is lenient like the JVM reader on 1.', () {
      final root = parse('[1., 2, +3]').root!;
      expect(root.elements[0].number, 1.0);
      expect(root.elements[0].integral, isFalse);
      expect(root.elements[2].number, 3.0);
      final bad = parse('[1e]').root!;
      // "1e" parses as 1 with a fraction marker on both implementations.
      expect(bad.elements[0].number, 1.0);
    });

    test('toPlain mirrors the source order', () {
      final plain = JsonTree.toPlain(parse('{"b": [true, null], "a": 2.5}').root!);
      final map = plain as Map<String, Object?>;
      expect(map.keys.toList(), ['b', 'a']);
      expect((map['b'] as List<Object?>)[0], isTrue);
      expect(map['a'], 2.5);
    });

    test('lineColumn is 1-based', () {
      expect(JsonTree.lineColumn('a\nbc', 3), [2, 2]);
    });
  });

  group('JsonFormatter', () {
    test('pretty prints in document order', () {
      const text = '{"b":1,"a":[2,3],"e":{},"nested":{"x":"y"}}';
      expect(JsonFormatter.format(text), '''
{
  "b": 1,
  "a": [
    2,
    3
  ],
  "e": {},
  "nested": {
    "x": "y"
  }
}''');
    });

    test('refuses broken text', () {
      expect(JsonFormatter.format('{"a":}'), isNull);
    });

    test('normalizes lenient numbers, keeps strings as written', () {
      expect(JsonFormatter.format('{"n": 1., "m": +5, "s": "a\\tb"}'), '''
{
  "n": 1,
  "m": 5,
  "s": "a\\tb"
}''');
      // A strict token stays as written; only lenient ones normalize (1. and +5 above).
      expect(JsonFormatter.format('{"n": 1.50}'), '''
{
  "n": 1.50
}''');
    });

    test('quote escapes', () {
      expect(JsonFormatter.quote('a"b\nc'), r'"a\"b\nc"');
      expect(JsonFormatter.quote('\u0001'), r'"\u0001"');
    });
  });

  group('SchemaDocument', () {
    test('JSON pointer lookup with unescaping', () {
      final doc = SchemaStore.parse('''
        {"\$defs": {"a/b": {"x": 1}, "c": {"~d": 2}}, "top": 3}
      ''')!;
      expect(doc.lookup('')!['top'], 3.0);
      expect(doc.lookup(r'#/$defs/a~1b')!['x'], 1.0);
      // A pointer at a scalar is not an object: null, as the JVM cast answers.
      expect(doc.lookup(r'#/$defs/c/~0d'), isNull);
      expect(doc.lookup(r'#/$defs/c')!['~d'], 2.0);
      expect(doc.lookup(r'#/$defs/missing'), isNull);
      expect(doc.lookup('#/0'), isNull);
    });

    test('sameNumber is qFuzzyCompare(a + 1, b + 1)', () {
      expect(SchemaDocument.sameNumber(1.0, 1.0), isTrue);
      expect(SchemaDocument.sameNumber(1.0, 1.0000000000001), isTrue);
      expect(SchemaDocument.sameNumber(1.0, 2.0), isFalse);
      // Near zero the +1 shift dominates: 0 and 1e-17 are the same.
      expect(SchemaDocument.sameNumber(0.0, 1e-17), isTrue);
    });

    test('formatNumber', () {
      expect(SchemaDocument.formatNumber(5.0), '5');
      expect(SchemaDocument.formatNumber(-5.0), '-5');
      expect(SchemaDocument.formatNumber(1.5), '1.5');
    });

    test('deref follows refs', () {
      final doc = SchemaStore.parse('''
        {"\$defs": {"target": {"properties": {"a": {"type": "string"}}}},
         "node": {"allOf": [{"\$ref": "#/\$defs/target"}]}}
      ''')!;
      final node = doc.lookup('#/node')!;
      expect(doc.branchProperty(node, 'a')!['type'], 'string');
      expect(doc.branchRequired(node), isEmpty);
    });
  });

  group('SchemaStore', () {
    test('the bundled schema loads and strips the Android-unsupported variants', () {
      final document = schema();
      final endpoint = document.lookup(SchemaStore.endpoint)!;
      final encoded = convert.jsonEncode(endpoint);
      // Tailscale is registered by the core's stubs but cannot run on Android.
      expect(encoded.contains('tailscale'), isFalse);
      expect(document.lookup(r'#/$defs/Outbound'), isNotNull);
    });

    test('validator builds a oneOf union from several roots', () {
      final validator = SchemaStore.validator(schema(), [SchemaStore.outbound]);
      expect(validator, isNotNull);
      expect(SchemaStore.validator(schema(), const []), isNull);
    });
  });

  group('SchemaValidator (real schema)', () {
    SchemaValidator outboundValidator(JsonRelaxations relaxations) {
      final validator = SchemaStore.validator(schema(), [SchemaStore.outbound])!;
      relaxations.applyTo(validator);
      return validator;
    }

    JsonValue parseValue(String text) {
      final result = parse(text);
      expect(result.ok, isTrue, reason: result.error);
      return result.root!;
    }

    test('a valid shadowsocks outbound passes', () {
      final validator = outboundValidator(const JsonRelaxations());
      final issues = validator.validate(parseValue('''
        {"type": "shadowsocks", "tag": "ss", "server": "example.com",
         "server_port": 8388, "method": "aes-128-gcm", "password": "pw"}
      '''));
      expect(issues.where((issue) => issue.severity == Severity.error), isEmpty);
    });

    test('a wrong variant is named, not a field error', () {
      final validator = outboundValidator(const JsonRelaxations());
      final issues = validator.validate(parseValue('{"type": "no-such-protocol"}'));
      expect(issues, isNotEmpty);
      expect(issues.first.variantMismatch, isTrue);
      expect(issues.first.message, startsWith('Unknown type'));
    });

    test('the schema only requires the type; a wrong field type is an error', () {
      final validator = outboundValidator(const JsonRelaxations());
      // The sing-box schema's outbound branches require only "type" (the core checks the rest).
      expect(
        validator.validate(parseValue('{"type": "shadowsocks"}')),
        isEmpty,
      );
      final issues = validator.validate(parseValue('{"type": "shadowsocks", "server": 5}'));
      expect(
        issues.any((issue) => issue.severity == Severity.error && issue.message.contains('Expected string')),
        isTrue,
        reason: issues.map((issue) => issue.message).join('\n'),
      );
    });

    test('Throne-only keys relax to warnings, unknown keys as warnings', () {
      final validator = outboundValidator(ProfileJsonTables.relaxations('hysteria'));
      final issues = validator.validate(parseValue('''
        {"type": "hysteria", "server": "s", "server_port": 1, "recv_window": 100,
         "definitely_not_a_field": true, "auth_str": "x",
         "tls": {"enabled": true, "spoof_enabled": false}}
      '''));
      expect(issues.where((issue) => issue.severity == Severity.error), isEmpty,
          reason: issues.map((issue) => issue.message).join('\n'));
      expect(
        issues.any((issue) => issue.severity == Severity.warning && issue.message.contains('definitely_not_a_field')),
        isTrue,
        reason: issues.map((issue) => issue.message).join('\n'),
      );
    });

    test('issues come back in source order', () {
      final validator = outboundValidator(const JsonRelaxations());
      final issues = validator.validate(parseValue('''
        {"type": "shadowsocks", "server": "s", "server_port": 8388, "method": "aes-128-gcm",
         "password": "pw", "extra_one": 1, "extra_two": 2}
      '''));
      final offsets = issues.map((issue) => issue.span.offset).toList();
      expect(offsets, orderedEquals(offsets.toList()..sort()));
    });
  });

  group('SchemaCompletion (real schema)', () {
    late SchemaCompletion outboundCompletion;
    late SchemaCompletion configCompletion;

    setUp(() {
      // The stored-profile form resolves through the Outbound root; the raw config editor
      // resolves from the document root (RawRouteItem).
      outboundCompletion = SchemaCompletion(schema(), [SchemaStore.outbound]);
      configCompletion = SchemaCompletion(schema(), [SchemaStore.config]);
    });

    test('the discriminator comes first at the outbound root', () {
      final suggestions = outboundCompletion.suggest('{""', 2);
      expect(suggestions, isNotEmpty);
      expect(suggestions.first.label, 'type');
      final insert = suggestions.first.insert;
      expect(insert, contains('"type"'));
    });

    test('value suggestions after a key', () {
      final text = '{"type": ""';
      final suggestions = outboundCompletion.suggest(text, text.length - 1);
      expect(suggestions, isNotEmpty);
      // The outbound union's own "type" values are constants of the narrowed branches.
      final labels = suggestions.map((suggestion) => suggestion.label).toSet();
      expect(labels, contains('shadowsocks'));
    });

    test('a typed prefix filters alphabetically, starts-with first', () {
      final text = '{"ty';
      final suggestions = outboundCompletion.suggest(text, text.length);
      expect(suggestions, isNotEmpty);
      for (final suggestion in suggestions) {
        expect(suggestion.label.toLowerCase(), startsWith('ty'));
      }
    });

    test('a string caret inside an object-typed array yields no choices', () {
      final text = '{"outbounds": ["';
      // The element is an object union whose only choice would be the {} snippet, which a
      // string caret cannot take — empty, exactly like the Kotlin engine.
      expect(configCompletion.suggest(text, text.length), isEmpty);
    });

    test('tags of sibling outbounds answer an x-tag-reference', () {
      const text = '{"outbounds": [{"type": "direct", "tag": "my-direct"}, '
          '{"type": "selector", "outbounds": ["';
      final suggestions = configCompletion.suggest(text, text.length);
      final labels = suggestions.map((suggestion) => suggestion.label).toSet();
      expect(labels, contains('my-direct'));
    });
  });
}

// The span-aware JSON parser of JsonTree.cpp (Kotlin JsonTree.kt): comments count as whitespace,
// the first error stops the parse, object keys keep their order and their own spans.

enum JsonType { null_, bool_, number, string, array, object_ }

class JsonSpan {
  final int offset;
  final int length;
  const JsonSpan(this.offset, this.length);
  int get end => offset + length;

  @override bool operator ==(Object other) => other is JsonSpan && other.offset == offset && other.length == length;
  @override int get hashCode => Object.hash(offset, length);
}

/// One parsed value with its source span; object keys keep their order and their own spans (JsonTree.h).
class JsonValue {
  final JsonType type;
  JsonSpan span = const JsonSpan(0, 0);
  bool bool_ = false;
  double number = 0.0;
  bool integral = false;
  String string = '';
  final List<String> keys = [];
  final List<JsonSpan> keySpans = [];
  final List<JsonValue> members = [];
  final List<JsonValue> elements = [];
  JsonValue(this.type);

  /// The last occurrence, as the core's decoder keeps the last duplicate.
  int indexOfKey(String key) {
    for (var i = keys.length - 1; i >= 0; i--) {
      if (keys[i] == key) return i;
    }
    return -1;
  }

  JsonValue? member(String key) {
    final i = indexOfKey(key);
    return i < 0 ? null : members[i];
  }
}

class JsonParseResult {
  final JsonValue? root;
  final String error;
  final JsonSpan errorSpan;
  const JsonParseResult(this.root, this.error, this.errorSpan);
  bool get ok => root != null;
}

class JsonTree {
  static const _maxParseDepth = 96;

  static JsonParseResult parse(String text) => _Parser(text).run();

  static String typeName(JsonType type) => switch (type) {
    JsonType.null_ => 'null',
    JsonType.bool_ => 'boolean',
    JsonType.number => 'number',
    JsonType.string => 'string',
    JsonType.array => 'array',
    JsonType.object_ => 'object',
  };

  /// 1-based line and column of [offset].
  static List<int> lineColumn(String text, int offset) {
    var line = 1, column = 1;
    final end = offset < text.length ? offset : text.length;
    for (var i = 0; i < end; i++) {
      if (text[i] == '\n') { line++; column = 1; } else { column++; }
    }
    return [line, column];
  }

  /// Converts a parsed tree to plain Dart values: LinkedHashMap, List, String, double, bool, null.
  static Object? toPlain(JsonValue value) => switch (value.type) {
    JsonType.null_ => null,
    JsonType.bool_ => value.bool_,
    JsonType.number => value.number,
    JsonType.string => value.string,
    JsonType.array => [for (final element in value.elements) toPlain(element)],
    JsonType.object_ => {
      for (var i = 0; i < value.keys.length; i++) value.keys[i]: toPlain(value.members[i]),
    },
  };

  /// Kotlin's lenient text-to-double over a scanner token: the scanner allows a trailing dot and a
  /// dangling exponent sign the strict grammar rejects ("1.", "1e+"); the JVM reads both as 1.0.
  static double? tokenToDouble(String token) {
    var candidate = token;
    var value = double.tryParse(candidate);
    if (value != null) return value;
    if (candidate.endsWith('.')) candidate = candidate.substring(0, candidate.length - 1);
    while (true) {
      final last = candidate.isEmpty ? '' : candidate[candidate.length - 1];
      if (last == 'e' || last == 'E' || ((last == '+' || last == '-') &&
          candidate.length >= 2 && (candidate[candidate.length - 2] == 'e' || candidate[candidate.length - 2] == 'E'))) {
        candidate = candidate.substring(0, candidate.length - 1);
      } else {
        break;
      }
    }
    if (candidate != token) value = double.tryParse(candidate);
    if (value != null) return value;
    // ".5" and "+.5": Dart wants a digit before the dot, the JVM does not.
    final bare = token.replaceFirst(RegExp('^[+-]'), '');
    if (bare.startsWith('.')) {
      final sign = token.length > bare.length ? (token.startsWith('-') ? '-' : '+') : '';
      return double.tryParse('$sign 0$bare'.replaceAll(' ', ''));
    }
    return null;
  }

  static int _hexDigit(int charCode) {
    if (charCode >= 0x30 && charCode <= 0x39) return charCode - 0x30;
    if (charCode >= 0x41 && charCode <= 0x46) return charCode - 0x41 + 10;
    if (charCode >= 0x61 && charCode <= 0x66) return charCode - 0x61 + 10;
    return -1;
  }
}

class _ParseFailure implements Exception {
  const _ParseFailure();
}

class _Parser {
  final String text;
  int pos = 0;
  String _error = '';
  JsonSpan _errorSpan = const JsonSpan(0, 0);
  _Parser(this.text);

  JsonParseResult run() {
    _skipTrivia();
    if (_atEnd()) return JsonParseResult(null, 'Empty document', JsonSpan(pos, 0));
    JsonValue root;
    try {
      root = parseValue(0);
    } on _ParseFailure {
      return JsonParseResult(null, _error, _errorSpan);
    }
    _skipTrivia();
    if (!_atEnd()) {
      return JsonParseResult(null, 'Unexpected content after the end of the document', JsonSpan(pos, 1));
    }
    return JsonParseResult(root, '', const JsonSpan(0, 0));
  }

  bool _atEnd() => pos >= text.length;

  int get _peek => pos < text.length ? text.codeUnitAt(pos) : 0;

  Never _fail(String message, int offset, [int length = 1]) {
    if (_error.isEmpty) {
      _error = message;
      _errorSpan = JsonSpan(offset, length);
    }
    throw const _ParseFailure();
  }

  void _skipTrivia() {
    while (pos < text.length) {
      final c = text.codeUnitAt(pos);
      if (c == 0x20 || c == 0x09 || c == 0x0a || c == 0x0d) {
        pos++;
        continue;
      }
      if (c == 0x2f /* / */ && pos + 1 < text.length) {
        final next = text.codeUnitAt(pos + 1);
        if (next == 0x2f /* // */) {
          pos += 2;
          while (pos < text.length && text.codeUnitAt(pos) != 0x0a) {
            pos++;
          }
          continue;
        }
        if (next == 0x2a /* block */) {
          pos += 2;
          while (pos + 1 < text.length && !(text.codeUnitAt(pos) == 0x2a && text.codeUnitAt(pos + 1) == 0x2f)) {
            pos++;
          }
          pos = pos + 1 < text.length ? pos + 2 : text.length;
          continue;
        }
      }
      break;
    }
  }

  void _expect(int c, String message) {
    _skipTrivia();
    if (_peek != c) _fail(message, pos);
    pos++;
  }

  JsonValue parseValue(int depth) {
    if (depth > JsonTree._maxParseDepth) _fail('The document is nested too deeply', pos);
    _skipTrivia();
    if (_atEnd()) _fail('Unexpected end of document', pos, 0);
    return switch (_peek) {
      0x7b /* { */ => _parseObject(depth),
      0x5b /* [ */ => _parseArray(depth),
      0x22 /* " */ => _parseString(),
      0x74 /* t */ => _parseKeyword('true'),
      0x66 /* f */ => _parseKeyword('false'),
      0x6e /* n */ => _parseKeyword('null'),
      _ => _parseNumber(),
    };
  }

  JsonValue _parseObject(int depth) {
    final start = pos;
    pos++;
    final out = JsonValue(JsonType.object_);
    _skipTrivia();
    if (_peek == 0x7d /* } */) {
      pos++;
      out.span = JsonSpan(start, pos - start);
      return out;
    }
    while (true) {
      _skipTrivia();
      if (_peek != 0x22) _fail('Expected a property name in quotes', pos);
      final key = _parseString();
      _expect(0x3a, 'Expected a colon after the property name');
      final member = parseValue(depth + 1);
      out.keys.add(key.string);
      out.keySpans.add(key.span);
      out.members.add(member);
      _skipTrivia();
      if (_peek == 0x2c /* , */) {
        pos++;
        _skipTrivia();
        if (_peek == 0x7d) _fail('Trailing comma', pos - 1);
        continue;
      }
      if (_peek == 0x7d) {
        pos++;
        out.span = JsonSpan(start, pos - start);
        return out;
      }
      _fail('Expected a comma or a closing brace', pos);
    }
  }

  JsonValue _parseArray(int depth) {
    final start = pos;
    pos++;
    final out = JsonValue(JsonType.array);
    _skipTrivia();
    if (_peek == 0x5d /* ] */) {
      pos++;
      out.span = JsonSpan(start, pos - start);
      return out;
    }
    while (true) {
      out.elements.add(parseValue(depth + 1));
      _skipTrivia();
      if (_peek == 0x2c) {
        pos++;
        _skipTrivia();
        if (_peek == 0x5d) _fail('Trailing comma', pos - 1);
        continue;
      }
      if (_peek == 0x5d) {
        pos++;
        out.span = JsonSpan(start, pos - start);
        return out;
      }
      _fail('Expected a comma or a closing bracket', pos);
    }
  }

  JsonValue _parseString() {
    final start = pos;
    pos++;
    final sb = StringBuffer();
    while (true) {
      if (_atEnd()) _fail('Unterminated string', start);
      final c = text.codeUnitAt(pos);
      if (c == 0x22) {
        pos++;
        return JsonValue(JsonType.string)
          ..string = sb.toString()
          ..span = JsonSpan(start, pos - start);
      }
      if (c == 0x5c /* \ */) {
        if (pos + 1 >= text.length) _fail('Unterminated escape sequence', pos);
        final esc = text.codeUnitAt(pos + 1);
        pos += 2;
        switch (esc) {
          case 0x22: sb.write('"');
          case 0x5c: sb.write(r'\');
          case 0x2f: sb.write('/');
          case 0x62: sb.write('\b');
          case 0x66: sb.write('\u000c');
          case 0x6e: sb.write('\n');
          case 0x72: sb.write('\r');
          case 0x74: sb.write('\t');
          case 0x75:
            if (pos + 4 > text.length) _fail('Incomplete unicode escape sequence', pos - 2, 2);
            var code = 0;
            for (var i = pos; i < pos + 4; i++) {
              final digit = JsonTree._hexDigit(text.codeUnitAt(i));
              if (digit < 0) _fail('Invalid unicode escape sequence', pos - 2, 6);
              code = code * 16 + digit;
            }
            sb.writeCharCode(code);
            pos += 4;
          default:
            _fail('Invalid escape sequence', pos - 2, 2);
        }
        continue;
      }
      if (c < 0x20) _fail('Control character in string', pos);
      sb.writeCharCode(c);
      pos++;
    }
  }

  JsonValue _parseKeyword(String keyword) {
    final start = pos;
    if (!text.startsWith(keyword, pos)) _fail('Invalid value', pos);
    pos += keyword.length;
    final out = keyword == 'null'
        ? JsonValue(JsonType.null_)
        : (JsonValue(JsonType.bool_)..bool_ = keyword == 'true');
    out.span = JsonSpan(start, pos - start);
    return out;
  }

  JsonValue _parseNumber() {
    final start = pos;
    if (_peek == 0x2d /* - */ || _peek == 0x2b /* + */) pos++;
    var fractional = false;
    while (!_atEnd()) {
      final c = text.codeUnitAt(pos);
      if (c >= 0x30 && c <= 0x39) {
        pos++;
        continue;
      }
      if (c == 0x2e || c == 0x65 || c == 0x45) {
        fractional = true;
        pos++;
        if (!_atEnd() && (_peek == 0x2d || _peek == 0x2b)) pos++;
        continue;
      }
      break;
    }
    if (pos == start) _fail('Invalid value', pos);
    final token = text.substring(start, pos);
    final number = JsonTree.tokenToDouble(token);
    if (number == null) _fail('Invalid number', start, pos - start);
    return JsonValue(JsonType.number)
      ..number = number
      ..integral = !fractional
      ..span = JsonSpan(start, pos - start);
  }
}

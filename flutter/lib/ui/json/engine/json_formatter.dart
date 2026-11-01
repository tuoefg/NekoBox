// The pretty printer over the span tree (Kotlin JsonFormatter.kt): 2-space indent, keys in document
// order, strings as written, comments dropped. Refuses text with a syntax error, as the desktop's
// Format does (JsonEditorDialog.cpp:39-43).

import 'json_tree.dart';
import 'schema_document.dart';

class JsonFormatter {
  static final _strictNumber = RegExp(r'^-?(0|[1-9][0-9]*)(\.[0-9]+)?([eE][+-]?[0-9]+)?$');

  static String? format(String text, [String indent = '  ']) {
    final root = JsonTree.parse(text).root;
    if (root == null) return null;
    return formatValue(text, root, indent);
  }

  static String formatValue(String text, JsonValue root, [String indent = '  ']) {
    final out = StringBuffer();
    _write(text, root, indent, 0, out);
    return out.toString();
  }

  static void _write(String text, JsonValue value, String indent, int depth, StringBuffer out) {
    switch (value.type) {
      case JsonType.object_:
        if (value.keys.isEmpty) {
          out.write('{}');
          return;
        }
        out.write('{\n');
        for (var i = 0; i < value.keys.length; i++) {
          out.write(indent * (depth + 1));
          final key = value.keySpans[i];
          out
            ..write(text.substring(key.offset, key.end))
            ..write(': ');
          _write(text, value.members[i], indent, depth + 1, out);
          if (i < value.keys.length - 1) out.write(',');
          out.write('\n');
        }
        out.write(indent * depth);
        out.write('}');
      case JsonType.array:
        if (value.elements.isEmpty) {
          out.write('[]');
          return;
        }
        out.write('[\n');
        for (var i = 0; i < value.elements.length; i++) {
          out.write(indent * (depth + 1));
          _write(text, value.elements[i], indent, depth + 1, out);
          if (i < value.elements.length - 1) out.write(',');
          out.write('\n');
        }
        out.write(indent * depth);
        out.write(']');
      case JsonType.number:
        final token = text.substring(value.span.offset, value.span.end);
        out.write(_strictNumber.hasMatch(token)
            ? token
            : value.number.isFinite
                ? SchemaDocument.formatNumber(value.number)
                : token.startsWith('+') ? token.substring(1) : token);
      case JsonType.string:
        out.write(text.substring(value.span.offset, value.span.end));
      case JsonType.bool_:
        out.write(value.bool_ ? 'true' : 'false');
      case JsonType.null_:
        out.write('null');
    }
  }

  /// A JSON string literal of [value].
  static String quote(String value) {
    final out = StringBuffer();
    out.write('"');
    for (final code in value.codeUnits) {
      switch (code) {
        case 0x22: out.write(r'\"');
        case 0x5c: out.write(r'\\');
        case 0x0a: out.write(r'\n');
        case 0x0d: out.write(r'\r');
        case 0x09: out.write(r'\t');
        case 0x08: out.write(r'\b');
        case 0x0c: out.write(r'\f');
        default:
          if (code < 0x20) {
            out.write('\\u${code.toRadixString(16).padLeft(4, '0')}');
          } else {
            out.writeCharCode(code);
          }
      }
    }
    out.write('"');
    return out.toString();
  }
}

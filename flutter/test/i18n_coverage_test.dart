// The i18n coverage gate: every UI string the Dart code passes through tr() must have a zh entry
// (the en key IS the source text). Dynamic keys (form labels, section titles) are checked through
// their literal declarations. Runs in CI through the flutter-tests job.
import 'dart:io';

import 'package:flutter_test/flutter_test.dart';

void main() {
  test('every tr key has a zh translation', () {
    final zh = File('lib/l10n.dart').readAsStringSync();
    final mapStart = zh.indexOf('const _zh = <String, String>{');
    final mapEnd = zh.indexOf('};', mapStart);
    final region = zh.substring(mapStart, mapEnd);
    final keys = <String>{
      ...RegExp(r"'((?:[^'\\]|\\.)*)':").allMatches(region).map((m) => m.group(1)!),
      ...RegExp(r'"((?:[^"\\]|\\.)*)":').allMatches(region).map((m) => m.group(1)!),
    };

    final missing = <String, List<String>>{};
    final lib = Directory('lib');
    for (final file in lib.listSync(recursive: true).whereType<File>()) {
      if (!file.path.endsWith('.dart') || file.path.replaceAll('\\', '/').contains('/generated/')) continue;
      final source = file.readAsStringSync();
      final used = <String>{};

      // The direct literal form tr('...'): the deterministic key set. Dynamic lookups
      // (map-key passthroughs, ternary expressions) keep the runtime English fallback and
      // are not enforceable here.
      used.addAll(RegExp(r"\btr\(\s*'((?:[^'\\]|\\.)*)'\s*[,)]")
          .allMatches(source)
          .map((m) => m.group(1)!));

      // The form labels and section titles the schema pages pass dynamically.
      if (file.path.endsWith('protocol_form.dart')) {
        used.addAll(RegExp(r"_Field\('[^']+', '([^']+)'").allMatches(source).map((m) => m.group(1)!));
        used.addAll(RegExp(r"_Section\('([^']+)'").allMatches(source).map((m) => m.group(1)!));
      }

      for (final key in used) {
        if (!keys.contains(key)) missing.putIfAbsent(key, () => []).add(file.path);
      }
    }

    expect(missing, isEmpty,
        reason: 'keys without zh entries:\n' +
            [for (final entry in missing.entries) '${entry.key}  (${entry.value.join(', ')})'].join('\n'));
  });
}

import 'dart:io';

import 'package:b_box_ui/ui/json/engine/schema_completion.dart';
import 'package:b_box_ui/ui/json/engine/schema_document.dart';
import 'package:b_box_ui/ui/json/engine/schema_store.dart';
import 'package:flutter_test/flutter_test.dart';

void main() {
  test('probe3', () {
    final document = SchemaStore.parse(File('assets/schema/sing-box.json').readAsStringSync())!;
    final completion = SchemaCompletion(document, [SchemaStore.config]);
    final text = '{"outbounds": ["';
    final scan = completion.suggest(text, text.length);
    print('suggestions: ${scan.length}');
    // Mirror the internals through public-ish surface: the alternatives of the root.
    final rootSchema = document.lookup(SchemaStore.config)!;
    print('root props: ${rootSchema.keys.toList()}');
    final outbounds = SchemaDocument.obj(rootSchema['properties'])?['outbounds'];
    print('outbounds rule: $outbounds');
    final items = SchemaDocument.obj(outbounds)?['items'];
    print('items: $items');
    final outboundDef = document.lookup(SchemaDocument.obj(items)?[r'$ref'] ?? '');
    print('outbound def keys: ${outboundDef?.keys.toList()}');
  });
}

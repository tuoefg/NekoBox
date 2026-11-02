import 'dart:async';
import 'package:b_box_ui/main.dart';
import 'package:b_box_ui/widgets.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';

void main() {
  testWidgets('a text setting shows the stored value on the row, the example only as its hint', (tester) async {
    tester.binding.platformDispatcher.localesTestValue = const [Locale('en')];
    addTearDown(tester.binding.platformDispatcher.clearLocalesTestValue);
    const channel = MethodChannel('test.setting-text');
    final events = StreamController<dynamic>.broadcast();
    final settings = <String, dynamic>{
      'speedTestMode': 3, 'simpleDlUrl': '', 'testUrl': 'http://cp.cloudflare.com/',
    };
    final edits = <Json>[];
    final messenger = TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger;
    messenger.setMockMethodCallHandler(channel, (call) async {
      if (call.method == 'ready') {
        return {'version': 'test', 'coreVersion': 'test',
        'dashboard': {'status': 'Started'}, 'settings': settings};
      }
      if (call.method == 'settings.set') {
        final args = json(call.arguments);
        edits.add(args);
        settings[args['key'] as String] = args['value'];
        return settings;
      }
      return null;
    });
    addTearDown(() => messenger.setMockMethodCallHandler(channel, null));
    final box = BoxController(channel: channel, events: events.stream);
    await tester.pumpWidget(BoxApp(controller: box));
    await tester.pumpAndSettle();
    openPage(tester.element(find.byType(HomePage)), const TestingPage());
    await tester.pumpAndSettle();
    const example = 'A file of known size used to measure throughput';
    // A configured value is readable on the row itself.
    expect(find.text('http://cp.cloudflare.com/'), findsOneWidget);
    // An unconfigured one shows the example in its place.
    expect(find.text(example), findsOneWidget);
    // Configuring goes through the dialog, where the example is the field's hint.
    await tester.tap(find.text('Simple download URL'));
    await tester.pumpAndSettle();
    expect(find.descendant(of: find.byType(TextFormField), matching: find.text(example)), findsOneWidget);
    await tester.enterText(find.byType(TextFormField), 'http://example.test/file');
    await tester.tap(find.text('Submit'));
    await tester.pumpAndSettle();
    expect(edits.last, {'key': 'simpleDlUrl', 'value': 'http://example.test/file'});
    // The row now carries the value, not the example.
    expect(find.text('http://example.test/file'), findsOneWidget);
    expect(find.text(example), findsNothing);
    expect(tester.takeException(), isNull);
    await tester.pumpWidget(const SizedBox());
    await events.close();
  });
}

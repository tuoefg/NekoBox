import 'dart:async';
import 'package:b_box_ui/main.dart';
import 'package:b_box_ui/pages/settings.dart';
import 'package:b_box_ui/widgets.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';

void main() {
  testWidgets('every service switch applies immediately without an apply button', (tester) async {
    tester.binding.platformDispatcher.localesTestValue = const [Locale('en')];
    addTearDown(tester.binding.platformDispatcher.clearLocalesTestValue);
    const channel = MethodChannel('test.service-settings');
    final events = StreamController<dynamic>.broadcast();
    final settings = <String, dynamic>{'allowBypass': false, 'showGroupInNotification': false, 'showDirectSpeed': false};
    final edits = <Json>[];
    final messenger = TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger;
    messenger.setMockMethodCallHandler(channel, (call) async {
      if (call.method == 'ready') return {'version': 'test', 'coreVersion': 'test',
        'dashboard': {'status': 'Started'}, 'settings': settings};
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
    openPage(tester.element(find.byType(HomePage)), const GeneralSettingsPage());
    await tester.pumpAndSettle();
    await tester.tap(find.text('Service'));
    await tester.pumpAndSettle();
    expect(find.byType(ServiceSettingsPage), findsOneWidget);
    expect(find.text('Apply service changes'), findsNothing);
    for (final (title, key, value, restarts) in [
      ('Allow VPN bypass', 'allowBypass', true, true),
      ('Show group name in the notification', 'showGroupInNotification', true, false),
      ('Show direct speed', 'showDirectSpeed', true, false),
    ]) {
      await tester.tap(find.text(title));
      await tester.pumpAndSettle();
      // Only a switch marked autoRestart asks the host to reload the running service.
      expect(edits.last, {'key': key, 'value': value, if (restarts) 'restart': true});
      expect(box.settings[key], value);
      expect(box.applyRequired, null);
    }
    expect(edits.length, 3);
    expect(tester.takeException(), isNull);
    await tester.pumpWidget(const SizedBox());
    await events.close();
  });

  testWidgets('service switches stay disabled until the native restart completes', (tester) async {
    tester.binding.platformDispatcher.localesTestValue = const [Locale('en')];
    addTearDown(tester.binding.platformDispatcher.clearLocalesTestValue);
    const channel = MethodChannel('test.service-settings-busy');
    final events = StreamController<dynamic>.broadcast();
    final reply = Completer<Json>();
    final edits = <Json>[];
    final messenger = TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger;
    messenger.setMockMethodCallHandler(channel, (call) async {
      if (call.method == 'ready') return {'version': 'test', 'coreVersion': 'test',
        'dashboard': {'status': 'Started'}, 'settings': {'allowBypass': false, 'showDirectSpeed': false}};
      if (call.method == 'settings.set') { edits.add(json(call.arguments)); return reply.future; }
      return null;
    });
    addTearDown(() => messenger.setMockMethodCallHandler(channel, null));
    final box = BoxController(channel: channel, events: events.stream);
    await tester.pumpWidget(BoxApp(controller: box));
    await tester.pumpAndSettle();
    openPage(tester.element(find.byType(HomePage)), const ServiceSettingsPage());
    await tester.pumpAndSettle();
    await tester.tap(find.text('Allow VPN bypass'));
    await tester.pump();
    await tester.pump();
    final switches = find.descendant(of: find.byType(ServiceSettingsPage), matching: find.byType(CommonSwitch));
    // The page has more switches than fit a test viewport, so assert on the ones that are built.
    expect(switches, findsWidgets);
    expect(box.serviceSettingsChanging, true);
    expect(tester.widgetList<CommonSwitch>(switches).every((widget) => widget.onChanged == null), true);
    await tester.tap(find.text('Show direct speed'));
    await tester.pump();
    expect(edits.length, 1);
    reply.complete({'allowBypass': true, 'showDirectSpeed': false});
    await tester.pumpAndSettle();
    expect(box.serviceSettingsChanging, false);
    expect(tester.widgetList<CommonSwitch>(switches).every((widget) => widget.onChanged != null), true);
    expect(tester.takeException(), isNull);
    await tester.pumpWidget(const SizedBox());
    await events.close();
  });
}

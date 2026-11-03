import 'dart:async';
import 'package:b_box_ui/main.dart';
import 'package:b_box_ui/widgets.dart';
import 'package:b_box_ui/pages/profiles.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'support/errors.dart';

/// A rule as BboxRoutes.ruleToMap emits it: metadata, the string fields, every list field and the booleans.
Map<String, Object?> rule({String name = '', String type = 'custom', String action = 'route'}) => {
  'name': name, 'type': type, 'disabled': false, 'action': action, 'outbound_id': -1,
  'ip_version': '', 'network': '', 'protocol': '', 'reject_method': '', 'override_address': '',
  'override_port': '', 'tls_fragment_fallback_delay': '', 'tls_spoof': '', 'tls_spoof_method': '',
  'strategy': '',
  'inbound': <String>[], 'domain': <String>[], 'domain_suffix': <String>[],
  'domain_keyword': <String>[], 'domain_regex': <String>[], 'source_ip_cidr': <String>[],
  'ip_cidr': <String>[], 'source_port': <String>[], 'source_port_range': <String>[], 'port': <String>[],
  'port_range': <String>[], 'process_name': <String>[], 'process_path': <String>[],
  'process_path_regex': <String>[], 'package_name': <String>[], 'package_name_regex': <String>[],
  'network_type': <String>[], 'wifi_ssid': <String>[], 'wifi_bssid': <String>[],
  'rule_set': <String>[], 'sniffers': <String>[],
  'source_ip_is_private': false, 'ip_is_private': false, 'invert': false, 'no_drop': false,
  'sniff_override_dest': false, 'network_is_expensive': false, 'tls_fragment': false,
  'tls_record_fragment': false,
};

/// Android turns `enableSuggestions: false` into TYPE_TEXT_VARIATION_VISIBLE_PASSWORD, because several IMEs
/// ignore TYPE_TEXT_FLAG_NO_SUGGESTIONS. The system then treats the field as a password box, switches to its
/// secure keyboard and drops the clipboard, so no ordinary field may ask for it.
void expectOrdinaryKeyboard(WidgetTester tester, String where) {
  final fields = tester.widgetList<EditableText>(find.byType(EditableText)).toList();
  expect(fields, isNotEmpty, reason: '$where: expected at least one text field');
  for (final field in fields) {
    if (field.obscureText) continue; // A real secret may take the password keyboard.
    expect(field.enableSuggestions, isTrue,
      reason: '$where: enableSuggestions false reads as a password field on Android and blocks paste');
    expect(field.keyboardType, isNot(TextInputType.visiblePassword),
      reason: '$where: a visible-password keyboard is the password IME');
  }
}

void main() {
  late StreamController<dynamic> events;

  Future<void> mount(WidgetTester tester) async {
    reportFlutterErrors();
    tester.binding.platformDispatcher.localesTestValue = const [Locale('en')];
    addTearDown(tester.binding.platformDispatcher.clearLocalesTestValue);
    tester.view.physicalSize = const Size(360, 1400);
    tester.view.devicePixelRatio = 1;
    addTearDown(tester.view.resetPhysicalSize);
    addTearDown(tester.view.resetDevicePixelRatio);
    const channel = MethodChannel('test.input-keyboard');
    events = StreamController<dynamic>.broadcast();
    final messenger = TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger;
    messenger.setMockMethodCallHandler(channel, (call) async {
      switch (call.method) {
        case 'ready':
          return {
            'version': 'test', 'coreVersion': 'test', 'preferences': {'locale': 'en'},
            'dashboard': {'status': 'Stopped'},
          };
        case 'routes.get':
          return {
            'id': 1, 'name': 'Default', 'defaultOutbound': 'direct', 'isRemote': false,
            'remoteUrl': '', 'autoUpdate': false, 'isRaw': false, 'endpointIds': <int>[],
            'rules': '', 'ruleCount': 1,
          };
        case 'routes.list':
          return {
            'currentId': 1,
            'profiles': [
              {'id': 1, 'name': 'Default', 'current': true, 'isRemote': false, 'isRaw': false,
                'ruleCount': 1, 'simpleRuleCount': 0},
            ],
          };
        case 'routes.rules':
          return {'rules': [rule(name: 'Direct LAN')]};
      }
      return null;
    });
    final box = BoxController(channel: channel, events: events.stream);
    await tester.pumpWidget(BoxApp(controller: box));
    await tester.pumpAndSettle();
    addTearDown(() async {
      await tester.pumpWidget(const SizedBox());
      await events.close();
      messenger.setMockMethodCallHandler(channel, null);
    });
  }

  testWidgets('the remote link field keeps a keyboard that can paste', (tester) async {
    await mount(tester);
    openPage(tester.element(find.byType(HomePage)), const ProfileForm(initial: {'remote': true}));
    await tester.pumpAndSettle();
    expect(find.text('URL'), findsOneWidget);
    expectOrdinaryKeyboard(tester, 'profile remote URL');
    expect(tester.takeException(), isNull);
  });

  testWidgets('the rule fields keep a keyboard that can paste', (tester) async {
    await mount(tester);
    openPage(tester.element(find.byType(HomePage)), const RouteEditorPage(id: 1));
    await tester.pumpAndSettle();

    await tester.tap(find.text('Add rule'));
    await tester.pumpAndSettle();
    expectOrdinaryKeyboard(tester, 'add rule');

    Navigator.of(tester.element(find.text('Add rule').first)).pop();
    await tester.pumpAndSettle();

    await tester.tap(find.text('Direct LAN'));
    await tester.pumpAndSettle();
    expectOrdinaryKeyboard(tester, 'edit rule');
    expect(tester.takeException(), isNull);
  });

}

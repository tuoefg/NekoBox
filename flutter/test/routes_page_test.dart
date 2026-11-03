import 'dart:async';
import 'package:b_box_ui/main.dart';
import 'package:b_box_ui/widgets.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'support/errors.dart';

/// A rule as BboxRoutes.ruleToMap emits it: metadata, the string fields, every list field and the booleans.
Map<String, Object?> rule({
  String name = '',
  String type = 'custom',
  String action = 'route',
  int outboundId = -1,
  List<String> domain = const [],
  List<String> domainSuffix = const [],
  List<String> ipCidr = const [],
  List<String> processName = const [],
  List<String> networkType = const [],
}) => {
  'name': name, 'type': type, 'disabled': false, 'action': action, 'outbound_id': outboundId,
  'ip_version': '', 'network': '', 'protocol': '', 'reject_method': '', 'override_address': '',
  'override_port': '', 'tls_fragment_fallback_delay': '', 'tls_spoof': '', 'tls_spoof_method': '',
  'strategy': '',
  'inbound': <String>[], 'domain': domain, 'domain_suffix': domainSuffix,
  'domain_keyword': <String>[], 'domain_regex': <String>[], 'source_ip_cidr': <String>[],
  'ip_cidr': ipCidr, 'source_port': <String>[], 'source_port_range': <String>[], 'port': <String>[],
  'port_range': <String>[], 'process_name': processName, 'process_path': <String>[],
  'process_path_regex': <String>[], 'package_name': <String>[], 'package_name_regex': <String>[],
  'network_type': networkType, 'wifi_ssid': <String>[], 'wifi_bssid': <String>[],
  'rule_set': <String>[], 'sniffers': <String>[],
  'source_ip_is_private': false, 'ip_is_private': false, 'invert': false, 'no_drop': false,
  'sniff_override_dest': false, 'network_is_expensive': false, 'tls_fragment': false,
  'tls_record_fragment': false,
};

void main() {
  late StreamController<dynamic> events;
  late List<Map<String, Object?>> rules;

  Future<void> mount(WidgetTester tester) async {
    tester.binding.platformDispatcher.localesTestValue = const [Locale('en')];
    addTearDown(tester.binding.platformDispatcher.clearLocalesTestValue);
    tester.view.physicalSize = const Size(360, 1400);
    tester.view.devicePixelRatio = 1;
    addTearDown(tester.view.resetPhysicalSize);
    addTearDown(tester.view.resetDevicePixelRatio);
    const channel = MethodChannel('test.routes-page');
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
            'rules': '', 'ruleCount': rules.length,
          };
        case 'routes.list':
          return {
            'currentId': 1,
            'profiles': [
              {'id': 1, 'name': 'Default', 'current': true, 'isRemote': false, 'isRaw': false,
                'ruleCount': rules.length, 'simpleRuleCount': 0},
            ],
          };
        case 'routes.rules':
          return {'rules': rules};
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

  testWidgets('the route profile editor and its rule editor render without exceptions', (tester) async {
    reportFlutterErrors();
    rules = [
      rule(name: 'Direct LAN', domainSuffix: ['.lan']),
      rule(name: 'Simple', type: 'simple_address_proxy', outboundId: -1, domain: ['example.org']),
      rule(name: 'Simple warp', type: 'simple_process_name_warp_bypass', outboundId: -5,
        processName: ['com.example']),
    ];
    await mount(tester);
    openPage(tester.element(find.byType(HomePage)), const RouteEditorPage(id: 1));
    await tester.pumpAndSettle();
    expect(find.text('Default'), findsWidgets);
    expect(find.text('Direct LAN'), findsOneWidget);
    expect(find.text('Simple'), findsOneWidget);
    expect(tester.takeException(), isNull);

    // Editing an existing plain rule.
    await tester.tap(find.text('Direct LAN'));
    await tester.pumpAndSettle();
    expect(find.text('Edit rule'), findsWidgets);
    expect(tester.takeException(), isNull);
    Navigator.of(tester.element(find.text('Edit rule').first)).pop();
    await tester.pumpAndSettle();

    // Editing a simple rule (type token carries the action suffix).
    await tester.tap(find.text('Simple'));
    await tester.pumpAndSettle();
    expect(find.text('Edit rule'), findsWidgets);
    expect(tester.takeException(), isNull);
    Navigator.of(tester.element(find.text('Edit rule').first)).pop();
    await tester.pumpAndSettle();

    // Editing a simple rule whose action is warp_bypass.
    await tester.tap(find.text('Simple warp'));
    await tester.pumpAndSettle();
    expect(find.text('Edit rule'), findsWidgets);
    expect(tester.takeException(), isNull);
    Navigator.of(tester.element(find.text('Edit rule').first)).pop();
    await tester.pumpAndSettle();

    // Adding a new rule starts from an empty draft.
    await tester.tap(find.text('Add rule'));
    await tester.pumpAndSettle();
    expect(find.text('Add rule'), findsWidgets);
    expect(tester.takeException(), isNull);
  });
}

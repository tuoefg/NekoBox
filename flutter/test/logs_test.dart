import 'dart:async';
import 'package:b_box_ui/main.dart';
import 'package:b_box_ui/pages/logs.dart';
import 'package:b_box_ui/widgets.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';

const _levels = ['Trace', 'Debug', 'Info', 'Warn', 'Error', 'Fatal', 'Panic'];
final _records = <Json>[
  for (var index = 0; index < _levels.length; index++)
    {'id': index + 1, 'level': _levels[index], 'message': 'core ${_levels[index]} record'},
];

void main() {
  late StreamController<dynamic> events;
  late BoxController box;
  String? exported;
  final calls = <MethodCall>[];
  final messages = <String>[];
  Object? saveResult = true;
  Completer<Object?>? pendingSave;

  Future<void> mount(WidgetTester tester, {bool remote = false}) async {
    tester.binding.platformDispatcher.localesTestValue = const [Locale('en')];
    addTearDown(tester.binding.platformDispatcher.clearLocalesTestValue);
    tester.view.physicalSize = const Size(360, 1200);
    tester.view.devicePixelRatio = 1;
    addTearDown(tester.view.resetPhysicalSize);
    addTearDown(tester.view.resetDevicePixelRatio);
    const channel = MethodChannel('test.logs');
    events = StreamController<dynamic>.broadcast();
    final messenger = TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger;
    calls.clear(); messages.clear(); saveResult = true; pendingSave = null;
    messenger.setMockMethodCallHandler(channel, (call) async {
      calls.add(call);
      if (call.method == 'ready') return {
        'version': 'test', 'coreVersion': 'test',
        'dashboard': {'status': 'Stopped',
          if (remote) ...{'remote': 'Server', 'remoteConnected': true}},
      };
      if (call.method == 'document.save') {
        exported = json(call.arguments)['content'] as String;
        if (saveResult is PlatformException) throw saveResult!;
        return pendingSave == null ? saveResult : await pendingSave!.future;
      }
      if (call.method == 'logs.clear') {
        events.add({'type': 'logs', 'value': {'defaultLogLevel': box.defaultLogLevel, 'entries': []}});
      }
      if (call.method == 'logs.logcat.clear') {
        events.add({'type': 'logcat', 'value': {'reset': true, 'entries': []}});
      }
      return null;
    });
    box = BoxController(channel: channel, events: events.stream);
    final messageSubscription = box.messages.stream.listen(messages.add);
    exported = null;
    await tester.pumpWidget(BoxApp(controller: box));
    await tester.pumpAndSettle();
    addTearDown(() async {
      await tester.pumpWidget(const SizedBox());
      await messageSubscription.cancel();
      await events.close();
      messenger.setMockMethodCallHandler(channel, null);
    });
    openPage(tester.element(find.byType(HomePage)), const LogsPage());
    await tester.pumpAndSettle();
  }

  CommonScaffold scaffold(WidgetTester tester) => tester.widget<CommonScaffold>(
    find.descendant(of: find.byType(LogsPage), matching: find.byType(CommonScaffold)).first,
  );

  Future<void> emit(WidgetTester tester, String? level, [List<Json>? records]) async {
    events.add({'type': 'logs', 'value': {'defaultLogLevel': level, 'entries': records ?? _records}});
    await tester.pumpAndSettle();
  }

  Future<void> choose(WidgetTester tester, String label) async {
    await tester.tap(find.byTooltip('More').last);
    await tester.pumpAndSettle();
    await tester.tap(find.text(label).last);
    await tester.pumpAndSettle();
  }

  Future<void> expectExport(WidgetTester tester, List<String> levels) async {
    scaffold(tester).iconActions.first.onPressed!();
    await tester.pumpAndSettle();
    expect(exported, isNotNull);
    expect(exported!.split('\n').where((line) => line.isNotEmpty),
      levels.map((level) => '— [$level] core $level record'));
  }

  for (final remote in [false, true]) {
    testWidgets('default follows the core threshold including severe logs, remote=$remote', (tester) async {
      await mount(tester, remote: remote);
      for (var index = 0; index < _levels.length; index++) {
        final level = _levels[index];
        await emit(tester, level);
        final selected = scaffold(tester).menuItems.where((item) => item.checked == true);
        expect(selected.map((item) => item.label), ['Follow configuration ($level)']);
        await expectExport(tester, _levels.sublist(index));
        expect(box.logs, hasLength(_levels.length), reason: 'Filtering must retain the original records.');
        if (level == 'Warn') {
          expect(find.text('core Error record'), findsOneWidget);
          expect(find.text('core Info record'), findsNothing);
        }
      }
      expect(tester.takeException(), isNull);
    });
  }

  testWidgets('manual filters survive core updates and can return to following configuration', (tester) async {
    await mount(tester);
    await emit(tester, 'Info');
    await choose(tester, 'All');
    await emit(tester, 'Error');
    await expectExport(tester, _levels);
    await choose(tester, 'Debug');
    await emit(tester, 'Warn');
    await expectExport(tester, ['Debug']);
    await choose(tester, 'Follow configuration (Warn)');
    await expectExport(tester, ['Warn', 'Error', 'Fatal', 'Panic']);
    await emit(tester, 'Info');
    await expectExport(tester, ['Info', 'Warn', 'Error', 'Fatal', 'Panic']);
    expect(tester.takeException(), isNull);
  });

  testWidgets('logcat switches subscriptions, retains only the live window, exports and clears', (tester) async {
    await mount(tester);
    await emit(tester, 'Error');
    await tester.tap(find.text('Logcat'));
    await tester.pumpAndSettle();
    expect(calls.where((call) => call.method == 'logs.visible').last.arguments, {'visible': false});
    expect(calls.where((call) => call.method == 'logs.logcat.visible').last.arguments, {'visible': true});
    events.add({'type': 'logcat', 'value': {'reset': true, 'entries': _records}});
    await tester.pumpAndSettle();
    await expectExport(tester, _levels);
    final saved = json(calls.lastWhere((call) => call.method == 'document.save').arguments);
    expect(saved['name'], 'Logcat.log');
    expect(saved['redact'], true);
    expect(messages.last, 'Exported');

    events.add({'type': 'logcat', 'value': {'reset': false, 'firstId': 4, 'entries': [
      _records.last, {'id': 8, 'level': 'info', 'message': 'later logcat record'},
    ]}});
    await tester.pumpAndSettle();
    expect(box.logcat.map((line) => line['id']), [4, 5, 6, 7, 8]);
    await choose(tester, 'Pause');
    await choose(tester, 'Clear');
    expect(calls.where((call) => call.method == 'logs.logcat.clear'), hasLength(1));
    expect(box.logcat, isEmpty);
    expect(find.text('No logs'), findsOneWidget);
    expect(scaffold(tester).iconActions.first.onPressed, isNull);

    events.add({'type': 'logcat', 'value': {'reset': true, 'entries': [
      {'id': 9, 'level': 'info', 'message': 'after clear'},
    ]}});
    await tester.pumpAndSettle();
    expect(find.text('after clear'), findsOneWidget);
    expect(find.text('later logcat record'), findsNothing);
    await tester.tap(find.text('Core log'));
    await tester.pumpAndSettle();
    expect(calls.where((call) => call.method == 'logs.logcat.visible').last.arguments, {'visible': false});
    expect(calls.where((call) => call.method == 'logs.visible').last.arguments, {'visible': true});
    expect(tester.takeException(), isNull);
  });

  for (final result in [true, false, null]) {
    testWidgets('export distinguishes success, failure and cancellation: $result', (tester) async {
      await mount(tester);
      await emit(tester, 'Error');
      saveResult = result;
      await expectExport(tester, ['Error', 'Fatal', 'Panic']);
      expect(messages, result == null ? isEmpty : [result ? 'Exported' : 'Export failed']);
      expect(scaffold(tester).iconActions.first.onPressed, isNotNull);
      expect(tester.takeException(), isNull);
    });
  }

  testWidgets('export prevents reentry and reports platform errors without losing logs', (tester) async {
    await mount(tester);
    expect(scaffold(tester).iconActions.first.onPressed, isNull);
    await emit(tester, 'Error');
    pendingSave = Completer<Object?>();
    final export = scaffold(tester).iconActions.first.onPressed!;
    export(); export();
    await tester.pumpAndSettle();
    expect(calls.where((call) => call.method == 'document.save'), hasLength(1));
    expect(scaffold(tester).iconActions.first.onPressed, isNull);
    pendingSave!.complete(null);
    await tester.pumpAndSettle();
    expect(messages, isEmpty);
    expect(scaffold(tester).iconActions.first.onPressed, isNotNull);
    pendingSave = null;
    saveResult = PlatformException(code: 'failed', message: 'Cannot write document');
    scaffold(tester).iconActions.first.onPressed!();
    await tester.pumpAndSettle();
    expect(messages, ['Cannot write document']);
    expect(box.logs, hasLength(_levels.length));
    expect(scaffold(tester).iconActions.first.onPressed, isNotNull);
    scaffold(tester).onKeywordsUpdate!(['no matching records']);
    await tester.pumpAndSettle();
    expect(scaffold(tester).iconActions.first.onPressed, isNull);
    expect(tester.takeException(), isNull);
  });

  testWidgets('unknown defaults, pause, search, export and clear keep records available', (tester) async {
    await mount(tester);
    await emit(tester, null);
    await expectExport(tester, _levels);
    await emit(tester, 'Warn');
    await choose(tester, 'Pause');
    await emit(tester, 'Warn', [..._records, {'id': 8, 'level': 'Error', 'message': 'later record'}]);
    scaffold(tester).onKeywordsUpdate!(['core']);
    await tester.pumpAndSettle();
    await expectExport(tester, ['Warn', 'Error', 'Fatal', 'Panic']);
    scaffold(tester).onKeywordsUpdate!([]);
    await tester.pumpAndSettle();
    expect(find.text('later record'), findsNothing);
    await choose(tester, 'Resume');
    expect(find.text('later record'), findsOneWidget);
    await choose(tester, 'Clear');
    expect(box.logs, isEmpty);
    expect(box.defaultLogLevel, 'Warn');
    expect(find.text('No logs'), findsOneWidget);
    expect(tester.takeException(), isNull);
  });

  const noiseMessage = 'connection: report handshake success: connection reset by peer';

  testWidgets('benign connection-reset errors fold behind a counting chip on the core tab', (tester) async {
    await mount(tester);
    await emit(tester, null, [..._records,
      {'id': 8, 'level': 'Error', 'message': noiseMessage},
      {'id': 9, 'level': 'Error', 'message': noiseMessage},
      {'id': 10, 'level': 'Error', 'message': 'real failure: denied'},
    ]);
    // Folded by default: the chip counts the noise, the list keeps only real failures.
    expect(find.text('Folded 2 connection resets'), findsOneWidget);
    expect(find.textContaining('report handshake success'), findsNothing);
    expect(find.text('real failure: denied'), findsOneWidget);
    // Expanding shows every record and asks nothing.
    await tester.tap(find.text('Folded 2 connection resets'));
    await tester.pumpAndSettle();
    expect(find.text('Hide connection-reset noise'), findsOneWidget);
    expect(find.textContaining('report handshake success'), findsNWidgets(2));
    // Collapsing again hides them.
    await tester.tap(find.text('Hide connection-reset noise'));
    await tester.pumpAndSettle();
    expect(find.textContaining('report handshake success'), findsNothing);
    expect(tester.takeException(), isNull);
  });

  testWidgets('the logcat tab never folds: system noise is the system\'s own', (tester) async {
    await mount(tester);
    expect(tester.takeException(), isNull, reason: 'after mount');
    await emit(tester, null, [..._records, {'id': 8, 'level': 'Error', 'message': noiseMessage}]);
    expect(tester.takeException(), isNull, reason: 'after core emit');
    expect(find.text('Folded 1 connection resets'), findsOneWidget);
    // Switching to Logcat keeps the record and offers no fold chip.
    await tester.tap(find.text('Logcat'));
    await tester.pumpAndSettle();
    expect(tester.takeException(), isNull, reason: 'after tab switch');
    events.add({'type': 'logcat', 'value': {'entries': [
      {'id': 1, 'level': 'Error', 'message': noiseMessage},
    ]}});
    await tester.pumpAndSettle();
    expect(tester.takeException(), isNull, reason: 'after logcat emit');
    expect(find.text('Folded 1 connection resets'), findsNothing);
    expect(find.textContaining('report handshake success'), findsOneWidget);
    expect(tester.takeException(), isNull, reason: 'final');
  });
}

import 'dart:async';
import 'package:b_box_ui/box.dart';
import 'package:b_box_ui/common/ip_probe.dart';
import 'package:b_box_ui/common/routed_ip_probe.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();
  const channel = MethodChannel('test.routed-probe');
  final messenger = TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger;
  late BoxController box;
  late RoutedIpProbe probe;
  final calls = <String>[];
  final sessions = <String>{};
  Completer<void>? opening;
  Completer<void>? response;
  PlatformException? openError;

  setUp(() {
    calls.clear(); sessions.clear(); opening = null; response = null; openError = null;
    box = BoxController(channel: channel, events: const Stream.empty());
    probe = RoutedIpProbe(box);
    messenger.setMockMethodCallHandler(channel, (call) async {
      calls.add(call.method);
      final id = json(call.arguments)['id'] as String;
      switch (call.method) {
        case 'dashboard.egress': return {'ip': '203.0.113.9'};
        case 'dashboard.probe.open':
          if (opening != null) await opening!.future;
          if (openError != null) throw openError!;
          sessions.add(id);
          return null;
        case 'dashboard.probe.get':
          if (!sessions.contains(id)) throw PlatformException(code: 'missing_session');
          if (response != null) await response!.future;
          return {'status': 200, 'body': 'answer'};
        case 'dashboard.probe.close':
          sessions.remove(id);
          return null;
      }
      throw StateError(call.method);
    });
  });

  tearDown(() async {
    await probe.close();
    expect(sessions, isEmpty);
    box.dispose();
    messenger.setMockMethodCallHandler(channel, null);
  });

  Future<IpProbeResponse> get() => probe.get('https://example.com/probe', const Duration(seconds: 1));
  Future<void> flushCalls() => Future<void>.delayed(Duration.zero);

  test('fresh egress opens one session before concurrent quality requests', () async {
    await probe.egress();
    expect(sessions, isEmpty);
    opening = Completer<void>();
    final answers = Future.wait([get(), get(), get()]);
    await flushCalls();
    expect(calls, ['dashboard.egress', 'dashboard.probe.open']);
    opening!.complete();
    expect((await answers).map((answer) => answer.body), ['answer', 'answer', 'answer']);
    expect(calls.where((method) => method == 'dashboard.probe.open'), hasLength(1));
    expect(calls.where((method) => method == 'dashboard.probe.get'), hasLength(3));
  });

  test('cancel while opening waits for it, closes once and never sends get', () async {
    opening = Completer<void>();
    final failed = expectLater(get(), throwsA(isA<IpProbeCancelled>()));
    await flushCalls();
    probe.cancel();
    final close = probe.close();
    opening!.complete();
    await failed;
    await close;
    expect(calls, ['dashboard.probe.open', 'dashboard.probe.close']);
    expect(probe.cancelled, isTrue);
    await expectLater(get(), throwsA(isA<IpProbeCancelled>()));
    expect(calls, ['dashboard.probe.open', 'dashboard.probe.close']);
  });

  test('successful close prevents reopen without invalidating a completed lookup', () async {
    expect((await get()).status, 200);
    await probe.close();
    final count = calls.length;
    await expectLater(get(), throwsA(isA<IpProbeCancelled>()));
    await expectLater(probe.open(), throwsA(isA<IpProbeCancelled>()));
    await expectLater(probe.egress(), throwsA(isA<IpProbeCancelled>()));
    expect(calls, hasLength(count));
    expect(probe.cancelled, isFalse);
  });

  test('late response after cancel is discarded and the session is released', () async {
    response = Completer<void>();
    final failed = expectLater(get(), throwsA(isA<IpProbeCancelled>()));
    await flushCalls();
    expect(calls.last, 'dashboard.probe.get');
    probe.cancel();
    await probe.close();
    response!.complete();
    await failed;
    expect(calls.where((method) => method == 'dashboard.probe.close'), hasLength(1));
  });

  test('open failure reaches every waiter without sending unowned requests', () async {
    openError = PlatformException(code: 'unavailable');
    await Future.wait([
      expectLater(get(), throwsA(isA<PlatformException>())),
      expectLater(get(), throwsA(isA<PlatformException>())),
    ]);
    expect(calls, ['dashboard.probe.open']);
    await probe.close();
    expect(calls, ['dashboard.probe.open', 'dashboard.probe.close']);
  });
}

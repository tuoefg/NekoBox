import 'dart:async';
import 'package:flutter/foundation.dart';
import 'package:flutter/services.dart';
import '../box.dart';
import 'ip_probe.dart';

/// The native session uses the device network while stopped and sing-box while
/// running, sharing cancellation between IP and quality requests.
class RoutedIpProbe implements IpProbeTransport {
  RoutedIpProbe(this.box) : id = '${DateTime.now().microsecondsSinceEpoch}:${_serial++}';
  static int _serial = 0;
  final BoxController box;
  final String id;
  bool _cancelled = false, _closed = false;
  Future<void>? _opening;
  Future<void>? _closing;
  @override bool get cancelled => _cancelled;

  Future<void> open() async {
    if (cancelled || _closed) throw const IpProbeCancelled();
    await (_opening ??= _open());
    if (cancelled || _closed) throw const IpProbeCancelled();
  }
  Future<void> _open() async {
    await box.call('dashboard.probe.open', {'id': id});
  }

  Future<Json> egress() async {
    if (cancelled || _closed) throw const IpProbeCancelled();
    final result = json(await box.call('dashboard.egress', {'id': id}));
    if (cancelled || _closed) throw const IpProbeCancelled();
    return result;
  }

  @override Future<IpProbeResponse> get(String url, Duration timeout) async {
    if (cancelled || _closed) throw const IpProbeCancelled();
    // Share one native session across the parallel quality sources.
    await open();
    if (cancelled || _closed) throw const IpProbeCancelled();
    try {
      final result = json(await box.call('dashboard.probe.get', {'id': id, 'url': url, 'timeoutMillis': timeout.inMilliseconds}));
      if (cancelled || _closed) throw const IpProbeCancelled();
      return IpProbeResponse(status: (result['status'] as num?)?.toInt() ?? 503, body: result['body'] as String? ?? '');
    } on PlatformException catch (error) {
      if (cancelled || _closed) throw const IpProbeCancelled();
      if (error.message?.contains('deadline exceeded') == true) throw TimeoutException('IP source timed out');
      rethrow;
    }
  }

  void cancel() { _cancelled = true; unawaited(close()); }

  @override Future<void> close() {
    _closed = true;
    return _closing ??= _close();
  }
  Future<void> _close() async {
    // A delayed open must finish before close, or it could leave a session behind.
    try { await _opening; } catch (_) { /* The opener reports its own failure. */ }
    try { await box.call('dashboard.probe.close', {'id': id}); }
    catch (error) { debugPrint('Unable to close IP probe: ${BoxController.describe(error)}'); }
  }
}

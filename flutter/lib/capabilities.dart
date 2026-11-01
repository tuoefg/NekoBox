import 'box.dart';

/// What the host can actually answer.
///
/// The host advertises a manifest on `ready`; anything false there is a B-box feature with no
/// counterpart in Throne (terminal, request capture, remote sessions, reports, endpoints) and is
/// removed from the navigation instead of being left as an empty page.
///
/// An empty manifest means an unmodified host that knows nothing about capabilities, so nothing is
/// pruned in that case.
class BoxCapabilities {
  const BoxCapabilities(this.values);

  final Json values;

  static const empty = BoxCapabilities(<String, dynamic>{});

  factory BoxCapabilities.parse(Object? value) => BoxCapabilities(json(value));

  bool get known => values.isNotEmpty;

  bool of(String capability) => !known || values[capability] == true;
}

import 'dart:math' as math;

import '../widgets.dart';
import '../components/data_subscription.dart';

/// The batch test panel over the bridge's `groups.testSession`: one scope (a group, a selection or
/// the running connection), live progress with the latency histogram, the ranked best results, the
/// speed test's live curve, and the finished-session actions (sort, remove unavailable, connect).
class TestPanelPage extends StatefulWidget {
  const TestPanelPage({super.key});
  @override State<TestPanelPage> createState() => _TestPanelPageState();
}

class _TestPanelPageState extends State<TestPanelPage> {
  int _kind = 0;
  int? _groupId;
  bool _starting = false;
  // -1 follows the Testing settings' speed test mode until the picker changes it.
  int _speedMode = -1;

  List<Json> get _scopes => context.box.profiles;

  int get _effectiveSpeedMode {
    if (_speedMode >= 0) return _speedMode;
    final stored = context.box.settings['speedTestMode'];
    return stored is num ? stored.toInt() : 0;
  }

  int get _scopeId {
    if (_groupId != null) return _groupId!;
    final selected = context.box.selectedProfileId;
    if (selected > 0) return selected;
    // Nothing is selected yet, and Start would send an empty scope. Fall back to the first
    // configuration so the panel tests something; the Scope row names it.
    final profiles = context.box.profiles;
    return profiles.isEmpty ? 0 : ((profiles.first['id'] as num?)?.toInt() ?? 0);
  }

  Future<void> _start() async {
    if (_starting) return;
    setState(() => _starting = true);
    final scopeId = _scopeId;
    final kind = _kind;
    try {
      await context.box.act('groups.testSession.start', {
        'kind': kind, 'groupId': scopeId,
        'speedMode': kind == 2 ? _effectiveSpeedMode : -1,
      });
    } finally {
      if (mounted) setState(() => _starting = false);
    }
  }

  List<int> _failedIds(Json session) => [
    for (final row in rows(session['rows']))
      if (row['failed'] == true && !(session['removed'] as List? ?? []).contains(row['id']))
        (row['id'] as num).toInt(),
  ];

  Future<void> _removeUnavailable(Json session) async {
    final ids = _failedIds(session);
    if (ids.isEmpty) return;
    final confirmed = await confirm(context, 'Remove the unavailable profiles?', action: 'Delete');
    if (!confirmed || !mounted) return;
    final answer = json(await context.box.call('groups.testSession.remove', {'ids': ids}));
    if (mounted) {
      final removed = (answer['removed'] as num? ?? 0).toInt();
      showAppBanner(context, context.tr('Removed N profiles').replaceFirst('N', '$removed'));
    }
  }

  Widget _menuRow(String title, String label, VoidCallback onTap) => ListItem(
    title: Text(title), trailing: Text(label), onTap: onTap);

  Widget _histogram(Json session) {
    final histogram = json(session['histogram']);
    final edges = (histogram['edges'] as List? ?? const []);
    final bins = (histogram['bins'] as List? ?? const []).map((e) => (e as num).toInt()).toList();
    final peak = bins.fold<int>(1, math.max);
    Color band(int index) => index < 5
        ? context.colorScheme.primary
        : index < 10 ? context.colorScheme.tertiary : context.colorScheme.error;
    return Column(crossAxisAlignment: CrossAxisAlignment.stretch, spacing: 6, children: [
      SizedBox(height: 64, child: Row(crossAxisAlignment: CrossAxisAlignment.end, spacing: 2, children: [
        for (final (index, count) in bins.indexed)
          Expanded(child: Tooltip(
            message: index < edges.length
                ? '<= ${edges[index]} ms'
                : '> ${edges.isEmpty ? 0 : edges.last} ms',
            child: FractionallySizedBox(
              heightFactor: count / peak,
              child: DecoratedBox(
                decoration: BoxDecoration(color: band(index), borderRadius: BorderRadius.circular(3)),
                child: const SizedBox.shrink()),
            ),
          )),
      ])),
      Row(spacing: 12, children: [
        Text('${context.tr('Failed')}: ${(histogram['failed'] as num? ?? 0).toInt()}', style: context.listCaptionStyle),
        Text('${context.tr('Connect only')}: ${(histogram['connectOnly'] as num? ?? 0).toInt()}', style: context.listCaptionStyle),
      ]),
    ]);
  }

  Widget _speedLive(Json session) {
    final speed = json(session['speed']);
    final samples = (speed['samples'] as List? ?? const []).map((sample) {
      final list = sample as List;
      return [((list[0] as num).toDouble()), ((list[1] as num).toDouble()), list[2] == true];
    }).toList();
    final peak = samples.fold<double>(1.0, (peak, sample) => math.max(peak, (sample[1] as num).toDouble()));
    String bps(value) => value > 1e6 ? '${(value / 1e6).toStringAsFixed(1)} Mbps' : '${(value / 1e3).toStringAsFixed(0)} kbps';
    return generateSection(title: context.tr('Testing now'), items: [
      DecorationListItem(contentPadding: const EdgeInsets.symmetric(horizontal: 16, vertical: 10), title: Column(
        crossAxisAlignment: CrossAxisAlignment.stretch, spacing: 8,
        children: [
          Row(spacing: 8, children: [
            Flexible(child: Text(speed['name']?.toString() ?? '', maxLines: 1, overflow: TextOverflow.ellipsis,
              style: AppTextStyle.title3.of(context, emphasized: true))),
            Text(speed['stage']?.toString() ?? '', style: context.listCaptionStyle),
          ]),
          Wrap(spacing: 12, children: [
            Text('${context.tr('Download')}: ${bps((speed['dlBps'] as num? ?? 0).toDouble())}'),
            Text('${context.tr('Upload')}: ${bps((speed['ulBps'] as num? ?? 0).toDouble())}'),
            if ((speed['pingMs'] as num? ?? 0) > 0) Text('${speed['pingMs']} ms'),
          ]),
          if (speed['server']?.toString().isNotEmpty == true)
            Text('${speed['server']} (${speed['serverCountry']})', style: context.listCaptionStyle),
          SizedBox(height: 72, child: LineChart(
            key: ValueKey(speed['profileId']),
            values: [for (final sample in samples) (sample[1] as double) / peak],
            revision: samples.length, capacity: 600, minScale: 0.1, baselineRatio: 1,
            color: context.colorScheme.primary,
          )),
        ],
      )),
    ]);
  }

  @override Widget build(BuildContext context) {
    final box = context.watchBox(BoxAspect.testSession);
    final session = box.testSession;
    final active = (session['kind'] as num? ?? -1).toInt() >= 0;
    final running = session['running'] == true;
    final finished = active && !running;
    final groupId = (session['groupId'] as num? ?? 0).toInt();
    final ranking = rows(session['ranking']);
    final scopes = _scopes;
    return BoxDataSubscription(data: BoxData.testSession, child: Scaffold(
      title: context.tr('Test panel'),
      confirmationAction: running
          ? IconButtonData(glyph: AppGlyphs.playPause(1), tooltip: context.tr('Stop'), onPressed: () => box.act('groups.testSession.stop'))
          : IconButtonData(glyph: AppGlyphs.playPause(0), tooltip: context.tr('Start'), isLoading: _starting, onPressed: _starting ? null : _start),
      menuItems: [
        if (finished && groupId > 0) PopupMenuItem(glyph: AppGlyphs.sort, label: context.tr('Sort'),
          onPressed: () => box.act('groups.testSession.sort', {'groupId': groupId})),
        if (finished && _failedIds(session).isNotEmpty) PopupMenuItem(glyph: AppGlyphs.delete,
          label: context.tr('Remove unavailable'), onPressed: () => _removeUnavailable(session)),
        if (finished) PopupMenuItem(glyph: AppGlyphs.close, label: context.tr('Dismiss'),
          onPressed: () => box.act('groups.testSession.dismiss')),
      ],
      body: ListView(shrinkWrap: true,
        padding: EdgeInsets.fromLTRB(16, context.sectionsTopPadding, 16, 20 + context.bottomInset),
        children: [
          generateSection(items: [
            _menuRow(context.tr('Test kind'), switch (_kind) {
              1 => context.tr('IP check'), 2 => context.tr('Speed test'),
              _ => context.tr('Latency'),
            }, () async {
              final picked = await showDialog<int>(context: context, builder: (context) => SimpleDialog(
                title: Text(context.tr('Test kind')),
                children: [
                  for (final (value, label) in [(0, 'Latency'), (1, 'IP check'), (2, 'Speed test')])
                    SimpleDialogOption(onPressed: () => Navigator.pop(context, value), child: Text(context.tr(label))),
                ],
              ));
              if (picked != null) setState(() => _kind = picked);
            }),
            // A speed session measures one direction or both; the mode starts from the Testing
            // setting so the panel and the stored default agree until it is changed here.
            if (_kind == 2)
              _menuRow(context.tr('Speed test mode'), context.tr(speedTestModeName(_effectiveSpeedMode)), () async {
                final picked = await showDialog<int>(context: context, builder: (context) => SimpleDialog(
                  title: Text(context.tr('Speed test mode')),
                  children: [
                    for (final value in speedTestModes) SimpleDialogOption(
                      onPressed: () => Navigator.pop(context, value),
                      child: Text(context.tr(speedTestModeName(value)))),
                  ],
                ));
                if (picked != null) setState(() => _speedMode = picked);
              }),
            _menuRow(context.tr('Scope'), scopes.where((profile) => profile['id'] == _scopeId)
                .firstOrNull?['name']?.toString() ?? context.tr('None'), () async {
              final picked = await showDialog<int>(context: context, builder: (context) => SimpleDialog(
                title: Text(context.tr('Scope')),
                children: [
                  for (final profile in scopes) SimpleDialogOption(
                    onPressed: () => Navigator.pop(context, (profile['id'] as num).toInt()),
                    child: Text(profile['name'].toString())),
                ],
              ));
              if (picked != null) setState(() => _groupId = picked);
            }),
          ]),
          if (!active) const SizedBox.shrink() else ...[
            generateSection(title: context.tr('Progress'), items: [
              DecorationListItem(contentPadding: const EdgeInsets.symmetric(horizontal: 16, vertical: 10), title: Column(
                crossAxisAlignment: CrossAxisAlignment.stretch, spacing: 8,
                children: [
                  Row(spacing: 8, children: [
                    Flexible(child: Text(session['scopeLabel']?.toString() ?? '', maxLines: 1, overflow: TextOverflow.ellipsis,
                      style: AppTextStyle.title3.of(context, emphasized: true))),
                    if (session['preparing'] == true) Text(context.tr('Preparing…'), style: context.listCaptionStyle),
                    if (session['stopping'] == true) Text(context.tr('Stopping…'), style: context.listCaptionStyle),
                  ]),
                  Row(spacing: 12, children: [
                    Text('${session['done'] ?? 0}/${session['total'] ?? 0}', style: AppTextStyle.body.of(context, emphasized: true)),
                    Text('${context.tr('Ok')}: ${session['ok'] ?? 0}', style: context.listCaptionStyle),
                    Text('${context.tr('Failed')}: ${session['failed'] ?? 0}', style: context.listCaptionStyle),
                    if ((session['aborted'] as num? ?? 0) > 0)
                      Text('${context.tr('Aborted')}: ${session['aborted']}', style: context.listCaptionStyle),
                  ]),
                  LinearProgressIndicator(
                    value: (session['total'] as num? ?? 0).toInt() > 0
                        ? ((session['done'] as num? ?? 0).toInt() / (session['total'] as num).toInt()).clamp(0.0, 1.0)
                        : null,
                  ),
                  if (!running && (session['failure']?.toString() ?? 'NONE') != 'NONE')
                    Text(switch (session['failure'].toString()) {
                      'EMPTY' => context.tr('Nothing to test'),
                      'BUSY' => context.tr('A test is already running'),
                      'UNREACHABLE' => context.tr('The core service is unreachable'),
                      'CORE_DIED' => context.tr('The core service died'),
                      _ => context.tr('The test did not finish'),
                    }, style: context.listCaptionStyle.copyWith(color: context.colorScheme.error)),
                ],
              )),
            ]),
            if (session['countryMode'] != true && (session['histogram'] as Map? ?? {}).isNotEmpty && (session['kind'] as num).toInt() == 0)
              generateSection(title: context.tr('Latency histogram'), items: [
                DecorationListItem(contentPadding: const EdgeInsets.symmetric(horizontal: 16, vertical: 12), title: _histogram(session)),
              ]),
            if (session['speed'] != null && session['running'] == true) _speedLive(session),
            if (ranking.isNotEmpty) generateSection(title: context.tr('Fastest servers'), items: [
              for (final (index, ranked) in ranking.indexed)
                ListItem(
                  title: Text(ranked['name']?.toString().isNotEmpty == true
                      ? ranked['name'].toString() : '#${ranked['profileId']}',
                    maxLines: 1, overflow: TextOverflow.ellipsis),
                  subtitle: Text([
                    if ((ranked['latency'] as num? ?? 0) > 0) '${ranked['latency']} ms',
                    if (ranked['dlSpeed']?.toString().isNotEmpty == true) ranked['dlSpeed'].toString(),
                    if (ranked['ulSpeed']?.toString().isNotEmpty == true) '↑ ${ranked['ulSpeed']}',
                    if (ranked['country']?.toString().isNotEmpty == true) ranked['country'].toString(),
                  ].join(' · ')),
                  trailing: index == 0 && !running
                      ? TextButton(onPressed: () => box.act('profiles.select', {'id': (ranked['profileId'] as num).toInt()}),
                          child: Text(context.tr('Connect')))
                      : null,
                  onTap: running ? null : () => box.act('profiles.select', {'id': (ranked['profileId'] as num).toInt()}),
                ),
            ], footer: context.tr('Tap a result to connect through it.')),
          ],
        ],
      ),
    ));
  }
}

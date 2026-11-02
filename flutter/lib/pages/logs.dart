import 'package:super_sliver_list/super_sliver_list.dart';
import 'package:flutter/services.dart';
import '../widgets.dart';
import '../components/record.dart';
import '../components/data_subscription.dart';

class LogsPage extends StatefulWidget {
  const LogsPage({super.key});
  @override State<LogsPage> createState() => _LogsPageState();
}

class _LogsPageState extends State<LogsPage> {
  String _query = '', _level = 'Default';
  bool _logcat = false, _exporting = false, _showNoise = false;
  static const _priorities = {
    'panic': 0, 'fatal': 1, 'error': 2, 'warn': 3, 'warning': 3,
    'info': 4, 'debug': 5, 'trace': 6,
  };
  List<Json>? _paused;
  late final _scroll = ScrollController(initialScrollOffset: double.maxFinite);
  late final _physics = FollowEndScrollPhysics(isFollowing: () => _paused == null);
  @override void dispose() { _scroll.dispose(); super.dispose(); }

  void _pause() {
    if (_paused == null) setState(() => _paused = List.of(_logcat ? context.box.logcat : context.box.logs));
  }

  void _resume() {
    if (_paused != null) setState(() => _paused = null);
  }

  bool _matchesLevel(Json item, String? defaultLevel) {
    final level = item['level'].toString().toLowerCase();
    if (_level == 'Default') {
      // Logcat has no configured level to follow, so Default means everything there.
      final threshold = _logcat ? null : _priorities[defaultLevel?.toLowerCase()];
      // Before the core reports its level, keep received records available.
      if (threshold == null) return true;
      final priority = _priorities[level];
      return priority != null && priority <= threshold;
    }
    return _level == 'All' || level == _level.toLowerCase();
  }

  /// The sing-box lazy handshake reports an ERROR when the local app resets a
  /// connection before the remote dial finished; the connection was dead anyway,
  /// so the log line is noise a VPN always produces (analysis of 2026-11-03).
  /// Only the core log carries it — Logcat noise is the system's own.
  bool _isNoise(Json item) => !_logcat &&
    item['level'].toString().toLowerCase() == 'error' &&
    item['message'].toString().contains('report handshake success') &&
    item['message'].toString().contains('connection reset by peer');

  Future<void> _export(List<Json> logs) async {
    if (_exporting || logs.isEmpty) return;
    final box = context.box;
    setState(() => _exporting = true);
    try {
      final saved = await box.call('document.save', {
        // Log lines carry URLs and keys; the bridge redacts like the native export.
        'name': _logcat ? 'Logcat.log' : 'B-box.log', 'redact': true,
        'content': logs.map((line) => '${recordTime(line['receivedAt'])} [${line['level']}] ${line['message']}').join('\n'),
      });
      // A cancelled system picker is neither an export nor an error.
      if (mounted && saved != null) box.messages.add(context.tr(saved == true ? 'Exported' : 'Export failed'));
    } catch (error) {
      box.report(error);
    } finally {
      if (mounted) setState(() => _exporting = false);
    }
  }

  /// Dumps every kept crash record into one redacted file; the records survive
  /// core.log's rotation, so the user can hand them over after a restart.
  Future<void> _exportCrashes() async {
    if (_exporting) return;
    final box = context.box;
    setState(() => _exporting = true);
    try {
      final files = rows(await box.call('crashes.list', {}));
      if (!mounted) return;
      if (files.isEmpty) {
        box.messages.add(context.tr('No crash logs'));
        return;
      }
      final parts = <String>[];
      for (final file in files) {
        // One unreadable record (pruned mid-export, say) must not sink the whole file.
        try {
          final read = json(await box.call('crashes.read', {'name': file['name']}));
          parts.add('===== ${file['name']} =====\n${read['content']}');
        } catch (error) {
          parts.add('===== ${file['name']} =====\n(unreadable: ${BoxController.describe(error)})');
        }
      }
      final stamp = DateTime.fromMillisecondsSinceEpoch(files.first['time'] as int);
      final date = '${stamp.year}-${stamp.month.toString().padLeft(2, '0')}-${stamp.day.toString().padLeft(2, '0')}'
          '-${stamp.hour.toString().padLeft(2, '0')}-${stamp.minute.toString().padLeft(2, '0')}-${stamp.second.toString().padLeft(2, '0')}';
      final saved = await box.call('document.save', {
        'name': 'crashes-$date.log', 'redact': true,
        'content': parts.join('\n\n'),
      });
      if (mounted && saved != null) box.messages.add(context.tr(saved == true ? 'Exported' : 'Export failed'));
    } catch (error) {
      box.report(error);
    } finally {
      if (mounted) setState(() => _exporting = false);
    }
  }

  @override Widget build(BuildContext context) {
    final box = context.watchBox(BoxAspect.logs);
    if (_logcat) context.watchBox(BoxAspect.logcat);
    final words = _query.toLowerCase().trim().split(RegExp(r'\s+'));
    final matches = (_paused ?? (_logcat ? box.logcat : box.logs)).where((item) =>
      _matchesLevel(item, box.defaultLogLevel) &&
      words.every(([item['level'], item['message']].join(' ').toLowerCase()).contains),
    );
    final noiseCount = matches.where(_isNoise).length;
    // The fold is a display convenience: exports carry every matching record, and the
    // export button stays alive while only folded noise is on screen.
    final exportable = matches.toList();
    final logs = (_showNoise ? matches : matches.where((item) => !_isNoise(item))).toList();
    return BoxDataSubscription(data: _logcat ? BoxData.logcat : BoxData.logs, child: CommonScaffold(
      title: context.tr('Logs'),
      onKeywordsUpdate: (words) => setState(() => _query = words.join(' ')),
      searchState: AppBarSearchState(onSearch: (query) => setState(() => _query = query)),
      iconActions: [
        IconButtonData(glyph: AppGlyphs.export, tooltip: context.tr('Export'),
          onPressed: exportable.isEmpty || _exporting ? null : () => _export(exportable)),
        IconButtonData(glyph: AppGlyphs.error, tooltip: context.tr('Export crash logs'),
          onPressed: _exporting ? null : _exportCrashes),
      ],
      menuItems: [
        CommonPopupMenuItem(glyph: _paused == null ? AppGlyphs.playPause(1) : AppGlyphs.playPause(0),
          label: context.tr(_paused == null ? 'Pause' : 'Resume'), onPressed: _paused == null ? _pause : _resume),
        CommonPopupMenuItem(glyph: AppGlyphs.delete, label: context.tr('Clear'), onPressed: () async {
          if (await box.act(_logcat ? 'logs.logcat.clear' : 'logs.clear') && mounted) setState(() => _paused = null);
        }),
        for (final level in ['Default', 'All', 'Trace', 'Debug', 'Info', 'Warn', 'Error', 'Fatal', 'Panic'])
          CommonPopupMenuItem(label: level == 'Default'
              ? '${context.tr('Follow configuration')}${_logcat || box.defaultLogLevel == null ? '' : ' (${context.tr(box.defaultLogLevel!)})'}'
              : context.tr(level), checked: _level == level,
            section: level == 'Default' ? CommonPopupMenuSection(title: context.tr('Log level')) : null,
            onPressed: () => setState(() => _level = level)),
      ],
      // The body starts at the top of the window with the app bar floating over it, so the switch needs the
      // same inset every other page's content takes — without it the segments drew across the status bar.
      body: Padding(padding: EdgeInsets.only(top: context.contentTopPadding), child: Column(children: [
        Padding(
          padding: EdgeInsets.fromLTRB(16, 8, 16, 4),
          child: SegmentedControl<bool>(
            segments: [(false, context.tr('Core log')), (true, 'Logcat')],
            value: _logcat,
            onChanged: (value) => setState(() { _logcat = value; _paused = null; }),
          ),
        ),
        if (noiseCount > 0) Padding(
          padding: const EdgeInsets.fromLTRB(16, 0, 16, 4),
          child: Align(alignment: AlignmentDirectional.centerStart, child: _NoiseChip(
            label: _showNoise
              ? context.tr('Hide connection-reset noise')
              : context.tr('Folded {count} connection resets').replaceAll('{count}', '$noiseCount'),
            active: _showNoise,
            onPressed: () => setState(() => _showNoise = !_showNoise),
          )),
        ),
        Expanded(child: NullStatusSwitcher(
        isEmpty: logs.isEmpty, isSearching: _query.trim().isNotEmpty || !['Default', 'All'].contains(_level),
        nullStatus: NullStatus(label: context.tr('No logs'), illustration: NullStatusIllustration.logs),
        child: logs.isEmpty ? const SizedBox.shrink() : Align(
          alignment: Alignment.topCenter,
          child: FloatingScrollbar(
            controller: _scroll,
            hintBuilder: (fraction) => recordTime(logs[(fraction * (logs.length - 1)).round()]['receivedAt']),
            child: ScrollToEndBox(
              controller: _scroll, enable: _paused == null,
              onCancelToEnd: _pause, onResumeToEnd: _resume,
              child: SuperListView.separated(
                controller: _scroll, physics: _physics, reverse: true, shrinkWrap: true,
                padding: EdgeInsets.only(top: 8, bottom: 16 + context.bottomInset),
                extentEstimation: recordListExtentEstimation,
                itemCount: logs.length,
                separatorBuilder: (_, _) => const SizedBox(height: recordListGap),
                itemBuilder: (context, index) {
                  final log = logs[index];
                  final level = log['level'].toString().toLowerCase();
                  final tone = ['error', 'fatal', 'panic'].contains(level) ? RecordTone.error
                      : ['warn', 'warning'].contains(level) ? RecordTone.warning : RecordTone.neutral;
                  return RecordListItem(
                    key: ValueKey(log['id']),
                    // Tap copies the line: the wrapping Text body has no selection handles,
                    // and a crash line is exactly what gets pasted into a bug report.
                    onTap: () async {
                      await Clipboard.setData(ClipboardData(text: log['message'].toString()));
                      if (!context.mounted) return;
                      box.messages.add(context.tr('Copied'));
                    },
                    header: RecordHeader(children: [
                      if ((log['receivedAt'] as num? ?? 0) > 0) RecordTimestamp(recordTime(log['receivedAt'])),
                      RecordLabel(label: level, tone: tone, onPressed: () => context.commonScaffoldState?.addKeyword(level)),
                    ]),
                    body: Text(log['message'].toString(), style: RecordTextStyles.of(context).primary),
                  );
                },
              ),
            ),
          ),
        ),
        ))],
      )),
    ));
  }
}

/// The fold toggle for the core log's known-benign connection-reset errors: a quiet
/// pill that states the count while folded and asks nothing while expanded.
class _NoiseChip extends StatelessWidget {
  const _NoiseChip({required this.label, required this.active, required this.onPressed});
  final String label;
  final bool active;
  final VoidCallback onPressed;

  @override
  Widget build(BuildContext context) {
    final scheme = context.colorScheme;
    return Material(
      color: active ? scheme.secondaryContainer : SurfaceLevel.card.colorOf(scheme),
      shape: AppShape.all(20),
      clipBehavior: Clip.antiAlias,
      child: InkWell(
        onTap: onPressed,
        child: Padding(
          padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 6),
          child: Row(mainAxisSize: MainAxisSize.min, children: [
            Flexible(child: Text(label, maxLines: 1, overflow: TextOverflow.ellipsis,
              style: AppTextStyle.caption1.of(context))),
          ]),
        ),
      ),
    );
  }
}

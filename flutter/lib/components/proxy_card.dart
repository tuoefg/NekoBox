import 'package:flutter/services.dart';
import '../widgets.dart';
import '../pages/protocol_form.dart';
import '../pages/node_json_page.dart';
import '../pages/test_panel.dart';

// Metrics and card presentation from FlClash v0.9.2 proxies/common.dart and card.dart.
class ProxyCardMetrics {
  ProxyCardMetrics.of(BuildContext context, Json preferences)
      : compact = preferences['proxyCardSize'] == 'Compact' ||
            preferences['proxyCardSize'] == null && preferences['proxyLayout'] == 'Compact',
        loose = preferences['proxyCardSize'] == 'Loose',
        measure = MeasureScope.of(context);

  final bool compact, loose;
  final Measure measure;
  double get nameHeight => measure.textHeight(AppTextStyle.subheadline) * (compact ? 1 : 2);
  double get detailHeight => measure.textHeight(AppTextStyle.footnote);
  double get delayHeight => measure.textHeight(AppTextStyle.caption2);
  double get extent => 28 + nameHeight + detailHeight + (loose ? delayHeight + 6 : 0);
  double get rowExtent => extent + 8;
}

int proxyColumns(double width, Json preferences) {
  final standard = (width / 250).ceil().clamp(2, 8);
  final density = preferences['proxyDensity'] ?? 'Standard';
  return (standard + (density == 'Tight' ? 1 : density == 'Loose' ? -1 : 0)).clamp(1, 8);
}

class ProxyNodeCard extends StatelessWidget {
  const ProxyNodeCard({super.key, required this.group, required this.node,
    required this.testing, required this.onSelect, required this.onTest});
  final Json group, node;
  final bool testing;
  final VoidCallback onSelect, onTest;

  Future<String> _link(BuildContext context, {bool json = false}) async =>
      (await context.box.call('profiles.nodeLink', {'group': group['tag'], 'tag': node['tag'], 'json': json})).toString();

  /// A speed session over this node alone (download, upload or both); the panel that opens shows
  /// its live curve and the finished ranking, since a card only carries latency.
  Future<void> _speedTest(BuildContext context, int speedMode) async {
    final box = context.box;
    try {
      final started = await box.call('groups.testSession.start', {
        'kind': 2, 'profileIds': [node['id']], 'speedMode': speedMode,
      });
      if (!context.mounted) return;
      if (started == false) {
        ScaffoldMessenger.of(context).showSnackBar(
          SnackBar(content: Text(context.tr('A test is already running'))));
        return;
      }
      await openPage(context, const TestPanelPage());
    } catch (error) { box.report(error); }
  }

  @override Widget build(BuildContext context) {
    final metrics = ProxyCardMetrics.of(context, context.box.preferences);
    final selected = group['selected'] == node['tag'];
    final delay = (node['delay'] as num?)?.toInt() ?? 0;
    final tested = (node['testedAt'] as num? ?? 0) > 0;
    final delayText = delay > 0 ? '$delay ms' : tested ? context.tr('Unreachable') : '—';
    final delayColor = !tested ? context.colorScheme.onSurfaceVariant : delay <= 0
        ? context.colorScheme.error : delay < 300 ? context.colorScheme.success : context.colorScheme.warning;
    return ContextMenuRegion(
      key: ValueKey((group['tag'], node['tag'])),
      menuItems: [
        if (group['selectable'] == true)
          CommonPopupMenuItem(glyph: AppGlyphs.checkCircle, label: context.tr('Select'), checked: selected,
            onPressed: onSelect),
        CommonPopupMenuItem(glyph: AppGlyphs.bolt, label: context.tr('Test latency'),
          onPressed: testing ? null : onTest),
        // The download/upload speed test lives here, as a submenu of its modes; a session tests
        // one node at a time and the panel reports the result.
        CommonPopupMenuItem(glyph: AppGlyphs.speed, label: context.tr('Speed test'), subItems: [
          CommonPopupMenuItem(glyph: AppGlyphs.dataUsage, label: context.tr('Download and upload'),
            onPressed: () => _speedTest(context, 0)),
          CommonPopupMenuItem(glyph: AppGlyphs.arrowDown, label: context.tr('Download'),
            onPressed: () => _speedTest(context, 1)),
          CommonPopupMenuItem(glyph: AppGlyphs.arrowUp, label: context.tr('Upload'),
            onPressed: () => _speedTest(context, 2)),
        ]),
        CommonPopupMenuItem(glyph: AppGlyphs.copy, label: context.tr('Copy link'), onPressed: () async {
          final box = context.box;
          try {
            final link = await _link(context);
            await Clipboard.setData(ClipboardData(text: link));
            if (context.mounted) ScaffoldMessenger.of(context).showSnackBar(SnackBar(content: Text(context.tr('Copied'))));
          } catch (error) { box.report(error); }
        }),
        CommonPopupMenuItem(glyph: AppGlyphs.qrCode, label: context.tr('QR code'), onPressed: () async {
          final box = context.box;
          try {
            final link = await _link(context);
            if (context.mounted) openForm(context, BoxPage(title: 'QR code', children: [
              Center(child: QrCodeView(data: link)),
            ]));
          } catch (error) { box.report(error); }
        }),
        CommonPopupMenuItem(glyph: AppGlyphs.upload, label: context.tr('Share'), onPressed: () async {
          final box = context.box;
          try { await box.call('share.text', {'content': await _link(context)}); }
          catch (error) { box.report(error); }
        }),
        CommonPopupMenuItem(glyph: AppGlyphs.edit, label: context.tr('Edit'), onPressed: () async {
          if (node['isSelector'] == true) {
            await openPage(context, AutoSelectorPage(id: (node['id'] as num?)?.toInt() ?? 0));
            return;
          }
          // Throne's node Edit opens the typed editor; the JSON document is the secondary entry.
          await openForm(context, ProtocolFormPage(id: (node['id'] as num?)?.toInt() ?? 0));
        }),
        CommonPopupMenuItem(glyph: AppGlyphs.sliders, label: context.tr('Node tuning'), onPressed: () async {
          await openPage(context, NodeTuningPage(id: (node['id'] as num?)?.toInt() ?? 0));
        }),
        CommonPopupMenuItem(glyph: AppGlyphs.code, label: context.tr('Edit as JSON'),
          onPressed: () => openForm(context, NodeJsonPage(id: (node['id'] as num?)?.toInt() ?? 0))),
        CommonPopupMenuItem(glyph: AppGlyphs.delete, label: context.tr('Delete'), danger: true, onPressed: () async {
          final box = context.box;
          try {
            final confirmed = await showDialog<bool>(context: context, builder: (context) => AlertDialog(
              title: Text(context.tr('Delete node?')),
              content: Text(node['tag'].toString()),
              actions: [
                TextButton(onPressed: () => Navigator.pop(context, false), child: Text(context.tr('Cancel'))),
                TextButton(onPressed: () => Navigator.pop(context, true), child: Text(context.tr('Delete'))),
              ],
            ));
            if (confirmed == true) await box.call('profiles.nodeDelete', {'id': node['id']});
          } catch (error) { box.report(error); }
        }),
        CommonPopupMenuItem(glyph: AppGlyphs.info, label: context.tr('Details'), onPressed: () => openForm(context, BoxPage(title: 'Details', children: [
          DetailRows({'Name': node['tag'], 'Protocol': node['type'], 'Latency': delayText, 'Proxy groups': group['tag']}),
        ]))),
      ],
      child: CommonCard(
        radius: AppCorner.lg, enterActionsOnRight: true, isSelected: selected,
        // Both actions outlive the service: a selection is stored for the next start and a test runs
        // over its own instance of the profile, so a stopped VPN must not turn the card inert.
        onPressed: group['selectable'] == true ? onSelect : null,
        child: Padding(padding: const EdgeInsets.symmetric(horizontal: 12), child: Column(
          mainAxisAlignment: MainAxisAlignment.center, crossAxisAlignment: CrossAxisAlignment.start, children: [
            Row(crossAxisAlignment: CrossAxisAlignment.start, children: [
              Expanded(child: SizedBox(height: metrics.nameHeight, child: EmojiText(node['tag'].toString(),
                maxLines: metrics.compact ? 1 : 2, overflow: TextOverflow.ellipsis, style: AppTextStyle.subheadline.of(context)))),
              if (selected) const Padding(padding: EdgeInsetsDirectional.only(start: 4), child: SelectionBadge()),
            ]),
            const SizedBox(height: 8),
            if (metrics.loose) SizedBox(height: metrics.detailHeight, child: Text(node['type'].toString(), maxLines: 1,
              overflow: TextOverflow.ellipsis, style: AppTextStyle.footnote.of(context).copyWith(color: context.colorScheme.onSurface.opacity80))),
            if (metrics.loose) const SizedBox(height: 6),
            SizedBox(height: metrics.loose ? metrics.delayHeight : metrics.detailHeight, child: Row(children: [
              if (!metrics.loose) Expanded(child: TooltipText(text: Text(node['type'].toString(), maxLines: 1,
                overflow: TextOverflow.ellipsis, style: AppTextStyle.footnote.of(context).copyWith(color: context.colorScheme.onSurface.opacity80)))),
              if (!metrics.loose) const SizedBox(width: 4),
              Flexible(child: Semantics(button: true, label: context.tr('Test latency'), child: GestureDetector(
                behavior: HitTestBehavior.opaque, onTap: testing ? null : onTest,
                child: FadeBox(alignment: metrics.loose ? Alignment.centerLeft : Alignment.centerRight, child: testing
                  ? CommonCircleLoading(size: metrics.delayHeight)
                  : !tested ? GlyphIcon(AppGlyphs.bolt, size: metrics.delayHeight)
                  : Text(delayText, maxLines: 1, overflow: TextOverflow.ellipsis,
                    style: AppTextStyle.caption2.of(context, emphasized: true).copyWith(color: delayColor))),
              ))),
            ])),
          ],
        )),
      ),
    );
  }
}

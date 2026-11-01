import '../widgets.dart';
import 'connections.dart';
import 'requests.dart';
import 'logs.dart';
import 'settings.dart';
import 'diagnostics.dart';
import 'endpoints.dart';
import 'reports.dart';
import 'terminal.dart';

/// Throne answers nothing behind these entries, so they only exist when the host advertises that it
/// can: see [BoxCapabilities] and `Capabilities.map()` on the Android side.
///
/// The monitors section (requests, connections, logs, terminal, remote control), diagnostics,
/// reports and endpoints are all B-box or libbox-private features with no Throne counterpart.
class ToolsPage extends StatelessWidget {
  const ToolsPage({super.key});
  @override Widget build(BuildContext context) {
    final box = context.box, caps = box.capabilities;
    return BoxPage(title: 'Tools', children: [
      _section(<ListItem>[
        if (caps.of('capture'))
          ListItem.open(leading: const GlyphIcon(AppGlyphs.filter), title: Text(context.tr('Requests')), widget: const RequestsPage()),
        if (caps.of('connections'))
          ListItem.open(leading: const GlyphIcon(AppGlyphs.connections), title: Text(context.tr('Connections')), widget: const ConnectionsPage()),
        if (caps.of('logs'))
          ListItem.open(leading: const GlyphIcon(AppGlyphs.logs), title: Text(context.tr('Logs')), widget: const LogsPage()),
        if (caps.of('terminal'))
          ListItem.open(leading: const GlyphIcon(AppGlyphs.code), title: Text(context.tr('Terminal')), widget: const TerminalPage()),
        if (caps.of('remote'))
          ListItem.open(leading: const GlyphIcon(AppGlyphs.proxies), title: Text(context.tr('Remote control')), widget: const RemotePage()),
      ]),
      _section(<ListItem>[
        ListItem.open(leading: const GlyphIcon(AppGlyphs.paintbrush), title: Text(context.tr('Appearance')), widget: const AppearancePage()),
        ListItem.open(leading: const GlyphIcon(AppGlyphs.settings), title: Text(context.tr('General')), widget: const GeneralSettingsPage()),
        ListItem.open(leading: const GlyphIcon(AppGlyphs.appsList), title: Text(context.tr('Access control')), widget: const AccessPage()),
        ListItem.open(leading: const GlyphIcon(AppGlyphs.cloudSync), title: Text(context.tr('Backup and restore')), widget: const BackupPage()),
      ], title: context.tr('Settings')),
      _section(<ListItem>[
        if (caps.of('quality'))
          ListItem.open(leading: const GlyphIcon(AppGlyphs.speed), title: Text(context.tr('Network quality')), widget: const DiagnosticsPage(quality: true)),
        if (caps.of('stun'))
          ListItem.open(leading: const GlyphIcon(AppGlyphs.proxies), title: Text(context.tr('STUN test')), widget: const DiagnosticsPage(quality: false)),
      ], title: context.tr('Network diagnostics')),
      _section(<ListItem>[
        if (caps.of('reports'))
          for (final kind in ['crash', 'oom', 'power'])
            ListItem.open(leading: const GlyphIcon(AppGlyphs.profiles), title: Text(context.tr(reportTitle(kind))), widget: ReportsPage(kind: kind)),
      ], title: context.tr('Reports')),
      _section(<ListItem>[
        if (caps.of('endpoints') && box.running)
          for (final kind in ['tailscale', 'openvpn', 'openconnect', 'usbip'])
            if (rows(box.toolState[kind]).isNotEmpty)
              ListItem.open(leading: const GlyphIcon(AppGlyphs.devices), title: Text(endpointName(kind)), widget: EndpointsPage(kind: kind)),
      ], title: context.tr('Endpoints')),
      _section(<ListItem>[
        ListItem.open(leading: const GlyphIcon(AppGlyphs.info), title: Text(context.tr('About')), widget: const AboutPage()),
      ], title: context.tr('Other')),
    ]);
  }
}

/// Sections whose every entry was pruned must not leave an empty card behind.
Widget _section(List<ListItem> items, {String? title}) =>
  items.isEmpty ? const SizedBox.shrink() : generateSection(title: title, items: items);

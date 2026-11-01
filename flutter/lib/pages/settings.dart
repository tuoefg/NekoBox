import '../widgets.dart';

export 'appearance.dart';
export 'access.dart';
export 'about.dart';
export 'backup.dart';

/// A host setting rendered as a switch, as every service setting upstream is.
class NativeToggle extends StatelessWidget {
  const NativeToggle(this.title, this.setting, {super.key, this.restart = true, this.autoRestart = false});
  final String title, setting;
  final bool restart, autoRestart;
  @override Widget build(BuildContext context) {
    final box = context.box;
    return ListItem.toggle(
      title: Text(context.tr(title)), value: box.settings[setting] == true,
      onChanged: box.serviceSettingsChanging || autoRestart && box.transitioning && !box.remote
        ? null : (value) => box.setSetting(setting, value, restart: restart, autoRestart: autoRestart),
    );
  }
}

/// The Throne settings groups: what upstream plus a group B-box never had.
///
/// Inbound, TUN, DNS, routing, core, testing and subscriptions are taken from Throne's own
/// settings tree; they replace nothing because the UI had no equivalent pages.
class GeneralSettingsPage extends StatelessWidget {
  const GeneralSettingsPage({super.key});
  @override Widget build(BuildContext context) {
    final caps = context.box.capabilities;
    return BoxPage(title: 'General', children: [
      generateSection(items: [
        ListItem.open(leading: const GlyphIcon(AppGlyphs.vpn), title: Text(context.tr('Service')), widget: const ServiceSettingsPage()),
        ListItem.open(leading: const GlyphIcon(AppGlyphs.devices), title: Text(context.tr('Inbound')), widget: const InboundPage()),
        ListItem.open(leading: const GlyphIcon(AppGlyphs.split), title: Text(context.tr('TUN')), widget: const TunPage()),
        ListItem.open(leading: const GlyphIcon(AppGlyphs.dns), title: Text(context.tr('DNS')), widget: const DnsPage()),
        ListItem.open(leading: const GlyphIcon(AppGlyphs.rules), title: Text(context.tr('Routing')), widget: const RoutingPage()),
        ListItem.open(leading: const GlyphIcon(AppGlyphs.proxies), title: Text(context.tr('WARP')), widget: const WarpPage()),
        ListItem.open(leading: const GlyphIcon(AppGlyphs.cpu), title: Text(context.tr('Core')), widget: const CorePage()),
        ListItem.open(leading: const GlyphIcon(AppGlyphs.speed), title: Text(context.tr('Testing')), widget: const TestingPage()),
        ListItem.open(leading: const GlyphIcon(AppGlyphs.profiles), title: Text(context.tr('Subscriptions')), widget: const SubscriptionPage()),
        if (caps.of('update'))
          ListItem.open(leading: const GlyphIcon(AppGlyphs.info), title: Text(context.tr('Updates')), widget: const UpdatePage()),
      ]),
    ]);
  }
}

/// Lifecycle plus the handful of Android-side behaviours that Throne reads on its own.
///
/// Removed here, because Throne has no equivalent: auto redirect, dynamic notification, deprecation
/// warnings, the core memory killer and its limit, and power reports.
class ServiceSettingsPage extends StatelessWidget {
  const ServiceSettingsPage({super.key});
  @override Widget build(BuildContext context) => BoxPage(title: 'Service', loading: context.box.serviceSettingsChanging, children: [
    generateSection(items: [
      ListItem<String>.options(
        title: Text(context.tr('Service mode')), options: const ['vpn', 'proxy'],
        value: context.box.settings['serviceMode'] == 'proxy' ? 'proxy' : 'vpn',
        textBuilder: (value) => context.tr(value == 'vpn' ? 'VPN' : 'Proxy only'),
        onChanged: (value) => context.box.setSetting('serviceMode', value, autoRestart: true),
      ),
      // "Allow bypass" lives on the Access screen as the per-app mode; the private-range switch is
      // under TUN, because upstream keeps them in two different stores.
      const NativeToggle('Always show the address', 'alwaysShowAddress', restart: false),
      // Both are read by the host on its next update, so they need no core restart.
      const NativeToggle('Show group name in the notification', 'showGroupInNotification', restart: false),
      const NativeToggle('Show direct speed', 'showDirectSpeed', restart: false),
    ]),
    generateSection(title: context.tr('Connections'), items: [
      const NativeToggle('Reset connections on network change', 'networkChangeResetConnections', restart: false),
      const NativeToggle('Reset connections when waking up', 'wakeResetConnections', restart: false),
    ]),
    generateSection(title: context.tr('Behaviour'), items: [
      const NativeToggle('Connect after reboot', 'rememberEnable', restart: false),
      const NativeToggle('Hide from recent apps', 'hideFromRecentApps', restart: false),
      const NativeToggle('Treat the VPN as metered', 'meteredNetwork', autoRestart: true),
      const NativeToggle('Skip delete confirmation', 'skipDeleteConfirmation', restart: false),
      // A failed rule-set download normally blocks a start; this lets the core come up anyway.
      const NativeToggle('Start without rule sets', 'startWithoutRuleSets', restart: false),
    ]),
    generateSection(items: [
      ListItem(title: Text(context.tr('Battery optimization')),
        subtitle: Text(context.tr(context.box.settings['batteryExempt'] == true ? 'Unrestricted' : 'Allow background operation')),
        onTap: () => context.box.act('battery.open')),
    ]),
  ]);
}

class RemotePage extends StatefulWidget {
  const RemotePage({super.key});
  @override State<RemotePage> createState() => _RemotePageState();
}
class _RemotePageState extends State<RemotePage> {
  Future<List<Json>>? _servers;
  @override void didChangeDependencies() { super.didChangeDependencies(); _servers ??= _load(); }
  Future<List<Json>> _load() async => rows(await context.box.call('remote.list'));
  Future<void> _edit([Json? server]) async {
    await openForm(context, RemoteForm(initial: server ?? {}));
    if (mounted) setState(() => _servers = _load());
  }
  @override Widget build(BuildContext context) => CommonScaffold(
    title: context.tr('Remote control'),
    primaryAction: IconButtonData(glyph: AppGlyphs.add, tooltip: context.tr('Add server'), onPressed: _edit),
    body: FutureBuilder<List<Json>>(future: _servers, builder: (context, snapshot) => ListView(
      padding: EdgeInsets.fromLTRB(16, context.contentTopPadding, 16, 24 + context.bottomInset),
      children: [
        if (context.box.remote) ListItem(title: Text(context.box.dashboard['remote'].toString()), trailing: TextButton(
          onPressed: () => context.box.act('remote.disconnect'), child: Text(context.tr('Disconnect')))),
        if (snapshot.hasError) ErrorState(snapshot.error!),
        if (snapshot.hasData && snapshot.data!.isEmpty) const EmptyState('No remote servers'),
        for (final server in snapshot.data ?? <Json>[]) Padding(padding: const EdgeInsets.only(bottom: 12), child: CommonCard(
          onPressed: () => context.box.act('remote.connect', {'id': server['id']}),
          child: ListItem(title: Text(server['name'].toString()), subtitle: Text(server['url'].toString()), trailing: BoxMenu(items: [
            CommonPopupMenuItem(glyph: AppGlyphs.edit, label: context.tr('Edit'), onPressed: () => _edit(server)),
            CommonPopupMenuItem(glyph: AppGlyphs.delete, label: context.tr('Delete'), danger: true, onPressed: () async {
              if (await confirm(context, 'Delete', message: server['name'].toString()) && context.mounted) {
                await context.box.act('remote.delete', {'id': server['id']});
                if (mounted) setState(() => _servers = _load());
              }
            }),
          ])),
        )),
      ],
    )),
  );
}

class RemoteForm extends StatefulWidget {
  const RemoteForm({super.key, required this.initial});
  final Json initial;
  @override State<RemoteForm> createState() => _RemoteFormState();
}
class _RemoteFormState extends State<RemoteForm> {
  late final _name = TextEditingController(text: widget.initial['name'] as String? ?? '');
  late final _url = TextEditingController(text: widget.initial['url'] as String? ?? '');
  late final _secret = TextEditingController(text: widget.initial['secret'] as String? ?? '');
  bool _saving = false;
  @override void dispose() { _name.dispose(); _url.dispose(); _secret.dispose(); super.dispose(); }
  @override Widget build(BuildContext context) => BoxPage(title: 'Remote control', loading: _saving,
    confirmationAction: IconButtonData(glyph: AppGlyphs.check, tooltip: context.tr('Save'), onPressed: _saving ? null : () async {
      setState(() => _saving = true);
      final saved = await context.box.act('remote.save', {...widget.initial, 'name': _name.text, 'url': _url.text, 'secret': _secret.text});
      if (mounted) {
        setState(() => _saving = false);
        if (saved) context.safeNestedPop();
      }
    }),
    children: [
      TextField(controller: _name, decoration: InputDecoration(labelText: context.tr('Name'))),
      const SizedBox(height: 16),
      TextField(controller: _url, keyboardType: TextInputType.url, decoration: InputDecoration(labelText: context.tr('Address'))),
      const SizedBox(height: 16),
      TextField(controller: _secret, obscureText: true, decoration: InputDecoration(labelText: context.tr('Secret'))),
    ],
  );
}

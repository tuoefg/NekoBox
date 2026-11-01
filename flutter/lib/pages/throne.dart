import '../widgets.dart';

/// Throne settings that the B-box UI never had.
///
/// They use the same primitives as every other sheet here ([ListItem.toggle],
/// [ListItem.input], [ListItem.options]) and write through `settings.set` with the Throne key name,
/// so nothing here introduces a second storage or a second restart story.
class SettingToggle extends StatelessWidget {
  const SettingToggle(this.title, this.setting, {super.key, this.restart = true, this.autoRestart = false});
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

class SettingNumber extends StatelessWidget {
  const SettingNumber(this.title, this.setting, {super.key, this.min, this.max, this.unit, this.autoRestart = false});
  final String title, setting;
  final int? min, max;
  final String? unit;
  final bool autoRestart;
  @override Widget build(BuildContext context) => ListItem.input(
    title: Text(context.tr(title)), dialogTitle: context.tr(title),
    value: (context.box.settings[setting] ?? 0).toString(),
    suffixText: unit, keyboardType: TextInputType.number,
    validator: (value) {
      final number = int.tryParse(value ?? '');
      if (number == null) return context.tr('Invalid number');
      if (min != null && number < min!) return '$min–$max';
      if (max != null && number > max!) return '$min–$max';
      return null;
    },
    onChanged: (value) { if (value != null) context.box.setSetting(setting, int.parse(value), autoRestart: autoRestart); },
  );
}

class SettingText extends StatelessWidget {
  const SettingText(this.title, this.setting, {super.key, this.hint});
  final String title, setting;
  final String? hint;
  @override Widget build(BuildContext context) => ListItem.input(
    title: Text(context.tr(title)), subtitle: hint == null ? null : Text(context.tr(hint!)),
    dialogTitle: context.tr(title), value: (context.box.settings[setting] ?? '').toString(),
    onChanged: (value) { if (value != null) context.box.setSetting(setting, value); },
  );
}

/// A DNS server row whose dialog offers the presets and the servers typed before, as the native
/// editable combo box does (DnsServerSuggestions over DnsServerHistory::Fill). A picked server
/// saves and joins the history; the field itself takes anything.
class SettingDnsServer extends StatelessWidget {
  const SettingDnsServer(this.title, this.setting, {super.key, this.hint, required this.kind});
  final String title, setting, kind;
  final String? hint;

  @override Widget build(BuildContext context) {
    final value = (context.box.settings[setting] ?? '').toString();
    return ListItem(
      title: Text(context.tr(title)),
      subtitle: hint == null || value.isNotEmpty ? null : Text(context.tr(hint!)),
      trailing: ConstrainedBox(
        constraints: const BoxConstraints(maxWidth: 180),
        child: Text(value, overflow: TextOverflow.ellipsis, textAlign: TextAlign.end),
      ),
      onTap: () async {
        // A missing suggestion list (the API is off) only leaves the field freehand.
        var suggestions = const <String>[];
        try {
          final wire = json(await context.box.call('dns.suggestions'));
          suggestions = (wire[kind] as List? ?? const []).whereType<String>().toList();
        } catch (_) {}
        if (!context.mounted) return;
        final picked = await showDialog<String>(context: context, builder: (context) => _DnsServerDialog(
          title: context.tr(title), initial: value, suggestions: suggestions,
        ));
        if (picked != null && context.mounted) context.box.setSetting(setting, picked);
      },
    );
  }
}

class _DnsServerDialog extends StatefulWidget {
  const _DnsServerDialog({required this.title, required this.initial, required this.suggestions});
  final String title, initial;
  final List<String> suggestions;
  @override State<_DnsServerDialog> createState() => _DnsServerDialogState();
}

class _DnsServerDialogState extends State<_DnsServerDialog> {
  late final TextEditingController _controller = TextEditingController(text: widget.initial);
  @override void dispose() { _controller.dispose(); super.dispose(); }

  @override Widget build(BuildContext context) => AlertDialog(
    title: Text(widget.title),
    content: SizedBox(
      width: 360,
      child: Column(mainAxisSize: MainAxisSize.min, crossAxisAlignment: CrossAxisAlignment.stretch, children: [
        TextField(
          controller: _controller, autocorrect: false, enableSuggestions: false,
          keyboardType: TextInputType.url,
          decoration: const InputDecoration(border: OutlineInputBorder()),
        ),
        if (widget.suggestions.isNotEmpty) Flexible(
          child: ListView(shrinkWrap: true, children: [
            for (final server in widget.suggestions)
              ListTile(
                dense: true, title: Text(server),
                onTap: () => Navigator.pop(context, server),
              ),
          ]),
        ),
      ]),
    ),
    actions: [
      TextButton(onPressed: () => Navigator.pop(context), child: Text(context.tr('Cancel'))),
      TextButton(onPressed: () => Navigator.pop(context, _controller.text), child: Text(context.tr('Save'))),
    ],
  );
}

class SettingChoice<T> extends StatelessWidget {
  const SettingChoice(this.title, this.setting, {super.key, required this.options, required this.text, this.autoRestart = false});
  final String title, setting;
  final List<T> options;
  final String Function(T value) text;
  final bool autoRestart;
  @override Widget build(BuildContext context) {
    final value = context.box.settings[setting];
    return ListItem<T>.options(
      title: Text(context.tr(title)), options: options, textBuilder: (value) => context.tr(text(value)),
      value: value is T ? value : options.first,
      onChanged: (value) => context.box.setSetting(setting, value, autoRestart: autoRestart),
    );
  }
}

/// A setting Throne stores as a list of lines — private ranges, hosts rows, WARP addresses.
///
/// The sheet shows the list inline; editing opens a dialog over it, matching how the other
/// primitives edit a single value rather than pushing a page.
class SettingLines extends StatelessWidget {
  const SettingLines(this.title, this.setting, {super.key, this.hint, this.autoRestart = false});
  final String title, setting;
  final String? hint;
  final bool autoRestart;

  @override Widget build(BuildContext context) {
    // Hosts have kept this setting as a newline string at times, so both shapes are accepted here.
    final raw = context.box.settings[setting];
    final lines = raw is List
        ? raw.whereType<String>().toList()
        : raw is String
            ? raw.split('\n').map((line) => line.trim()).where((line) => line.isNotEmpty).toList()
            : const <String>[];
    return ListItem(
      title: Text(context.tr(title)),
      subtitle: lines.isEmpty
          ? Text(context.tr('Built-in defaults'))
          : Text(lines.join(', '), maxLines: 1, overflow: TextOverflow.ellipsis),
      onTap: () => _edit(context, lines),
    );
  }

  Future<void> _edit(BuildContext context, List<String> lines) async {
    final box = context.box;
    final saved = await showDialog<String>(
      context: context,
      builder: (context) => _LinesEditor(
        title: context.tr(title),
        hint: hint == null ? null : context.tr(hint!),
        initial: lines.join('\n'),
      ),
    );
    if (saved != null) {
      final value = saved
          .split('\n')
          .map((line) => line.trim())
          .where((line) => line.isNotEmpty)
          .toList();
      box.setSetting(setting, value, autoRestart: autoRestart);
    }
  }
}

/// The editor body owns its controller so the dialog's closing animation can still
/// touch the [TextField] after the pop future completes.
class _LinesEditor extends StatefulWidget {
  const _LinesEditor({required this.title, required this.initial, this.hint});
  final String title;
  final String initial;
  final String? hint;
  @override
  State<_LinesEditor> createState() => _LinesEditorState();
}

class _LinesEditorState extends State<_LinesEditor> {
  late final TextEditingController _controller = TextEditingController(text: widget.initial);
  @override
  void dispose() {
    _controller.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) => AlertDialog(
    title: Text(widget.title),
    content: Column(mainAxisSize: MainAxisSize.min, crossAxisAlignment: CrossAxisAlignment.start, children: [
      if (widget.hint != null) Padding(
        padding: const EdgeInsets.only(bottom: 12),
        child: Text(widget.hint!, style: Theme.of(context).textTheme.bodySmall),
      ),
      TextField(
        controller: _controller, minLines: 3, maxLines: 10, autocorrect: false,
        keyboardType: TextInputType.multiline,
        decoration: const InputDecoration(border: OutlineInputBorder()),
      ),
    ]),
    actions: [
      TextButton(onPressed: () => Navigator.pop(context), child: Text(context.tr('Cancel'))),
      TextButton(onPressed: () => Navigator.pop(context, _controller.text), child: Text(context.tr('Save'))),
    ],
  );
}

/// The page action that returns the listed settings to their Throne defaults: the rows are dropped
/// so every read falls back to the default the schema carries. Each settings page lists its own keys.
IconButtonData restoreDefaultsAction(BuildContext context, List<String> keys) => IconButtonData(
  glyph: AppGlyphs.reset, tooltip: context.tr('Restore defaults'),
  onPressed: () async {
    final box = context.box;
    final ok = await confirm(context, 'Restore defaults?', action: 'Restore',
      message: context.tr('Every setting on this page returns to its Throne default.'));
    if (ok) await box.resetSettings(keys);
  },
);

// --------------------------------------------------------------------------------------------- inbound

class InboundPage extends StatelessWidget {
  const InboundPage({super.key});
  @override Widget build(BuildContext context) {
    final vpnMode = context.box.settings['serviceMode'] != 'proxy';
    return BoxPage(title: 'Inbound', actions: [
      restoreDefaultsAction(context, const [
        'inboundSocksPort', 'randomInboundPort', 'allowLan', 'coreDnsInPort', 'disableMixedInbound',
        'inboundAuth', 'inboundUser', 'inboundPass', 'appendHttpProxy', 'httpProxyBypass',
      ]),
    ], children: [
      generateSection(items: [
        SettingNumber('Mixed port', 'inboundSocksPort', min: 1, max: 65535, autoRestart: true),
        SettingToggle('Random inbound port', 'randomInboundPort'),
        SettingToggle('Allow LAN access', 'allowLan', autoRestart: true),
        SettingNumber('DNS inbound port', 'coreDnsInPort', min: 1, max: 65535, autoRestart: true),
        SettingToggle('Disable the mixed inbound', 'disableMixedInbound', autoRestart: true),
      ], footer: context.tr('These four listeners must use different ports.')),
      generateSection(title: context.tr('Inbound authentication'), items: [
        SettingToggle('Enable authentication', 'inboundAuth', autoRestart: true),
        SettingText('Username', 'inboundUser'),
        SettingText('Password', 'inboundPass'),
      ], footer: vpnMode ? null : context.tr('Proxy-only service mode always keeps the mixed inbound.')),
      if (context.box.capabilities.of('systemProxy')) generateSection(title: context.tr('System proxy'), items: [
        SettingToggle('Append the HTTP proxy', 'appendHttpProxy', autoRestart: true),
        SettingLines('Proxy bypass', 'httpProxyBypass',
          hint: 'One host or CIDR per line. Empty means the proxy is applied to every connection.'),
      ]),
    ]);
  }
}

// ------------------------------------------------------------------------------------------------- tun

class TunPage extends StatelessWidget {
  const TunPage({super.key});
  @override Widget build(BuildContext context) => BoxPage(title: 'TUN', actions: [
    restoreDefaultsAction(context, const [
      'vpnMtu', 'vpnIpv6', 'vpnTunIpv4Cidr', 'vpnTunIpv6Cidr', 'allowBypass', 'vpnPrivateRanges', 'enableTunRouting',
    ]),
  ], children: [
    generateSection(items: [
      SettingNumber('MTU', 'vpnMtu', min: 1000, max: 10000, autoRestart: true),
      SettingToggle('TUN IPv6', 'vpnIpv6', autoRestart: true),
      SettingText('TUN IPv4 address', 'vpnTunIpv4Cidr'),
      SettingText('TUN IPv6 address', 'vpnTunIpv6Cidr'),
    ]),
    generateSection(title: context.tr('Routing'), items: [
      SettingToggle('Bypass private ranges', 'allowBypass', autoRestart: true),
      if (context.box.settings['allowBypass'] == true)
        SettingLines('Private ranges', 'vpnPrivateRanges',
          hint: 'One CIDR per line. These are reached directly instead of through the tunnel.'),
      SettingToggle('Route through TUN', 'enableTunRouting'),
    ]),
  ]);
}

// ------------------------------------------------------------------------------------------------- dns

const dnsStrategies = ['', 'ipv4_only', 'ipv6_only', 'prefer_ipv4', 'prefer_ipv6'];
const dnsFinalOuts = ['remote', 'direct'];

class DnsPage extends StatelessWidget {
  const DnsPage({super.key});
  @override Widget build(BuildContext context) => BoxPage(title: 'DNS', actions: [
    restoreDefaultsAction(context, const [
      'remoteDns', 'remoteDnsDisableIpv6', 'directDns', 'directDnsDisableIpv6', 'dnsFinalOut',
      'enableDnsRouting', 'fakeDns', 'fakeipDisableIpv6', 'dnsUseHosts', 'dnsPredefinedEnable',
      'dnsPredefinedRules', 'coreBoxUnderlyingDns', 'dnsReverseMapping', 'dnsDisableCache',
      'dnsDisableExpire', 'dnsCacheCapacity', 'dnsPersistCache', 'dnsOptimistic', 'dnsOptimisticTimeout',
      'dnsQueryTimeout',
    ]),
  ], children: [
    generateSection(title: context.tr('Remote DNS'), items: [
      SettingDnsServer('Remote DNS', 'remoteDns', hint: 'https://1.1.1.1/dns-query', kind: 'remote'),
      SettingToggle('Disable IPv6 answers', 'remoteDnsDisableIpv6'),
    ]),
    generateSection(title: context.tr('Direct DNS'), items: [
      SettingDnsServer('Direct DNS', 'directDns', kind: 'direct'),
      SettingToggle('Disable IPv6 answers', 'directDnsDisableIpv6'),
    ]),
    generateSection(items: [
      SettingChoice<String>('Default DNS server', 'dnsFinalOut', options: dnsFinalOuts, text: (value) => value == 'remote' ? 'Remote DNS' : 'Direct DNS'),
      SettingToggle('DNS routing', 'enableDnsRouting'),
      SettingToggle('FakeDNS', 'fakeDns', autoRestart: true),
      SettingToggle('Disable IPv6 for FakeIP', 'fakeipDisableIpv6'),
      SettingToggle('Use system hosts', 'dnsUseHosts'),
      SettingToggle('Predefined hosts', 'dnsPredefinedEnable'),
      if (context.box.settings['dnsPredefinedEnable'] == true)
        SettingLines('Predefined hosts entries', 'dnsPredefinedRules',
          hint: 'One entry per line, in hosts format: "address hostname".'),
      SettingText('Underlying DNS', 'coreBoxUnderlyingDns', hint: 'empty means the system resolver'),
      SettingToggle('Reverse mapping', 'dnsReverseMapping'),
    ]),
    generateSection(title: context.tr('Cache'), items: [
      SettingToggle('Disable DNS cache', 'dnsDisableCache'),
      SettingToggle('Disable cache expiration', 'dnsDisableExpire'),
      SettingNumber('Cache capacity', 'dnsCacheCapacity', min: 0),
      SettingToggle('Persist the cache', 'dnsPersistCache'),
      SettingToggle('Optimistic cache', 'dnsOptimistic'),
      if (context.box.settings['dnsOptimistic'] == true)
        SettingText('Optimistic cache timeout', 'dnsOptimisticTimeout', hint: 'e.g. 3m'),
      SettingText('Query timeout', 'dnsQueryTimeout', hint: 'e.g. 5s, empty to use the core default'),
    ]),
  ]);
}

// --------------------------------------------------------------------------------------------- routing

const routingDomainStrategies = ['', 'ipv4_only', 'ipv6_only', 'prefer_ipv4', 'prefer_ipv6'];
const ruleSetMirrors = [0, 1, 2, 3, 4, 5];

class RoutingPage extends StatelessWidget {
  const RoutingPage({super.key});
  @override Widget build(BuildContext context) {
    final autoUpdate = context.box.settings['routeAutoUpdateEnabled'] == true;
    return BoxPage(title: 'Routing', actions: [
      restoreDefaultsAction(context, const [
        'outboundDomainStrategy', 'domainStrategy', 'rulesetMirror', 'adblockEnable',
        'routeAutoUpdateEnabled', 'routeAutoUpdateMinutes',
      ]),
    ], children: [
      generateSection(items: [
        ListItem.open(leading: const GlyphIcon(AppGlyphs.rulePresets), title: Text(context.tr('Route profiles')), widget: const RouteProfilesPage()),
      ], footer: context.tr('The current profile builds every configuration that has no group override.')),
      generateSection(items: [
        SettingChoice<String>('Outbound domain strategy', 'outboundDomainStrategy',
          options: routingDomainStrategies, text: _strategyName),
        SettingChoice<String>('Domain strategy', 'domainStrategy',
          options: routingDomainStrategies, text: _strategyName),
        SettingChoice<int>('Rule set mirror', 'rulesetMirror', options: ruleSetMirrors, text: _mirrorName),
        SettingToggle('Ad blocking', 'adblockEnable', autoRestart: true),
      ]),
      generateSection(title: context.tr('Automatic updates'), items: [
        SettingToggle('Update rule sets', 'routeAutoUpdateEnabled'),
        if (autoUpdate) SettingNumber('Update interval', 'routeAutoUpdateMinutes', min: 30, unit: 'min'),
      ]),
    ]);
  }
}

String _strategyName(String value) => switch (value) {
  '' => 'Automatic', 'ipv4_only' => 'IPv4 only', 'ipv6_only' => 'IPv6 only',
  'prefer_ipv4' => 'Prefer IPv4', _ => 'Prefer IPv6',
};

String _mirrorName(int value) => switch (value) {
  1 => 'jsDelivr · Cloudflare', 2 => 'jsDelivr', 3 => 'GitHub',
  4 => 'Cloudflare Pages', 5 => 'Throne mirror', _ => 'GitHub raw',
};

// ------------------------------------------------------------------------------------------------- warp

const warpModes = ['wireguard', 'masque', 'amnezia'];

/// Cloudflare WARP, which Throne treats as an outbound of its own rather than a profile.
///
/// WireGuard and MASQUE keep separate key material, so only the section of the chosen mode is shown;
/// the switch and the registration hosts apply to all three.
class WarpPage extends StatelessWidget {
  const WarpPage({super.key});
  @override Widget build(BuildContext context) {
    final mode = context.box.settings['warpMode']?.toString() ?? 'wireguard';
    return BoxPage(title: 'WARP', actions: [
      restoreDefaultsAction(context, const [
        'enableWarp', 'warpMode', 'warpEndpoint', 'warpPrivateKey', 'warpPublicKey', 'warpIfcAddrs',
        'warpReserved', 'warpMasqueEndpoint', 'warpMasquePrivateKey', 'warpMasquePeerPublicKey',
        'warpMasqueSni', 'warpMasqueHttpMode', 'warpApiHosts',
      ]),
    ], children: [
      generateSection(items: [
        SettingToggle('Enable WARP', 'enableWarp', autoRestart: true),
        SettingChoice<String>('WARP mode', 'warpMode', options: warpModes, text: _warpModeName, autoRestart: true),
      ], footer: context.tr('WARP replaces the outbound of the running profile with Cloudflare WARP.')),
      if (mode != 'masque') generateSection(title: context.tr('WireGuard'), items: [
        SettingText('Endpoint', 'warpEndpoint', hint: 'host:port'),
        SettingText('Private key', 'warpPrivateKey'),
        SettingText('Public key', 'warpPublicKey'),
        SettingLines('Interface addresses', 'warpIfcAddrs', hint: 'One CIDR per line, for example 172.16.0.2/32'),
        SettingLines('Reserved bytes', 'warpReserved', hint: 'Three comma-separated integers, usually empty'),
      ]),
      if (mode == 'masque') generateSection(title: context.tr('MASQUE'), items: [
        SettingText('Endpoint', 'warpMasqueEndpoint'),
        SettingText('Private key', 'warpMasquePrivateKey'),
        SettingText('Peer public key', 'warpMasquePeerPublicKey'),
        SettingText('SNI', 'warpMasqueSni'),
        SettingChoice<int>('HTTP mode', 'warpMasqueHttpMode', options: const [0, 1, 2], text: _masqueHttpModeName),
      ]),
      generateSection(title: context.tr('Registration'), items: [
        SettingLines('API hosts', 'warpApiHosts', hint: 'Empty uses api.cloudflareclient.com'),
      ]),
    ]);
  }
}

String _warpModeName(String value) => switch (value) {
  'masque' => 'MASQUE', 'amnezia' => 'AmneziaWG', _ => 'WireGuard',
};

String _masqueHttpModeName(int value) => switch (value) {
  1 => 'HTTP/3 only', 2 => 'HTTP/2 only', _ => 'HTTP/3 with fallback',
};

// ------------------------------------------------------------------------------------------------ core

const logLevels = ['trace', 'debug', 'info', 'warn', 'error', 'fatal', 'panic'];
const xrayLogLevels = ['debug', 'info', 'warning', 'error', 'none'];
const ntpOutbounds = ['direct', 'proxy'];

class CorePage extends StatelessWidget {
  const CorePage({super.key});
  @override Widget build(BuildContext context) {
    final clashApi = context.box.settings['clashApiEnabled'] == true;
    final boxApi = context.box.settings['boxApiEnabled'] == true;
    return BoxPage(title: 'Core', actions: [
      restoreDefaultsAction(context, const [
        'logLevel', 'xrayLogLevel', 'logBufSize', 'enableStats', 'disableTrafficStats', 'skipCert',
        'useMozillaCerts', 'xrayUseSystemCa', 'clashApiEnabled', 'clashApiPort', 'clashApiListenAddr',
        'clashApiSecret', 'boxApiEnabled', 'boxApiPort', 'boxApiSecret', 'enableNtp', 'ntpServerAddress',
        'ntpServerPort', 'ntpInterval', 'ntpOutbound',
      ]),
    ], children: [
      generateSection(items: [
        ListItem(title: Text(context.tr('Core version')), subtitle: Text(context.box.coreVersion)),
        SettingChoice<String>('Log level', 'logLevel', options: logLevels, text: (value) => value.toUpperCase()),
        SettingChoice<String>('Xray log level', 'xrayLogLevel', options: xrayLogLevels, text: (value) => value.toUpperCase()),
        SettingNumber('Log buffer', 'logBufSize', min: 0, unit: 'KiB'),
        SettingToggle('Traffic statistics', 'enableStats', autoRestart: true),
        SettingToggle('Disable per-profile statistics', 'disableTrafficStats', autoRestart: true),
        SettingToggle('Skip certificate verification', 'skipCert'),
        SettingToggle('Use the Mozilla root store', 'useMozillaCerts'),
        SettingToggle('Xray uses the system CA', 'xrayUseSystemCa'),
      ]),
      generateSection(title: context.tr('Clash API'), items: [
        SettingToggle('Enable Clash API', 'clashApiEnabled', autoRestart: true),
        if (clashApi) ...[
          SettingNumber('Port', 'clashApiPort', min: 1, max: 65535, autoRestart: true),
          SettingText('Listen address', 'clashApiListenAddr'),
          SettingText('Secret', 'clashApiSecret'),
        ],
      ]),
      generateSection(title: context.tr('Core API'), items: [
        SettingToggle('Enable core API', 'boxApiEnabled', autoRestart: true),
        if (boxApi) ...[
          SettingNumber('Port', 'boxApiPort', min: 1, max: 65535, autoRestart: true),
          SettingText('Secret', 'boxApiSecret'),
        ],
      ]),
      generateSection(title: context.tr('NTP'), items: [
        SettingToggle('Enable NTP', 'enableNtp', autoRestart: true),
        SettingText('NTP server', 'ntpServerAddress'),
        SettingNumber('NTP port', 'ntpServerPort', min: 0, max: 65535),
        SettingText('NTP interval', 'ntpInterval', hint: 'e.g. 30m, empty to use the core default'),
        SettingChoice<String>('NTP outbound', 'ntpOutbound', options: ntpOutbounds, text: (value) => value),
      ]),
    ]);
  }
}

// ---------------------------------------------------------------------------------------------- testing

const speedTestModes = [0, 1, 2, 3, 4];
const exitLookupProviders = ['auto', 'ip-api', 'ipwho', 'ipinfo', 'ipapi-co', 'ip2location', 'freeipapi', 'cloudflare'];

class TestingPage extends StatelessWidget {
  const TestingPage({super.key});
  @override Widget build(BuildContext context) => BoxPage(title: 'Testing', actions: [
    restoreDefaultsAction(context, const [
      'testUrl', 'urlTestTimeoutMs', 'testConcurrent', 'directTestUrl', 'speedTestMode',
      'speedTestTimeoutMs', 'simpleDlUrl', 'exitCountryLookup', 'exitLookupProvider',
    ]),
  ], children: [
    generateSection(items: [
      SettingText('Test URL', 'testUrl'),
      SettingNumber('Test timeout', 'urlTestTimeoutMs', min: 0, unit: 'ms'),
      SettingNumber('Concurrent tests', 'testConcurrent', min: 1, max: 499),
      SettingText('Direct test URL', 'directTestUrl'),
    ]),
    generateSection(title: context.tr('Speed test'), items: [
      SettingChoice<int>('Speed test mode', 'speedTestMode', options: speedTestModes, text: _speedTestModeName),
      SettingNumber('Speed test timeout', 'speedTestTimeoutMs', min: 0, unit: 'ms'),
      if (context.box.settings['speedTestMode'] == 3)
        SettingText('Simple download URL', 'simpleDlUrl', hint: 'A file of known size used to measure throughput'),
    ]),
    generateSection(title: context.tr('Exit detection'), items: [
      SettingToggle('Look up exit country', 'exitCountryLookup'),
      SettingChoice<String>('Lookup provider', 'exitLookupProvider', options: exitLookupProviders, text: (value) => value),
    ]),
  ]);
}

String _speedTestModeName(int value) => switch (value) {
  1 => 'Download', 2 => 'Upload', 3 => 'Simple download', 4 => 'Country', _ => 'Download and upload',
};

// ------------------------------------------------------------------------------------------ node presets

const muxProtocols = ['h2mux', 'smux', 'yamux'];
const muxModes = ['max_streams', 'max_connections'];
const xrayXudpModes = ['reject', 'allow', 'skip'];
const fragmentImplementations = ['built-in', 'custom'];
const tlsSpoofMethods = ['', 'wrong-sequence', 'wrong-checksum', 'wrong-ack', 'wrong-md5', 'wrong-timestamp'];

/// The defaults the importer stamps on the nodes it builds — Mux, Xray multiplex, fragment, TLS
/// tricks, HTTP/2 tuning and the QUIC dialer. A running configuration is never rebuilt from these;
/// they apply to the next import or creation, exactly as the native preset screen does.
class PresetsPage extends StatelessWidget {
  const PresetsPage({super.key});
  @override Widget build(BuildContext context) {
    final brutal = context.box.settings['muxBrutal'] == true;
    return BoxPage(title: 'Node presets', actions: [
      restoreDefaultsAction(context, const [
        'muxProtocol', 'muxMode', 'muxConcurrency', 'muxMaxConnections', 'muxMinStreams', 'muxPadding',
        'muxBrutal', 'muxBrutalUpMbps', 'muxBrutalDownMbps', 'muxDefaultOn', 'xrayMuxConcurrency',
        'xrayMuxMaxConnections', 'xrayMuxXudpConcurrency', 'xrayMuxXudpProxyUdp443', 'xrayMuxDefaultOn',
        'fragmentDefaultOn', 'fragmentImplementation', 'fragmentSize', 'fragmentSleep', 'tlsTricksDefaultOn',
        'utlsFingerprint', 'tlsSpoof', 'tlsSpoofMethod', 'h2IdleTimeout', 'h2KeepAlivePeriod',
        'h2StreamReceiveWindow', 'h2ConnectionReceiveWindow', 'h2MaxConcurrentStreams',
        'quicInitialPacketSize', 'quicDisablePathMtuDiscovery',
      ]),
    ], children: [
      generateSection(title: context.tr('Mux'), items: [
        SettingChoice<String>('Mux protocol', 'muxProtocol', options: muxProtocols, text: (value) => value),
        SettingChoice<String>('Mux mode', 'muxMode', options: muxModes, text: _muxModeName),
        SettingNumber('Mux concurrency', 'muxConcurrency', min: 0),
        SettingNumber('Mux max connections', 'muxMaxConnections', min: 0),
        SettingNumber('Mux min streams', 'muxMinStreams', min: 0),
        SettingToggle('Mux padding', 'muxPadding'),
        SettingToggle('Brutal congestion', 'muxBrutal'),
        if (brutal) ...[
          SettingNumber('Brutal upload', 'muxBrutalUpMbps', min: 0, unit: 'Mbps'),
          SettingNumber('Brutal download', 'muxBrutalDownMbps', min: 0, unit: 'Mbps'),
        ],
        SettingToggle('Enable Mux by default', 'muxDefaultOn'),
      ], footer: context.tr('Applied when a node is imported, not to nodes already saved.')),
      generateSection(title: context.tr('Xray multiplex'), items: [
        SettingNumber('Concurrency', 'xrayMuxConcurrency', min: -1, max: 32767),
        SettingNumber('Max connections', 'xrayMuxMaxConnections', min: 0, max: 32767),
        SettingNumber('XUDP concurrency', 'xrayMuxXudpConcurrency', min: -1, max: 32767),
        SettingChoice<String>('XUDP proxy UDP/443', 'xrayMuxXudpProxyUdp443', options: xrayXudpModes, text: (value) => value),
        SettingToggle('Enable Xray Mux by default', 'xrayMuxDefaultOn'),
      ]),
      generateSection(title: context.tr('Fragment'), items: [
        SettingToggle('Fragment by default', 'fragmentDefaultOn'),
        SettingChoice<String>('Fragment implementation', 'fragmentImplementation',
          options: fragmentImplementations, text: (value) => value),
        SettingText('Fragment size', 'fragmentSize', hint: 'e.g. 10-100, empty uses the built-in range'),
        SettingText('Fragment sleep', 'fragmentSleep', hint: 'e.g. 2-5, empty uses the built-in range'),
      ]),
      generateSection(title: context.tr('TLS tricks'), items: [
        SettingToggle('TLS tricks by default', 'tlsTricksDefaultOn'),
        SettingText('uTLS fingerprint', 'utlsFingerprint', hint: 'e.g. chrome, firefox, safari'),
        SettingText('TLS spoof', 'tlsSpoof', hint: 'a SNI to offer instead of the real one'),
        SettingChoice<String>('TLS spoof method', 'tlsSpoofMethod', options: tlsSpoofMethods, text: _tlsSpoofName),
      ]),
      generateSection(title: context.tr('HTTP/2'), items: [
        SettingText('Idle timeout', 'h2IdleTimeout', hint: 'e.g. 5m, empty to use the core default'),
        SettingText('Keep-alive period', 'h2KeepAlivePeriod', hint: 'e.g. 30s, empty to use the core default'),
        SettingText('Stream receive window', 'h2StreamReceiveWindow'),
        SettingText('Connection receive window', 'h2ConnectionReceiveWindow'),
        SettingNumber('Max concurrent streams', 'h2MaxConcurrentStreams', min: 0),
      ]),
      generateSection(title: context.tr('QUIC'), items: [
        SettingNumber('Initial packet size', 'quicInitialPacketSize', min: 0, unit: 'B'),
        SettingToggle('Disable path MTU discovery', 'quicDisablePathMtuDiscovery'),
      ]),
    ]);
  }
}

String _muxModeName(String value) => value == 'max_connections' ? 'Max connections' : 'Max streams';

String _tlsSpoofName(String value) => switch (value) {
  'wrong-sequence' => 'Wrong sequence',
  'wrong-checksum' => 'Wrong checksum',
  'wrong-ack' => 'Wrong acknowledgement',
  'wrong-md5' => 'Wrong MD5',
  'wrong-timestamp' => 'Wrong timestamp',
  _ => 'Off',
};

// ---------------------------------------------------------------------------------------- subscriptions

const subscriptionTlsVersions = [0, 1, 2];
const subscriptionHttpVersions = [0, 1];

class SubscriptionPage extends StatelessWidget {
  const SubscriptionPage({super.key});
  @override Widget build(BuildContext context) {
    final autoUpdate = context.box.settings['subAutoUpdateEnabled'] == true;
    return BoxPage(title: 'Subscriptions', actions: [
      restoreDefaultsAction(context, const [
        'userAgent', 'netUseProxy', 'netInsecure', 'subTlsVersion', 'subHttpVersion', 'subSendHwid',
        'subCustomHwidParams', 'subAutoUpdateEnabled', 'subAutoUpdateMinutes', 'subRespectServerInterval',
        'subClear', 'subShowChangePopup', 'allowStoppingActiveProfile',
      ]),
    ], children: [
      generateSection(title: context.tr('Request'), items: [
        SettingText('User agent', 'userAgent', hint: 'empty means the built-in user agent'),
        SettingToggle('Fetch through the proxy', 'netUseProxy'),
        SettingToggle('Allow insecure TLS', 'netInsecure'),
        SettingChoice<int>('TLS version', 'subTlsVersion', options: subscriptionTlsVersions, text: _tlsVersionName),
        SettingChoice<int>('HTTP version', 'subHttpVersion', options: subscriptionHttpVersions, text: _httpVersionName),
        SettingToggle('Send device identifier', 'subSendHwid'),
        if (context.box.settings['subSendHwid'] == true)
          SettingText('Custom HWID parameters', 'subCustomHwidParams',
            hint: 'key=value pairs separated by commas, sent alongside the identifier'),
      ]),
      generateSection(title: context.tr('Automatic updates'), items: [
        SettingToggle('Update subscriptions', 'subAutoUpdateEnabled'),
        if (autoUpdate) SettingNumber('Update interval', 'subAutoUpdateMinutes', min: 30, unit: 'min'),
        SettingToggle('Respect the server interval', 'subRespectServerInterval'),
      ]),
      generateSection(title: context.tr('Updating'), items: [
        SettingToggle('Clear on failure', 'subClear'),
        SettingToggle('Show the change report', 'subShowChangePopup'),
        SettingToggle('Allow stopping the running profile', 'allowStoppingActiveProfile'),
      ]),
    ]);
  }
}

String _tlsVersionName(int value) => switch (value) { 1 => 'TLS 1.2', 2 => 'TLS 1.3', _ => 'Automatic' };
String _httpVersionName(int value) => switch (value) { 1 => 'HTTP/1.1', _ => 'Automatic' };

// -------------------------------------------------------------------------------------------------- misc

class UpdatePage extends StatelessWidget {
  const UpdatePage({super.key});
  @override Widget build(BuildContext context) => BoxPage(title: 'Updates', actions: [
    restoreDefaultsAction(context, const ['allowBetaUpdate', 'updateCheckAuto']),
  ], children: [
    generateSection(items: [
      SettingToggle('Allow beta versions', 'allowBetaUpdate'),
      SettingToggle('Check for updates automatically', 'updateCheckAuto'),
    ]),
  ]);
}

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

// --------------------------------------------------------------------------------------------- inbound

class InboundPage extends StatelessWidget {
  const InboundPage({super.key});
  @override Widget build(BuildContext context) {
    final vpnMode = context.box.settings['serviceMode'] != 'proxy';
    return BoxPage(title: 'Inbound', children: [
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
  @override Widget build(BuildContext context) => BoxPage(title: 'TUN', children: [
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
  @override Widget build(BuildContext context) => BoxPage(title: 'DNS', children: [
    generateSection(title: context.tr('Remote DNS'), items: [
      SettingText('Remote DNS', 'remoteDns', hint: 'https://1.1.1.1/dns-query'),
      SettingToggle('Disable IPv6 answers', 'remoteDnsDisableIpv6'),
    ]),
    generateSection(title: context.tr('Direct DNS'), items: [
      SettingText('Direct DNS', 'directDns'),
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
    return BoxPage(title: 'Routing', children: [
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
    return BoxPage(title: 'WARP', children: [
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
    return BoxPage(title: 'Core', children: [
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
  @override Widget build(BuildContext context) => BoxPage(title: 'Testing', children: [
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

// ---------------------------------------------------------------------------------------- subscriptions

const subscriptionTlsVersions = [0, 1, 2];
const subscriptionHttpVersions = [0, 1];

class SubscriptionPage extends StatelessWidget {
  const SubscriptionPage({super.key});
  @override Widget build(BuildContext context) {
    final autoUpdate = context.box.settings['subAutoUpdateEnabled'] == true;
    return BoxPage(title: 'Subscriptions', children: [
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
  @override Widget build(BuildContext context) => BoxPage(title: 'Updates', children: [
    generateSection(items: [
      SettingToggle('Allow beta versions', 'allowBetaUpdate'),
      SettingToggle('Check for updates automatically', 'updateCheckAuto'),
    ]),
  ]);
}

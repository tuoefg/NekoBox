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
      ], footer: context.tr('These four listeners must use different ports.')),
      generateSection(title: context.tr('Inbound authentication'), items: [
        SettingToggle('Enable authentication', 'inboundAuth', autoRestart: true),
        SettingText('Username', 'inboundUser'),
        SettingText('Password', 'inboundPass'),
      ], footer: vpnMode ? null : context.tr('Proxy-only service mode always keeps the mixed inbound.')),
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
      SettingText('Underlying DNS', 'coreBoxUnderlyingDns', hint: 'empty means the system resolver'),
    ]),
    generateSection(title: context.tr('Cache'), items: [
      SettingToggle('Disable DNS cache', 'dnsDisableCache'),
      SettingToggle('Disable cache expiration', 'dnsDisableExpire'),
      SettingNumber('Cache capacity', 'dnsCacheCapacity', min: 0),
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
      generateSection(title: context.tr('WARP'), items: [
        SettingToggle('Enable WARP', 'enableWarp'),
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

// ------------------------------------------------------------------------------------------------ core

const logLevels = ['trace', 'debug', 'info', 'warn', 'error', 'fatal', 'panic'];

class CorePage extends StatelessWidget {
  const CorePage({super.key});
  @override Widget build(BuildContext context) {
    final clashApi = context.box.settings['clashApiEnabled'] == true;
    final boxApi = context.box.settings['boxApiEnabled'] == true;
    return BoxPage(title: 'Core', children: [
      generateSection(items: [
        ListItem(title: Text(context.tr('Core version')), subtitle: Text(context.box.coreVersion)),
        SettingChoice<String>('Log level', 'logLevel', options: logLevels, text: (value) => value.toUpperCase()),
        SettingNumber('Log buffer', 'logBufSize', min: 0, unit: 'KiB'),
        SettingToggle('Traffic statistics', 'enableStats', autoRestart: true),
        SettingToggle('Disable per-profile statistics', 'disableTrafficStats', autoRestart: true),
        SettingToggle('Skip certificate verification', 'skipCert'),
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
        if (boxApi) SettingNumber('Port', 'boxApiPort', min: 1, max: 65535, autoRestart: true),
      ]),
      generateSection(title: context.tr('NTP'), items: [
        SettingToggle('Enable NTP', 'enableNtp', autoRestart: true),
        SettingText('NTP server', 'ntpServerAddress'),
        SettingNumber('NTP port', 'ntpServerPort', min: 0, max: 65535),
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
    ]),
  ]);
}

import 'dart:convert' as convert;

import 'package:flutter/services.dart';
import '../widgets.dart';
import '../components/form.dart';

/// The typed protocol forms: one schema per protocol, fields at sing-box JSON paths, rendered by a
/// generic engine and saved through `profiles.nodeUpdate` (the bridge parses and model-validates the
/// document, the native editor's save gate). Blocks mirror the native screens' shared blocks: tls,
/// transport, multiplex and quic. Field lists follow the native *SettingsActivity bindings.
///
/// Synthetic keys (`__state`, `__must`) carry menu state the JSON has no room for; they are stripped
/// before the document is saved.
class ProtocolFormPage extends StatefulWidget {
  const ProtocolFormPage({super.key, required this.id});
  final int id;
  @override State<ProtocolFormPage> createState() => _ProtocolFormPageState();
}

class _ProtocolFormPageState extends State<ProtocolFormPage> {
  Json? _root;
  String? _error;
  bool _loading = true, _saving = false, _attempted = false;
  late final TextEditingController _name = TextEditingController();

  @override void initState() {
    super.initState();
    WidgetsBinding.instance.addPostFrameCallback((_) => _load());
  }

  @override void dispose() { _name.dispose(); super.dispose(); }

  Future<void> _load() async {
    setState(() { _error = null; _loading = true; });
    try {
      final data = json(await context.box.call('profiles.nodeConfig', {'id': widget.id}));
      if (!mounted) return;
      _name.text = data['name']?.toString() ?? '';
      final decoded = convert.jsonDecode(data['json']?.toString() ?? '{}');
      setState(() { _root = json(decoded); _loading = false; });
    } catch (error) {
      if (mounted) setState(() { _error = BoxController.describe(error); _loading = false; });
    }
  }

  String get _type => _root?['type']?.toString() ?? '';

  /// The form reads a dotted path; missing segments read as null (and as '' in text fields).
  Object? _get(String path) {
    Object? current = _root;
    for (final segment in path.split('.')) {
      if (current is Json) { current = current[segment]; } else { return null; }
    }
    return current;
  }

  void _set(String path, Object? value) {
    final root = _root;
    if (root == null) return;
    final segments = path.split('.');
    Json current = root;
    for (final segment in segments.take(segments.length - 1)) {
      final next = current[segment];
      current[segment] = next is Json ? next : <String, dynamic>{};
      current = current[segment] as Json;
    }
    if (value == null) {
      current.remove(segments.last);
    } else {
      current[segments.last] = value;
    }
  }

  String _text(String path) => _get(path)?.toString() ?? '';
  int _int(String path) => (_get(path) as num?)?.toInt() ?? 0;
  bool _bool(String path) => _get(path) == true;
  List<String> _lines(String path) => (_get(path) as List? ?? const []).map((e) => '$e').toList();

  // ------------------------------------------------------------------------------------------------ blocks

  List<_Field> _tlsBlock({required bool mustTls}) => [
    // The must-TLS screens hide the switch; the save keeps the block on regardless.
    if (!mustTls) _Field('tls.enabled', 'Enabled', 'bool'),
    _Field('tls.server_name', 'Server name', 'text'),
    _Field('tls.insecure', 'Allow insecure', 'bool'),
    _Field('tls.alpn', 'ALPN', 'lines', hint: 'One protocol per line'),
    _Field('tls.utls.enabled', 'uTLS', 'bool'),
    _Field('tls.utls.fingerprint', 'uTLS fingerprint', 'choice', choices: _utlsChoices, showIf: ('tls.utls.enabled', 'true')),
    _Field('tls.reality.enabled', 'Reality', 'bool'),
    _Field('tls.reality.public_key', 'Reality public key', 'text', showIf: ('tls.reality.enabled', 'true')),
    _Field('tls.reality.short_id', 'Reality short ID', 'text', showIf: ('tls.reality.enabled', 'true')),
    _Field('tls.ech.enabled', 'ECH', 'bool'),
    _Field('tls.ech.config', 'ECH config', 'lines', showIf: ('tls.ech.enabled', 'true')),
  ];

  List<_Field> _transportBlock() => [
    _Field('transport.type', 'Transport', 'choice', choices: const [
      ('tcp', 'TCP'), ('ws', 'WebSocket'), ('http', 'HTTP'), ('grpc', 'gRPC'), ('httpupgrade', 'HTTPUpgrade'),
    ]),
    _Field('transport.host', 'Host', 'text', showIf: ('transport.type', 'ws,http,httpupgrade')),
    _Field('transport.path', 'Path', 'text', showIf: ('transport.type', 'ws,http,httpupgrade')),
    _Field('transport.method', 'Method', 'text', showIf: ('transport.type', 'http')),
    _Field('transport.headers', 'Headers', 'headers', showIf: ('transport.type', 'ws,http,httpupgrade')),
    _Field('transport.service_name', 'Service name', 'text', showIf: ('transport.type', 'grpc')),
    _Field('transport.idle_timeout', 'Idle timeout', 'text', showIf: ('transport.type', 'http,grpc')),
    _Field('transport.ping_timeout', 'Ping timeout', 'text', showIf: ('transport.type', 'http,grpc')),
    _Field('transport.max_early_data', 'Max early data', 'int', showIf: ('transport.type', 'ws')),
    _Field('transport.early_data_header_name', 'Early data header', 'text', showIf: ('transport.type', 'ws')),
  ];

  List<_Field> _muxBlock() => [
    _Field('multiplex.__state', 'Multiplex', 'muxState'),
    _Field('multiplex.__mode', 'Mode', 'choice',
        choices: const [('streams', 'Max streams'), ('connections', 'Max connections')],
        showIf: ('multiplex.__state', 'custom')),
    _Field('multiplex.protocol', 'Protocol', 'choice',
        choices: const [('h2mux', 'h2mux'), ('smux', 'smux'), ('yamux', 'yamux')],
        showIf: ('multiplex.__state', 'custom')),
    _Field('multiplex.max_streams', 'Max streams', 'int', showIf: ('multiplex.__mode', 'streams')),
    _Field('multiplex.max_connections', 'Max connections', 'int', showIf: ('multiplex.__mode', 'connections')),
    _Field('multiplex.min_streams', 'Min streams', 'int', showIf: ('multiplex.__mode', 'connections')),
    _Field('multiplex.padding', 'Padding', 'bool', showIf: ('multiplex.__state', 'custom')),
    _Field('multiplex.brutal.enabled', 'TCP Brutal', 'bool', showIf: ('multiplex.__state', 'custom')),
    _Field('multiplex.brutal.up_mbps', 'Brutal up (Mbps)', 'int', showIf: ('multiplex.__brutal', 'true')),
    _Field('multiplex.brutal.down_mbps', 'Brutal down (Mbps)', 'int', showIf: ('multiplex.__brutal', 'true')),
  ];

  List<_Field> _quicBlock() => [
    _Field('quic.idle_timeout', 'Idle timeout', 'text'),
    _Field('quic.keep_alive_period', 'Keep alive period', 'text'),
    _Field('quic.stream_receive_window', 'Stream receive window', 'text'),
    _Field('quic.connection_receive_window', 'Connection receive window', 'text'),
    _Field('quic.max_concurrent_streams', 'Max concurrent streams', 'int'),
    _Field('quic.initial_packet_size', 'Initial packet size', 'int'),
  ];

  /// The multiplex state menu derives from the JSON the way the native model does: no object keeps
  /// the default, `enabled: false` is off, an object of only `enabled` is on, everything else custom.
  String get _muxState {
    final multiplex = _get('multiplex');
    if (multiplex is! Json) return '';
    if (multiplex['enabled'] != true) return 'off';
    final customKeys = multiplex.keys.where((key) => key != 'enabled').toList();
    return customKeys.isEmpty ? 'on' : 'custom';
  }

  /// The pool mode is a UI choice (`__mode` in the scratch tree) with the stored shape as its default.
  String get _muxMode {
    final stored = _get('__mode')?.toString();
    if (stored != null && stored.isNotEmpty) return stored;
    return _get('multiplex.max_connections') != null ? 'connections' : 'streams';
  }

  /// The utls fingerprint menu's "Default" is the empty fingerprint (the native derive rule).
  List<(String, String)> get _utlsChoices => const [
    ('', 'Default'), ('chrome', 'chrome'), ('firefox', 'firefox'), ('edge', 'edge'), ('safari', 'safari'),
    ('360', '360'), ('qq', 'qq'), ('ios', 'ios'), ('android', 'android'), ('random', 'random'),
    ('randomized', 'randomized'), ('unsafe', 'unsafe'),
  ];

  // ------------------------------------------------------------------------------------------------ schemas

  bool get _supported => const ['shadowsocks', 'vmess', 'vless', 'trojan', 'tuic', 'hysteria', 'wireguard'].contains(_type);

  List<_Section> _schemas() => switch (_type) {
    'shadowsocks' => [
      ..._basic(),
      _Section(null, [
        _Field('method', 'Method', 'choice', choices: [
          for (final method in const [
            'none', '2022-blake3-aes-128-gcm', '2022-blake3-aes-256-gcm', '2022-blake3-chacha20-poly1305',
            'aes-128-gcm', 'aes-192-gcm', 'aes-256-gcm', 'chacha20-ietf-poly1305', 'xchacha20-ietf-poly1305',
            'aes-128-ctr', 'aes-192-ctr', 'aes-256-ctr', 'aes-128-cfb', 'aes-192-cfb', 'aes-256-cfb',
            'rc4-md5', 'chacha20-ietf', 'xchacha20',
          ]) (method, method),
        ]),
        _Field('password', 'Password', 'password'),
        _Field('plugin', 'Plugin', 'text'),
        _Field('plugin_opts', 'Plugin options', 'text'),
        _Field('uot', 'UDP over TCP', 'bool'),
      ]),
    ],
    'vmess' => [
      ..._basic(),
      _Section(null, [
        _Field('uuid', 'UUID', 'text', required: true),
        _Field('security', 'Encryption', 'choice', choices: const [
          ('auto', 'auto'), ('none', 'none'), ('zero', 'zero'),
          ('aes-128-gcm', 'aes-128-gcm'), ('chacha20-poly1305', 'chacha20-poly1305'),
        ]),
        _Field('alter_id', 'Alter ID', 'int'),
        _Field('packet_encoding', 'Packet encoding', 'choice',
            choices: const [('', 'Default'), ('packetaddr', 'packetaddr'), ('xudp', 'xudp')]),
        _Field('global_padding', 'Global padding', 'bool'),
        _Field('authenticated_length', 'Authenticated length', 'bool'),
      ]),
      _Section('Transport', _transportBlock()),
      _Section('TLS', _tlsBlock(mustTls: false)),
      _Section('Multiplex', _muxBlock()),
    ],
    'vless' => [
      ..._basic(),
      _Section(null, [
        _Field('uuid', 'UUID', 'text', required: true),
        _Field('flow', 'Flow', 'choice', choices: const [('none', 'none'), ('xtls-rprx-vision', 'xtls-rprx-vision')]),
      ]),
      _Section('Transport', _transportBlock()),
      _Section('TLS', _tlsBlock(mustTls: false)),
      _Section('Multiplex', _muxBlock()),
    ],
    'trojan' => [
      ..._basic(),
      _Section(null, [
        _Field('password', 'Password', 'password', required: true),
      ]),
      _Section('Transport', _transportBlock()),
      _Section('TLS', _tlsBlock(mustTls: true)),
      _Section('Multiplex', _muxBlock()),
    ],
    'tuic' => [
      ..._basic(),
      _Section(null, [
        _Field('uuid', 'UUID', 'text', required: true),
        _Field('password', 'Password', 'password'),
        _Field('congestion_control', 'Congestion control', 'choice',
            choices: const [('', 'Default'), ('cubic', 'cubic'), ('new_reno', 'new_reno'), ('bbr', 'bbr')]),
        _Field('udp_relay_mode', 'UDP relay mode', 'choice',
            choices: const [('', 'Default'), ('native', 'native'), ('quic', 'quic')]),
        _Field('udp_over_stream', 'UDP over stream', 'bool'),
        _Field('zero_rtt_handshake', 'Zero RTT handshake', 'bool'),
        _Field('heartbeat', 'Heartbeat', 'text'),
      ]),
      _Section('TLS', _tlsBlock(mustTls: true)),
      _Section('QUIC', _quicBlock()),
    ],
    'hysteria' => [
      ..._basic(),
      _Section(null, [
        _Field('protocol_version', 'Protocol version', 'choice', choices: const [('1', 'Hysteria 1'), ('2', 'Hysteria 2')]),
        _Field('auth_type', 'Auth type', 'choice', choices: const [('', 'Default'), ('base64', 'base64'), ('string', 'string')], showIf: ('protocol_version', '1')),
        _Field('auth', 'Auth', 'password', showIf: ('protocol_version', '1')),
        _Field('password', 'Password', 'password', showIf: ('protocol_version', '2')),
        _Field('obfs_type', 'Obfuscation', 'choice',
            choices: const [('', 'None'), ('salamander', 'salamander'), ('gecko', 'gecko')], showIf: ('protocol_version', '2')),
        _Field('obfs', 'Obfuscation password', 'password', showIf: ('protocol_version', '2')),
        _Field('up_mbps', 'Up (Mbps)', 'int', showIf: ('protocol_version', '2')),
        _Field('down_mbps', 'Down (Mbps)', 'int', showIf: ('protocol_version', '2')),
        _Field('server_ports', 'Server ports', 'lines', showIf: ('protocol_version', '2')),
        _Field('hop_interval', 'Hop interval', 'text', showIf: ('protocol_version', '2')),
      ]),
      _Section('TLS', _tlsBlock(mustTls: true)),
      _Section('QUIC', _quicBlock()),
    ],
    'wireguard' => [
      _Section(null, [
        _Field('name', 'Name', 'name'),
        _Field('peer.address', 'Endpoint address', 'text', required: true),
        _Field('peer.port', 'Endpoint port', 'int', required: true),
        _Field('address', 'Local addresses', 'list'),
        _Field('private_key', 'Private key', 'password', required: true),
        _Field('peer.public_key', 'Peer public key', 'text', required: true),
        _Field('peer.pre_shared_key', 'Pre-shared key', 'password'),
        _Field('peer.reserved', 'Reserved', 'list'),
        _Field('peer.persistent_keepalive', 'Persistent keepalive', 'int'),
        _Field('mtu', 'MTU', 'int'),
        _Field('system', 'System interface', 'bool'),
      ]),
    ],
    _ => [],
  };

  List<_Section> _basic() => [
    _Section(null, [
      _Field('name', 'Name', 'name'),
      _Field('server', 'Server', 'text', required: true),
      _Field('server_port', 'Server port', 'int', required: true),
    ]),
  ];

  // ------------------------------------------------------------------------------------------------ validation

  ({String path, String message})? get _issue {
    for (final section in _schemas()) {
      for (final field in section.fields) {
        if (field.kind == 'name') continue;
        final visible = _visible(field);
        if (!visible) continue;
        if (field.required) {
          final empty = switch (field.kind) {
            'bool' => false,
            'int' => _int(field.path) == 0,
            'muxState' => _muxState.isEmpty,
            _ => _text(field.path).trim().isEmpty,
          };
          if (empty) return (path: field.path, message: '${context.tr(field.label)}: ${context.tr('Required')}');
        }
        if (field.kind == 'int' && field.path == 'server_port') {
          final port = _int(field.path);
          if (port < 1 || port > 65535) return (path: field.path, message: context.tr('Must be a port number'));
        }
      }
    }
    return null;
  }

  bool _visible(_Field field) {
    final showIf = field.showIf;
    if (showIf == null) return true;
    final (path, values) = showIf;
    final current = switch (path) {
      'multiplex.__state' => _muxState,
      'multiplex.__mode' => _muxMode,
      'multiplex.__brutal' => _bool('multiplex.brutal.enabled') ? 'true' : 'false',
      _ => _get(path)?.toString() ?? '',
    };
    return values.split(',').contains(current);
  }

  void _changed(String _) { if (_attempted) setState(() {}); }

  // ------------------------------------------------------------------------------------------------ save

  Future<void> _save() async {
    if (_saving || _loading) return;
    if (_issue != null) { setState(() => _attempted = true); return; }
    final root = _root;
    if (root == null) return;
    final box = context.box;
    setState(() => _saving = true);
    try {
      // The must-TLS protocols keep the block on; the synthetic keys never reach storage.
      if (const ['trojan', 'tuic', 'hysteria'].contains(_type)) _set('tls.enabled', true);
      void strip(Json node) {
        for (final key in node.keys.toList()) {
          if (key.startsWith('__')) { node.remove(key); continue; }
          if (node[key] is Json) strip(node[key] as Json);
        }
      }
      strip(root);
      await box.call('profiles.nodeUpdate', {
        'id': widget.id,
        'name': _name.text.trim(),
        'content': convert.jsonEncode(root),
      });
      if (mounted) context.safeNestedPop();
    } catch (error) {
      if (mounted) setState(() { _saving = false; _attempted = true; _error = BoxController.describe(error); });
    }
  }

  // ------------------------------------------------------------------------------------------------ build

  Widget _fieldRow(_Field field) {
    final invalid = _attempted && _issue?.path == field.path;
    switch (field.kind) {
      case 'name':
        return FormRow(title: context.tr(field.label), invalid: invalid,
          trailing: FormRowTextField(controller: _name, onChanged: _changed));
      case 'bool':
        return FormRow(title: context.tr(field.label), onPressed: () { _set(field.path, !_bool(field.path)); _changed(''); },
          trailing: CommonSwitch(value: _bool(field.path), onChanged: (value) { _set(field.path, value); _changed(''); }));
      case 'choice':
        final choices = field.choices ?? const <(String, String)>[];
        final current = switch (field.path) {
          'multiplex.__state' => _muxState,
          'multiplex.__mode' => _muxMode,
          _ => _text(field.path),
        };
        final label = choices.firstWhere((choice) => choice.$1 == current,
            orElse: () => (current, current)).$2;
        return ListItem(title: Text(context.tr(field.label)), trailing: Text(label,
          maxLines: 1, overflow: TextOverflow.ellipsis),
          onTap: () async {
            final picked = await showDialog<String>(context: context, builder: (context) => SimpleDialog(
              title: Text(context.tr(field.label)),
              children: [
                for (final (value, label) in choices) SimpleDialogOption(
                  onPressed: () => Navigator.pop(context, value), child: Text(label)),
              ],
            ));
            if (picked == null || !mounted) return;
            setState(() {
              switch (field.path) {
                case 'multiplex.__state':
                  _applyMuxState(picked);
                case 'multiplex.__mode':
                  _set('__mode', picked);
                case 'tls.utls.fingerprint':
                  _set('tls.utls.fingerprint', picked.isEmpty ? null : picked);
                  _set('tls.utls.enabled', picked.isNotEmpty ? true : null);
                default:
                  _set(field.path, picked.isEmpty ? null : picked);
              }
              _changed('');
            });
          });
      case 'int':
        final controller = TextEditingController(text: _int(field.path) > 0 ? '${_int(field.path)}' : '');
        return FormRow(title: context.tr(field.label), invalid: invalid,
          trailing: Row(mainAxisSize: MainAxisSize.min, spacing: 4, children: [
            Flexible(child: FormRowTextField(
              controller: controller,
              keyboardType: TextInputType.number, textInputAction: TextInputAction.next,
              inputFormatters: [FilteringTextInputFormatter.digitsOnly],
              onChanged: (value) { _set(field.path, int.tryParse(value.trim()) ?? 0); _changed(value); })),
          ]));
      case 'lines':
        final controller = TextEditingController(text: _lines(field.path).join('\n'));
        return FormRow(title: context.tr(field.label), invalid: invalid,
          trailing: FormRowTextField(controller: controller, hintText: field.hint,
            onChanged: (value) {
              _set(field.path, value.split('\n').map((line) => line.trim()).where((line) => line.isNotEmpty).toList());
              _changed(value);
            }));
      case 'list':
        final controller = TextEditingController(text: _lines(field.path).join(','));
        return FormRow(title: context.tr(field.label), invalid: invalid,
          trailing: FormRowTextField(controller: controller,
            onChanged: (value) {
              _set(field.path, value.split(',').map((item) => item.trim()).where((item) => item.isNotEmpty).toList());
              _changed(value);
            }));
      case 'headers':
        final map = _get(field.path);
        final text = map is Json ? map.entries.map((e) => '${e.key}: ${e.value}').join('\n') : '';
        final controller = TextEditingController(text: text);
        return FormRow(title: context.tr(field.label), invalid: invalid,
          trailing: FormRowTextField(controller: controller,
            onChanged: (value) {
              final headers = <String, dynamic>{};
              for (final line in value.split('\n')) {
                final separator = line.indexOf(':');
                if (separator <= 0) continue;
                headers[line.substring(0, separator).trim()] = line.substring(separator + 1).trim();
              }
              _set(field.path, headers.isEmpty ? null : headers);
              _changed(value);
            }));
      default:
        final isPassword = field.kind == 'password';
        final controller = TextEditingController(text: _text(field.path));
        return FormRow(title: context.tr(field.label), invalid: invalid,
          trailing: FormRowTextField(controller: controller, hintText: field.hint,
            obscureText: isPassword, autocorrect: false, enableSuggestions: false,
            onChanged: (value) { _set(field.path, value.trim().isEmpty ? null : value); _changed(value); }));
    }
  }

  /// The state menu writes what the core reads: off = `enabled: false`, on = `enabled: true` with the
  /// rest cleared, custom = enabled with the fields as shown.
  void _applyMuxState(String state) {
    switch (state) {
      case '' || 'on':
        _set('multiplex', state == 'on' ? <String, dynamic>{'enabled': true} : null);
      case 'off':
        _set('multiplex', <String, dynamic>{'enabled': false});
      case 'custom':
        _set('multiplex', {
          'enabled': true,
          'protocol': _text('multiplex.protocol').isEmpty ? 'h2mux' : _text('multiplex.protocol'),
          if (_muxMode == 'connections') 'max_connections': _int('multiplex.max_connections'),
          'min_streams': _int('multiplex.min_streams'),
          'padding': _bool('multiplex.padding'),
          'brutal': {
            'enabled': _bool('multiplex.brutal.enabled'),
            if (_bool('multiplex.brutal.enabled')) ...{
              'up_mbps': _int('multiplex.brutal.up_mbps'),
              'down_mbps': _int('multiplex.brutal.down_mbps'),
            },
          },
        });
    }
  }

  @override Widget build(BuildContext context) {
    if (_loading) return CommonScaffold(title: context.tr('Edit settings'), isLoading: true, body: const SizedBox.shrink());
    if (!_supported) return CommonScaffold(title: context.tr('Edit settings'), body: NullStatus(
      label: context.tr('No typed form for this protocol'),
      description: context.tr('Use the node editor to change the configuration text.'),
    ));
    final issue = _attempted ? _issue : null;
    final schemas = _schemas();
    return FormPage(
      title: 'Edit settings', saving: _saving, onSave: _save,
      error: _error ?? issue?.message,
      children: [
        for (final section in schemas) ...[
          if (section.fields.any(_visible)) generateSection(
            title: section.title == null ? null : context.tr(section.title!),
            items: [for (final field in section.fields) if (_visible(field)) _fieldRow(field)],
          ),
        ],
      ],
    );
  }
}

/// One typed field at a sing-box JSON [path]; [showIf] keeps the row for the listed values of
/// another path (comma separated), mirroring the native screens' visibility wiring.
class _Field {
  const _Field(this.path, this.label, this.kind, {this.choices, this.required = false, this.hint, this.showIf});
  final String path;
  final String label;
  final String kind;
  final List<(String, String)>? choices;
  final bool required;
  final String? hint;
  final (String, String)? showIf;
}

class _Section {
  const _Section(this.title, this.fields);
  final String? title;
  final List<_Field> fields;
}

import '../widgets.dart';
import '../components/form.dart';

/// Route profiles: the named rule sets a configuration is built with, one of them the global choice.
///
/// The list shows every profile and which one is current; the editor holds the name, the default
/// outbound, the remote subscription and the simple address rules as `prefix:value -> outbound`
/// lines — the same shape the native importer accepts.
class RouteProfilesPage extends StatefulWidget {
  const RouteProfilesPage({super.key});
  @override
  State<RouteProfilesPage> createState() => _RouteProfilesPageState();
}

class _RouteProfilesPageState extends State<RouteProfilesPage> {
  List<Json> _profiles = const [];
  int _currentId = 0;
  bool _loading = true;

  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addPostFrameCallback((_) => _load());
  }

  Future<void> _load() async {
    try {
      final data = json(await context.box.call('routes.list'));
      if (!mounted) return;
      setState(() {
        _profiles = (data['profiles'] as List? ?? const []).map((item) => json(item)).toList();
        _currentId = (data['currentId'] as num?)?.toInt() ?? 0;
        _loading = false;
      });
    } catch (error) {
      if (mounted) setState(() => _loading = false);
      if (mounted) context.box.report(error);
    }
  }

  Future<void> _open(Json profile) async {
    await openPage(context, RouteEditorPage(id: (profile['id'] as num?)?.toInt() ?? 0));
    if (mounted) await _load();
  }

  @override
  Widget build(BuildContext context) {
    return BoxPage(title: 'Route profiles', children: [
      if (_loading)
        generateSection(items: [ListItem(title: Text(context.tr('Loading')))])
      else ...[
        generateSection(items: [
          for (final profile in _profiles)
            ListItem(
              title: Text(profile['name'].toString().isEmpty ? context.tr('Untitled') : profile['name'].toString()),
              subtitle: Text(_summary(profile)),
              trailing: (profile['id'] as num?)?.toInt() == _currentId
                  ? const GlyphIcon(AppGlyphs.checkCircle)
                  : null,
              onTap: () => _open(profile),
            ),
        ], footer: context.tr('The current profile builds every configuration that has no group override.')),
        generateSection(items: [
          ListItem(
            leading: const GlyphIcon(AppGlyphs.add),
            title: Text(context.tr('Add route profile')),
            onTap: () => _open(const {'id': 0}),
          ),
        ]),
      ],
    ]);
  }

  String _summary(Json profile) {
    final rules = (profile['ruleCount'] as num?)?.toInt() ?? 0;
    final marks = <String>[
      if (profile['isRaw'] == true) context.tr('Read-only'),
      if (profile['isRemote'] == true) context.tr('Remote'),
      '$rules ${context.tr('rules')}',
    ];
    return marks.join(' · ');
  }
}

/// The profile editor. Save rebuilds the simple address rules from the text and leaves every other
/// rule kind in place.
class RouteEditorPage extends StatefulWidget {
  const RouteEditorPage({super.key, required this.id});
  final int id;
  @override
  State<RouteEditorPage> createState() => _RouteEditorPageState();
}

class _RouteEditorPageState extends State<RouteEditorPage> {
  late final TextEditingController _name;
  late final TextEditingController _url;
  String _defaultOutbound = 'direct';
  bool _isRemote = false;
  bool _autoUpdate = false;
  bool _current = false;
  bool _isRaw = false;
  bool _saving = false;
  String? _error;
  String _endpointIds = '';
  List<Json> _rules = const [];

  @override
  void initState() {
    super.initState();
    _name = TextEditingController();
    _url = TextEditingController();
    WidgetsBinding.instance.addPostFrameCallback((_) => _load());
  }

  @override
  void dispose() {
    _name.dispose();
    _url.dispose();
    super.dispose();
  }

  Future<void> _load() async {
    final box = context.box;
    try {
      final data = json(await box.call('routes.get', {'id': widget.id}));
      final list = json(await box.call('routes.list'));
      if (!mounted) return;
      setState(() {
        _name.text = data['name']?.toString() ?? '';
        _defaultOutbound = data['defaultOutbound']?.toString() ?? 'direct';
        _isRemote = data['isRemote'] == true;
        _url.text = data['remoteUrl']?.toString() ?? '';
        _autoUpdate = data['autoUpdate'] == true;
        _isRaw = data['isRaw'] == true;
        _endpointIds = (data['endpointIds'] as List? ?? const []).join(', ');
        _current = (list['currentId'] as num?)?.toInt() == widget.id;
      });
      await _loadRules();
    } catch (error) {
      if (mounted) setState(() => _error = BoxController.describe(error));
    }
  }

  Future<void> _loadRules() async {
    if (widget.id == 0) return;
    final box = context.box;
    try {
      final data = json(await box.call('routes.rules', {'id': widget.id}));
      if (!mounted) return;
      setState(() => _rules = (data['rules'] as List? ?? const []).map((e) => json(e)).toList());
    } catch (error) {
      if (mounted) context.box.report(error);
    }
  }

  Future<void> _editRule(int index) async {
    if (widget.id == 0) {
      context.box.report(StateError(context.tr('Save the profile first')));
      return;
    }
    final added = await openPage<bool>(context, RuleEditorPage(
      profileId: widget.id,
      index: index,
      rule: index < 0 ? const {} : _rules[index],
      rules: _rules,
    ));
    if (added == true && mounted) await _loadRules();
  }

  Future<void> _save() async {
    setState(() { _saving = true; _error = null; });
    try {
      await context.box.call('routes.save', {'value': {
        'id': widget.id,
        'name': _name.text,
        'defaultOutbound': _defaultOutbound,
        'isRemote': _isRemote,
        'remoteUrl': _url.text,
        'autoUpdate': _autoUpdate,
        'endpointIds': _endpointIds.split(',').map((e) => int.tryParse(e.trim())).whereType<int>().toList(),
      }});
      if (mounted) context.safeNestedPop();
    } catch (error) {
      if (mounted) setState(() { _saving = false; _error = BoxController.describe(error); });
    }
  }

  Future<void> _delete() async {
    final confirmed = await showDialog<bool>(context: context, builder: (context) => AlertDialog(
      title: Text(context.tr('Delete route profile?')),
      content: Text(_name.text),
      actions: [
        TextButton(onPressed: () => Navigator.pop(context, false), child: Text(context.tr('Cancel'))),
        TextButton(onPressed: () => Navigator.pop(context, true), child: Text(context.tr('Delete'))),
      ],
    ));
    if (confirmed != true || !mounted) return;
    try {
      await context.box.call('routes.delete', {'id': widget.id});
      if (mounted) { context.safeNestedPop(); }
    } catch (error) {
      if (mounted) context.box.report(error);
    }
  }

  @override
  Widget build(BuildContext context) {
    return FormPage(
      title: widget.id == 0 ? 'Add route profile' : 'Edit',
      saving: _saving,
      onSave: _isRaw ? null : _save,
      error: _error,
      actions: [
        if (widget.id > 0 && !_isRaw)
          IconButtonData(glyph: AppGlyphs.delete, tooltip: 'Delete', onPressed: _delete),
      ],
      children: [
        if (_isRaw) generateSection(items: [
          ListItem(
            leading: const GlyphIcon(AppGlyphs.info),
            title: Text(context.tr('Read-only')),
            subtitle: Text(context.tr('This profile is built in and cannot be changed here.')),
          ),
        ]),
        generateSection(items: [
          FormRow(title: context.tr('Name'), trailing: FormRowTextField(
            controller: _name, hintText: context.tr('Required'),
            autocorrect: false, enableSuggestions: false,
          )),
          ListItem(
            title: Text(context.tr('Default outbound')),
            trailing: Text(context.tr(_outboundLabel(_defaultOutbound))),
            onTap: () async {
              final picked = await showDialog<String>(context: context, builder: (context) => SimpleDialog(
                title: Text(context.tr('Default outbound')),
                children: [
                  for (final value in const ['proxy', 'direct', 'block', 'warp-bypass'])
                    SimpleDialogOption(
                      onPressed: () => Navigator.pop(context, value),
                      child: Text(context.tr(_outboundLabel(value))),
                    ),
                ],
              ));
              if (picked != null && mounted) setState(() => _defaultOutbound = picked);
            },
          ),
        ]),
        generateSection(title: context.tr('Remote'), items: [
          ListItem.toggle(
            title: Text(context.tr('Remote profile')),
            value: _isRemote,
            onChanged: (value) => setState(() {
              _isRemote = value;
              if (!value) _autoUpdate = false;
            }),
          ),
          if (_isRemote) FormRow(title: context.tr('URL'), trailing: FormRowTextField(
            controller: _url, hintText: 'https://', keyboardType: TextInputType.url,
            autocorrect: false, enableSuggestions: false,
          )),
          if (_isRemote) ListItem.toggle(
            title: Text(context.tr('Auto update')),
            value: _autoUpdate,
            onChanged: (value) => setState(() => _autoUpdate = value),
          ),
        ], footer: context.tr('A remote profile downloads its rules from the URL.')),
        generateSection(title: context.tr('Endpoints'), items: [
          FormRow(title: context.tr('Endpoint ids'), trailing: FormRowTextField(
            initialValue: _endpointIds,
            hintText: '1, 2, 3',
            keyboardType: TextInputType.number,
            autocorrect: false, enableSuggestions: false,
            onChanged: (value) => _endpointIds = value,
          )),
        ], footer: context.tr('Profile ids whose endpoints this route can prefer; used by preferred-by rules.')),
        generateSection(title: context.tr('Rules'), items: [
          for (final (index, rule) in _rules.indexed)
            ListItem(
              title: Text(_ruleName(rule, index)),
              subtitle: Text(_ruleSummary(rule)),
              onTap: _isRaw ? null : () => _editRule(index),
            ),
          if (!_isRaw) ListItem(
            leading: const GlyphIcon(AppGlyphs.add),
            title: Text(context.tr('Add rule')),
            onTap: () => _editRule(-1),
          ),
        ], footer: _isRaw
            ? context.tr('This profile is built in and cannot be changed here.')
            : context.tr('Rules run top to bottom; the first match wins.')),
        if (widget.id > 0) generateSection(items: [
          ListItem.toggle(
            title: Text(context.tr('Use this profile')),
            value: _current,
            onChanged: (value) async {
              if (!value) return;
              final box = context.box;
              try {
                await box.call('routes.setCurrent', {'id': widget.id});
                if (mounted) setState(() => _current = true);
              } catch (error) {
                if (mounted) box.report(error);
              }
            },
          ),
        ]),
      ],
    );
  }

  String _outboundLabel(String value) => switch (value) {
    'proxy' => 'Proxy',
    'block' => 'Block',
    'warp-bypass' => 'WARP bypass',
    _ => 'Direct',
  };

  String _ruleName(Json rule, int index) {
    final name = rule['name']?.toString() ?? '';
    if (name.isNotEmpty) return name;
    for (final key in const ['domain', 'domain_suffix', 'ip_cidr', 'port', 'rule_set', 'package_name']) {
      final list = rule[key];
      if (list is List && list.isNotEmpty) return '${list.first}';
    }
    return '#${index + 1}';
  }

  String _ruleSummary(Json rule) {
    final action = rule['action']?.toString() ?? 'route';
    final conditions = <String>[];
    for (final key in const [
      'domain', 'domain_suffix', 'domain_keyword', 'domain_regex', 'ip_cidr', 'source_ip_cidr',
      'port', 'port_range', 'process_name', 'package_name', 'rule_set',
    ]) {
      final list = rule[key];
      if (list is List && list.isNotEmpty) conditions.add('$key(${list.length})');
    }
    if (rule['invert'] == true) conditions.add(context.tr('Inverted'));
    return conditions.isEmpty ? action : '$action · ${conditions.join(' ')}';
  }
}

/// One route rule as a form: the match attributes on top, the action fields below, exactly the
/// members the stored [RouteRule] carries. Values of list members are comma-separated text here.
class RuleEditorPage extends StatefulWidget {
  const RuleEditorPage({super.key, required this.profileId, required this.index, required this.rule, required this.rules});
  final int profileId, index;
  final Json rule;
  final List<Json> rules;
  @override
  State<RuleEditorPage> createState() => _RuleEditorPageState();
}

class _RuleEditorPageState extends State<RuleEditorPage> {
  late Json _rule;
  bool _saving = false;
  String? _error;

  @override
  void initState() {
    super.initState();
    _rule = {
      ...widget.rule,
      if (widget.index < 0) 'name': '',
      if (widget.index < 0) 'type': 'custom',
      if (widget.index < 0) 'action': 'route',
      if (widget.index < 0) 'outbound_id': -2,
    };
  }

  void _set(String key, Object? value) => setState(() => _rule = {..._rule, key: value});

  List<String> _listOf(String key) =>
      (_rule[key] as List? ?? const []).map((e) => '$e').toList();

  void _setList(String key, String text) => _set(key, text
      .split(',')
      .map((e) => e.trim())
      .where((e) => e.isNotEmpty)
      .toList());

  Future<void> _save() async {
    setState(() { _saving = true; _error = null; });
    final box = context.box;
    try {
      final rules = [...widget.rules];
      if (widget.index < 0) {
        rules.add(_rule);
      } else {
        rules[widget.index] = _rule;
      }
      await box.call('routes.saveRules', {'id': widget.profileId, 'rules': rules});
      if (mounted) context.safeNestedPop(true);
    } catch (error) {
      if (!mounted) return;
      setState(() { _saving = false; _error = BoxController.describe(error); });
    }
  }

  Future<void> _delete() async {
    if (widget.index < 0) { context.safeNestedPop(false); return; }
    final confirmed = await showDialog<bool>(context: context, builder: (context) => AlertDialog(
      title: Text(context.tr('Delete rule?')),
      content: Text(_rule['name']?.toString() ?? '#${widget.index + 1}'),
      actions: [
        TextButton(onPressed: () => Navigator.pop(context, false), child: Text(context.tr('Cancel'))),
        TextButton(onPressed: () => Navigator.pop(context, true), child: Text(context.tr('Delete'))),
      ],
    ));
    if (confirmed != true || !mounted) return;
    final box = context.box;
    try {
      final rules = [...widget.rules]..removeAt(widget.index);
      await box.call('routes.saveRules', {'id': widget.profileId, 'rules': rules});
      if (mounted) context.safeNestedPop(true);
    } catch (error) {
      if (mounted) box.report(error);
    }
  }

  @override
  Widget build(BuildContext context) {
    final outboundId = (_rule['outbound_id'] as num?)?.toInt() ?? -2;
    final typeToken = _rule['type']?.toString() ?? 'custom';
    final isSimple = typeToken.startsWith('simple_');
    final isEndpoint = typeToken == 'endpoint_preferred_by';
    final simpleAction = isSimple ? _actionFromToken(typeToken) : '';
    final simpleKind = isSimple
        ? typeToken.substring('simple_'.length, typeToken.length - simpleAction.length - 1)
        : '';
    final typeLabel = isSimple ? _simpleKindName(simpleKind)
        : isEndpoint ? 'Endpoint preferred by' : 'Custom';
    return FormPage(
      title: widget.index < 0 ? 'Add rule' : 'Edit rule',
      saving: _saving,
      onSave: _save,
      error: _error,
      actions: [
        IconButtonData(glyph: AppGlyphs.delete, tooltip: 'Delete', onPressed: _delete),
      ],
      children: [
        generateSection(title: context.tr('Rule'), items: [
          FormRow(title: context.tr('Name'), trailing: FormRowTextField(
            initialValue: '${_rule['name'] ?? ''}', hintText: context.tr('Optional'),
            autocorrect: false, enableSuggestions: false,
            onChanged: (value) => _set('name', value),
          )),
          ListItem.toggle(
            title: Text(context.tr('Disabled')),
            value: _rule['disabled'] == true,
            onChanged: (value) => _set('disabled', value),
          ),
          ListItem(
            title: Text(context.tr('Type')),
            trailing: Text(context.tr(typeLabel)),
            onTap: () async {
              final picked = await showDialog<String>(context: context, builder: (context) => SimpleDialog(
                title: Text(context.tr('Type')),
                children: [
                  for (final (value, label) in [
                    ('custom', 'Custom'), ('address', 'Simple address'),
                    ('process_name', 'Simple process name'), ('process_path', 'Simple process path'),
                    ('endpoint_preferred_by', 'Endpoint preferred by'),
                  ])
                    SimpleDialogOption(
                      onPressed: () => Navigator.pop(context, value),
                      child: Text(context.tr(label)),
                    ),
                ],
              ));
              if (picked == null) return;
              if (picked == 'custom' || picked == 'endpoint_preferred_by') {
                setState(() => _rule = {..._rule, 'type': picked});
              } else {
                _applySimple(picked, simpleAction.isEmpty ? 'proxy' : simpleAction);
              }
            },
          ),
          if (isSimple) ListItem(
            title: Text(context.tr('Target')),
            trailing: Text(context.tr(_simpleActionName(simpleAction))),
            onTap: () async {
              final picked = await showDialog<String>(context: context, builder: (context) => SimpleDialog(
                title: Text(context.tr('Target')),
                children: [
                  for (final value in const ['proxy', 'bypass', 'block', 'warp_bypass'])
                    SimpleDialogOption(
                      onPressed: () => Navigator.pop(context, value),
                      child: Text(context.tr(_simpleActionName(value))),
                    ),
                ],
              ));
              if (picked != null) _applySimple(simpleKind, picked);
            },
          ),
          if (!isSimple) ListItem(
            title: Text(context.tr(isEndpoint ? 'Preferred endpoint' : 'Outbound')),
            trailing: Text(_outboundName(outboundId)),
            onTap: () async {
              final picked = await showDialog<int>(context: context, builder: _outboundDialog);
              if (picked != null) _set('outbound_id', picked);
            },
          ),
        ], footer: isSimple
            ? context.tr('Simple rules share one outbound and one action; the match goes in the fields below.')
            : isEndpoint
                ? context.tr('This rule names one endpoint as preferred; it has no match conditions.')
                : null),
        if (!isEndpoint && (!isSimple || simpleKind == 'address')) generateSection(title: context.tr('Domains'), items: [
          _csvRow('Domain', 'domain'),
          _csvRow('Domain suffix', 'domain_suffix'),
          _csvRow('Domain keyword', 'domain_keyword'),
          _csvRow('Domain regex', 'domain_regex'),
        ]),
        if (!isEndpoint && (!isSimple || simpleKind == 'address')) generateSection(title: context.tr('IP and ports'), items: [
          _csvRow('IP CIDR', 'ip_cidr'),
          _csvRow('Source IP CIDR', 'source_ip_cidr'),
          _csvRow('Rule set', 'rule_set'),
          _csvRow('Port', 'port'),
          _csvRow('Port range', 'port_range'),
          _csvRow('Source port', 'source_port'),
          _csvRow('Source port range', 'source_port_range'),
          ListItem.toggle(
            title: Text(context.tr('Source IP is private')),
            value: _rule['source_ip_is_private'] == true,
            onChanged: (value) => _set('source_ip_is_private', value),
          ),
          ListItem.toggle(
            title: Text(context.tr('IP is private')),
            value: _rule['ip_is_private'] == true,
            onChanged: (value) => _set('ip_is_private', value),
          ),
        ]),
        if (!isEndpoint && (!isSimple || simpleKind == 'process_name' || simpleKind == 'process_path'))
          generateSection(title: context.tr('Processes and apps'), items: [
            if (!isSimple || simpleKind == 'process_name') _csvRow('Process name', 'process_name'),
            if (!isSimple || simpleKind == 'process_path') _csvRow('Process path', 'process_path'),
            if (!isSimple) ...[
              _csvRow('Process path regex', 'process_path_regex'),
              _csvRow('Package name', 'package_name'),
              _csvRow('Package name regex', 'package_name_regex'),
            ],
          ]),
        if (!isSimple && !isEndpoint) generateSection(title: context.tr('Network'), items: [
          _csvRow('Inbound', 'inbound'),
          _csvRow('Network type', 'network_type'),
          _csvRow('Wi-Fi SSID', 'wifi_ssid'),
          _csvRow('Wi-Fi BSSID', 'wifi_bssid'),
          _textRow('IP version', 'ip_version', hint: 'ipv4 / ipv6'),
          _textRow('Network', 'network', hint: 'tcp / udp'),
          _textRow('Protocol', 'protocol'),
          ListItem.toggle(
            title: Text(context.tr('Network is expensive')),
            value: _rule['network_is_expensive'] == true,
            onChanged: (value) => _set('network_is_expensive', value),
          ),
          ListItem.toggle(
            title: Text(context.tr('Invert the match')),
            value: _rule['invert'] == true,
            onChanged: (value) => _set('invert', value),
          ),
        ]),
        if (!isSimple && !isEndpoint) generateSection(title: context.tr('Action'), items: [
          ListItem(
            title: Text(context.tr('Action')),
            trailing: Text(_rule['action']?.toString() ?? 'route'),
            onTap: () async {
              final picked = await showDialog<String>(context: context, builder: (context) => SimpleDialog(
                title: Text(context.tr('Action')),
                children: [
                  for (final value in const ['route', 'reject', 'sniff', 'resolve', 'route-options', 'bypass'])
                    SimpleDialogOption(onPressed: () => Navigator.pop(context, value), child: Text(value)),
                ],
              ));
              if (picked != null) _set('action', picked);
            },
          ),
          _csvRow('Reject method', 'reject_method', hint: 'default / drop / reply'),
          ListItem.toggle(
            title: Text(context.tr('No drop')),
            value: _rule['no_drop'] == true,
            onChanged: (value) => _set('no_drop', value),
          ),
          _textRow('Override address', 'override_address'),
          _textRow('Override port', 'override_port'),
          ListItem.toggle(
            title: Text(context.tr('TLS fragment')),
            value: _rule['tls_fragment'] == true,
            onChanged: (value) => _set('tls_fragment', value),
          ),
          _textRow('TLS fragment fallback delay', 'tls_fragment_fallback_delay'),
          ListItem.toggle(
            title: Text(context.tr('TLS record fragment')),
            value: _rule['tls_record_fragment'] == true,
            onChanged: (value) => _set('tls_record_fragment', value),
          ),
          _textRow('TLS spoof', 'tls_spoof'),
          _textRow('TLS spoof method', 'tls_spoof_method'),
          _csvRow('Sniffers', 'sniffers'),
          ListItem.toggle(
            title: Text(context.tr('Sniff override destination')),
            value: _rule['sniff_override_dest'] == true,
            onChanged: (value) => _set('sniff_override_dest', value),
          ),
          _textRow('Strategy', 'strategy', hint: 'prefer_ipv4 / prefer_ipv6 / …'),
        ]),
      ],
    );
  }

  Widget _csvRow(String title, String key, {String? hint}) => FormRow(
    title: context.tr(title),
    trailing: FormRowTextField(
      initialValue: _listOf(key).join(', '),
      hintText: hint ?? context.tr('Comma-separated'),
      autocorrect: false, enableSuggestions: false,
      onChanged: (value) => _setList(key, value),
    ),
  );

  Widget _textRow(String title, String key, {String? hint}) => FormRow(
    title: context.tr(title),
    trailing: FormRowTextField(
      initialValue: '${_rule[key] ?? ''}',
      hintText: hint,
      autocorrect: false, enableSuggestions: false,
      onChanged: (value) => _set(key, value),
    ),
  );

  String _outboundName(int id) => switch (id) {
    -1 => 'Proxy',
    -3 => 'Block',
    -5 => 'WARP bypass',
    -2 => 'Direct',
    > 0 => '#$id',
    _ => '$id',
  };

  /// The action suffix of a simple token (`warp_bypass` contains an underscore, so match longest first).
  static String _actionFromToken(String token) {
    for (final action in const ['warp_bypass', 'proxy', 'bypass', 'block']) {
      if (token.endsWith('_$action')) return action;
    }
    return '';
  }

  /// A simple rule's kind is its match and its action its fixed target: `simple_<kind>_<action>`.
  /// Block rejects (action=reject, outbound BLOCK); the rest route with the matching outbound.
  void _applySimple(String kind, String action) {
    final token = 'simple_${kind}_$action';
    final isBlock = action == 'block';
    setState(() => _rule = {
      ..._rule,
      'type': token,
      'action': isBlock ? 'reject' : 'route',
      'outbound_id': switch (action) {
        'proxy' => -1, 'bypass' => -2, 'warp_bypass' => -5, _ => -3,
      },
    });
  }

  String _simpleKindName(String kind) => switch (kind) {
    'address' => 'Simple address',
    'process_name' => 'Simple process name',
    'process_path' => 'Simple process path',
    _ => 'Custom',
  };

  String _simpleActionName(String action) => switch (action) {
    'proxy' => 'Proxy',
    'bypass' => 'Direct',
    'block' => 'Block',
    'warp_bypass' => 'WARP bypass',
    _ => 'Proxy',
  };

  Widget _outboundDialog(BuildContext context) {
    final nodes = <Json>[];
    for (final group in context.box.groups) {
      final items = group['items'];
      if (items is List) nodes.addAll(items.map(json));
    }
    return SimpleDialog(
      title: Text(context.tr('Outbound')),
      children: [
        for (final (id, label) in [(-1, 'Proxy'), (-2, 'Direct'), (-3, 'Block'), (-5, 'WARP bypass')])
          SimpleDialogOption(onPressed: () => Navigator.pop(context, id), child: Text(context.tr(label))),
        for (final node in nodes)
          SimpleDialogOption(
            onPressed: () => Navigator.pop(context, (node['id'] as num?)?.toInt()),
            child: Text('${node['tag']}'),
          ),
      ],
    );
  }
}

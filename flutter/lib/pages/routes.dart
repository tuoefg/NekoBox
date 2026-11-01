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

  /// The official routeprofiles bundles: pick a country and its remote profiles are added,
  /// prefilled from the snapshot and fetched online — the native "Download profiles" flow.
  Future<void> _addPresets() async {
    final box = context.box;
    List<Json> bundles;
    try {
      final data = json(await box.call('routes.presets'));
      bundles = (data['bundles'] as List? ?? const []).map((e) => json(e)).toList();
    } catch (error) {
      box.report(error);
      return;
    }
    if (!mounted) return;
    if (bundles.isEmpty) {
      box.report(StateError(context.tr('No official profiles are bundled in this build')));
      return;
    }
    final picked = await showDialog<Json>(context: context, builder: (context) => SimpleDialog(
      title: Text(context.tr('Throne official profiles')),
      children: [
        for (final bundle in bundles)
          SimpleDialogOption(
            onPressed: () => Navigator.pop(context, bundle),
            child: Text('${bundle['country']} (${((bundle['entries'] as List?) ?? const []).length})'),
          ),
      ],
    ));
    if (picked == null || !mounted) return;
    try {
      final result = json(await box.call('routes.addPreset', {
        'country': picked['country'], 'autoUpdate': true,
      }));
      final added = (result['added'] as num?)?.toInt() ?? 0;
      if (mounted) {
        ScaffoldMessenger.of(context).showSnackBar(SnackBar(
          content: Text('$added ${context.tr('route profiles added')}'),
        ));
        await _load();
      }
    } catch (error) {
      box.report(error);
    }
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
          ListItem(
            leading: const GlyphIcon(AppGlyphs.cloudDownload),
            title: Text(context.tr('Throne official profiles')),
            onTap: _addPresets,
          ),
        ], footer: context.tr('The official profiles are remote route profiles of the routeprofiles repository.')),
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

  Future<void> _batchAdd() async {
    if (widget.id == 0) {
      context.box.report(StateError(context.tr('Save the profile first')));
      return;
    }
    final text = await showDialog<String>(context: context, builder: (context) => const _BatchAddDialog());
    if (text == null || text.trim().isEmpty || !mounted) return;
    final box = context.box;
    try {
      await box.call('routes.batchAddRules', {'id': widget.id, 'text': text});
      if (mounted) await _loadRules();
    } catch (error) {
      if (mounted) box.report(error);
    }
  }

  /// The Default chain's own rules — one hijack-dns rule that routes DNS. Replaces the list outright.
  Future<void> _resetRules() async {
    if (widget.id == 0) {
      context.box.report(StateError(context.tr('Save the profile first')));
      return;
    }
    final ok = await confirm(context, 'Restore default rules?',
        message: 'The rules become the Default profile\'s own: one hijack-dns rule that routes DNS.',
        action: 'Restore');
    if (!ok || !mounted) return;
    final box = context.box;
    try {
      await box.call('routes.resetRules', {'id': widget.id});
      if (mounted) await _loadRules();
    } catch (error) {
      if (mounted) box.report(error);
    }
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
              title: Text(ruleName(rule, index)),
              subtitle: Text(ruleSummary(context, rule)),
              onTap: _isRaw ? null : () => _editRule(index),
            ),
          if (!_isRaw) ListItem(
            leading: const GlyphIcon(AppGlyphs.add),
            title: Text(context.tr('Add rule')),
            onTap: () => _editRule(-1),
          ),
          if (!_isRaw && _rules.length >= 2) ListItem(
            leading: const GlyphIcon(AppGlyphs.sort),
            title: Text(context.tr('Reorder rules')),
            subtitle: Text(context.tr('Drag a rule to change the order; the first match wins')),
            onTap: () async {
              final changed = await openPage<bool>(context, RuleOrderPage(profileId: widget.id, rules: _rules));
              if (changed == true && mounted) await _loadRules();
            },
          ),
          if (!_isRaw) ListItem(
            leading: const GlyphIcon(AppGlyphs.importFile),
            title: Text(context.tr('Batch add simple rules')),
            onTap: _batchAdd,
          ),
          if (!_isRaw) ListItem(
            leading: const GlyphIcon(AppGlyphs.reset),
            title: Text(context.tr('Restore default rules')),
            onTap: _resetRules,
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
}

/// A rule's row title: its name, or the first match value, or its position.
String ruleName(Json rule, int index) {
  final name = rule['name']?.toString() ?? '';
  if (name.isNotEmpty) return name;
  for (final key in const ['domain', 'domain_suffix', 'ip_cidr', 'port', 'rule_set', 'package_name']) {
    final list = rule[key];
    if (list is List && list.isNotEmpty) return '${list.first}';
  }
  return '#${index + 1}';
}

/// A rule's row subtitle: the action plus a count per non-empty match list.
String ruleSummary(BuildContext context, Json rule) {
  final action = rule['action']?.toString() ?? 'route';
  final conditions = <String>[];
  for (final key in const [
    'domain', 'domain_suffix', 'domain_keyword', 'domain_regex', 'ip_cidr', 'source_ip_cidr',
    'port', 'port_range', 'process_name', 'package_name', 'rule_set', 'network_type',
    'wifi_ssid', 'wifi_bssid',
  ]) {
    final list = rule[key];
    if (list is List && list.isNotEmpty) conditions.add('$key(${list.length})');
  }
  if (rule['network_is_expensive'] == true) conditions.add(context.tr('Metered'));
  if (rule['invert'] == true) conditions.add(context.tr('Inverted'));
  return conditions.isEmpty ? action : '$action · ${conditions.join(' ')}';
}

/// The rules in drag order. The list *is* the profile's rule order, so a reorder saves the same
/// rules permuted — the bridge stores rules in list order and nothing else changes.
class RuleOrderPage extends StatefulWidget {
  const RuleOrderPage({super.key, required this.profileId, required this.rules});
  final int profileId;
  final List<Json> rules;
  @override
  State<RuleOrderPage> createState() => _RuleOrderPageState();
}

class _RuleOrderPageState extends State<RuleOrderPage> {
  late final List<Json> _rules = List.of(widget.rules);
  bool _saving = false;

  Widget _item(int index) => ReorderableDelayedDragStartListener(
    key: ObjectKey(_rules[index]), index: index,
    child: ItemPositionProvider(position: ItemPosition.get(index, _rules.length),
      child: DecorationListItem(contentPadding: const EdgeInsets.only(left: 16),
        title: Text(ruleName(_rules[index], index)),
        subtitle: Text(ruleSummary(context, _rules[index])),
        trailing: SortHandle(index: index))),
  );

  @override
  Widget build(BuildContext context) => CommonScaffold(
    title: context.tr('Reorder rules'),
    confirmationAction: IconButtonData(glyph: AppGlyphs.check, tooltip: context.tr('Save'), isLoading: _saving,
      onPressed: _saving ? null : () async {
        setState(() => _saving = true);
        try {
          await context.box.call('routes.saveRules', {'id': widget.profileId, 'rules': _rules});
          if (mounted) context.safeNestedPop(true);
        } catch (error) {
          if (mounted) { setState(() => _saving = false); context.box.report(error); }
        }
      }),
    body: AbsorbPointer(absorbing: _saving, child: ReorderableListView.builder(
      buildDefaultDragHandles: false,
      padding: EdgeInsets.fromLTRB(16, context.contentTopPadding, 16, 32 + context.bottomInset),
      itemCount: _rules.length,
      proxyDecorator: (child, index, animation) => commonProxyDecorator(_item(index), index, animation),
      onReorderItem: (old, next) => setState(() => _rules.insert(next, _rules.removeAt(old))),
      itemBuilder: (_, index) => _item(index),
    )),
  );
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
          _ruleSetRow(),
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
          _networkTypeRow(),
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

  /// sing-box's network types as a multi-choice, the way the native rule editor offers them
  /// (RouteRuleActivity's multi-choice dialog over route_rule_network_type_entries); the empty
  /// selection means the condition does not narrow the match, as upstream.
  Widget _networkTypeRow() {
    const options = ['wifi', 'cellular', 'ethernet', 'other'];
    final current = _listOf('network_type');
    return ListItem(
      title: Text(context.tr('Network type')),
      subtitle: Text(current.isEmpty ? context.tr('Any') : current.map(_networkTypeName).join(', ')),
      onTap: () async {
        final picked = await showDialog<Set<String>>(
          context: context,
          builder: (context) => _MultiChoiceDialog(
            title: context.tr('Network type'), options: options, initial: current.toSet(),
          ),
        );
        if (picked != null) _setList('network_type', options.where(picked.contains).join(','));
      },
    );
  }

  /// The rule-set row opens the picker over Throne's preset rule sets (the routeprofiles srslist),
  /// which keeps hand-typed entries in the same field — the native picker's contract.
  Widget _ruleSetRow() {
    final current = _listOf('rule_set');
    return ListItem(
      title: Text(context.tr('Rule set')),
      subtitle: Text(current.isEmpty ? context.tr('Comma-separated') : current.join(', ')),
      onTap: () async {
        final box = context.box;
        final value = await showDialog<String>(
          context: context,
          builder: (context) => _RuleSetDialog(box: box, initial: current),
        );
        if (value != null) _setList('rule_set', value);
      },
    );
  }

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

String _networkTypeName(String value) => switch (value) {
  'wifi' => 'Wi-Fi', 'cellular' => 'Cellular', 'ethernet' => 'Ethernet', _ => 'Other',
};

/// A multi-choice dialog over a fixed option set; the answer is the selected subset.
class _MultiChoiceDialog extends StatefulWidget {
  const _MultiChoiceDialog({required this.title, required this.options, required this.initial});
  final String title;
  final List<String> options;
  final Set<String> initial;
  @override
  State<_MultiChoiceDialog> createState() => _MultiChoiceDialogState();
}

class _MultiChoiceDialogState extends State<_MultiChoiceDialog> {
  late final _selected = widget.initial.toSet();

  @override
  Widget build(BuildContext context) => AlertDialog(
    title: Text(widget.title),
    content: Column(mainAxisSize: MainAxisSize.min, children: [
      for (final option in widget.options)
        ListItem.checkbox(
          value: _selected.contains(option),
          title: Text(context.tr(_networkTypeName(option))),
          onChanged: (value) => setState(() {
            if (value == true) { _selected.add(option); } else { _selected.remove(option); }
          }),
        ),
    ]),
    actions: [
      TextButton(onPressed: () => Navigator.pop(context), child: Text(context.tr('Cancel'))),
      TextButton(onPressed: () => Navigator.pop(context, _selected), child: Text(context.tr('Save'))),
    ],
  );
}

/// The batch-add dialog: one simple rule per line, `prefix:value` with an optional `-> target`.
/// The controller lives here so the closing animation is safe (the same rule as the other editors).
class _BatchAddDialog extends StatefulWidget {
  const _BatchAddDialog();
  @override
  State<_BatchAddDialog> createState() => _BatchAddDialogState();
}

class _BatchAddDialogState extends State<_BatchAddDialog> {
  late final TextEditingController _text = TextEditingController();

  @override
  void dispose() {
    _text.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) => AlertDialog(
    title: Text(context.tr('Batch add simple rules')),
    content: SizedBox(
      width: 360,
      child: TextField(
        controller: _text, minLines: 5, maxLines: 10, autocorrect: false,
        keyboardType: TextInputType.multiline,
        decoration: InputDecoration(
          labelText: context.tr('Rules'),
          helperText: context.tr('One rule per line: suffix:example.com -> proxy. Outbounds are proxy, direct, block and warp-bypass.'),
          border: const OutlineInputBorder(),
        ),
      ),
    ),
    actions: [
      TextButton(onPressed: () => Navigator.pop(context), child: Text(context.tr('Cancel'))),
      TextButton(onPressed: () => Navigator.pop(context, _text.text), child: Text(context.tr('Add'))),
    ],
  );
}

/// Throne's preset rule sets (the routeprofiles srslist) over the rule's own list: search the
/// catalog, tick what belongs in the rule, and hand-typed entries in the field above survive —
/// the same contract as the native multi-select picker (unknown entries stay selected).
class _RuleSetDialog extends StatefulWidget {
  const _RuleSetDialog({required this.box, required this.initial});
  final BoxController box;
  final List<String> initial;
  @override State<_RuleSetDialog> createState() => _RuleSetDialogState();
}

class _RuleSetDialogState extends State<_RuleSetDialog> {
  late final TextEditingController _values = TextEditingController(text: widget.initial.join(', '));
  late final TextEditingController _query = TextEditingController();
  List<Json> _entries = const [];
  bool _loading = true;

  @override
  void initState() {
    super.initState();
    _search();
  }

  @override
  void dispose() {
    _values.dispose();
    _query.dispose();
    super.dispose();
  }

  Future<void> _search() async {
    setState(() => _loading = true);
    try {
      final wire = json(await widget.box.call('routes.ruleSets', {'query': _query.text, 'limit': 200}));
      if (mounted) setState(() { _entries = rows(wire['entries']); _loading = false; });
    } catch (_) {
      if (mounted) setState(() => _loading = false);
    }
  }

  Set<String> get _selected =>
      _values.text.split(',').map((value) => value.trim()).where((value) => value.isNotEmpty).toSet();

  void _toggle(String name) {
    final selected = _selected;
    if (!selected.remove(name)) selected.add(name);
    setState(() => _values.text = selected.join(', '));
  }

  @override Widget build(BuildContext context) => AlertDialog(
    title: Text(context.tr('Throne preset rules')),
    content: SizedBox(
      width: 420, height: 420,
      child: Column(crossAxisAlignment: CrossAxisAlignment.stretch, children: [
        TextField(
          controller: _values, autocorrect: false, enableSuggestions: false,
          minLines: 1, maxLines: 2,
          decoration: InputDecoration(
            labelText: context.tr('Rule set'),
            helperText: context.tr('Comma-separated'),
            border: const OutlineInputBorder(),
          ),
        ),
        const SizedBox(height: 8),
        TextField(
          controller: _query, autocorrect: false, enableSuggestions: false,
          decoration: InputDecoration(
            labelText: context.tr('Search'),
            border: const OutlineInputBorder(),
            prefixIcon: const GlyphIcon(AppGlyphs.search),
          ),
          onChanged: (_) => _search(),
        ),
        const SizedBox(height: 8),
        Expanded(
          child: _loading
              ? const Center(child: CircularProgressIndicator())
              : _entries.isEmpty
                  ? Center(child: Text(context.tr('No results')))
                  : ListView(children: [
                      for (final entry in _entries)
                        CheckboxListTile(
                          dense: true,
                          value: _selected.contains('${entry['name']}'),
                          title: Text('${entry['name']}'),
                          onChanged: (_) => _toggle('${entry['name']}'),
                        ),
                    ]),
        ),
      ]),
    ),
    actions: [
      TextButton(onPressed: () => Navigator.pop(context), child: Text(context.tr('Cancel'))),
      TextButton(onPressed: () => Navigator.pop(context, _values.text), child: Text(context.tr('Save'))),
    ],
  );
}

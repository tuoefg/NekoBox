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
    final remote = profile['isRemote'] == true;
    return remote
        ? '${context.tr('Remote')} · $rules ${context.tr('rules')}'
        : '$rules ${context.tr('rules')}';
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
  late final TextEditingController _rules;
  String _defaultOutbound = 'direct';
  bool _isRemote = false;
  bool _autoUpdate = false;
  bool _current = false;
  bool _saving = false;
  String? _error;

  @override
  void initState() {
    super.initState();
    _name = TextEditingController();
    _url = TextEditingController();
    _rules = TextEditingController();
    WidgetsBinding.instance.addPostFrameCallback((_) => _load());
  }

  @override
  void dispose() {
    _name.dispose();
    _url.dispose();
    _rules.dispose();
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
        _rules.text = data['rules']?.toString() ?? '';
        _current = (list['currentId'] as num?)?.toInt() == widget.id;
      });
    } catch (error) {
      if (mounted) setState(() => _error = BoxController.describe(error));
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
        'rules': _rules.text,
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
      onSave: _save,
      error: _error,
      actions: [
        if (widget.id > 0)
          IconButtonData(glyph: AppGlyphs.delete, tooltip: 'Delete', onPressed: _delete),
      ],
      children: [
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
        ]),
        generateSection(title: context.tr('Simple rules'), items: [
          Padding(
            padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 8),
            child: TextField(
              controller: _rules, minLines: 4, maxLines: 12, autocorrect: false,
              keyboardType: TextInputType.multiline,
              decoration: InputDecoration(
                labelText: context.tr('Rules'),
                border: const OutlineInputBorder(),
              ),
            ),
          ),
        ], footer: context.tr('One rule per line: suffix:example.com -> proxy. Outbounds are proxy, direct, block and warp-bypass.')),
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

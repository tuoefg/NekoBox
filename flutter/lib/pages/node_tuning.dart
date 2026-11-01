import '../widgets.dart';
import '../components/form.dart';

/// The per-node overrides for mux, TLS fragment and TLS tricks — the same three the native editor
/// keeps under the profile's own settings. Mux is Keep Default / On / Off / Custom; the other two
/// are Keep Default / On / Off. Anything the protocol has no field for is left out.
class NodeTuningPage extends StatefulWidget {
  const NodeTuningPage({super.key, required this.id});
  final int id;
  @override
  State<NodeTuningPage> createState() => _NodeTuningPageState();
}

class _NodeTuningPageState extends State<NodeTuningPage> {
  Json _info = const {};
  bool _loading = true;
  bool _saving = false;
  String? _error;

  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addPostFrameCallback((_) => _load());
  }

  Future<void> _load() async {
    final box = context.box;
    try {
      final data = json(await box.call('profiles.nodeTuningInfo', {'id': widget.id}));
      if (mounted) setState(() { _info = data; _loading = false; });
    } catch (error) {
      if (!mounted) return;
      setState(() { _loading = false; _error = BoxController.describe(error); });
    }
  }

  Future<void> _save() async {
    setState(() { _saving = true; _error = null; });
    final box = context.box;
    try {
      final data = await box.call('profiles.nodeTuningSave', {'id': widget.id, 'value': _info});
      if (mounted) setState(() { _saving = false; _info = json(data); });
    } catch (error) {
      if (!mounted) return;
      setState(() { _saving = false; _error = BoxController.describe(error); });
    }
  }

  void _set(String key, Object? value) => setState(() => _info = {..._info, key: value});

  @override
  Widget build(BuildContext context) {
    if (_loading) {
      return FormPage(title: 'Node tuning', onSave: () {}, saving: true, children: const []);
    }
    final hasMux = _info['hasMux'] == true;
    final hasTls = _info['hasTls'] == true;
    final muxState = (_info['muxState'] as num?)?.toInt() ?? 0;
    return FormPage(
      title: 'Node tuning',
      saving: _saving,
      onSave: _save,
      error: _error,
      children: [
        if (!hasMux && !hasTls)
          generateSection(items: [
            ListItem(title: Text(context.tr('This protocol has no tunable options'))),
          ]),
        if (hasMux) generateSection(title: context.tr('Mux'), items: [
          ListItem(
            title: Text(context.tr('Mux')),
            trailing: Text(context.tr(_muxStateName(muxState))),
            onTap: () async {
              final picked = await showDialog<int>(context: context, builder: (context) => SimpleDialog(
                title: Text(context.tr('Mux')),
                children: [
                  for (final (value, label) in [(0, 'Keep default'), (1, 'On'), (2, 'Off'), (3, 'Custom')])
                    SimpleDialogOption(onPressed: () => Navigator.pop(context, value), child: Text(context.tr(label))),
                ],
              ));
              if (picked != null) _set('muxState', picked);
            },
          ),
          if (muxState == 3) ...[
            ListItem(
              title: Text(context.tr('Mux protocol')),
              trailing: Text('${_info['muxProtocol'] ?? 'smux'}'),
              onTap: () async {
                final picked = await showDialog<String>(context: context, builder: (context) => SimpleDialog(
                  title: Text(context.tr('Mux protocol')),
                  children: [
                    for (final value in const ['smux', 'yamux', 'h2mux'])
                      SimpleDialogOption(onPressed: () => Navigator.pop(context, value), child: Text(value)),
                  ],
                ));
                if (picked != null) _set('muxProtocol', picked);
              },
            ),
            _number('Mux max streams', 'muxMaxStreams'),
            _number('Mux max connections', 'muxMaxConnections'),
            _number('Mux min streams', 'muxMinStreams'),
            ListItem.toggle(
              title: Text(context.tr('Mux padding')),
              value: _info['muxPadding'] == true,
              onChanged: (value) => _set('muxPadding', value),
            ),
            ListItem.toggle(
              title: Text(context.tr('Brutal congestion')),
              value: _info['muxBrutal'] == true,
              onChanged: (value) => _set('muxBrutal', value),
            ),
            if (_info['muxBrutal'] == true) ...[
              _number('Brutal upload', 'muxBrutalUp', unit: 'Mbps'),
              _number('Brutal download', 'muxBrutalDown', unit: 'Mbps'),
            ],
          ],
        ]),
        if (hasTls) generateSection(title: context.tr('Fragment'), items: [
          ListItem(
            title: Text(context.tr('Fragment')),
            trailing: Text(context.tr(_tristateName((_info['fragmentState'] as num?)?.toInt() ?? 0))),
            onTap: () async {
              final picked = await _tristateDialog(context.tr('Fragment'));
              if (picked != null) _set('fragmentState', picked);
            },
          ),
          FormRow(title: context.tr('Fragment fallback delay'), trailing: FormRowTextField(
            initialValue: '${_info['fragmentFallbackDelay'] ?? ''}',
            hintText: 'e.g. 200ms',
            autocorrect: false, enableSuggestions: false,
            onChanged: (value) => _set('fragmentFallbackDelay', value),
          )),
          ListItem.toggle(
            title: Text(context.tr('TLS record fragment')),
            value: _info['recordFragment'] == true,
            onChanged: (value) => _set('recordFragment', value),
          ),
        ]),
        if (hasTls) generateSection(title: context.tr('TLS tricks'), items: [
          ListItem(
            title: Text(context.tr('TLS tricks')),
            trailing: Text(context.tr(_tristateName((_info['tlsTricksState'] as num?)?.toInt() ?? 0))),
            onTap: () async {
              final picked = await _tristateDialog(context.tr('TLS tricks'));
              if (picked != null) _set('tlsTricksState', picked);
            },
          ),
        ], footer: context.tr('Mixed-case SNI and other tricks, applied when the node is dialed.')),
      ],
    );
  }

  Widget _number(String title, String key, {String? unit}) => FormRow(
    title: context.tr(title),
    trailing: FormRowTextField(
      initialValue: '${_info[key] ?? ''}',
      keyboardType: TextInputType.number,
      hintText: unit,
      onChanged: (value) => _set(key, int.tryParse(value)),
    ),
  );

  String _muxStateName(int state) => switch (state) {
    1 => 'On', 2 => 'Off', 3 => 'Custom', _ => 'Keep default',
  };

  String _tristateName(int state) => switch (state) {
    1 => 'On', 2 => 'Off', _ => 'Keep default',
  };

  Future<int?> _tristateDialog(String title) => showDialog<int>(context: context, builder: (context) => SimpleDialog(
    title: Text(title),
    children: [
      for (final (value, label) in [(0, 'Keep default'), (1, 'On'), (2, 'Off')])
        SimpleDialogOption(onPressed: () => Navigator.pop(context, value), child: Text(context.tr(label))),
    ],
  ));
}

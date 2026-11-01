import 'dart:async';

import '../widgets.dart';
import '../components/form.dart';

/// The auto-selector editor: a `type=autoselector` node that keeps probing the nodes of its source
/// groups and switches between them. Every field is written back onto the stored selector.
class AutoSelectorPage extends StatefulWidget {
  const AutoSelectorPage({super.key, required this.id});
  final int id;
  @override
  State<AutoSelectorPage> createState() => _AutoSelectorPageState();
}

class _AutoSelectorPageState extends State<AutoSelectorPage> {
  Json _info = const {};
  Json _status = const {};
  bool _loading = true;
  bool _saving = false;
  String? _error;
  final Set<int> _gids = {};
  Timer? _statusTimer;

  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addPostFrameCallback((_) => _load());
    // The status is a live read: while the page is open it keeps itself current.
    _statusTimer = Timer.periodic(const Duration(seconds: 3), (_) => _loadStatus());
  }

  @override
  void dispose() {
    _statusTimer?.cancel();
    super.dispose();
  }

  Future<void> _load() async {
    final box = context.box;
    try {
      final data = json(await box.call('profiles.autoSelectorInfo', {'id': widget.id}));
      if (!mounted) return;
      setState(() {
        _info = data;
        _gids..clear()..addAll((data['gids'] as List? ?? const []).map((e) => (e as num).toInt()));
        _loading = false;
      });
      await _loadStatus();
    } catch (error) {
      if (!mounted) return;
      setState(() { _loading = false; _error = BoxController.describe(error); });
    }
  }

  Future<void> _loadStatus() async {
    final box = context.box;
    try {
      final raw = await box.call('profiles.autoSelectorStatus');
      if (mounted) setState(() => _status = json(raw));
    } catch (error) {
      // The status is a live read; a service that is not running simply has none.
    }
  }

  Future<void> _pin(int memberId) async {
    final box = context.box;
    try {
      await box.call('profiles.autoSelectorPin', {'id': widget.id, 'memberId': memberId});
      if (mounted) await _load();
    } catch (error) {
      if (mounted) box.report(error);
    }
  }

  Future<void> _recheck() async {
    final box = context.box;
    try {
      await box.call('profiles.autoSelectorRecheck', {});
      if (mounted) await _loadStatus();
    } catch (error) {
      if (mounted) box.report(error);
    }
  }

  Future<void> _save() async {
    setState(() { _saving = true; _error = null; });
    final box = context.box;
    try {
      await box.call('profiles.autoSelectorSave', {'id': widget.id, 'value': {
        ..._info,
        'gids': _gids.toList(),
      }});
      if (mounted) context.safeNestedPop();
    } catch (error) {
      if (!mounted) return;
      setState(() { _saving = false; _error = BoxController.describe(error); });
    }
  }

  void _set(String key, Object? value) => setState(() => _info = {..._info, key: value});

  @override
  Widget build(BuildContext context) {
    if (_loading) {
      return FormPage(title: 'Auto selector', onSave: () {}, saving: true, children: const []);
    }
    final groups = (_info['groups'] as List? ?? const []).map((e) => json(e)).toList();
    return FormPage(
      title: 'Auto selector',
      saving: _saving,
      onSave: _save,
      error: _error,
      actions: [
        IconButtonData(glyph: AppGlyphs.refresh, tooltip: 'Refresh', onPressed: _load),
      ],
      children: [
        ..._statusSection(context),
        generateSection(title: context.tr('Filters'), items: [
          FormRow(title: context.tr('Name'), trailing: FormRowTextField(
            initialValue: '${_info['name'] ?? ''}',
            hintText: context.tr('Optional'),
            autocorrect: false, enableSuggestions: false,
            onChanged: (value) => _set('name', value),
          )),
          FormRow(title: context.tr('Name filter'), trailing: FormRowTextField(
            initialValue: '${_info['nameFilter'] ?? ''}',
            hintText: context.tr('Regex on the node name'),
            autocorrect: false, enableSuggestions: false,
            onChanged: (value) => _set('nameFilter', value),
          )),
          FormRow(title: context.tr('Country filter'), trailing: FormRowTextField(
            initialValue: '${_info['countryFilter'] ?? ''}',
            hintText: context.tr('Comma-separated country codes'),
            autocorrect: false, enableSuggestions: false,
            onChanged: (value) => _set('countryFilter', value),
          )),
          ListItem.toggle(
            title: Text(context.tr('Exclude unavailable')),
            value: _info['excludeUnavailable'] == true,
            onChanged: (value) => _set('excludeUnavailable', value),
          ),
        ]),
        generateSection(title: context.tr('Scheduling'), items: [
          _number('Probe interval', 'intervalSec', unit: 's'),
          _number('Benchmark interval', 'benchIntervalSec', unit: 's'),
          _number('Watch interval', 'watchIntervalSec', unit: 's'),
          _number('Active size', 'activeSize'),
          _number('Expected', 'expected'),
        ]),
        generateSection(title: context.tr('Probing'), items: [
          FormRow(title: context.tr('Test URL'), trailing: FormRowTextField(
            initialValue: '${_info['testURL'] ?? ''}',
            hintText: context.tr('Empty uses the Testing page URL'),
            keyboardType: TextInputType.url, autocorrect: false, enableSuggestions: false,
            onChanged: (value) => _set('testURL', value),
          )),
          FormRow(title: context.tr('Connectivity URL'), trailing: FormRowTextField(
            initialValue: '${_info['connectivityURL'] ?? ''}',
            hintText: context.tr('Empty uses the test URL'),
            keyboardType: TextInputType.url, autocorrect: false, enableSuggestions: false,
            onChanged: (value) => _set('connectivityURL', value),
          )),
          _number('Tolerance', 'toleranceMs', unit: 'ms'),
          _number('Max RTT', 'maxRTTms', unit: 'ms'),
          _number('Dial retries', 'dialRetries'),
          _number('Sampling', 'sampling'),
        ]),
        generateSection(title: context.tr('Balancing'), items: [
          ListItem.toggle(
            title: Text(context.tr('Balance')),
            value: _info['balance'] == true,
            onChanged: (value) => _set('balance', value),
          ),
          if (_info['balance'] == true) ...[
            ListItem(
              title: Text(context.tr('Balance mode')),
              trailing: Text(context.tr(_balanceName('${_info['balanceMode'] ?? 'rotate'}'))),
              onTap: () async {
                final picked = await showDialog<String>(context: context, builder: (context) => SimpleDialog(
                  title: Text(context.tr('Balance mode')),
                  children: [
                    for (final value in const ['rotate', 'sticky'])
                      SimpleDialogOption(
                        onPressed: () => Navigator.pop(context, value),
                        child: Text(context.tr(_balanceName(value))),
                      ),
                  ],
                ));
                if (picked != null) _set('balanceMode', picked);
              },
            ),
            _number('Balance interval', 'balanceIntervalSec', unit: 's'),
          ],
          ListItem.toggle(
            title: Text(context.tr('Interrupt on switch')),
            value: _info['interruptOnSwitch'] == true,
            onChanged: (value) => _set('interruptOnSwitch', value),
          ),
        ]),
        generateSection(title: context.tr('Source groups'), items: [
          for (final group in groups)
            ListItem.checkbox(
              title: Text('${group['name']}'),
              value: _gids.contains((group['id'] as num?)?.toInt()),
              onChanged: (value) => setState(() {
                final id = (group['id'] as num?)?.toInt() ?? 0;
                if (value == true) { _gids.add(id); } else { _gids.remove(id); }
              }),
            ),
        ], footer: context.tr('The selector only ranks the nodes of these groups.')),
        generateSection(title: context.tr('Advanced'), items: [
          _number('Pool cap', 'poolCap'),
          _number('Build limit', 'buildLimit'),
          _number('Result validity', 'resultValidityMins', unit: 'min'),
        ]),
      ],
    );
  }

  Widget _number(String title, String key, {String? unit}) {
    return FormRow(title: context.tr(title), trailing: FormRowTextField(
      initialValue: '${_info[key] ?? ''}',
      keyboardType: TextInputType.number,
      hintText: unit,
      onChanged: (value) => _set(key, int.tryParse(value)),
    ));
  }

  String _balanceName(String value) => value == 'sticky' ? 'Sticky' : 'Rotate';

  /// The live view of the running selector: its phase and selection, then the ranked members,
  /// each one pin-able. A stopped service has no members to show.
  List<Widget> _statusSection(BuildContext context) {
    final autoNote = context.tr('Refreshes every few seconds while this page is open.');
    final phase = _status['phase']?.toString() ?? 'idle';
    if (phase == 'idle') {
      return [generateSection(items: [
        ListItem(title: Text(context.tr('Status')), subtitle: Text(context.tr('Not running'))),
      ], footer: autoNote)];
    }
    final members = (_status['members'] as List? ?? const []).map((e) => json(e)).toList();
    final pinnedId = (_status['pinned_id'] as num?)?.toInt() ?? -1;
    return [
      generateSection(title: context.tr('Status'), items: [
        ListItem(title: Text(context.tr('Phase')), trailing: Text('${_status['core_phase'] ?? phase}')),
        ListItem(title: Text(context.tr('Selected')),
          subtitle: Text('${_status['selected_name'] ?? ''}')),
        ListItem(title: Text(context.tr('Pinned')),
          trailing: Text(pinnedId < 0 ? context.tr('Automatic') : '#$pinnedId'),
          onTap: () => _pin(-1)),
        ListItem(title: Text(context.tr('Members')), subtitle: Text(
          '${_status['members_alive'] ?? 0} alive · ${_status['members_qualified'] ?? 0} qualified · ${_status['members_cooldown'] ?? 0} cooldown')),
        ListItem(leading: const GlyphIcon(AppGlyphs.bolt), title: Text(context.tr('Recheck now')),
          onTap: _recheck),
      ], footer: autoNote),
      if (members.isNotEmpty)
        generateSection(title: context.tr('Ranking'), items: [
          for (final member in members)
            ListItem(
              title: Text('${member['name'] ?? member['tag']}'),
              subtitle: Text(_memberSummary(member)),
              trailing: member['id'] == pinnedId ? const GlyphIcon(AppGlyphs.pin) : null,
              onTap: () => _memberMenu(member),
            ),
        ]),
    ];
  }

  String _memberSummary(Json member) {
    final rank = (member['rank'] as num?)?.toInt() ?? 0;
    final state = member['state']?.toString() ?? '';
    final average = (member['average_ms'] as num?)?.toInt() ?? 0;
    final samples = (member['samples'] as num?)?.toInt() ?? 0;
    return '#$rank · $state · ${average}ms · $samples×';
  }

  Future<void> _memberMenu(Json member) async {
    final picked = await showDialog<String>(context: context, builder: (context) => SimpleDialog(
      title: Text('${member['name'] ?? member['tag']}'),
      children: [
        SimpleDialogOption(onPressed: () => Navigator.pop(context, 'pin'), child: Text(context.tr('Pin'))),
        SimpleDialogOption(onPressed: () => Navigator.pop(context, 'auto'), child: Text(context.tr('Use automatic'))),
      ],
    ));
    if (!mounted) return;
    if (picked == 'pin') await _pin((member['id'] as num?)?.toInt() ?? -1);
    if (picked == 'auto') await _pin(-1);
  }
}

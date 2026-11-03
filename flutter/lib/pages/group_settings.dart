import 'package:material_ui/material_ui.dart' as material;
import 'package:flutter/services.dart';
import '../widgets.dart';
import '../components/form.dart';

/// The group settings form: every ProxyGroup field the native GroupSettingsActivity exposes —
/// the common row, the chain slots, the routing profile, the subscription options and the kept
/// sort. Validation mirrors the native FormIssue(Severity.Error) set: the URL gate, the name
/// filter patterns, the update interval and the header values.
class GroupSettingsPage extends StatefulWidget {
  const GroupSettingsPage({super.key, required this.id});
  final int id;
  @override State<GroupSettingsPage> createState() => _GroupSettingsPageState();
}

class _GroupSettingsPageState extends State<GroupSettingsPage> {
  Json? _data;
  String? _error;
  bool _loading = true, _saving = false, _attempted = false;

  final _name = TextEditingController();
  final _url = TextEditingController();
  final _userAgent = TextEditingController();
  final _hwid = TextEditingController();
  final _hwidOs = TextEditingController();
  final _hwidOsVersion = TextEditingController();
  final _hwidModel = TextEditingController();
  final _updateInterval = TextEditingController();
  final _nameInclude = TextEditingController();
  final _nameExclude = TextEditingController();

  bool _skipAutoUpdate = false, _autoClearUnavailable = false, _importSubRoute = false;
  int _frontProxyId = -1, _landingProxyId = -1;
  String _routeId = '-1';
  int _sortMethod = 0, _testSortBy = 0, _trafficSortBy = 0, _testItemsToShow = 0;
  bool _sortDescending = false;
  Json _options = {}, _defaults = {};

  @override void initState() {
    super.initState();
    WidgetsBinding.instance.addPostFrameCallback((_) => _load());
  }

  @override void dispose() {
    for (final controller in [_name, _url, _userAgent, _hwid, _hwidOs, _hwidOsVersion, _hwidModel, _updateInterval, _nameInclude, _nameExclude]) {
      controller.dispose();
    }
    super.dispose();
  }

  Future<void> _load() async {
    setState(() { _error = null; _loading = true; });
    try {
      final data = json(await context.box.call('groupSettings.get', {'id': widget.id}));
      if (!mounted) return;
      _name.text = data['name']?.toString() ?? '';
      _url.text = data['url']?.toString() ?? '';
      final options = json(data['options']);
      _userAgent.text = options['userAgent']?.toString() ?? '';
      _hwid.text = options['hwid']?.toString() ?? '';
      _hwidOs.text = options['hwidOs']?.toString() ?? '';
      _hwidOsVersion.text = options['hwidOsVersion']?.toString() ?? '';
      _hwidModel.text = options['hwidModel']?.toString() ?? '';
      _updateInterval.text = (options['updateInterval'] as num? ?? 0) > 0 ? '${options['updateInterval']}' : '';
      _nameInclude.text = options['nameInclude']?.toString() ?? '';
      _nameExclude.text = options['nameExclude']?.toString() ?? '';
      setState(() {
        _data = data;
        _options = options;
        _defaults = json(data['defaults']);
        _skipAutoUpdate = data['skipAutoUpdate'] == true;
        _autoClearUnavailable = data['autoClearUnavailable'] == true;
        _importSubRoute = data['importSubRoute'] == true;
        _frontProxyId = (data['frontProxyId'] as num? ?? -1).toInt();
        _landingProxyId = (data['landingProxyId'] as num? ?? -1).toInt();
        _routeId = data['routeId']?.toString() ?? '-1';
        _sortMethod = (data['sortMethod'] as num? ?? 0).toInt();
        _sortDescending = data['sortDescending'] == true;
        _testSortBy = (data['testSortBy'] as num? ?? 0).toInt();
        _trafficSortBy = (data['trafficSortBy'] as num? ?? 0).toInt();
        _testItemsToShow = (data['testItemsToShow'] as num? ?? 0).toInt();
        _loading = false;
      });
    } catch (error) {
      if (mounted) setState(() { _error = BoxController.describe(error); _loading = false; });
    }
  }

  int _optionInt(String key) => (_options[key] as num? ?? 0).toInt();
  bool _optionBool(String key) => _options[key] == true;

  void _setOption(String key, Object? value) => setState(() => _options = {..._options, key: value});

  /// The bridge names the kind in `type` ("subscription" / "basic"), the same key the save sends back.
  bool get _subscription => _data?['type'] == 'subscription';

  /// The HWID fields are only editable while the identity is actually sent (the native enable rule).
  bool get _hwidSent {
    final mode = _optionInt('sendHwid');
    return mode == 1 || (mode == 0 && _defaults['sendHwid'] == true);
  }

  ({String field, String message})? get _issue {
    if (_name.text.trim().isEmpty) return (field: 'name', message: '${context.tr('Name')}: ${context.tr('Required')}');
    if (_subscription) {
      final url = _url.text.trim();
      // The fetcher reads content:// URLs too, and those carry no host.
      if (!url.toLowerCase().startsWith('content://')) {
        final uri = Uri.tryParse(url);
        if (url.isEmpty || uri == null || !['http', 'https'].contains(uri.scheme) || uri.host.isEmpty) {
          return (field: 'url', message: context.tr('Must be a URL starting with http:// or https://'));
        }
      }
    }
    for (final (field, key) in [('nameInclude', _nameInclude.text), ('nameExclude', _nameExclude.text)]) {
      if (key.trim().isEmpty) continue;
      try { RegExp(key.trim(), caseSensitive: false, unicode: true); }
      on FormatException catch (e) {
        return (field: field, message: context.tr('Invalid regular expression') + (e.message.isEmpty ? '' : ': ${e.message}'));
      }
    }
    final interval = _updateInterval.text.trim();
    if (interval.isNotEmpty) {
      final minutes = int.tryParse(interval);
      if (minutes == null || minutes < 0 || minutes > 525600) {
        return (field: 'updateInterval', message: context.tr('Update interval'));
      }
      if (minutes > 0 && minutes < 30) {
        return (field: 'updateInterval', message: context.tr('Must be at least 30 minutes, or empty to keep the default.'));
      }
    }
    for (final (field, text) in [
      ('userAgent', _userAgent.text.trim()),
      if (_hwidSent) ...[
        ('hwid', _hwid.text.trim()), ('hwidOs', _hwidOs.text.trim()),
        ('hwidOsVersion', _hwidOsVersion.text.trim()), ('hwidModel', _hwidModel.text.trim()),
      ],
    ]) {
      if (text.length >= 1000 || text.contains('\n') || text.contains('\r')) {
        return (field: field, message: context.tr('Must be shorter than 1000 characters, on a single line.'));
      }
    }
    return null;
  }

  void _changed(String _) { if (_attempted) setState(() {}); }

  Future<void> _save() async {
    if (_saving || _loading) return;
    if (_issue != null) { setState(() => _attempted = true); return; }
    final box = context.box;
    setState(() => _saving = true);
    try {
      final answer = json(await box.call('groupSettings.set', {'value': {
        'id': widget.id,
        'type': _data?['type'],
        'name': _name.text.trim(),
        'url': _subscription ? _url.text.trim() : '',
        'skipAutoUpdate': _skipAutoUpdate,
        'autoClearUnavailable': _autoClearUnavailable,
        'frontProxyId': _frontProxyId,
        'landingProxyId': _landingProxyId,
        'routeId': _routeId,
        'importSubRoute': _importSubRoute,
        'sortMethod': _sortMethod,
        'sortDescending': _sortDescending,
        'testSortBy': _testSortBy,
        'trafficSortBy': _trafficSortBy,
        'testItemsToShow': _testItemsToShow,
        'options': {
          'userAgent': _userAgent.text.trim(),
          'tlsVersion': _options['tlsVersion'],
          'httpVersion': _options['httpVersion'],
          'sendHwid': _optionInt('sendHwid'),
          'hwid': _hwid.text.trim(),
          'hwidOs': _hwidOs.text.trim(),
          'hwidOsVersion': _hwidOsVersion.text.trim(),
          'hwidModel': _hwidModel.text.trim(),
          'updateInterval': int.tryParse(_updateInterval.text.trim()) ?? 0,
          'respectServerInterval': _optionInt('respectServerInterval'),
          'keepWorking': _optionBool('keepWorking'),
          'removeDuplicates': _optionBool('removeDuplicates'),
          'removeInsecure': _optionBool('removeInsecure'),
          'removeInvalid': _optionBool('removeInvalid'),
          'urlTest': _optionBool('urlTest'),
          'removeUnavailable': _optionBool('removeUnavailable'),
          'sortByLatency': _optionBool('sortByLatency'),
          'nameInclude': _nameInclude.text.trim(),
          'nameExclude': _nameExclude.text.trim(),
          'showInfoCard': _optionBool('showInfoCard'),
        },
      }}));
      if (!mounted) return;
      // A changed routing profile of the running group reloaded the service already.
      if (answer['restartNeeded'] == true) {
        showAppBanner(context, context.tr('Done'));
      }
      context.safeNestedPop();
    } catch (error) {
      if (mounted) setState(() { _saving = false; _attempted = true; _error = BoxController.describe(error); });
    }
  }

  /// The pick lists of a menu row: value + label pairs, the current one checked by the dialog's radio.
  Future<void> _pick(String title, List<(String, String)> choices, String current, ValueChanged<String> onPicked) async {
    final picked = await showDialog<String>(context: context, builder: (context) => SimpleDialog(
      title: Text(title),
      children: [
        for (final (value, label) in choices) SimpleDialogOption(
          onPressed: () => Navigator.pop(context, value),
          child: Text(label, style: value == current ? TextStyle(fontWeight: FontWeight.bold) : null),
        ),
      ],
    ));
    if (picked != null) onPicked(picked);
  }

  Widget _menuRow(String title, String label, Future<void> Function() onPick) => ListItem(
    title: Text(title), trailing: Text(label),
    onTap: () async { setState(() => _attempted = false); await onPick(); },
  );

  String _proxyLabel(int id) {
    if (id <= 0) return context.tr('None');
    for (final candidate in rows(_data?['candidates'])) {
      if ((candidate['id'] as num?)?.toInt() == id) return candidate['label'].toString();
    }
    return 'Profile #$id';
  }

  String _routeLabel(String id) {
    for (final choice in rows(_data?['routeChoices'])) {
      if (choice['id']?.toString() == id) return choice['label'].toString();
    }
    return id;
  }

  String _intervalLabel() {
    final global = (_defaults['autoUpdate'] as num? ?? 0).toInt();
    final keep = '${context.tr('Keep default')} (${global >= 30 ? '$global ${context.tr('minutes')}' : context.tr('Off')})';
    final minutes = int.tryParse(_updateInterval.text.trim());
    if (minutes == null || minutes <= 0) return keep;
    return '$minutes ${context.tr('minutes')}';
  }

  String _serverIntervalLabel() {
    final value = _optionInt('respectServerInterval');
    return switch (value) {
      1 => context.tr('Use server setting'),
      2 => context.tr('Ignore server setting'),
      _ => '${context.tr('Keep default')} (${_defaults['respectServerInterval'] == true ? context.tr('Use server setting') : context.tr('Ignore server setting')})',
    };
  }

  @override Widget build(BuildContext context) {
    if (_loading) return Scaffold(title: context.tr('Group settings'), isLoading: true, body: const SizedBox.shrink());
    if (_data == null) {
      return Scaffold(title: context.tr('Group settings'),
      body: ErrorState(_error ?? context.tr('No such group'), retry: _load));
    }
    final issue = _attempted ? _issue : null;
    final candidates = rows(_data?['candidates']).map((candidate) =>
      ((candidate['id'] as num).toInt().toString(), candidate['label'].toString())).toList();
    final routeChoices = rows(_data?['routeChoices']).map((choice) =>
      (choice['id']?.toString() ?? '-1', choice['label']?.toString() ?? '')).toList();
    // A refused save says why: without this the sheet stayed open and looked like a dead button.
    return FormPage(
      title: 'Group settings', saving: _saving, onSave: _save, error: issue?.message ?? _error,
      children: [
        generateSection(items: [
          FormRow(title: context.tr('Name'), invalid: issue?.field == 'name',
            trailing: FormRowTextField(controller: _name, hintText: context.tr('Required'),
              textInputAction: TextInputAction.next, onChanged: _changed)),
          ListItem(title: Text(context.tr('Type')), trailing: Text(context.tr(_subscription ? 'Subscription' : 'Basic'))),
          if (_subscription) FormRow(title: context.tr('URL'), invalid: issue?.field == 'url',
            trailing: FormRowTextField(controller: _url, hintText: context.tr('Required'),
              keyboardType: TextInputType.text, textInputAction: TextInputAction.next,
              autocorrect: false, onChanged: _changed)),
          FormRow(title: context.tr('Auto clear unavailable'), onPressed: () => setState(() => _autoClearUnavailable = !_autoClearUnavailable),
            trailing: Switch(value: _autoClearUnavailable, onChanged: (value) => setState(() => _autoClearUnavailable = value))),
        ]),
        generateSection(title: context.tr('Chain'), items: [
          _menuRow(context.tr('Front proxy'), _proxyLabel(_frontProxyId), () => _pick(
            context.tr('Front proxy'),
            [('-1', context.tr('None')), ...candidates],
            _frontProxyId <= 0 ? '-1' : '$_frontProxyId',
            (value) => setState(() => _frontProxyId = int.tryParse(value) ?? -1),
          )),
          _menuRow(context.tr('Landing proxy'), _proxyLabel(_landingProxyId), () => _pick(
            context.tr('Landing proxy'),
            [('-1', context.tr('None')), ...candidates],
            _landingProxyId <= 0 ? '-1' : '$_landingProxyId',
            (value) => setState(() => _landingProxyId = int.tryParse(value) ?? -1),
          )),
        ], footer: context.tr('A front or landing profile forwards traffic before or after the proxy of this group.')),
        generateSection(title: context.tr('Routing profile'), items: [
          _menuRow(context.tr('Routing profile'), _routeLabel(_routeId), () => _pick(
            context.tr('Routing profile'), routeChoices, _routeId,
            (value) => setState(() => _routeId = value),
          )),
          if (_subscription) FormRow(title: context.tr('Import routing profile from subscription'), onPressed: () => setState(() => _importSubRoute = !_importSubRoute),
            trailing: Switch(value: _importSubRoute, onChanged: (value) => setState(() => _importSubRoute = value))),
        ], footer: context.tr('The routing profile this group connects with; default follows the global one.')),
        if (_subscription) generateSection(title: context.tr('Subscription'), items: [
          FormRow(title: context.tr('Skip auto update'), onPressed: () => setState(() => _skipAutoUpdate = !_skipAutoUpdate),
            trailing: Switch(value: _skipAutoUpdate, onChanged: (value) => setState(() => _skipAutoUpdate = value))),
          FormRow(title: context.tr('User agent'), invalid: issue?.field == 'userAgent',
            trailing: FormRowTextField(controller: _userAgent, hintText: _defaults['userAgent']?.toString(),
              autocorrect: false, onChanged: _changed)),
          _menuRow(context.tr('TLS version'), switch (_options['tlsVersion']) {
            1 => 'TLS 1.2', 2 => 'TLS 1.3',
            _ => '${context.tr('Keep default')} (${switch (_defaults['tlsVersion'] as num? ?? 0) { 1 => 'TLS 1.2', 2 => 'TLS 1.3', _ => 'Auto' }})',
          }, () => _pick(context.tr('TLS version'), [
            ('', context.tr('Keep default')), ('0', 'Auto'), ('1', 'TLS 1.2'), ('2', 'TLS 1.3'),
          ], _options['tlsVersion']?.toString() ?? '', (value) => _setOption('tlsVersion', value.isEmpty ? null : int.tryParse(value)))),
          _menuRow(context.tr('HTTP version'), switch (_options['httpVersion']) {
            1 => 'HTTP/1.1',
            _ => '${context.tr('Keep default')} (${switch (_defaults['httpVersion'] as num? ?? 0) { 1 => 'HTTP/1.1', _ => 'Auto' }})',
          }, () => _pick(context.tr('HTTP version'), [
            ('', context.tr('Keep default')), ('0', 'Auto'), ('1', 'HTTP/1.1'),
          ], _options['httpVersion']?.toString() ?? '', (value) => _setOption('httpVersion', value.isEmpty ? null : int.tryParse(value)))),
          _menuRow(context.tr('Update interval'), _intervalLabel(), () async {
            final controller = TextEditingController(text: _updateInterval.text);
            final picked = await showDialog<String>(context: context, builder: (context) => material.AlertDialog(
              title: Text(context.tr('Update interval')),
              content: TextField(controller: controller, autofocus: true, keyboardType: TextInputType.number,
                inputFormatters: [FilteringTextInputFormatter.digitsOnly],
                decoration: InputDecoration(hintText: context.tr('Keep default')),
                onSubmitted: (value) => Navigator.pop(context, value)),
              actions: [
                TextButton(onPressed: () => Navigator.pop(context), child: Text(context.tr('Cancel'))),
                TextButton(onPressed: () => Navigator.pop(context, controller.text), child: Text(context.tr('OK'))),
              ],
            ));
            if (picked != null) setState(() { _updateInterval.text = picked.trim(); if (_attempted) setState(() {}); });
          }),
          _menuRow(context.tr('Server interval'), _serverIntervalLabel(), () => _pick(
            context.tr('Server interval'),
            [('0', context.tr('Keep default')), ('1', context.tr('Use server setting')), ('2', context.tr('Ignore server setting'))],
            '${_optionInt('respectServerInterval')}',
            (value) => _setOption('respectServerInterval', int.tryParse(value) ?? 0),
          )),
        ], footer: context.tr('Empty fields keep the global subscription settings.')),
        if (_subscription) generateSection(title: context.tr('Device identity'), items: [
          _menuRow(context.tr('Send HWID'), switch (_optionInt('sendHwid')) {
            1 => context.tr('On'), 2 => context.tr('Off'),
            _ => '${context.tr('Keep default')} (${_defaults['sendHwid'] == true ? context.tr('On') : context.tr('Off')})',
          }, () => _pick(context.tr('Send HWID'), [
            ('0', context.tr('Keep default')), ('1', context.tr('On')), ('2', context.tr('Off')),
          ], '${_optionInt('sendHwid')}', (value) => _setOption('sendHwid', int.tryParse(value) ?? 0))),
          if (_hwidSent) ...[
            FormRow(title: context.tr('HWID'), invalid: issue?.field == 'hwid',
              trailing: FormRowTextField(controller: _hwid, hintText: _defaults['hwid']?.toString(),
                autocorrect: false, onChanged: _changed)),
            FormRow(title: context.tr('HWID OS'), invalid: issue?.field == 'hwidOs',
              trailing: FormRowTextField(controller: _hwidOs, hintText: _defaults['hwidOs']?.toString(),
                autocorrect: false, onChanged: _changed)),
            FormRow(title: context.tr('HWID OS version'), invalid: issue?.field == 'hwidOsVersion',
              trailing: FormRowTextField(controller: _hwidOsVersion, hintText: _defaults['hwidOsVersion']?.toString(),
                autocorrect: false, onChanged: _changed)),
            FormRow(title: context.tr('HWID model'), invalid: issue?.field == 'hwidModel',
              trailing: FormRowTextField(controller: _hwidModel, hintText: _defaults['hwidModel']?.toString(),
                autocorrect: false, onChanged: _changed)),
          ],
        ]),
        if (_subscription) generateSection(title: context.tr('After update'), items: [
          for (final (key, title) in const [
            ('keepWorking', 'Keep working profiles'),
            ('removeDuplicates', 'Remove duplicate servers'),
            ('removeInsecure', 'Remove insecure servers'),
            ('removeInvalid', 'Remove invalid servers'),
            ('urlTest', 'URL test after update'),
            ('removeUnavailable', 'Remove unavailable servers'),
            ('sortByLatency', 'Sort by latency'),
          ])
            FormRow(title: context.tr(title), onPressed: () => _setOption(key, !_optionBool(key)),
              trailing: Switch(value: _optionBool(key), onChanged: (value) => _setOption(key, value))),
          FormRow(title: context.tr('Show info card'), onPressed: () => _setOption('showInfoCard', !_optionBool('showInfoCard')),
            trailing: Switch(value: _optionBool('showInfoCard'), onChanged: (value) => _setOption('showInfoCard', value))),
        ], footer: context.tr('The unavailable-removal and latency-sort options only take effect while the URL test is on.')),
        if (_subscription) generateSection(title: context.tr('Name filters'), items: [
          FormRow(title: context.tr('Only names matching'), invalid: issue?.field == 'nameInclude',
            trailing: FormRowTextField(controller: _nameInclude, hintText: context.tr('Regular expression'),
              autocorrect: false, onChanged: _changed)),
          FormRow(title: context.tr('Remove names matching'), invalid: issue?.field == 'nameExclude',
            trailing: FormRowTextField(controller: _nameExclude, hintText: context.tr('Regular expression'),
              autocorrect: false, onChanged: _changed)),
        ], footer: context.tr('Two regular expressions the server names must match, and must not match.')),
        generateSection(title: context.tr('Sorting and display'), items: [
          _menuRow(context.tr('Sort method'), switch (_sortMethod) {
            1 => context.tr('By type'), 2 => context.tr('By address'), 3 => context.tr('By name'),
            4 => context.tr('By test result'), 5 => context.tr('By id'), 6 => context.tr('By traffic'),
            7 => context.tr('By security'), 8 => context.tr('By latency'),
            _ => context.tr('Manual order'),
          }, () => _pick(context.tr('Sort method'), [
            ('0', context.tr('Manual order')), ('1', context.tr('By type')), ('2', context.tr('By address')),
            ('3', context.tr('By name')), ('4', context.tr('By test result')), ('5', context.tr('By id')),
            ('6', context.tr('By traffic')), ('7', context.tr('By security')), ('8', context.tr('By latency')),
          ], '$_sortMethod', (value) => setState(() => _sortMethod = int.tryParse(value) ?? 0))),
          FormRow(title: context.tr('Descending'), onPressed: () => setState(() => _sortDescending = !_sortDescending),
            trailing: Switch(value: _sortDescending, onChanged: (value) => setState(() => _sortDescending = value))),
          _menuRow(context.tr('Test sort by'), switch (_testSortBy) {
            1 => context.tr('Download speed'), 2 => context.tr('Upload speed'), 3 => context.tr('IP out'),
            _ => context.tr('Latency'),
          }, () => _pick(context.tr('Test sort by'), [
            ('0', context.tr('Latency')), ('1', context.tr('Download speed')),
            ('2', context.tr('Upload speed')), ('3', context.tr('IP out')),
          ], '$_testSortBy', (value) => setState(() => _testSortBy = int.tryParse(value) ?? 0))),
          _menuRow(context.tr('Traffic sort by'), switch (_trafficSortBy) {
            1 => context.tr('Downloaded'), 2 => context.tr('Uploaded'),
            _ => context.tr('Total'),
          }, () => _pick(context.tr('Traffic sort by'), [
            ('0', context.tr('Total')), ('1', context.tr('Downloaded')), ('2', context.tr('Uploaded')),
          ], '$_trafficSortBy', (value) => setState(() => _trafficSortBy = int.tryParse(value) ?? 0))),
          _menuRow(context.tr('Test items to show'), switch (_testItemsToShow) {
            1 => context.tr('None'), 2 => context.tr('IP only'), 3 => context.tr('Speed only'),
            _ => context.tr('All'),
          }, () => _pick(context.tr('Test items to show'), [
            ('0', context.tr('All')), ('1', context.tr('None')), ('2', context.tr('IP only')), ('3', context.tr('Speed only')),
          ], '$_testItemsToShow', (value) => setState(() => _testItemsToShow = int.tryParse(value) ?? 0))),
        ]),
      ],
    );
  }
}
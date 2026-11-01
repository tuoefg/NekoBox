import 'package:flutter/services.dart';
import '../widgets.dart';
import '../components/code_editor.dart';
import '../ui/json/engine/json_tree.dart';
import '../ui/json/engine/schema_validator.dart';

class ProfileEditor extends StatefulWidget {
  const ProfileEditor({super.key, required this.profile});
  final Json profile;
  @override State<ProfileEditor> createState() => _ProfileEditorState();
}

class _ProfileEditorState extends State<ProfileEditor> {
  final _text = JsonCodeController();
  final _undo = UndoHistoryController();
  final _find = TextEditingController();
  String? _original, _error, _revision;
  bool _saving = false, _checking = false, _loading = false, _search = false;
  bool get _dirty => _original != null && _text.text != _original;

  @override void initState() {
    super.initState();
    _text.addListener(_changed);
    WidgetsBinding.instance.addPostFrameCallback((_) => _load());
  }
  void _changed() {
    if (!mounted) return;
    // The manifest is not a sing-box document: the only annotations are the parser's own.
    final parsed = JsonTree.parse(_text.text);
    _text.issues = parsed.ok
        ? const []
        : [JsonIssue(Severity.error, parsed.error, '', parsed.errorSpan)];
    setState(() {});
  }
  @override void dispose() { _text.dispose(); _undo.dispose(); _find.dispose(); super.dispose(); }

  Future<void> _load({bool confirmDiscard = false}) async {
    if (_saving || _checking || _loading) return;
    if (confirmDiscard && _dirty && !await confirm(context, 'Discard changes?', action: 'Discard')) return;
    if (!mounted) return;
    setState(() { _error = null; _loading = true; });
    try {
      final data = json(await context.box.call('profiles.config', {'id': widget.profile['id']}));
      final content = data['content'] as String;
      if (!mounted) return;
      _text.text = content;
      setState(() { _original = content; _revision = data['revision'] as String; });
    } catch (error) { if (mounted) setState(() => _error = BoxController.describe(error)); }
    finally { if (mounted) setState(() => _loading = false); }
  }

  Future<void> _save() async {
    if (_saving || _checking || _loading || _revision == null) return;
    final box = context.box;
    setState(() { _saving = true; _error = null; });
    try {
      final saved = json(await box.call('profiles.write', {'id': widget.profile['id'], 'content': _text.text, 'revision': _revision}));
      if (mounted) {
        setState(() { _original = _text.text; _revision = saved['revision'] as String; });
        ScaffoldMessenger.of(context).showSnackBar(SnackBar(content: Text(context.tr('Done'))));
      }
    } catch (error) { if (mounted) setState(() => _error = BoxController.describe(error)); }
    if (mounted) setState(() => _saving = false);
  }

  void _applyTemplate() {
    if (_text.text.trim().isNotEmpty && _text.text.trim() != '{}') return;
    _text.text = '''
{
  "name": "",
  "url": "",
  "nodes": []
}''';
  }

  Future<void> _check({bool format = false}) async {
    if (_checking || _saving || _loading) return;
    final box = context.box;
    setState(() { _checking = true; _error = null; });
    try {
      if (format) {
        // The engine pretty-prints locally; the whitelist check still needs the bridge.
        final formatted = formatJson(_text.text);
        if (!mounted) return;
        if (formatted == null) {
          setState(() => _error = context.tr('Not valid JSON'));
          return;
        }
        _text.text = formatted;
        ScaffoldMessenger.of(context).showSnackBar(SnackBar(content: Text(context.tr('Done'))));
      } else {
        final result = await box.call('config.check', {'content': _text.text});
        if (!mounted) return;
        if (result != null) {
          ScaffoldMessenger.of(context).showSnackBar(SnackBar(content: Text(context.tr('Configuration is valid'))));
        }
      }
    } catch (error) { if (mounted) setState(() => _error = BoxController.describe(error)); }
    if (mounted) setState(() => _checking = false);
  }

  void _nextMatch() {
    final query = _find.text;
    if (query.isEmpty) return;
    var start = _text.selection.isValid ? _text.selection.end : 0;
    var index = _text.text.indexOf(query, start);
    if (index < 0) index = _text.text.indexOf(query);
    if (index < 0) return;
    _text.selection = TextSelection(baseOffset: index, extentOffset: index + query.length);
  }

  @override Widget build(BuildContext context) => BoxEditGuard(dirty: _dirty, busy: _saving || _checking || _loading,
    child: CommonScaffold(
      title: widget.profile['name'].toString() + (_dirty ? ' •' : ''),
      isLoading: _saving || _checking || _loading || (_original == null && _error == null),
      confirmationAction: IconButtonData(glyph: AppGlyphs.check, tooltip: context.tr('Save'),
        onPressed: _saving || _checking || _loading || _original == null ? null : _save),
      iconActions: [
        IconButtonData(glyph: AppGlyphs.refresh, tooltip: context.tr('Reload'), onPressed: _saving || _checking || _loading ? null : () => _load(confirmDiscard: true)),
        IconButtonData(glyph: AppGlyphs.add, tooltip: context.tr('Template'), onPressed: _saving || _checking || _loading ? null : _applyTemplate),
        IconButtonData(glyph: AppGlyphs.code, tooltip: context.tr('Format'), onPressed: _saving || _checking || _loading || _original == null ? null : () => _check(format: true)),
        IconButtonData(glyph: AppGlyphs.verified, tooltip: context.tr('Validate'), onPressed: _saving || _checking || _loading || _original == null ? null : _check),
        IconButtonData(glyph: AppGlyphs.search, tooltip: context.tr('Search'), onPressed: () => setState(() => _search = !_search)),
        IconButtonData(glyph: AppGlyphs.undo, tooltip: context.tr('Undo'), onPressed: _saving || _checking || _loading ? null : _undo.undo),
        IconButtonData(glyph: AppGlyphs.redo, tooltip: context.tr('Redo'), onPressed: _saving || _checking || _loading ? null : _undo.redo),
      ],
      body: Material(type: MaterialType.transparency, child: Padding(
        padding: EdgeInsets.only(top: context.appBarInset, bottom: MediaQuery.paddingOf(context).bottom),
        child: Column(children: [
          if (_search) Padding(padding: const EdgeInsets.all(12), child: TextField(
            controller: _find, autofocus: true, onSubmitted: (_) => _nextMatch(),
            decoration: InputDecoration(hintText: context.tr('Search'), suffixIcon: IconButton(icon: const GlyphIcon(AppGlyphs.arrowDown), onPressed: _nextMatch)),
          )),
          if (_error != null) Container(
            width: double.infinity, padding: const EdgeInsets.all(12), color: context.colorScheme.errorContainer,
            child: SelectableText(_error!, style: TextStyle(color: context.colorScheme.onErrorContainer)),
          ),
          if (widget.profile['remote'] == true) Padding(padding: const EdgeInsets.all(12), child: Text(
            context.tr('Configuration changes will replace the remote content until its next update.'), style: context.listCaptionStyle,
          )),
          Expanded(child: _original == null
              ? _error == null ? const SizedBox.shrink() : ErrorState(_error!, retry: _load)
              : Column(children: [
                  JsonIssuesStrip(issues: _text.issues, text: _text.text, controller: _text),
                  Expanded(child: CodeEditor(
                    controller: _text, undoController: _undo, readOnly: _saving || _checking || _loading)),
                ])),
        ]),
      )),
    ),
  );
}

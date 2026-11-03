import 'dart:async';

import '../ui/json/engine/schema_completion.dart';
import '../ui/json/relaxations.dart';
import '../widgets.dart';
import '../components/code_editor.dart';
import '../components/form.dart';

/// The raw JSON editor of one node: the canonical sing-box document, checked against the schema
/// (the outbound root, or none at all for the Xray shapes the schema does not describe) with
/// Throne's relaxations, completed per caret context, formatted by the engine, saved through
/// `profiles.nodeUpdate` — whose model validation is the final gate.
class NodeJsonPage extends StatefulWidget {
  const NodeJsonPage({super.key, required this.id});
  final int id;
  @override State<NodeJsonPage> createState() => _NodeJsonPageState();
}

class _NodeJsonPageState extends State<NodeJsonPage> {
  final _text = JsonCodeController();
  final _undo = UndoHistoryController();
  final _name = TextEditingController();
  SchemaCompletion? _completion;
  String? _original, _error, _loadError;
  bool _saving = false, _loading = true, _formatting = false;
  Timer? _validateDebounce;
  bool get _dirty => _original != null && _text.text != _original;

  @override void initState() {
    super.initState();
    _text.addListener(_changed);
    WidgetsBinding.instance.addPostFrameCallback((_) => _load());
  }

  @override void dispose() {
    _validateDebounce?.cancel();
    _text.removeListener(_changed);
    _text.dispose();
    _undo.dispose();
    _name.dispose();
    super.dispose();
  }

  Future<void> _load({bool confirmDiscard = false}) async {
    // _loading starts true and only the load itself clears it, so guarding on it here made the first
    // load return before doing anything and the editor stayed an empty sheet.
    if (_saving) return;
    if (confirmDiscard && _dirty && !await confirm(context, 'Discard changes?', action: 'Discard')) return;
    if (!mounted) return;
    setState(() { _loadError = null; _loading = true; });
    try {
      final data = json(await context.box.call('profiles.nodeConfig', {'id': widget.id}));
      if (!mounted) return;
      _name.text = data['name']?.toString() ?? '';
      final content = data['json']?.toString() ?? '';
      final document = await SchemaStore.get();
      SchemaCompletion? completion;
      List<JsonIssue> issues = const [];
      if (document != null) {
        final type = _documentType(content);
        final relaxations = ProfileJsonTables.relaxations(type);
        final roots = ProfileJsonTables.schemaRoots(
          xray: data['xray'] == true, endpoint: data['endpoint'] == true);
        final validator = SchemaStore.validator(document, roots);
        if (validator != null) {
          relaxations.applyTo(validator);
          issues = validator.validate(JsonTree.parse(content).root!);
        }
        completion = SchemaCompletion(document, roots, typeAliases: relaxations.typeAliases);
      }
      if (!mounted) return;
      setState(() {
        _text.text = content;
        _text.issues = issues;
        _completion = completion;
        _original = content;
        _loading = false;
      });
    } catch (error) {
      if (mounted) setState(() { _loadError = BoxController.describe(error); _loading = false; });
    }
  }

  /// The configuration's own type name, for the relaxation tables.
  String _documentType(String content) {
    final parsed = JsonTree.parse(content);
    final type = parsed.root?.member('type');
    return type?.string ?? '';
  }

  void _changed() {
    if (!mounted) return;
    setState(() {});
    _validateDebounce?.cancel();
    _validateDebounce = Timer(const Duration(milliseconds: 300), _validate);
  }

  /// The validator answers per keystroke batch; a document that does not parse shows the parse error.
  void _validate() {
    if (!mounted) return;
    final documentSchema = _completion;
    final parsed = JsonTree.parse(_text.text);
    if (parsed.root == null) {
      setState(() => _text.issues = [JsonIssue(Severity.error, parsed.error, '', parsed.errorSpan)]);
      return;
    }
    if (documentSchema == null) {
      setState(() => _text.issues = const []);
      return;
    }
    final validator = SchemaStore.validator(documentSchema.doc, documentSchema.roots);
    if (validator == null) {
      setState(() => _text.issues = const []);
      return;
    }
    final type = _documentType(_text.text);
    ProfileJsonTables.relaxations(type).applyTo(validator);
    setState(() => _text.issues = validator.validate(parsed.root!));
  }

  Future<void> _format() async {
    if (_formatting) return;
    setState(() => _formatting = true);
    final formatted = formatJson(_text.text);
    if (!mounted) return;
    setState(() {
      _formatting = false;
      if (formatted != null) _text.text = formatted;
    });
    if (formatted == null) {
      showAppBanner(context, context.tr('Not valid JSON'));
    }
  }

  Future<void> _save() async {
    if (_saving || _loading) return;
    final box = context.box;
    setState(() => _saving = true);
    try {
      await box.call('profiles.nodeUpdate', {'id': widget.id, 'name': _name.text.trim(), 'content': _text.text});
      if (!mounted) return;
      setState(() { _original = _text.text; _saving = false; });
      showAppBanner(context, context.tr('Done'));
    } catch (error) {
      if (!mounted) return;
      setState(() { _saving = false; _error = BoxController.describe(error); });
    }
  }

  @override Widget build(BuildContext context) {
    return Scaffold(
      title: _name.text.isEmpty ? context.tr('Edit') : _name.text.toString() + (_dirty ? ' •' : ''),
      isLoading: _loading,
      confirmationAction: IconButtonData(glyph: AppGlyphs.check, tooltip: context.tr('Save'),
        onPressed: _saving || _loading || !_dirty ? null : _save),
      iconActions: [
        IconButtonData(glyph: AppGlyphs.refresh, tooltip: context.tr('Reload'),
          onPressed: _saving || _loading ? null : () => _load(confirmDiscard: true)),
        IconButtonData(glyph: AppGlyphs.code, tooltip: context.tr('Format'),
          onPressed: _saving || _loading ? null : _format),
      ],
      body: _loading ? const SizedBox.shrink() : _loadError != null
          ? ErrorState(_loadError!, retry: _load)
          : BoxEditGuard(dirty: _dirty, busy: _saving, child: Padding(
              padding: EdgeInsets.only(top: context.appBarInset),
              child: Column(crossAxisAlignment: CrossAxisAlignment.stretch, children: [
                if (_error != null) Container(
                  width: double.infinity, padding: const EdgeInsets.all(12),
                  color: context.colorScheme.errorContainer,
                  child: SelectableText(_error!, style: TextStyle(color: context.colorScheme.onErrorContainer))),
                Padding(padding: const EdgeInsets.fromLTRB(16, 8, 16, 0), child: FormRow(
                  title: context.tr('Name'),
                  trailing: FormRowTextField(controller: _name))),
                JsonIssuesStrip(issues: _text.issues, text: _text.text, controller: _text),
                Expanded(child: CodeEditor(
                  controller: _text, undoController: _undo,
                  completion: _completion)),
              ]),
            )),
    );
  }
}

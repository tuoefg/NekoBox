import 'dart:async';
import 'dart:math' as math;


import '../widgets.dart';

import '../ui/json/engine/json_formatter.dart';
import '../ui/json/engine/json_tree.dart';
import '../ui/json/engine/schema_completion.dart';
import '../ui/json/engine/schema_validator.dart';

/// A JSON text field with a gutter for logical lines, including wrapped lines, engine-driven token
/// colors, wavy underlines for [JsonIssue]s and, with a [SchemaCompletion], the suggestion panel.
class CodeEditor extends StatefulWidget {
  const CodeEditor({
    super.key,
    required this.controller,
    required this.undoController,
    this.readOnly = false,
    this.completion,
    this.issues = const [],
  });
  final JsonCodeController controller;
  final UndoHistoryController undoController;
  final bool readOnly;

  /// Non-null turns the suggestion panel on; the completer answers per caret context.
  final SchemaCompletion? completion;
  final List<JsonIssue> issues;

  @override State<CodeEditor> createState() => _CodeEditorState();
}

/// A [TextEditingController] whose [buildTextSpan] paints the engine's tokens: keys, strings,
/// numbers, literals and comments, plus a wavy underline per issue span.
class JsonCodeController extends TextEditingController {
  JsonCodeController({super.text});

  /// Set by the page when the validator (or the parser) ran; cleared on the next change.
  List<JsonIssue> issues = [];

  @override
  TextSpan buildTextSpan({required BuildContext context, TextStyle? style, required bool withComposing}) {
    final text = this.text;
    if (text.length > 400000 || value.composing.isValid && !value.composing.isCollapsed) {
      return super.buildTextSpan(context: context, style: style, withComposing: withComposing);
    }
    final colors = Theme.of(context).colorScheme;
    final parsed = JsonTree.parse(text);
    final spans = <TextSpan>[];
    var cursor = 0;

    // The issue covering [offset], if any; its wavy underline rides on the token's own span.
    JsonIssue? issueAt(int offset) {
      for (final issue in issues) {
        if (issue.span.length > 0 && offset >= issue.span.offset && offset < issue.span.end) return issue;
      }
      return null;
    }

    void emit(int start, int end, Color? color, {bool italic = false}) {
      if (end <= start) return;
      if (start > cursor) spans.add(TextSpan(text: text.substring(cursor, start)));
      final issue = issueAt(start);
      spans.add(TextSpan(
        text: text.substring(start, end),
        style: TextStyle(
          color: color,
          fontStyle: italic ? FontStyle.italic : null,
          decoration: issue == null ? null : TextDecoration.underline,
          decorationStyle: TextDecorationStyle.wavy,
          decorationColor: issue == null ? null
              : issue.severity == Severity.error ? colors.error : const Color(0xfff08c00),
          decorationThickness: issue == null ? null : 2,
        ),
      ));
      cursor = end;
    }

    if (parsed.root != null) {
      _paint(text, parsed.root!, colors, emit);
    } else {
      // The parse error underlines its own span; the rest stays plain.
      final span = parsed.errorSpan;
      if (span.length > 0) emit(span.offset, math.min(span.end, text.length), null);
    }
    if (cursor < text.length) spans.add(TextSpan(text: text.substring(cursor)));
    return TextSpan(style: style, children: spans);
  }

  void _paint(String text, JsonValue value, ColorScheme colors,
      void Function(int, int, Color?, {bool italic}) emit) {
    void walk(JsonValue node) {
      switch (node.type) {
        case JsonType.object_ || JsonType.array:
          if (node.type == JsonType.object_) {
            for (var i = 0; i < node.keys.length; i++) {
              final key = node.keySpans[i];
              emit(key.offset, key.end, colors.primary);
              walk(node.members[i]);
            }
          } else {
            for (final element in node.elements) {
              walk(element);
            }
          }
        case JsonType.string:
          emit(node.span.offset, node.span.end, colors.tertiary);
        case JsonType.number:
          emit(node.span.offset, node.span.end, colors.secondary);
        case JsonType.bool_ || JsonType.null_:
          emit(node.span.offset, node.span.end, colors.secondary, italic: true);
      }
    }
    walk(value);
  }
}

class _CodeEditorState extends State<CodeEditor> {
  final _scroll = ScrollController();
  String? _content;
  int _lineCount = 1;
  Object? _layoutKey;
  List<({int number, double top})> _lines = [];
  List<Suggestion> _suggestions = [];
  Timer? _suggestDebounce;
  int _suggestCaret = -1;

  @override void initState() {
    super.initState();
    widget.controller.addListener(_changed);
    _changed();
  }

  void _changed() {
    if (_content == widget.controller.text) return;
    setState(() {
      _content = widget.controller.text;
      _lineCount = '\n'.allMatches(_content!).length + 1;
    });
    _scheduleSuggestions();
  }

  @override void didUpdateWidget(CodeEditor oldWidget) {
    super.didUpdateWidget(oldWidget);
    if (oldWidget.controller != widget.controller) {
      oldWidget.controller.removeListener(_changed);
      widget.controller.addListener(_changed);
      _changed();
    }
  }

  @override void dispose() {
    widget.controller.removeListener(_changed);
    _suggestDebounce?.cancel();
    _scroll.dispose();
    super.dispose();
  }

  void _scheduleSuggestions() {
    final completion = widget.completion;
    if (completion == null) return;
    _suggestDebounce?.cancel();
    _suggestDebounce = Timer(const Duration(milliseconds: 150), () {
      if (!mounted) return;
      final text = widget.controller.text;
      final caret = widget.controller.selection.baseOffset;
      if (caret < 0 || caret > text.length) {
        if (_suggestions.isEmpty) return;
        setState(() { _suggestions = const []; });
        return;
      }
      final suggestions = completion.suggest(text, caret);
      if (suggestions.isEmpty && _suggestions.isEmpty) return;
      setState(() {
        _suggestions = suggestions;
        _suggestCaret = caret;
      });
    });
  }

  void _apply(Suggestion suggestion) {
    final controller = widget.controller;
    final caret = controller.selection.baseOffset;
    if (caret < 0) return;
    final start = math.max(0, caret - suggestion.before);
    final end = math.min(controller.text.length, caret + suggestion.after);
    final replaced = controller.text.replaceRange(start, end, suggestion.insert);
    final selectionStart = start + suggestion.selectStart;
    final selectionEnd = start + suggestion.selectEnd;
    controller.issues = const [];
    controller.value = TextEditingValue(
      text: replaced,
      selection: TextSelection(
        baseOffset: selectionStart.clamp(0, replaced.length),
        extentOffset: selectionEnd.clamp(0, replaced.length),
      ),
    );
  }

  @override Widget build(BuildContext context) {
    final scheme = Theme.of(context).colorScheme;
    final style = TextStyle(inherit: false, fontFamily: 'JetBrainsMono', fontSize: 13,
      fontWeight: FontWeight.normal, textBaseline: TextBaseline.alphabetic, height: 1.6, color: scheme.onSurface);
    final strut = StrutStyle.fromTextStyle(style, forceStrutHeight: true);
    final scaler = MediaQuery.textScalerOf(context);
    final locale = Localizations.maybeLocaleOf(context);
    final number = TextPainter(text: TextSpan(text: '$_lineCount', style: style),
      textDirection: TextDirection.ltr, textScaler: scaler, strutStyle: strut, locale: locale)..layout();
    final gutterWidth = number.width + 24;
    final numberBaseline = number.computeLineMetrics().single.baseline;
    number.dispose();
    return LayoutBuilder(builder: (context, constraints) {
      // RenderEditable in the pinned Flutter version reserves cursorWidth + 1
      // pixels after its text. Keep the same wrapping width as the text field.
      final textWidth = math.max(0.0, constraints.maxWidth - gutterWidth - 12 - 16 - 3);
      final layoutKey = (_content, textWidth, style, scaler, locale);
      if (_layoutKey != layoutKey) {
        final text = TextPainter(text: TextSpan(text: _content, style: style),
          textDirection: TextDirection.ltr, textScaler: scaler, strutStyle: strut, locale: locale)
          ..layout(minWidth: textWidth, maxWidth: textWidth);
        var logicalLine = 1;
        var start = true;
        _lines = [];
        for (final line in text.computeLineMetrics()) {
          if (start) _lines.add((number: logicalLine, top: line.baseline - numberBaseline));
          start = line.hardBreak;
          if (start) logicalLine++;
        }
        text.dispose();
        _layoutKey = layoutKey;
      }
      return Column(children: [
        Expanded(child: Row(textDirection: TextDirection.ltr, crossAxisAlignment: CrossAxisAlignment.stretch, children: [
          ExcludeSemantics(child: SizedBox(width: gutterWidth, child: ColoredBox(
            color: scheme.surfaceContainerLow,
            child: ClipRect(child: ListenableBuilder(listenable: _scroll, builder: (context, _) {
              final offset = _scroll.hasClients ? _scroll.offset : 0.0;
              // Only materialize visible numbers; scrolling does not lay out the document again.
              var low = 0, high = _lines.length;
              while (low < high) {
                final middle = (low + high) ~/ 2;
                if (_lines[middle].top < offset - 16) { low = middle + 1; }
                else { high = middle; }
              }
              final first = math.max(0, low - 1);
              return Stack(fit: StackFit.expand, children: [
                for (var index = first; index < _lines.length && _lines[index].top < offset + constraints.maxHeight; index++)
                  Positioned(top: 16 + _lines[index].top - offset, left: 0, right: 12,
                    child: Text('${_lines[index].number}', key: ValueKey('code-line-${_lines[index].number}'),
                      textAlign: TextAlign.right, textDirection: TextDirection.ltr,
                      style: style.copyWith(color: scheme.onSurfaceVariant), strutStyle: strut)),
              ]);
            })),
          ))),
          Expanded(child: NotificationListener<ScrollMetricsNotification>(onNotification: (_) {
            // A shorter document can clamp the offset during layout without a scroll event.
            WidgetsBinding.instance.addPostFrameCallback((_) { if (mounted) setState(() {}); });
            return false;
          }, child: TextField(
            controller: widget.controller, undoController: widget.undoController, scrollController: _scroll,
            expands: true, maxLines: null, minLines: null, readOnly: widget.readOnly,
            autocorrect: false, enableSuggestions: false,
            smartDashesType: SmartDashesType.disabled, smartQuotesType: SmartQuotesType.disabled,
            keyboardType: TextInputType.multiline, textDirection: TextDirection.ltr,
            textAlignVertical: TextAlignVertical.top, style: style, strutStyle: strut, cursorWidth: 2,
            decoration: const InputDecoration(border: InputBorder.none, isDense: true,
              contentPadding: EdgeInsets.fromLTRB(12, 16, 16, 16)),
          ))),
        ])),
        if (_suggestions.isNotEmpty) _SuggestionPanel(
          suggestions: _suggestions, onApply: _apply,
          onDismiss: () => setState(() => _suggestions = const []),
          currentCaret: _suggestCaret,
        ),
      ]);
    });
  }
}

/// The completion panel, docked under the field: label, detail, and the insert preview.
class _SuggestionPanel extends StatelessWidget {
  const _SuggestionPanel({required this.suggestions, required this.onApply, required this.onDismiss, required this.currentCaret});
  final List<Suggestion> suggestions;
  final ValueChanged<Suggestion> onApply;
  final VoidCallback onDismiss;
  final int currentCaret;

  @override Widget build(BuildContext context) {
    final colors = Theme.of(context).colorScheme;
    return Material(
      color: colors.surfaceContainerHigh,
      elevation: 4,
      child: SafeArea(top: false, child: ConstrainedBox(
        constraints: const BoxConstraints(maxHeight: 220),
        child: Column(mainAxisSize: MainAxisSize.min, crossAxisAlignment: CrossAxisAlignment.stretch, children: [
          Row(children: [
            Padding(padding: const EdgeInsets.only(left: 16),
              child: Text('${suggestions.length}', style: context.listCaptionStyle)),
            const Spacer(),
            IconButton(icon: const GlyphIcon(AppGlyphs.close), tooltip: 'Dismiss',
              onPressed: onDismiss),
          ]),
          Flexible(child: ListView.builder(
            shrinkWrap: true,
            itemCount: math.min(suggestions.length, 10),
            itemBuilder: (context, index) {
              final suggestion = suggestions[index];
              return ListItem(
                title: Text(suggestion.label, maxLines: 1, overflow: TextOverflow.ellipsis),
                subtitle: suggestion.detail.isEmpty ? null : Text(suggestion.detail,
                  maxLines: 1, overflow: TextOverflow.ellipsis, style: context.listCaptionStyle),
                onTap: () => onApply(suggestion),
              );
            },
          )),
        ]),
      )),
    );
  }
}

/// The issues strip the pages render above or below the editor: line, column, message; a tap moves
/// the caret to the span so the underline is on screen.
class JsonIssuesStrip extends StatelessWidget {
  const JsonIssuesStrip({super.key, required this.issues, required this.text, required this.controller});
  final List<JsonIssue> issues;
  final String text;
  final TextEditingController controller;

  @override Widget build(BuildContext context) {
    if (issues.isEmpty) return const SizedBox.shrink();
    final colors = Theme.of(context).colorScheme;
    return ConstrainedBox(
      constraints: const BoxConstraints(maxHeight: 120),
      child: Material(
        color: colors.surfaceContainerLow,
        child: ListView(shrinkWrap: true, children: [
          for (final issue in issues.take(20)) ListItem(
            title: Text(issue.message, maxLines: 2, overflow: TextOverflow.ellipsis),
            subtitle: Text(() {
              final at = JsonTree.lineColumn(text, issue.span.offset);
              return '${at[0]}:${at[1]}';
            }(), style: context.listCaptionStyle),
            trailing: GlyphIcon(issue.severity == Severity.error ? AppGlyphs.warning : AppGlyphs.info,
              size: 16, color: issue.severity == Severity.error ? colors.error : const Color(0xfff08c00)),
            onTap: () {
              final offset = issue.span.offset.clamp(0, text.length);
              controller.value = TextEditingValue(
                text: text,
                selection: TextSelection.collapsed(offset: offset),
              );
            },
          ),
        ]),
      ),
    );
  }
}

/// Formats [text] with the engine's pretty printer; null when the text does not parse.
String? formatJson(String text) => JsonFormatter.format(text);

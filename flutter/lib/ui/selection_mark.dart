import 'package:flutter/widgets.dart';
import 'package:b_box_ui/ui/context.dart';
import 'package:b_box_ui/ui/icons/icons.dart';
import 'package:b_box_ui/ui/shape.dart';

class SelectionCheck extends StatelessWidget {
  const SelectionCheck({super.key});

  static const size = 24.0;

  @override
  Widget build(BuildContext context) {
    return GlyphIcon(
      Glyphs.check,
      size: size,
      fill: 1,
      color: context.colorScheme.primary,
    );
  }
}

class SelectionBadge extends StatelessWidget {
  const SelectionBadge({
    super.key,
    this.size = 22,
    this.glyph = Glyphs.check,
    this.tonal = false,
  });

  final double size;
  final Glyph glyph;
  final bool tonal;

  @override
  Widget build(BuildContext context) {
    final colorScheme = context.colorScheme;
    return SizedBox.square(
      dimension: size,
      child: DecoratedBox(
        decoration: ShapeDecoration(
          shape: AppShape.circle,
          color: tonal
              ? colorScheme.primary.withValues(alpha: 0.14)
              : colorScheme.primary,
        ),
        child: Center(
          child: GlyphIcon(
            glyph,
            size: size - 6,
            fill: 1,
            color: tonal ? colorScheme.primary : colorScheme.onPrimary,
          ),
        ),
      ),
    );
  }
}

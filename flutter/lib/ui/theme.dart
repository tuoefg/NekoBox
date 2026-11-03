import 'package:flutter/widgets.dart';
import 'package:b_box_ui/ui/common_button.dart';
import 'package:b_box_ui/ui/metrics.dart';
import 'package:b_box_ui/ui/theme_data.dart';

class MinFilledButtonTheme extends StatelessWidget {
  final Widget child;

  const MinFilledButtonTheme({super.key, required this.child});

  @override
  Widget build(BuildContext context) {
    return ButtonTheme.merge(
      data: const ButtonThemeData(
        filled: ButtonStyle(
          padding: EdgeInsets.symmetric(horizontal: 8),
          tapTargetSize: TapTargetSize.shrinkWrap,
          visualDensity: VisualDensity.compact,
        ),
      ),
      child: child,
    );
  }
}

class MinIconButtonTheme extends StatelessWidget {
  final Widget child;

  const MinIconButtonTheme({super.key, required this.child});

  @override
  Widget build(BuildContext context) {
    return ButtonTheme.merge(
      data: ButtonThemeData(
        icon: ButtonStyle(
          padding: const EdgeInsets.symmetric(horizontal: 4),
          visualDensity: VisualDensity.compact,
          tapTargetSize: TapTargetSize.shrinkWrap,
          iconSize: 20.ap(context),
        ),
      ),
      child: child,
    );
  }
}

import 'package:flutter/widgets.dart';

import 'screens/animation_screen.dart';
import 'screens/app_lifecycle_screen.dart';
import 'screens/input_screen.dart';
import 'screens/keyboard_screen.dart';
import 'screens/orientation_screen.dart';
import 'screens/scroll_screen.dart';
import 'screens/swipe_screen.dart';
import 'screens/tap_screen.dart';
import 'screens/tree_screen.dart';

class RouterScreen extends StatelessWidget {
  final String route;
  const RouterScreen({super.key, required this.route});

  @override
  Widget build(BuildContext context) {
    switch (route) {
      case 'SwipeScreen':
        return const SwipeScreen();
      case 'ScrollScreen':
        return const ScrollScreen();
      case 'InputScreen':
        return const InputScreen();
      case 'KeyboardScreen':
        return const KeyboardScreen();
      case 'TreeScreen':
        return const TreeScreen();
      case 'OrientationScreen':
        return const OrientationScreen();
      case 'AnimationScreen':
        return const AnimationScreen();
      case 'AppLifecycleScreen':
        return const AppLifecycleScreen();
      case 'TapScreen':
      default:
        return const TapScreen();
    }
  }
}

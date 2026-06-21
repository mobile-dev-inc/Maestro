import 'package:flutter/material.dart';
import '../emitter.dart';

/// Swipe surface via GestureDetector pan callbacks. Emits TOUCH on press and SWIPE on release with
/// direction/distance/duration — same payload shape as the other fixtures.
class SwipeScreen extends StatefulWidget {
  const SwipeScreen({super.key});
  @override
  State<SwipeScreen> createState() => _SwipeScreenState();
}

class _SwipeScreenState extends State<SwipeScreen> {
  double _dx = 0, _dy = 0;
  int _downTime = 0;

  @override
  Widget build(BuildContext context) {
    return Semantics(
      identifier: 'swipe_surface',
      label: 'swipe_surface',
      container: true,
      child: GestureDetector(
        behavior: HitTestBehavior.opaque,
        onPanStart: (d) {
          _dx = 0;
          _dy = 0;
          _downTime = DateTime.now().millisecondsSinceEpoch;
          emit('TOUCH', {
            'x': d.localPosition.dx.round(),
            'y': d.localPosition.dy.round(),
          });
        },
        onPanUpdate: (d) {
          _dx += d.delta.dx;
          _dy += d.delta.dy;
        },
        onPanEnd: (d) {
          final durationMs = DateTime.now().millisecondsSinceEpoch - _downTime;
          if (_dx.abs() > 10 || _dy.abs() > 10) {
            final dir = _dx.abs() >= _dy.abs()
                ? (_dx > 0 ? 'RIGHT' : 'LEFT')
                : (_dy > 0 ? 'DOWN' : 'UP');
            emit('SWIPE', {
              'dir': dir,
              'dx': _dx.round(),
              'dy': _dy.round(),
              'durationMs': durationMs,
              'target': 'swipe_surface',
            });
          }
        },
        child: Container(
          color: const Color(0xFF4FC3F7),
          alignment: Alignment.center,
          child: const Text('swipe surface'),
        ),
      ),
    );
  }
}

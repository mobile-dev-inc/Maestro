import 'package:flutter/material.dart';
import '../emitter.dart';

/// waitUntilScreenIsStatic / waitForAppToSettle → a ~1.5s fade. Emits ANIM RUNNING at start and
/// SETTLED on completion, mirroring the other fixtures.
class AnimationScreen extends StatefulWidget {
  const AnimationScreen({super.key});
  @override
  State<AnimationScreen> createState() => _AnimationScreenState();
}

class _AnimationScreenState extends State<AnimationScreen>
    with SingleTickerProviderStateMixin {
  late final AnimationController _controller;

  @override
  void initState() {
    super.initState();
    _controller = AnimationController(
      vsync: this,
      duration: const Duration(milliseconds: 1500),
    );
    emit('ANIM', {'state': 'RUNNING'});
    _controller.forward().whenComplete(() => emit('ANIM', {'state': 'SETTLED'}));
  }

  @override
  void dispose() {
    _controller.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    return Center(
      child: FadeTransition(
        opacity: _controller,
        child: Semantics(
          identifier: 'animate_button',
          label: 'animate_button',
          child: Container(
            width: 160,
            height: 64,
            decoration: BoxDecoration(
              color: const Color(0xFF7E57C2),
              borderRadius: BorderRadius.circular(8),
            ),
          ),
        ),
      ),
    );
  }
}

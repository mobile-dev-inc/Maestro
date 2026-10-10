import 'package:flutter/material.dart';

/// Backs the `scrollUntilVisible_visibilityPercentage` flow, which checks that
/// `visibilityPercentage` is actually compared against the element's visible
/// fraction (https://github.com/mobile-dev-inc/Maestro/issues/3607).
///
/// Everything is sized as a fraction of the scroll viewport, so the two states
/// the flow asserts on are decided by layout rather than by how far any
/// particular swipe happens to travel:
///
///  * unscrolled — the target is 50% visible, clipped by the bottom of the
///    viewport, and only its upper marker is on screen;
///  * fully scrolled — the target is 100% visible and both markers are on
///    screen.
///
/// The content is just 1.5 viewports tall, so a downward swipe of any strength
/// clamps at the end of the list and lands in the second state. That keeps the
/// flow independent of scroll momentum, which differs between platforms.
///
/// `clipBehavior: Clip.none` is load-bearing. With the default clip, Flutter
/// trims the target's semantics rect to the viewport, so Maestro is handed
/// bounds that sit entirely on screen and computes 100% visibility no matter
/// how much of the target is really showing — the same reporting problem as
/// https://github.com/mobile-dev-inc/Maestro/issues/2411, which would leave
/// every threshold satisfied and the flow asserting nothing. Unclipped, the
/// target reports bounds that overhang the bottom of the screen and Maestro
/// derives a real 50%. The cost is that scrolled-away content paints over the
/// app bar, which is cosmetic and does not affect the flow.
class PartialVisibilityScreen extends StatelessWidget {
  const PartialVisibilityScreen({super.key});

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(
        backgroundColor: Theme.of(context).colorScheme.inversePrimary,
        title: const Text('Partial Visibility'),
      ),
      body: LayoutBuilder(
        builder: (context, constraints) {
          final viewport = constraints.maxHeight;
          final targetHeight = viewport * 0.5;

          return SingleChildScrollView(
            clipBehavior: Clip.none,
            child: Column(
              children: [
                // Pushes the target half out of the viewport before any scroll.
                SizedBox(
                  height: viewport * 0.75,
                  child: const Center(child: Text('Scroll for the target')),
                ),
                Semantics(
                  container: true,
                  explicitChildNodes: true,
                  label: 'Visibility target block',
                  child: Container(
                    height: targetHeight,
                    width: double.infinity,
                    color: Colors.indigo.shade100,
                    child: Stack(
                      children: [
                        const Positioned(
                          top: 8,
                          left: 8,
                          child: Text('Target upper marker'),
                        ),
                        // At 70% of the target's height: on screen only once
                        // the target is more than 70% visible.
                        Positioned(
                          top: targetHeight * 0.7,
                          left: 8,
                          child: const Text('Target lower marker'),
                        ),
                      ],
                    ),
                  ),
                ),
                // Leaves just enough scroll extent for the target to come fully
                // into view, and no more.
                SizedBox(height: viewport * 0.25),
              ],
            ),
          );
        },
      ),
    );
  }
}

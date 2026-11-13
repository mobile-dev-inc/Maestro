import 'package:flutter/material.dart';

/// Reproduces https://github.com/mobile-dev-inc/Maestro/issues/1409.
///
/// On iOS, a touchable with `accessibilityLabel` is exposed as a single
/// accessibility element whose `label` carries the label string. iOS does NOT
/// expose the inner `Text` child as a separate accessibility element, so the
/// visible text ("Click me!") is unreachable through the accessibility tree.
///
/// This mirrors React Native's
/// `<Pressable accessibilityLabel="easy-button"><Text>Click me!</Text></Pressable>`
/// using Flutter's `Semantics(container, button, label, excludeSemantics)` over
/// a `GestureDetector`. With label != visible text, `tapOn: "Click me!"` has
/// nothing to match against in any element's `text`/`label`/`accessibilityText`
/// — that's the #1409 failure.
///
/// The touchable is intentionally positioned in the top-left (not centered)
/// inside a larger column so a parent-bounds tap (a possible fallback in some
/// matchers) cannot accidentally land on the touchable region.
class Issue1409Repro extends StatefulWidget {
  const Issue1409Repro({super.key});

  @override
  State<Issue1409Repro> createState() => _Issue1409ReproState();
}

class _Issue1409ReproState extends State<Issue1409Repro> {
  bool _tapped = false;

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(
        title: const Text('issue 1409 repro'),
      ),
      body: SizedBox.expand(
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            const SizedBox(height: 24),
            Padding(
              padding: const EdgeInsets.only(left: 16),
              child: Semantics(
                container: true,
                button: true,
                label: 'easy-button',
                excludeSemantics: true,
                child: GestureDetector(
                  onTap: () => setState(() => _tapped = true),
                  child: Container(
                    padding: const EdgeInsets.symmetric(
                        horizontal: 16, vertical: 10),
                    decoration: BoxDecoration(
                      color: Colors.blueGrey.shade100,
                      borderRadius: BorderRadius.circular(6),
                    ),
                    child: const Text('Click me!'),
                  ),
                ),
              ),
            ),
            const Spacer(),
            if (_tapped)
              const Padding(
                padding: EdgeInsets.only(left: 16, bottom: 16),
                child: Text('tapped!'),
              ),
          ],
        ),
      ),
    );
  }
}

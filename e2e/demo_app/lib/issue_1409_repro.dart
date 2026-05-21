import 'package:flutter/material.dart';

/// Reproduces https://github.com/mobile-dev-inc/Maestro/issues/1409.
///
/// On iOS, when a touchable parent has an accessibility label, iOS treats it as
/// a single accessibility element and does not expose its child labels. The
/// React Native pattern `<Pressable accessibilityLabel="X"><Text>X</Text></Pressable>`
/// hits this: only the parent is in the hierarchy, with `label="X"` but empty
/// `title`/`value`. Maestro's iOS hierarchy mapping populates the `text`
/// attribute from `title`/`value` only, so `tapOn: "X"` fails to find anything.
///
/// We mirror the RN structure in Flutter with `Semantics(container, label,
/// excludeSemantics)` over a `GestureDetector`, which produces the same
/// "label-only, no text" element on iOS.
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
      body: Center(
        child: Column(
          mainAxisAlignment: MainAxisAlignment.center,
          children: [
            Semantics(
              container: true,
              button: true,
              label: 'Continue with Google',
              excludeSemantics: true,
              child: GestureDetector(
                onTap: () => setState(() => _tapped = true),
                child: Container(
                  padding: const EdgeInsets.symmetric(
                      horizontal: 24, vertical: 12),
                  decoration: BoxDecoration(
                    color: Colors.blueGrey.shade100,
                    borderRadius: BorderRadius.circular(8),
                  ),
                  child: const Text('Continue with Google'),
                ),
              ),
            ),
            const SizedBox(height: 24),
            if (_tapped) const Text('tapped!'),
          ],
        ),
      ),
    );
  }
}

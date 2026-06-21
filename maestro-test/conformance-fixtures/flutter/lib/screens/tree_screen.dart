import 'package:flutter/material.dart';

/// contentDescriptor → a small tree with stable identifiers (identifier→resource-id,
/// label→content-desc). explicitChildNodes keeps the children as distinct nodes.
class TreeScreen extends StatelessWidget {
  const TreeScreen({super.key});

  @override
  Widget build(BuildContext context) {
    return Semantics(
      identifier: 'tree_root',
      label: 'tree_root',
      container: true,
      explicitChildNodes: true,
      child: Padding(
        padding: const EdgeInsets.symmetric(horizontal: 40, vertical: 80),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Semantics(
              identifier: 'tree_label_a',
              label: 'tree_label_a',
              child: const Padding(
                padding: EdgeInsets.symmetric(vertical: 20),
                child: Text('Label A', style: TextStyle(fontSize: 18)),
              ),
            ),
            Semantics(
              identifier: 'tree_button_b',
              label: 'tree_button_b',
              button: true,
              child: ElevatedButton(
                onPressed: () {},
                child: const Text('Button B'),
              ),
            ),
          ],
        ),
      ),
    );
  }
}

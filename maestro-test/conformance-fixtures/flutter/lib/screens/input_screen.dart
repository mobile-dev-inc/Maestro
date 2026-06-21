import 'package:flutter/material.dart';
import '../emitter.dart';

/// inputText / eraseText → a TextField; emits TEXT_CHANGED on every change.
class InputScreen extends StatelessWidget {
  const InputScreen({super.key});

  @override
  Widget build(BuildContext context) {
    return Padding(
      padding: const EdgeInsets.fromLTRB(40, 200, 40, 0),
      child: Semantics(
        identifier: 'text_field',
        textField: true,
        child: TextField(
          decoration: const InputDecoration(
            hintText: 'Type here...',
            border: OutlineInputBorder(),
          ),
          onChanged: (t) => emit('TEXT_CHANGED', {'text': t}),
        ),
      ),
    );
  }
}

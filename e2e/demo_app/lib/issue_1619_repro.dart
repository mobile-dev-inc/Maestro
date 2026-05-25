import 'package:flutter/material.dart';

class Issue1619Repro extends StatefulWidget {
  const Issue1619Repro({super.key});

  @override
  State<Issue1619Repro> createState() => _Issue1619ReproState();
}

class _Issue1619ReproState extends State<Issue1619Repro> {
  int count = 0;

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(
        title: const Text('issue 1619 repro'),
      ),
      body: Center(
        child: Column(
          children: [
            Semantics(
              identifier: 'first',
              child: const Text("First"),
            ),
            Semantics(
              identifier: 'second',
              child: const Text("Second"),
            ),
          ],
        ),
      ),
    );
  }
}

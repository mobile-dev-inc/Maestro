import 'package:flutter/material.dart';

class Issue1677Repro extends StatefulWidget {
  const Issue1677Repro({super.key});

  @override
  State<Issue1677Repro> createState() => _Issue1677ReproState();
}

class _Issue1677ReproState extends State<Issue1677Repro> {
  int count = 0;

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(
        title: const Text('issue 1677 repro'),
      ),
      body: Center(
        child: Column(
          children: [
            Text('tap count: $count'),
            Semantics(
              identifier: 'parent_ident',
              child: InkWell(
                onTap: () => setState(() => count++),
                child: Container(
                  color: Colors.blue,
                  padding: const EdgeInsets.all(32),
                  child: Semantics(
                    identifier: 'child_ident',
                    child: const Text('press me'),
                  ),
                ),
              ),
            ),
          ],
        ),
      ),
    );
  }
}

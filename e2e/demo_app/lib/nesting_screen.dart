import 'package:flutter/material.dart';

class NestingScreen extends StatefulWidget {
  const NestingScreen({super.key});

  @override
  State<NestingScreen> createState() => _NestingScreenState();
}

class _NestingScreenState extends State<NestingScreen> {
  int count = 0;

  @override
  Widget build(BuildContext context) {
    const padding = EdgeInsets.symmetric(horizontal: 40, vertical: 48);

    return Scaffold(
      appBar: AppBar(
        title: const Text('Nesting test screen'),
      ),
      body: Container(
        padding: const EdgeInsets.all(8),
        child: Stack(
          children: [
            const Align(
              alignment: Alignment.centerLeft,
              child: RotatedBox(
                quarterTurns: 3,
                child: Text('left side'),
              ),
            ),
            const Align(
              alignment: Alignment.topCenter,
              child: Text('top side'),
            ),
            const Align(
              alignment: Alignment.centerRight,
              child: RotatedBox(
                quarterTurns: 3,
                child: Text('right side'),
              ),
            ),
            const Align(
              alignment: Alignment.bottomCenter,
              child: RotatedBox(
                quarterTurns: 2,
                child: Text('bottom side'),
              ),
            ),
            Semantics(
              identifier: 'level-0',
              explicitChildNodes: true,
              child: Container(
                padding: padding,
                child: Stack(
                  children: [
                    Semantics(
                      identifier: 'level-1',
                      explicitChildNodes: true,
                      child: Container(
                        color: Colors.blue,
                        padding: padding,
                        child: Stack(
                          children: [
                            Semantics(
                              identifier: 'level-2',
                              explicitChildNodes: true,
                              child: Container(
                                color: Colors.orange,
                                padding: padding,
                              ),
                            ),
                            const Text('Container at level 2'),
                          ],
                        ),
                      ),
                    ),
                    const Text('Container at level 1'),
                  ],
                ),
              ),
            ),
            const Text('Container at level 0'),
          ],
        ),
      ),
    );
  }
}

import 'package:flutter/material.dart';
import '../emitter.dart';

/// Vertical ListView; emits SCROLL with from/to offsets via a ScrollNotification listener.
class ScrollScreen extends StatefulWidget {
  const ScrollScreen({super.key});
  @override
  State<ScrollScreen> createState() => _ScrollScreenState();
}

class _ScrollScreenState extends State<ScrollScreen> {
  int _prev = 0;

  @override
  Widget build(BuildContext context) {
    return Semantics(
      identifier: 'scroll_container',
      label: 'scroll_container',
      container: true,
      child: NotificationListener<ScrollNotification>(
        onNotification: (n) {
          if (n is ScrollUpdateNotification) {
            final to = n.metrics.pixels.round();
            if (to != _prev) {
              emit('SCROLL', {'axis': 'Y', 'fromOffset': _prev, 'toOffset': to});
              _prev = to;
            }
          }
          return false;
        },
        child: ListView.builder(
          itemCount: 30,
          itemBuilder: (context, i) => Container(
            padding: const EdgeInsets.symmetric(horizontal: 40, vertical: 36),
            color: i.isOdd ? const Color(0xFFE3F2FD) : Colors.white,
            child: Text('Item ${i + 1}', style: const TextStyle(fontSize: 18)),
          ),
        ),
      ),
    );
  }
}

import 'package:flutter/material.dart';

/// A [LongPressDraggable] fixture for testing a drag that must hold before it
/// starts moving.
class LongPressDragDropScreen extends StatefulWidget {
  const LongPressDragDropScreen({super.key});

  @override
  State<LongPressDragDropScreen> createState() =>
      _LongPressDragDropScreenState();
}

class _LongPressDragDropScreenState extends State<LongPressDragDropScreen> {
  bool _dropped = false;

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(
        title: const Text('Long Press Drag Drop Test'),
        leading: IconButton(
          icon: const Icon(Icons.arrow_back),
          onPressed: () => Navigator.of(context).pop(),
        ),
      ),
      body: Padding(
        padding: const EdgeInsets.all(24),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            const SizedBox(height: 24),
            Center(
              child: LongPressDraggable<int>(
                data: 1,
                delay: Duration(milliseconds: 500),
                feedback: _box(
                  Colors.deepPurpleAccent,
                  'Long Press Drag Me',
                  elevation: 8,
                ),
                childWhenDragging: _box(
                  Colors.deepPurple.shade100,
                  'Long Press Drag Me',
                ),
                child: _box(Colors.deepPurpleAccent, 'Long Press Drag Me'),
              ),
            ),
            const Spacer(),
            DragTarget<int>(
              onAcceptWithDetails: (_) => setState(() => _dropped = true),
              builder: (context, candidate, rejected) {
                return _box(
                  _dropped ? Colors.green : Colors.transparent,
                  _dropped ? 'Long Press Drop Success' : 'Long Press Drop Here',
                  border: true,
                );
              },
            ),
            const Spacer(),
          ],
        ),
      ),
    );
  }

  Widget _box(
    Color color,
    String label, {
    bool border = false,
    double elevation = 0,
  }) {
    return Material(
      elevation: elevation,
      color: Colors.transparent,
      child: Container(
        width: 220,
        height: 120,
        alignment: Alignment.center,
        decoration: BoxDecoration(
          color: color,
          borderRadius: BorderRadius.circular(12),
          border: border ? Border.all(color: Colors.grey, width: 3) : null,
        ),
        child: Text(
          label,
          style: const TextStyle(fontSize: 20, fontWeight: FontWeight.bold),
        ),
      ),
    );
  }
}

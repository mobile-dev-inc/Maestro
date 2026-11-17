import 'package:flutter/material.dart';

/// A drag-and-drop test screen built on [Draggable] / [DragTarget] rather than
/// [ReorderableListView]. It exercises Maestro's `drag` command with a single
/// drag and reports "Drop Success" once the item is dropped on the target.
///
/// Unlike the reorder screen, this widget does not spawn a reorder overlay, so
/// it is not affected by the Flutter-on-iOS bug where a reordered item's
/// accessibility node collapses to the screen origin. That makes it a reliable
/// cross-platform demonstration that the drag command works on iOS.
class DragDropScreen extends StatefulWidget {
  const DragDropScreen({super.key});

  @override
  State<DragDropScreen> createState() => _DragDropScreenState();
}

class _DragDropScreenState extends State<DragDropScreen> {
  bool _dropped = false;

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(
        title: const Text('Drag Drop Test'),
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
              child: Draggable<int>(
                data: 1,
                feedback: _box(Colors.blueAccent, 'Drag Me', elevation: 8),
                childWhenDragging: _box(Colors.blue.shade100, 'Drag Me'),
                child: _box(Colors.blueAccent, 'Drag Me'),
              ),
            ),
            const Spacer(),
            DragTarget<int>(
              onAcceptWithDetails: (_) => setState(() => _dropped = true),
              builder: (context, candidate, rejected) {
                return _box(
                  _dropped ? Colors.green : Colors.transparent,
                  _dropped ? 'Drop Success' : 'Drop Here',
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

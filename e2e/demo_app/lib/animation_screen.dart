import 'package:flutter/material.dart';

class AnimationScreen extends StatefulWidget {
  const AnimationScreen({super.key});

  @override
  State<AnimationScreen> createState() => _AnimationScreenState();
}

class _AnimationScreenState extends State<AnimationScreen>
    with SingleTickerProviderStateMixin {
  static const List<String> _phases = [
    '🌑', '🌒', '🌓', '🌔', '🌕', '🌖', '🌗', '🌘',
  ];

  late final AnimationController _controller;
  DateTime? _startTime;
  Duration? _totalDuration;

  bool get _running =>
      _startTime != null &&
      _totalDuration != null &&
      DateTime.now().difference(_startTime!) < _totalDuration!;

  void _startAnimation(Duration totalDuration) {
    setState(() {
      _startTime = DateTime.now();
      _totalDuration = totalDuration;
    });
    _controller.repeat();
  }

  @override
  void initState() {
    super.initState();
    _controller = AnimationController(
      vsync: this,
      duration: const Duration(seconds: 1),
    )..addListener(() {
        if (!_running) _controller.stop();
        setState(() {});
      });
  }

  @override
  void dispose() {
    _controller.dispose();
    super.dispose();
  }

  String get _countdownText {
    if (_startTime == null || _totalDuration == null) return '';
    final elapsed = DateTime.now().difference(_startTime!);
    final remaining = _totalDuration! - elapsed;
    if (remaining.isNegative) return '0.0s';
    final seconds = remaining.inMilliseconds / 1000.0;
    return '${seconds.toStringAsFixed(1)}s';
  }

  String get _millisText {
    if (!_running) return '';
    return '${DateTime.now().millisecondsSinceEpoch % 10000}';
  }

  String _globe(int index) {
    final ms = DateTime.now().millisecondsSinceEpoch;
    final phase = (ms ~/ 125 + index) % _phases.length;
    return _phases[phase];
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(
        backgroundColor: Theme.of(context).colorScheme.inversePrimary,
        title: const Text('Animation Test'),
      ),
      body: Column(
        children: [
          Padding(
            padding: const EdgeInsets.all(16),
            child: Row(
              mainAxisAlignment: MainAxisAlignment.spaceEvenly,
              children: [
                ElevatedButton(
                  onPressed: () => _startAnimation(const Duration(seconds: 4)),
                  child: const Text('Animate (4s)'),
                ),
                ElevatedButton(
                  onPressed: () => _startAnimation(const Duration(seconds: 30)),
                  child: const Text('Animate (30s)'),
                ),
              ],
            ),
          ),
          Expanded(
            child: Center(
              child: Column(
                mainAxisSize: MainAxisSize.min,
                children: [
                  Row(
                    mainAxisSize: MainAxisSize.min,
                    children: [
                      for (int i = 0; i < 4; i++)
                        Padding(
                          padding: const EdgeInsets.all(8),
                          child: Text(
                            _running ? _globe(i) : '',
                            style: const TextStyle(fontSize: 36),
                          ),
                        ),
                    ],
                  ),
                  Padding(
                    padding: const EdgeInsets.symmetric(vertical: 8),
                    child: Text(
                      _countdownText,
                      style: const TextStyle(
                          fontSize: 48, fontWeight: FontWeight.bold),
                    ),
                  ),
                  Text(
                    _millisText,
                    style: TextStyle(
                      fontSize: 14,
                      color: Colors.grey[600],
                      fontFeatures: const [FontFeature.tabularFigures()],
                    ),
                  ),
                  const SizedBox(height: 8),
                  Row(
                    mainAxisSize: MainAxisSize.min,
                    children: [
                      for (int i = 4; i < 8; i++)
                        Padding(
                          padding: const EdgeInsets.all(8),
                          child: Text(
                            _running ? _globe(i) : '',
                            style: const TextStyle(fontSize: 36),
                          ),
                        ),
                    ],
                  ),
                ],
              ),
            ),
          ),
        ],
      ),
    );
  }
}
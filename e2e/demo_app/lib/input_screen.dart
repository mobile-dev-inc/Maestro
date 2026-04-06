import 'package:flutter/material.dart';
import 'package:shared_preferences/shared_preferences.dart';

class InputScreen extends StatefulWidget {
  const InputScreen({super.key});

  @override
  State<InputScreen> createState() => _InputScreenState();
}

class _InputScreenState extends State<InputScreen> {
  final TextEditingController _inputController = TextEditingController();
  FocusNode? _focusNode;

  @override
  void initState() {
    super.initState();
    _focusNode = FocusNode();
    SharedPreferences.getInstance().then((prefs) {
      final saved = prefs.getString('input_screen_text');
      if (saved != null) _inputController.text = saved;
    });
  }

  @override
  void dispose() {
    _focusNode?.dispose();
    _inputController.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(
        title: const Text('Input & Navigation'),
      ),
      body: Padding(
        padding: const EdgeInsets.all(16),
        child: SingleChildScrollView(
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Semantics(
                identifier: 'textInput',
                child: TextField(
                  key: const ValueKey('input_test_field'),
                  controller: _inputController,
                  focusNode: _focusNode,
                  maxLines: null,
                  decoration: const InputDecoration(
                    hintText: 'Test Input Field',
                    border: OutlineInputBorder(),
                  ),
                ),
              ),
              const SizedBox(height: 12),
              ElevatedButton(
                onPressed: () async {
                  final prefs = await SharedPreferences.getInstance();
                  await prefs.setString('input_screen_text', _inputController.text);
                },
                child: const Text('Save'),
              ),
            ],
          ),
        ),
      ),
    );
  }
}

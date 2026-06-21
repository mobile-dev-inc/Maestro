import 'package:flutter/material.dart';
import '../emitter.dart';

/// isKeyboardVisible / hideKeyboard rely on IME show/hide — detected via didChangeMetrics (the
/// keyboard inset). pressKey(ENTER) arrives idiomatically through TextField.onSubmitted. (Flutter,
/// like RN, can't observe arbitrary hardware keyevents from a TextField; only ENTER is exercised.)
class KeyboardScreen extends StatefulWidget {
  const KeyboardScreen({super.key});
  @override
  State<KeyboardScreen> createState() => _KeyboardScreenState();
}

class _KeyboardScreenState extends State<KeyboardScreen> with WidgetsBindingObserver {
  bool _imeVisible = false;

  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addObserver(this);
  }

  @override
  void dispose() {
    WidgetsBinding.instance.removeObserver(this);
    super.dispose();
  }

  @override
  void didChangeMetrics() {
    final inset = View.of(context).viewInsets.bottom;
    final visible = inset > 0;
    if (visible != _imeVisible) {
      _imeVisible = visible;
      emit('IME', {'state': visible ? 'SHOWN' : 'HIDDEN'});
    }
  }

  @override
  Widget build(BuildContext context) {
    return Padding(
      padding: const EdgeInsets.fromLTRB(40, 200, 40, 0),
      child: Semantics(
        identifier: 'text_field',
        textField: true,
        child: TextField(
          decoration: const InputDecoration(
            hintText: 'Focus me to show keyboard...',
            border: OutlineInputBorder(),
          ),
          textInputAction: TextInputAction.done,
          onSubmitted: (_) => emit('KEY', {'code': 'ENTER'}),
        ),
      ),
    );
  }
}

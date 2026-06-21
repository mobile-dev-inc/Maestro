import 'package:flutter/services.dart';

/// Bridge to the native MethodChannel handler, which forwards events to the MAESTRO_FIXTURE logcat
/// oracle (same contract as the native/compose/RN fixtures) and supplies the launched route.
const MethodChannel _channel = MethodChannel('maestro.fixture/bridge');

Future<void> emit(String event, [Map<String, dynamic> payload = const {}]) async {
  await _channel.invokeMethod('emit', {'event': event, 'payload': payload});
}

Future<void> seedState() async {
  await _channel.invokeMethod('seedState');
}

Future<String> getRoute() async {
  return (await _channel.invokeMethod<String>('getRoute')) ?? 'TapScreen';
}

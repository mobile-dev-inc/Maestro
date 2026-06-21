import 'package:flutter/material.dart';
import 'package:flutter/semantics.dart';

import 'emitter.dart';
import 'router.dart';

/// Kept for the app's lifetime so the semantics tree stays enabled. Flutter renders to a single
/// canvas and only builds its semantics tree when an accessibility client is attached; without this
/// the harness (UiAutomator) would see one empty FlutterView and resolve no widgets. This is the
/// defining requirement for testing Flutter with Maestro — see FLUTTER-FIXTURE.md.
// ignore: unused_field
late final SemanticsHandle _semanticsHandle;

Future<void> main() async {
  WidgetsFlutterBinding.ensureInitialized();
  _semanticsHandle = SemanticsBinding.instance.ensureSemantics();
  final route = await getRoute();
  runApp(FixtureApp(route: route));
}

class FixtureApp extends StatelessWidget {
  final String route;
  const FixtureApp({super.key, required this.route});

  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      debugShowCheckedModeBanner: false,
      home: Scaffold(body: SafeArea(child: RouterScreen(route: route))),
    );
  }
}

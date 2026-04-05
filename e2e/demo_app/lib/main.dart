import 'package:demo_app/connectivity_screen.dart';
import 'package:demo_app/cropped_screenshot_screen.dart';
import 'package:demo_app/defects_screen.dart';
import 'package:demo_app/notifications_permission_screen.dart';
import 'package:demo_app/form_screen.dart';
import 'package:demo_app/input_screen.dart';
import 'package:demo_app/issue_1619_repro.dart';
import 'package:demo_app/issue_1677_repro.dart';
import 'package:demo_app/location_screen.dart';
import 'package:demo_app/nesting_screen.dart';
import 'package:demo_app/gesture_tester_screen.dart';
import 'package:demo_app/sensors_screen.dart';
import 'package:demo_app/webview.dart';
import 'package:flutter/foundation.dart' show kIsWeb;
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:flutter_launch_arguments/flutter_launch_arguments.dart';


void main() {
  runApp(const MyApp());
}

class MyApp extends StatelessWidget {
  const MyApp({super.key});

  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      title: 'Flutter Demo',
      theme: ThemeData.light(),
      darkTheme: ThemeData.dark(),
      routes: {
        '/': (_) => const MyHomePage(title: 'Flutter Demo Home Page'),
        '/form': (_) => const FormScreen(),
      },
    );
  }
}

class MyHomePage extends StatefulWidget {
  const MyHomePage({super.key, required this.title});

  final String title;

  @override
  State<MyHomePage> createState() => _MyHomePageState();
}

class _MyHomePageState extends State<MyHomePage> {
  late final FlutterLaunchArguments _flutterLaunchArgumentsPlugin;

  int _counter = 0;
  int _delay = 0;

  @override
  void initState() {
    super.initState();
    _flutterLaunchArgumentsPlugin = FlutterLaunchArguments();
    _initializeVars();
  }

  Future<void> _initializeVars() async {
    final counterValue = await _flutterLaunchArgumentsPlugin.getInt('initialCounter');
    final delayValue = await _flutterLaunchArgumentsPlugin.getInt('delay');
    
    setState(() {
      _counter = counterValue ?? 0;
      _delay = delayValue ?? 0;
    });
  }

  Future<void> _incrementCounter() async {
    await Future.delayed(Duration(seconds: _delay));
    setState(() {
      _counter++;
    });
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(
        backgroundColor: Theme.of(context).colorScheme.inversePrimary,
        title: Text(widget.title),
      ),
      body: SingleChildScrollView(
        child: Center(
          child: Column(
            mainAxisAlignment: MainAxisAlignment.center,
            crossAxisAlignment: CrossAxisAlignment.center,
            children: [
              if (!kIsWeb)
                ElevatedButton(
                  onPressed: () {
                    Navigator.of(context).push(
                      MaterialPageRoute(builder: (_) => const SensorsScreen()),
                    );
                  },
                  child: const Text('Sensors'),
                ),
              ElevatedButton(
                onPressed: () {
                  Navigator.of(context).push(
                    MaterialPageRoute(builder: (_) => const LocationScreen()),
                  );
                },
                child: const Text('Location Test'),
              ),
              ElevatedButton(
                onPressed: () {
                  Navigator.of(context).push(
                    MaterialPageRoute(builder: (_) => const DefectsScreen()),
                  );
                },
                child: const Text('Defects Test'),
              ),
              ElevatedButton(
                onPressed: () {
                  Navigator.of(context).push(
                    MaterialPageRoute(builder: (_) => const NestingScreen()),
                  );
                },
                child: const Text('Nesting Test'),
              ),
              ElevatedButton(
                onPressed: () {
                  Navigator.of(context).push(
                    MaterialPageRoute(builder: (_) => const GestureTesterScreen()),
                  );
                },
                child: const Text('Gesture Tester'),
              ),
              ElevatedButton(
                onPressed: () {
                  Navigator.of(context).push(
                    MaterialPageRoute(builder: (_) => const FormScreen()),
                  );
                },
                child: const Text('Form Test'),
              ),
              ElevatedButton(
                onPressed: () {
                  Navigator.of(context).push(
                    MaterialPageRoute(builder: (_) => const InputScreen()),
                  );
                },
                child: const Text('Input/Keyboard'),
              ),
              ElevatedButton(
                onPressed: () {
                  const channel = MethodChannel('com.example.demo_app/password_test');
                  channel.invokeMethod('openPasswordTest');
                },
                child: const Text('Password autofill Test'),
              ),
              ElevatedButton(
                onPressed: () {
                  Navigator.of(context).push(
                    MaterialPageRoute(builder: (_) => const Issue1677Repro()),
                  );
                },
                child: const Text('issue 1677 repro'),
              ),
              ElevatedButton(
                onPressed: () {
                  Navigator.of(context).push(
                    MaterialPageRoute(builder: (_) => const Issue1619Repro()),
                  );
                },
                child: const Text('issue 1619 repro'),
              ),
              ElevatedButton(
                onPressed: () {
                  Navigator.of(context).push(
                    MaterialPageRoute(builder: (_) => const WebViewExample()),
                  );
                },
                child: const Text('Webview Test'),
              ),
              ElevatedButton(
                onPressed: () {
                  Navigator.of(context).push(
                    MaterialPageRoute(builder: (_) => const CroppedScreenshotScreen()),
                  );
                },
                child: const Text('Cropped Screenshot Test'),
              ),
              ElevatedButton(
                onPressed: () {
                  Navigator.of(context).push(
                    MaterialPageRoute(builder: (_) => const NotificationsPermissionScreen()),
                  );
                },
                child: const Text('Notifications Permission'),
              ),
              ElevatedButton(
                onPressed: () {
                  Navigator.of(context).push(
                    MaterialPageRoute(builder: (_) => const ConnectivityScreen()),
                  );
                },
                child: const Text('Connectivity Test'),
              ),
              const Text(
                'You have pushed the button this many times',
              ),
              Text(
                '$_counter',
                style: Theme.of(context).textTheme.headlineMedium,
              ),
            ],
          ),
        ),
      ),
      floatingActionButton: Semantics(
        identifier: 'fabAddIcon',
        child: FloatingActionButton(
          onPressed: _incrementCounter,
          tooltip: 'Increment',
          child: const Icon(Icons.add),
        ),
      ),
    );
  }
}

import 'package:flutter/foundation.dart';
import 'package:flutter/material.dart';
import 'package:webview_flutter/webview_flutter.dart';
import 'package:webview_flutter_android/webview_flutter_android.dart';

// `#devtools-regression-marker` is a non-interactive, aria-hidden div: it
// should not surface in Android's native a11y tree, but devtools augmentation
// emits it (via `node.id` in maestro-web.js). An `id:` match against it
// passes iff the devtools path is working — guards the DummyDns hostname fix.
const _html = '''
<!DOCTYPE html>
<html>
  <head><meta name="viewport" content="width=device-width,initial-scale=1"></head>
  <body>
    <h1>WebView Devtools Test</h1>
    <div id="devtools-regression-marker" aria-hidden="true">marker</div>
  </body>
</html>
''';

class WebViewDevtoolsTestScreen extends StatefulWidget {
  const WebViewDevtoolsTestScreen({super.key});

  @override
  State<WebViewDevtoolsTestScreen> createState() =>
      _WebViewDevtoolsTestScreenState();
}

class _WebViewDevtoolsTestScreenState extends State<WebViewDevtoolsTestScreen> {
  late final WebViewController controller;

  @override
  void initState() {
    super.initState();
    if (defaultTargetPlatform == TargetPlatform.android) {
      AndroidWebViewController.enableDebugging(true);
    }
    controller = WebViewController()
      ..setJavaScriptMode(JavaScriptMode.unrestricted)
      ..loadHtmlString(_html);
  }

  @override
  Widget build(BuildContext context) => Scaffold(
        appBar: AppBar(title: const Text('WebView Devtools Test')),
        body: WebViewWidget(controller: controller),
      );
}

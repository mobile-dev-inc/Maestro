import 'package:flutter/foundation.dart';
import 'package:flutter/material.dart';
import 'package:webview_flutter/webview_flutter.dart';
import 'package:webview_flutter_android/webview_flutter_android.dart';

const _nestingDepth = 700;

String _buildDeepHtml() {
  final open = StringBuffer();
  final close = StringBuffer();
  for (var i = 0; i < _nestingDepth; i++) {
    open.write('<div>');
    close.write('</div>');
  }
  return '''
<!DOCTYPE html>
<html>
  <head><meta name="viewport" content="width=device-width,initial-scale=1"></head>
  <body>
    <h1>WebView Deep DOM Test</h1>
    $open<div id="deep-dom-marker" aria-hidden="true">deep marker</div>$close
  </body>
</html>
''';
}

class WebViewDeepDomTestScreen extends StatefulWidget {
  const WebViewDeepDomTestScreen({super.key});

  @override
  State<WebViewDeepDomTestScreen> createState() =>
      _WebViewDeepDomTestScreenState();
}

class _WebViewDeepDomTestScreenState extends State<WebViewDeepDomTestScreen> {
  late final WebViewController controller;

  @override
  void initState() {
    super.initState();
    if (defaultTargetPlatform == TargetPlatform.android) {
      AndroidWebViewController.enableDebugging(true);
    }
    controller = WebViewController()
      ..setJavaScriptMode(JavaScriptMode.unrestricted)
      ..loadHtmlString(_buildDeepHtml());
  }

  @override
  Widget build(BuildContext context) => Scaffold(
        appBar: AppBar(title: const Text('WebView Deep DOM Test')),
        body: WebViewWidget(controller: controller),
      );
}

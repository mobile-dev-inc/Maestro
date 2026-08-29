import 'package:flutter/foundation.dart';
import 'package:flutter/material.dart';
import 'package:webview_flutter/webview_flutter.dart';
import 'package:webview_flutter_android/webview_flutter_android.dart';

const _nestingDepth = 700;

// This page exercises three WebView-hierarchy hazards in one snapshot:
//   1. Deep DOM: the marker sits under $_nestingDepth nested <div>s.
//   2. Circular DOM: a control named "id" clobbers form.id into a live <input> with a self-referential
//      property, so a naive JSON.stringify throws "Converting circular structure to JSON".
//   3. Clobbered id: the form's real id="clobbered-form-id" is only recovered by reading the attribute.
//      aria-hidden keeps it out of the accessibility tree, so the devtools snapshot is its only source.
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
    <form id="clobbered-form-id" aria-hidden="true">
      <input name="id" value="clobber">
    </form>
    $open<div id="deep-dom-marker" aria-hidden="true">deep marker</div>$close
    <script>
      var input = document.querySelector('input[name="id"]');
      input.__reactFiber\$demo = { stateNode: input };
    </script>
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

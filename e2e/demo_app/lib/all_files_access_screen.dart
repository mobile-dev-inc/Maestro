import 'package:flutter/material.dart';
import 'package:permission_handler/permission_handler.dart';

class AllFilesAccessScreen extends StatefulWidget {
  const AllFilesAccessScreen({super.key});

  @override
  State<AllFilesAccessScreen> createState() => _AllFilesAccessScreenState();
}

class _AllFilesAccessScreenState extends State<AllFilesAccessScreen> {
  String _status = 'Checking...';

  @override
  void initState() {
    super.initState();
    _check();
  }

  Future<void> _check() async {
    final granted = await Permission.manageExternalStorage.isGranted;
    if (!mounted) return;
    setState(() {
      _status = granted ? 'Allowed' : 'Not allowed';
    });
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(
        title: const Text('All Files Access'),
      ),
      body: Center(
        child: Column(
          mainAxisAlignment: MainAxisAlignment.center,
          children: [
            Semantics(
              identifier: 'allFilesAccessStatus',
              child: Text(
                _status,
                style: Theme.of(context).textTheme.titleMedium,
              ),
            ),
            const SizedBox(height: 20),
            Semantics(
              identifier: 'refreshAllFilesAccessButton',
              child: ElevatedButton(
                onPressed: _check,
                child: const Text('Refresh'),
              ),
            ),
          ],
        ),
      ),
    );
  }
}

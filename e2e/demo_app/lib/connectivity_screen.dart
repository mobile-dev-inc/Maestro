import 'dart:async';
import 'package:connectivity_plus/connectivity_plus.dart';
import 'package:flutter/material.dart';

class ConnectivityScreen extends StatefulWidget {
  const ConnectivityScreen({super.key});

  @override
  State<ConnectivityScreen> createState() => _ConnectivityScreenState();
}

class _ConnectivityScreenState extends State<ConnectivityScreen> {
  bool _isOnline = false;
  StreamSubscription? _subscription;

  @override
  void initState() {
    super.initState();
    Connectivity().checkConnectivity().then(_updateStatus);
    _subscription = Connectivity().onConnectivityChanged.listen(_updateStatus);
  }

  void _updateStatus(List<ConnectivityResult> results) {
    setState(() => _isOnline = results.any((r) => r != ConnectivityResult.none));
  }

  @override
  void dispose() {
    _subscription?.cancel();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) => Scaffold(
        appBar: AppBar(title: const Text('Connectivity Test')),
        body: Center(child: Text(_isOnline ? 'Online' : 'Offline')),
      );
}

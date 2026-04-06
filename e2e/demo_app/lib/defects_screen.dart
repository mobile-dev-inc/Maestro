import 'package:flutter/material.dart';

class DefectsScreen extends StatefulWidget {
  const DefectsScreen({super.key});

  @override
  State<DefectsScreen> createState() => _DefectsScreenState();
}

class _DefectsScreenState extends State<DefectsScreen> {
  int count = 0;

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(
        title: const Text('Defects test screen'),
      ),
      body: Stack(
        alignment: Alignment.topLeft,
        children: [
          const Positioned(
            left: 0,
            top: 100,
            child: Text(
              _loremIpsum,
              maxLines: 2,
            ),
          ),
          Positioned(
            top: 0,
            left: 0,
            child: ElevatedButton(
              onPressed: () {},
              child: const Text('Do thing A'),
            ),
          ),
          Align(
            alignment: Alignment.center,
            child: Image.asset('assets/bunny.jpg'),
          ),
          Positioned(
            left: 20,
            top: 20,
            child: ElevatedButton(
              onPressed: () {},
              child: const Text('Do thing B'),
            ),
          ),
          Positioned(
            right: 20,
            bottom: 20,
            child: ElevatedButton(
              onPressed: () {},
              child: const Text('Do thing X'),
            ),
          ),
          Positioned(
            right: 40,
            bottom: 40,
            child: ElevatedButton(
              onPressed: () {},
              child: const Text('Do thing Y'),
            ),
          ),
        ],
      ),
    );
  }
}

const _loremIpsum =
    'Lorem ipsum dolor sit amet, consectetur adipiscing elit, sed do eiusmod tempor incididunt ut labore et dolore magna aliqua. Ut enim ad minim veniam, quis nostrud exercitation ullamco laboris nisi ut aliquip ex ea commodo consequat. Duis aute irure dolor in reprehenderit in voluptate velit esse cillum dolore eu fugiat nulla pariatur. Excepteur sint occaecat cupidatat non proident, sunt in culpa qui officia deserunt mollit anim id est laborum.';

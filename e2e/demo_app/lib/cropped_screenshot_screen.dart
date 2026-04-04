import 'package:flutter/material.dart';

const double _containerPhysicalWidth = 400.0;
const double _containerPhysicalHeight = 320.0;
const double _paddingPhysical = 32.0;
const double _borderPhysicalWidth = 4.0;
const double _borderRadiusPhysical = 12.0;
const double _redSquarePhysicalSize = 160.0;
const double _blueRectPhysicalWidth = 320.0;
const double _blueRectPhysicalHeight = 80.0;

class CroppedScreenshotScreen extends StatelessWidget {
  const CroppedScreenshotScreen({super.key});

  @override
  Widget build(BuildContext context) {
    final double devicePixelRatio = MediaQuery.of(context).devicePixelRatio;
    final double containerWidth = _containerPhysicalWidth / devicePixelRatio;
    final double containerHeight = _containerPhysicalHeight / devicePixelRatio;
    final double padding = _paddingPhysical / devicePixelRatio;
    final double borderWidth = _borderPhysicalWidth / devicePixelRatio;
    final double borderRadius = _borderRadiusPhysical / devicePixelRatio;
    final double redSquareSize = _redSquarePhysicalSize / devicePixelRatio;
    final double blueRectWidth = _blueRectPhysicalWidth / devicePixelRatio;
    final double blueRectHeight = _blueRectPhysicalHeight / devicePixelRatio;

    return Scaffold(
      appBar: AppBar(
        title: const Text('Cropped Screenshot Test'),
      ),
      body: Center(
        child: Semantics(
          identifier: 'testContainer',
          child: Container(
            width: containerWidth,
            height: containerHeight,
            padding: EdgeInsets.all(padding),
            decoration: BoxDecoration(
              color: const Color(0xFFF0F0F0),
              border: Border.all(
                color: const Color(0xFF000000),
                width: borderWidth,
              ),
              borderRadius: BorderRadius.circular(borderRadius),
            ),
            child: Column(
              mainAxisAlignment: MainAxisAlignment.spaceBetween,
              crossAxisAlignment: CrossAxisAlignment.center,
              children: [
                Container(
                  width: redSquareSize,
                  height: redSquareSize,
                  color: const Color(0xFFFF0000),
                ),
                Container(
                  width: blueRectWidth,
                  height: blueRectHeight,
                  color: const Color(0xFF0000FF),
                ),
              ],
            ),
          ),
        ),
      ),
    );
  }
}

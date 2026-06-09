import 'package:flutter/material.dart';

// Position of the alert on screen (logical points), measured from the top-left
// of the device screen so the alert sits in the same place as the source
// screenshot. Tweak these (e.g. shift `_alertTop` by 22) to exercise
// screenshot-assertion tolerances.
const double _alertTop = 252.0;
const double _alertLeft = 47.0;
const double _alertWidth = 299.0;
const double _alertHeight = 333.0;
const double _alertShiftPx = 10.0;

const Color _brandBlue = Color(0xFF14467C);
const Color _brandGreen = Color(0xFF2BB673);
const Color _brandRed = Color(0xFFE53935);

class PatientCareScreen extends StatefulWidget {
  const PatientCareScreen({super.key});

  @override
  State<PatientCareScreen> createState() => _PatientCareScreenState();
}

class _PatientCareScreenState extends State<PatientCareScreen> {
  bool _shifted = false;

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      backgroundColor: const Color(0xFF7F7F7F),
      extendBodyBehindAppBar: true,
      appBar: AppBar(
        backgroundColor: Colors.transparent,
        elevation: 0,
        iconTheme: const IconThemeData(color: Colors.black),
      ),
      body: Stack(
        children: [
          Positioned(
            top: _alertTop + (_shifted ? _alertShiftPx : 0),
            left: _alertLeft,
            width: _alertWidth,
            height: _alertHeight,
            child: const _PatientCareAlert(),
          ),
          Positioned(
            left: 0,
            right: 0,
            bottom: 32,
            child: Center(
              child: Semantics(
                identifier: 'toggleShiftButton',
                child: ElevatedButton(
                  onPressed: () => setState(() => _shifted = !_shifted),
                  child: const Text('Toggle 10px'),
                ),
              ),
            ),
          ),
        ],
      ),
    );
  }
}

class _PatientCareAlert extends StatelessWidget {
  const _PatientCareAlert();

  @override
  Widget build(BuildContext context) {
    return Semantics(
      identifier: 'patientCareAlert',
      child: Container(
        decoration: BoxDecoration(
          color: Colors.white,
          borderRadius: BorderRadius.circular(20),
          border: Border.all(color: const Color(0xFFD0D0D0), width: 1),
          boxShadow: const [
            BoxShadow(
              color: Color(0x1A000000),
              blurRadius: 12,
              offset: Offset(0, 4),
            ),
          ],
        ),
        padding: const EdgeInsets.fromLTRB(20, 18, 20, 18),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            const Text(
              'Patient Care Made Mobile',
              textAlign: TextAlign.center,
              style: TextStyle(
                fontSize: 17,
                fontWeight: FontWeight.bold,
                color: Colors.black,
              ),
            ),
            const SizedBox(height: 10),
            const Center(child: _PhoneIllustration()),
            const SizedBox(height: 12),
            const _CheckRow('Keep your number private with a customizable caller ID'),
            const SizedBox(height: 6),
            const _CheckRow('Secure Voice, Video & Texting'),
            const SizedBox(height: 6),
            const _CheckRow('Free & HIPAA compliant'),
            const Spacer(),
            SizedBox(
              height: 42,
              child: ElevatedButton(
                style: ElevatedButton.styleFrom(
                  backgroundColor: _brandBlue,
                  foregroundColor: Colors.white,
                  shape: RoundedRectangleBorder(
                    borderRadius: BorderRadius.circular(21),
                  ),
                ),
                onPressed: () {},
                child: const Text(
                  'Set up Dialer',
                  style: TextStyle(fontSize: 16, fontWeight: FontWeight.w500),
                ),
              ),
            ),
          ],
        ),
      ),
    );
  }
}

class _CheckRow extends StatelessWidget {
  final String text;
  const _CheckRow(this.text);

  @override
  Widget build(BuildContext context) {
    return Row(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        const Icon(Icons.check, color: _brandGreen, size: 18),
        const SizedBox(width: 8),
        Expanded(
          child: Text(
            text,
            style: const TextStyle(fontSize: 13, color: Colors.black, height: 1.25),
          ),
        ),
      ],
    );
  }
}

class _PhoneIllustration extends StatelessWidget {
  const _PhoneIllustration();

  @override
  Widget build(BuildContext context) {
    return SizedBox(
      width: 78,
      height: 86,
      child: Stack(
        clipBehavior: Clip.none,
        children: [
          Container(
            width: 56,
            height: 84,
            margin: const EdgeInsets.only(left: 11, top: 2),
            decoration: BoxDecoration(
              border: Border.all(color: _brandBlue, width: 2),
              borderRadius: BorderRadius.circular(8),
            ),
            child: Padding(
              padding: const EdgeInsets.symmetric(vertical: 8),
              child: Column(
                mainAxisAlignment: MainAxisAlignment.spaceBetween,
                children: [
                  const Text(
                    'DOC\nOFFICE',
                    textAlign: TextAlign.center,
                    style: TextStyle(
                      fontSize: 8,
                      fontWeight: FontWeight.bold,
                      color: _brandBlue,
                      height: 1.1,
                    ),
                  ),
                  Row(
                    mainAxisAlignment: MainAxisAlignment.center,
                    children: [
                      _CallButton(color: _brandRed, icon: Icons.call_end),
                      SizedBox(width: 4),
                      _CallButton(color: _brandGreen, icon: Icons.call),
                    ],
                  ),
                ],
              ),
            ),
          ),
          Positioned(
            top: 0,
            right: 0,
            child: Container(
              width: 22,
              height: 22,
              decoration: const BoxDecoration(
                color: _brandGreen,
                shape: BoxShape.circle,
              ),
              child: const Icon(Icons.check, size: 14, color: Colors.white),
            ),
          ),
        ],
      ),
    );
  }
}

class _CallButton extends StatelessWidget {
  final Color color;
  final IconData icon;
  const _CallButton({required this.color, required this.icon});

  @override
  Widget build(BuildContext context) {
    return Container(
      width: 14,
      height: 14,
      decoration: BoxDecoration(color: color, shape: BoxShape.circle),
      child: Icon(icon, size: 8, color: Colors.white),
    );
  }
}

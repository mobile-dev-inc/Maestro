import 'package:flutter/material.dart';

/// Nested carousels for issue #3406: outer card pager + inner image pager.
///
/// The geometric center of each card falls inside the inner pager. A swipe that
/// starts at the card center advances the images, not the cards. A swipe that
/// starts in the lower text region (e.g. point "50%, 75%") advances the outer
/// card carousel.
class CarouselScreen extends StatefulWidget {
  const CarouselScreen({super.key});

  static const cardLabels = ['Card A', 'Card B', 'Card C'];

  @override
  State<CarouselScreen> createState() => _CarouselScreenState();
}

class _CarouselScreenState extends State<CarouselScreen> {
  final _outerController = PageController();
  int _outerIndex = 0;

  @override
  void dispose() {
    _outerController.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(
        title: const Text('Carousel Test'),
      ),
      body: Column(
        children: [
          Padding(
            padding: const EdgeInsets.all(8),
            child: Text(
              'Showing ${CarouselScreen.cardLabels[_outerIndex]}',
              key: ValueKey('showing-${CarouselScreen.cardLabels[_outerIndex]}'),
              style: Theme.of(context).textTheme.titleMedium,
            ),
          ),
          Expanded(
            child: PageView.builder(
              controller: _outerController,
              itemCount: CarouselScreen.cardLabels.length,
              onPageChanged: (index) {
                setState(() => _outerIndex = index);
              },
              itemBuilder: (context, index) {
                return _CompositeCard(label: CarouselScreen.cardLabels[index]);
              },
            ),
          ),
        ],
      ),
    );
  }
}

class _CompositeCard extends StatelessWidget {
  const _CompositeCard({required this.label});

  final String label;

  static const _imageColors = [
    Colors.indigo,
    Colors.teal,
    Colors.deepOrange,
  ];

  @override
  Widget build(BuildContext context) {
    // One accessibility node for the whole card so swipe.from matches card
    // bounds (center lands in the image pager; ~75% height lands in the text).
    return Semantics(
      container: true,
      label: label,
      child: ExcludeSemantics(
        child: Padding(
          padding: const EdgeInsets.fromLTRB(16, 8, 16, 24),
          child: Card(
            clipBehavior: Clip.antiAlias,
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.stretch,
              children: [
                // ~55% — center of the card falls here (inner pager)
                Expanded(
                  flex: 11,
                  child: PageView.builder(
                    itemCount: _imageColors.length,
                    itemBuilder: (context, index) {
                      return ColoredBox(
                        color: _imageColors[index],
                        child: Center(
                          child: Text(
                            'Photo ${index + 1}',
                            style: const TextStyle(
                              color: Colors.white,
                              fontSize: 28,
                              fontWeight: FontWeight.bold,
                            ),
                          ),
                        ),
                      );
                    },
                  ),
                ),
                // ~45% — element-relative point "50%, 75%" lands here
                Expanded(
                  flex: 9,
                  child: ColoredBox(
                    color: Theme.of(context).colorScheme.surfaceContainerHighest,
                    child: Center(
                      child: Padding(
                        padding: const EdgeInsets.all(16),
                        child: Text(
                          label,
                          textAlign: TextAlign.center,
                          style: Theme.of(context).textTheme.headlineMedium,
                        ),
                      ),
                    ),
                  ),
                ),
              ],
            ),
          ),
        ),
      ),
    );
  }
}

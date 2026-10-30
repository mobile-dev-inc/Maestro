import 'package:flutter/material.dart';

/// Nested carousels for issue #3406: outer card pager + inner image pager.
///
/// The geometric center of each card falls inside the inner image pager, so a
/// default `swipe.from` (which starts at the element center) advances the
/// images and leaves the card carousel where it was. An element-relative
/// `point: "50%, 85%"` starts in the lower text band instead, and reaches the
/// outer card carousel.
///
/// Both pagers are sized via [pageViewportFraction] so the flow needs no
/// device-specific tuning. A directional swipe travels from its start point to
/// 10% of the *screen* width, i.e. 0.4 * screenWidth when starting from a
/// centered element, and a PageView settles on the next page once the drag
/// passes half a page. Keeping a page at 60% of screen width makes that ratio
/// ~67% on every screen size, comfortably over the threshold.
class CarouselScreen extends StatefulWidget {
  const CarouselScreen({super.key});

  static const cardLabels = ['Card A', 'Card B', 'Card C'];

  /// Page width as a fraction of screen width. Must stay well under 0.8, or
  /// the 0.4 * screenWidth of available travel no longer clears half a page.
  static const pageViewportFraction = 0.6;

  /// Share of card height given to the inner image pager. The card center
  /// (50%) sits well inside it; `point: "50%, 85%"` sits well inside the text
  /// band below.
  static const imageBandFlex = 7;
  static const textBandFlex = 3;

  @override
  State<CarouselScreen> createState() => _CarouselScreenState();
}

class _CarouselScreenState extends State<CarouselScreen> {
  final _outerController =
      PageController(viewportFraction: CarouselScreen.pageViewportFraction);
  int _outerIndex = 0;

  /// Image index per card, so the header can report the visible card's image.
  final _imageIndices = List<int>.filled(CarouselScreen.cardLabels.length, 0);

  @override
  void dispose() {
    _outerController.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final cardLabel = CarouselScreen.cardLabels[_outerIndex];
    final imageNumber = _imageIndices[_outerIndex] + 1;

    return Scaffold(
      appBar: AppBar(
        title: const Text('Carousel Test'),
      ),
      body: Column(
        children: [
          Padding(
            padding: const EdgeInsets.all(8),
            child: Column(
              children: [
                // Reported outside the card, because the card exposes a single
                // accessibility node (see _CompositeCard). Flows assert on
                // these two labels to tell the pagers apart.
                Text(
                  'Showing $cardLabel',
                  key: ValueKey('showing-$cardLabel'),
                  style: Theme.of(context).textTheme.titleMedium,
                ),
                Text(
                  'Showing Photo $imageNumber',
                  key: ValueKey('showing-photo-$imageNumber'),
                  style: Theme.of(context).textTheme.titleMedium,
                ),
              ],
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
                return _CompositeCard(
                  label: CarouselScreen.cardLabels[index],
                  onImageChanged: (imageIndex) {
                    setState(() => _imageIndices[index] = imageIndex);
                  },
                );
              },
            ),
          ),
        ],
      ),
    );
  }
}

class _CompositeCard extends StatelessWidget {
  const _CompositeCard({required this.label, required this.onImageChanged});

  final String label;
  final ValueChanged<int> onImageChanged;

  static const _imageColors = [
    Colors.indigo,
    Colors.teal,
    Colors.deepOrange,
  ];

  @override
  Widget build(BuildContext context) {
    // One accessibility node for the whole card, as in the reported issue, so
    // swipe.from matches the card bounds rather than a sub-widget.
    return Semantics(
      container: true,
      label: label,
      child: ExcludeSemantics(
        child: Padding(
          padding: const EdgeInsets.fromLTRB(8, 8, 8, 24),
          child: Card(
            clipBehavior: Clip.antiAlias,
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.stretch,
              children: [
                Expanded(
                  flex: CarouselScreen.imageBandFlex,
                  child: PageView.builder(
                    itemCount: _imageColors.length,
                    onPageChanged: onImageChanged,
                    itemBuilder: (context, index) {
                      return ColoredBox(
                        color: _imageColors[index],
                        child: Center(
                          child: Text(
                            'Photo ${index + 1}',
                            style: const TextStyle(
                              color: Colors.white,
                              fontSize: 22,
                              fontWeight: FontWeight.bold,
                            ),
                          ),
                        ),
                      );
                    },
                  ),
                ),
                Expanded(
                  flex: CarouselScreen.textBandFlex,
                  child: ColoredBox(
                    color:
                        Theme.of(context).colorScheme.surfaceContainerHighest,
                    child: Center(
                      child: Padding(
                        padding: const EdgeInsets.all(8),
                        child: Text(
                          label,
                          textAlign: TextAlign.center,
                          style: Theme.of(context).textTheme.titleLarge,
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

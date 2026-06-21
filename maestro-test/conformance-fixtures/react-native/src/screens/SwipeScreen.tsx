import React, {useRef} from 'react';
import {PanResponder, StyleSheet, Text, View} from 'react-native';
import {emit} from '../emitter';

/**
 * Swipe surface via PanResponder (RN's built-in gesture system). Emits TOUCH on press and SWIPE on
 * release with direction/distance/duration — same payload shape as the native/compose fixtures.
 */
export default function SwipeScreen() {
  const downTime = useRef(0);
  const responder = useRef(
    PanResponder.create({
      onStartShouldSetPanResponder: () => true,
      onMoveShouldSetPanResponder: () => true,
      onPanResponderGrant: e => {
        downTime.current = Date.now();
        emit('TOUCH', {
          x: Math.round(e.nativeEvent.locationX),
          y: Math.round(e.nativeEvent.locationY),
        });
      },
      onPanResponderRelease: (_e, g) => {
        const durationMs = Date.now() - downTime.current;
        if (Math.abs(g.dx) > 10 || Math.abs(g.dy) > 10) {
          const dir =
            Math.abs(g.dx) >= Math.abs(g.dy)
              ? g.dx > 0
                ? 'RIGHT'
                : 'LEFT'
              : g.dy > 0
                ? 'DOWN'
                : 'UP';
          emit('SWIPE', {
            dir,
            dx: Math.round(g.dx),
            dy: Math.round(g.dy),
            durationMs,
            target: 'swipe_surface',
          });
        }
      },
    }),
  ).current;

  return (
    <View
      testID="swipe_surface"
      accessibilityLabel="swipe_surface"
      style={styles.surface}
      {...responder.panHandlers}>
      <Text style={styles.label}>swipe surface</Text>
    </View>
  );
}

const styles = StyleSheet.create({
  surface: {flex: 1, alignItems: 'center', justifyContent: 'center', backgroundColor: '#4fc3f7'},
  label: {color: '#01579b'},
});

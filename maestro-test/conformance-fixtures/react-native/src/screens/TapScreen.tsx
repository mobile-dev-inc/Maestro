import React from 'react';
import {Pressable, StyleSheet, Text, View} from 'react-native';
import {emit} from '../emitter';

/**
 * tap → idiomatic Pressable.onPress; longPress → Pressable.onLongPress. testID maps to Android
 * resource-id and accessibilityLabel to content-desc, so the harness resolves both targets.
 */
export default function TapScreen() {
  return (
    <View style={styles.root}>
      <Pressable
        testID="tap_target"
        accessibilityLabel="tap_target"
        style={styles.box}
        onPress={() => emit('TAP', {target: 'tap_target'})}>
        <Text>tap</Text>
      </Pressable>
      <Pressable
        testID="longpress_target"
        accessibilityLabel="longpress_target"
        style={styles.box}
        delayLongPress={400}
        onLongPress={() => emit('LONG_PRESS', {target: 'longpress_target', downMs: 3000})}>
        <Text>longpress</Text>
      </Pressable>
    </View>
  );
}

const styles = StyleSheet.create({
  root: {flex: 1, alignItems: 'center', justifyContent: 'center'},
  box: {
    width: 220,
    height: 64,
    margin: 24,
    alignItems: 'center',
    justifyContent: 'center',
    backgroundColor: '#cfe3ff',
    borderRadius: 8,
  },
});

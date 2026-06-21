import React from 'react';
import {StyleSheet, Text, View} from 'react-native';
import {emit} from '../emitter';

/**
 * Reproduces mobile-dev-inc/Maestro#821 — nested <Text> inside <Text> in React Native.
 *
 * RED: `<Text>Outer <Text onPress=...>Inner</Text></Text>` renders as a SINGLE Android TextView
 * backed by a Spannable. The inner <Text> is a span (a character range), NOT a separate Android
 * view — so it has no own AccessibilityNodeInfo, no resource-id, no content-desc, no bounds, and
 * cannot carry its own testID. The hierarchy shows ONE node whose text is the concatenated
 * "Outer Inner"; "Inner" never appears as a node of its own.
 *
 * GREEN control: a SEPARATE standalone <Text> with its own testID + accessibilityLabel — a normal,
 * individually-resolvable element.
 */
export default function NestedTextScreen() {
  return (
    <View style={styles.root}>
      {/* RED — inner Text is a span; it CANNOT have its own testID. */}
      <Text style={styles.outer}>
        Outer{' '}
        <Text style={styles.inner} onPress={() => emit('TAP', {target: 'inner_text'})}>
          Inner
        </Text>
      </Text>

      {/* GREEN — standalone Text, individually resolvable. */}
      <Text
        testID="standalone_text"
        accessibilityLabel="standalone_text"
        style={styles.standalone}
        onPress={() => emit('TAP', {target: 'standalone_text'})}>
        Standalone
      </Text>
    </View>
  );
}

const styles = StyleSheet.create({
  root: {flex: 1, alignItems: 'center', justifyContent: 'center'},
  outer: {fontSize: 18, margin: 24, color: '#222222'},
  inner: {color: '#0066cc', textDecorationLine: 'underline'},
  standalone: {fontSize: 18, margin: 24, color: '#0066cc'},
});

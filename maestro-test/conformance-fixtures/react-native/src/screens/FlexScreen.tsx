import React from 'react';
import {StyleSheet, Text, View} from 'react-native';

/**
 * Reproduces mobile-dev-inc/Maestro#2246 (Android-only).
 *
 * RED variant: a bare wrapper <View> whose only distinguishing props are testID /
 * accessibilityLabel and `style={{flex: 1}}`. RN view-flattening + importantForAccessibility
 * demotion, plus a flex container's first-layout bounds, can make the backing node report
 * isVisibleToUser == false (or get flattened away). ViewHierarchy.dumpNodeRec() then prunes any
 * child with !isVisibleToUser AND never recurses into it, so the container AND its child both
 * disappear — impossible to find by testID or accessibilityLabel.
 *
 * GREEN control: the identical structure WITHOUT flex:1 — a fixed-size wrapper, which is
 * materialized with non-empty on-screen bounds and resolves normally.
 *
 * Both are mounted simultaneously so FlexLayoutBehavior can assert green-found && red-missing.
 */
export default function FlexScreen() {
  return (
    <View style={styles.root}>
      {/* GREEN control — fixed size, no flex */}
      <View testID="flex_ok_root" accessibilityLabel="flex_ok_root" style={styles.okBox}>
        <Text testID="flex_ok_child" accessibilityLabel="flex_ok_child">
          ok child
        </Text>
      </View>

      {/* RED variant — bare flex:1 wrapper */}
      <View testID="flex_bug_root" accessibilityLabel="flex_bug_root" style={{flex: 1}}>
        <Text testID="flex_bug_child" accessibilityLabel="flex_bug_child">
          bug child
        </Text>
      </View>
    </View>
  );
}

const styles = StyleSheet.create({
  root: {flex: 1, padding: 24},
  okBox: {width: 240, height: 120, marginBottom: 24, backgroundColor: '#cfe3ff'},
});

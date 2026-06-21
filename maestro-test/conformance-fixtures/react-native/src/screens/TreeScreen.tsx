import React from 'react';
import {Pressable, StyleSheet, Text, View} from 'react-native';

/**
 * contentDescriptor → a small tree with stable identifiers. testID → resource-id and
 * accessibilityLabel → content-desc, exercising the resolver's id / accessibility strategies.
 */
export default function TreeScreen() {
  return (
    <View testID="tree_root" accessibilityLabel="tree_root" style={styles.root}>
      <Text testID="tree_label_a" accessibilityLabel="tree_label_a" style={styles.label}>
        Label A
      </Text>
      <Pressable testID="tree_button_b" accessibilityLabel="tree_button_b" style={styles.button}>
        <Text>Button B</Text>
      </Pressable>
    </View>
  );
}

const styles = StyleSheet.create({
  root: {flex: 1, paddingHorizontal: 40, paddingVertical: 80},
  label: {fontSize: 18, paddingVertical: 20},
  button: {
    backgroundColor: '#cfe3ff',
    paddingVertical: 12,
    paddingHorizontal: 20,
    borderRadius: 8,
    alignSelf: 'flex-start',
  },
});

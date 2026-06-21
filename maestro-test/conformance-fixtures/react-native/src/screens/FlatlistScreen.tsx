import React from 'react';
import {FlatList, Pressable, StyleSheet, Text, View} from 'react-native';

/**
 * Reproduces mobile-dev-inc/Maestro#2051 — nested testIDs inside a Pressable FlatList row are not
 * resolvable in the hierarchy.
 *
 * RED rows: each FlatList row is a <Pressable testID="row_N"> (accessible=true by default in RN).
 * Android treats an `accessible` ViewGroup as a single atomic accessibility element and DROPS its
 * descendants from the AccessibilityNodeInfo tree, so the nested <Text testID="row_N_title"> /
 * "row_N_subtitle" never reach getChild() in ViewHierarchy.dumpNodeRec — only the row's own testID
 * survives (as resource-id on the merged container node).
 *
 * GREEN control: a plain <View testID="plain_row"> (NOT accessible) with a nested
 * <Text testID="plain_title">. A non-accessible container is not merged, so its descendants stay as
 * separate nodes and the nested testID resolves normally.
 */
const ROWS = [0, 1];

export default function FlatlistScreen() {
  return (
    <View style={styles.root}>
      <FlatList
        testID="flatlist"
        data={ROWS}
        keyExtractor={i => String(i)}
        renderItem={({item}) => (
          <Pressable testID={`row_${item}`} style={styles.row}>
            <View>
              <Text testID={`row_${item}_title`} style={styles.title}>
                Title {item}
              </Text>
              <Text testID={`row_${item}_subtitle`} style={styles.subtitle}>
                Subtitle {item}
              </Text>
            </View>
          </Pressable>
        )}
        ListFooterComponent={
          // GREEN control: non-Pressable, non-accessible container → descendants exposed.
          <View testID="plain_row" style={styles.row}>
            <Text testID="plain_title" style={styles.title}>
              Plain Title
            </Text>
          </View>
        }
      />
    </View>
  );
}

const styles = StyleSheet.create({
  root: {flex: 1},
  row: {
    paddingHorizontal: 40,
    paddingVertical: 24,
    borderBottomWidth: 1,
    borderBottomColor: '#e0e0e0',
  },
  title: {fontSize: 18, fontWeight: '600'},
  subtitle: {fontSize: 14, color: '#666666'},
});

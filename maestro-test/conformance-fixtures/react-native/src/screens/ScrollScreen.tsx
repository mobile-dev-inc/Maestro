import React, {useRef} from 'react';
import {ScrollView, StyleSheet, Text, View} from 'react-native';
import {emit} from '../emitter';

/** Vertical ScrollView; emits SCROLL with from/to offsets as the content scrolls. */
export default function ScrollScreen() {
  const prev = useRef(0);
  return (
    <ScrollView
      testID="scroll_container"
      accessibilityLabel="scroll_container"
      style={styles.root}
      scrollEventThrottle={16}
      onScroll={e => {
        const y = Math.round(e.nativeEvent.contentOffset.y);
        if (y !== prev.current) {
          emit('SCROLL', {axis: 'Y', fromOffset: prev.current, toOffset: y});
          prev.current = y;
        }
      }}>
      {Array.from({length: 30}).map((_, i) => (
        <View key={i} style={[styles.item, i % 2 === 1 && styles.alt]}>
          <Text style={styles.itemText}>Item {i + 1}</Text>
        </View>
      ))}
    </ScrollView>
  );
}

const styles = StyleSheet.create({
  root: {flex: 1},
  item: {paddingHorizontal: 40, paddingVertical: 36, backgroundColor: '#ffffff'},
  alt: {backgroundColor: '#e3f2fd'},
  itemText: {fontSize: 18},
});

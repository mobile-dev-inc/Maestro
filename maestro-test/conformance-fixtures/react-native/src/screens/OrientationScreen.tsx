import React from 'react';
import {StyleSheet, Text, useWindowDimensions, View} from 'react-native';

/**
 * Orientation display only. The ORIENTATION event is emitted natively from MainActivity's
 * onConfigurationChanged (the activity declares configChanges so it isn't recreated on rotate).
 */
export default function OrientationScreen() {
  const {width, height} = useWindowDimensions();
  const value = width > height ? 'LANDSCAPE' : 'PORTRAIT';
  return (
    <View style={styles.root}>
      <Text testID="orientation_value" style={styles.text}>
        Orientation: {value}
      </Text>
    </View>
  );
}

const styles = StyleSheet.create({
  root: {flex: 1, alignItems: 'center', justifyContent: 'center'},
  text: {fontSize: 20},
});

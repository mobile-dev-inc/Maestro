import React, {useEffect, useRef} from 'react';
import {Animated, StyleSheet, View} from 'react-native';
import {emit} from '../emitter';

/**
 * waitUntilScreenIsStatic / waitForAppToSettle → a ~1.5s fade animation. Emits ANIM RUNNING at
 * start and SETTLED on completion, mirroring the native/compose AnimationScreen.
 */
export default function AnimationScreen() {
  const opacity = useRef(new Animated.Value(0)).current;

  useEffect(() => {
    emit('ANIM', {state: 'RUNNING'});
    Animated.timing(opacity, {toValue: 1, duration: 1500, useNativeDriver: true}).start(() => {
      emit('ANIM', {state: 'SETTLED'});
    });
  }, [opacity]);

  return (
    <View style={styles.root}>
      <Animated.View
        testID="animate_button"
        accessibilityLabel="animate_button"
        style={[styles.box, {opacity}]}
      />
    </View>
  );
}

const styles = StyleSheet.create({
  root: {flex: 1, alignItems: 'center', justifyContent: 'center'},
  box: {width: 160, height: 64, backgroundColor: '#7e57c2', borderRadius: 8},
});

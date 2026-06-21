import React from 'react';
import {Pressable, StyleSheet, Text, View} from 'react-native';
import {seedState} from '../emitter';

/**
 * launchApp / stopApp / killApp / clearAppState / openLink / backPress.
 * The STATE (seeded) event is emitted natively on launch and the seeded flag is persisted in
 * SharedPreferences (so clearAppState has data to wipe); LIFECYCLE/DEEPLINK/BACK are emitted from
 * MainActivity. This screen just renders the seed button, which calls the native seedState().
 */
export default function AppLifecycleScreen() {
  return (
    <View style={styles.root}>
      <Pressable
        testID="state_seed_button"
        accessibilityLabel="state_seed_button"
        style={styles.button}
        onPress={() => seedState()}>
        <Text>Seed State</Text>
      </Pressable>
    </View>
  );
}

const styles = StyleSheet.create({
  root: {flex: 1, alignItems: 'center', justifyContent: 'center'},
  button: {
    width: 200,
    height: 56,
    alignItems: 'center',
    justifyContent: 'center',
    backgroundColor: '#cfe3ff',
    borderRadius: 8,
  },
});

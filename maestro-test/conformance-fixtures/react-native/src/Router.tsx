import React from 'react';
import {StyleSheet, View} from 'react-native';
import TapScreen from './screens/TapScreen';
import SwipeScreen from './screens/SwipeScreen';
import ScrollScreen from './screens/ScrollScreen';
import InputScreen from './screens/InputScreen';
import KeyboardScreen from './screens/KeyboardScreen';
import TreeScreen from './screens/TreeScreen';
import OrientationScreen from './screens/OrientationScreen';
import AnimationScreen from './screens/AnimationScreen';
import AppLifecycleScreen from './screens/AppLifecycleScreen';
import FlexScreen from './screens/FlexScreen';
import FlatlistScreen from './screens/FlatlistScreen';
import NestedTextScreen from './screens/NestedTextScreen';

const SCREENS: Record<string, React.ComponentType> = {
  TapScreen,
  SwipeScreen,
  ScrollScreen,
  InputScreen,
  KeyboardScreen,
  TreeScreen,
  OrientationScreen,
  AnimationScreen,
  AppLifecycleScreen,
  // Reproductions of open RN issues (#2246 / #2051 / #821).
  FlexScreen,
  FlatlistScreen,
  NestedTextScreen,
};

export default function Router({route}: {route: string}) {
  const Screen = SCREENS[route] ?? TapScreen;
  return (
    <View style={styles.root}>
      <Screen />
    </View>
  );
}

const styles = StyleSheet.create({root: {flex: 1, backgroundColor: '#ffffff'}});

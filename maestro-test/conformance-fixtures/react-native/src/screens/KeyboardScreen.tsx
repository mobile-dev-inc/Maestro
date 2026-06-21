import React, {useEffect, useState} from 'react';
import {Keyboard, StyleSheet, TextInput, View} from 'react-native';
import {emit} from '../emitter';

/**
 * isKeyboardVisible / hideKeyboard rely on IME show/hide events — emitted via RN's Keyboard
 * listeners. pressKey(ENTER) is captured idiomatically through onSubmitEditing on the single-line
 * TextInput. (Arbitrary hardware keys aren't capturable from JS in RN; only ENTER is exercised.)
 */
export default function KeyboardScreen() {
  const [text, setText] = useState('');

  useEffect(() => {
    const show = Keyboard.addListener('keyboardDidShow', () => emit('IME', {state: 'SHOWN'}));
    const hide = Keyboard.addListener('keyboardDidHide', () => emit('IME', {state: 'HIDDEN'}));
    return () => {
      show.remove();
      hide.remove();
    };
  }, []);

  return (
    <View style={styles.root}>
      <TextInput
        testID="text_field"
        accessibilityLabel="text_field"
        style={styles.input}
        placeholder="Focus me to show keyboard..."
        value={text}
        onChangeText={setText}
        blurOnSubmit={false}
        onSubmitEditing={() => emit('KEY', {code: 'ENTER'})}
      />
    </View>
  );
}

const styles = StyleSheet.create({
  root: {flex: 1, paddingTop: 200, paddingHorizontal: 40},
  input: {borderWidth: 1, borderColor: '#888', borderRadius: 6, padding: 12, fontSize: 16},
});

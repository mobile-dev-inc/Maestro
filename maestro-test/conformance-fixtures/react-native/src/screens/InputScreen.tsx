import React, {useState} from 'react';
import {StyleSheet, TextInput, View} from 'react-native';
import {emit} from '../emitter';

/** inputText / eraseText → idiomatic TextInput; emits TEXT_CHANGED on every change. */
export default function InputScreen() {
  const [text, setText] = useState('');
  return (
    <View style={styles.root}>
      <TextInput
        testID="text_field"
        accessibilityLabel="text_field"
        style={styles.input}
        placeholder="Type here..."
        value={text}
        onChangeText={t => {
          setText(t);
          emit('TEXT_CHANGED', {text: t});
        }}
      />
    </View>
  );
}

const styles = StyleSheet.create({
  root: {flex: 1, paddingTop: 200, paddingHorizontal: 40},
  input: {borderWidth: 1, borderColor: '#888', borderRadius: 6, padding: 12, fontSize: 16},
});

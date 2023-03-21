import * as React from 'react';

import { StyleSheet, View, Text } from 'react-native';
import { multiply, setup, mockServerUrl } from 'maestro-rn-sdk';

export default function App() {
  const [result, setResult] = React.useState<string | undefined>();

  React.useEffect(() => {
    setup('projectId')
      .then(() => mockServerUrl('baseUrl'))
      .then(setResult)

    // multiply(3, 7).then(it => setResult(it.toString()));
  }, []);

  return (
    <View style={styles.container}>
      <Text>Result: {result}</Text>
    </View>
  );
}

const styles = StyleSheet.create({
  container: {
    flex: 1,
    alignItems: 'center',
    justifyContent: 'center',
  },
  box: {
    width: 60,
    height: 60,
    marginVertical: 20,
  },
});

import {NavigationContainer} from '@react-navigation/native';
import {createNativeStackNavigator} from '@react-navigation/native-stack';
import {useEffect, useRef, useState} from 'react';
import {Pressable, Text, TextInput, View} from 'react-native';

const Stack = createNativeStackNavigator();

export default function App() {
  return (
    <NavigationContainer>
      <Stack.Navigator screenOptions={{headerShown: false}}>
        <Stack.Screen name="Home" component={HomeScreen} />
        <Stack.Screen name="Navigation" component={NavigationScreen} />
        <Stack.Screen name="TextInput" component={TextInputScreen} />
        <Stack.Screen name="Focus" component={FocusScreen} />
      </Stack.Navigator>
    </NavigationContainer>
  );
}

// ---------------------------------------------------------------------------
// Home Screen — vertical menu of buttons that navigate to each test screen
// ---------------------------------------------------------------------------

const HomeScreen = ({navigation}: any) => {
  return (
    <View style={styles.screen}>
      <Text style={styles.title}>Home</Text>
      <View style={{gap: 20}}>
        <CustomButton onPress={() => navigation.navigate('Navigation')}>
          Navigation Test
        </CustomButton>
        <CustomButton onPress={() => navigation.navigate('TextInput')}>
          Text Input Test
        </CustomButton>
        <CustomButton onPress={() => navigation.navigate('Focus')}>
          Focus Test
        </CustomButton>
      </View>
    </View>
  );
};

// ---------------------------------------------------------------------------
// Navigation Screen — 2x2 grid for testing directional D-pad navigation
// ---------------------------------------------------------------------------

const NavigationScreen = () => {
  return (
    <View style={styles.screen}>
      <Text style={styles.title}>Navigation Test</Text>
      <View style={{gap: 20}}>
        <View style={{flexDirection: 'row', gap: 20}}>
          <CustomButton style={{flex: 1}}>Top Left</CustomButton>
          <CustomButton style={{flex: 1}}>Top Right</CustomButton>
        </View>
        <View style={{flexDirection: 'row', gap: 20}}>
          <CustomButton style={{flex: 1}}>Bottom Left</CustomButton>
          <CustomButton style={{flex: 1}}>Bottom Right</CustomButton>
        </View>
      </View>
    </View>
  );
};

// ---------------------------------------------------------------------------
// Text Input Screen — text field + label showing current text
// ---------------------------------------------------------------------------

const TextInputScreen = () => {
  const [text, setText] = useState('');

  return (
    <View style={styles.screen}>
      <Text style={styles.title}>Text Input Test</Text>
      <TextInput
        style={styles.textInput}
        value={text}
        onChangeText={setText}
        placeholder="Type here..."
        placeholderTextColor="#888"
      />
      <Text style={styles.label} accessibilityLabel={`Typed: ${text}`}>
        Typed: {text}
      </Text>
    </View>
  );
};

// ---------------------------------------------------------------------------
// Focus Screen — two buttons; Button 2 receives programmatic focus on mount
// ---------------------------------------------------------------------------

const FocusScreen = () => {
  const secondButtonRef = useRef<View>(null);

  useEffect(() => {
    (secondButtonRef.current as any)?.requestTVFocus?.();
  }, []);

  return (
    <View style={styles.screen}>
      <Text style={styles.title}>Focus Test</Text>
      <View style={{gap: 20}}>
        <CustomButton>Button 1</CustomButton>
        <CustomButton ref={secondButtonRef}>Button 2</CustomButton>
      </View>
    </View>
  );
};

// ---------------------------------------------------------------------------
// Shared components & styles
// ---------------------------------------------------------------------------

import {forwardRef} from 'react';

const CustomButton = forwardRef<View, any>(
  ({children, onPress, style, ...props}, ref) => {
    const [focused, setFocused] = useState(false);

    return (
      <Pressable
        ref={ref}
        onPress={onPress}
        onFocus={() => setFocused(true)}
        onBlur={() => setFocused(false)}
        style={[
          {
            backgroundColor: 'darkblue',
            borderWidth: 5,
            borderColor: focused ? 'white' : 'transparent',
            padding: 20,
          },
          style,
        ]}
        {...props}>
        <Text style={{color: 'white', fontSize: 24}}>{children}</Text>
      </Pressable>
    );
  },
);

const styles = {
  screen: {
    flex: 1,
    padding: 40,
    backgroundColor: 'black',
  } as const,
  title: {
    color: 'white',
    fontSize: 48,
    fontWeight: 'bold' as const,
    marginBottom: 40,
  },
  textInput: {
    borderWidth: 2,
    borderColor: '#444',
    color: 'white',
    fontSize: 24,
    padding: 16,
    marginBottom: 20,
  },
  label: {
    color: '#ccc',
    fontSize: 24,
  },
};

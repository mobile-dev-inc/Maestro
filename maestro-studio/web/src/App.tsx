import React from 'react';
import Main from './Main';
import { DeviceScreen } from './models';

function App() {
  const getDeviceScreen = async (): Promise<DeviceScreen> => {
    const response = await fetch('/api/device-screen')
    return await response.json()
  }
  return (
    <Main getDeviceScreen={getDeviceScreen} />
  );
}

export default App;

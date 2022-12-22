import InteractableDevice from './InteractableDevice';
import React from 'react';
import { mockDeviceScreen } from './mocks';

export default {
  title: 'InteractableDevice'
}

export const Main = () => {
  return (
    <InteractableDevice deviceScreen={mockDeviceScreen}/>
  )
}

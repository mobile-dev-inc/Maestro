import React from 'react';
import Inspect from './Inspect';
import { mockDeviceScreen } from './mocks';

export default {
  title: 'Inspect'
}

export const Main = () => {
  return (
    <Inspect deviceScreen={mockDeviceScreen} />
  )
}

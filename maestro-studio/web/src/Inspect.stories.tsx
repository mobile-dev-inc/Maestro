import React from 'react';
import { DeviceScreen } from './models';
import { sampleScreenshot } from './fixtures';
import Inspect from './Inspect';
import { sampleElements } from './sampleElements';

export default {
  title: 'Inspect'
}

const deviceScreen: DeviceScreen = {
  screenshot: sampleScreenshot,
  width: 1080,
  height: 2340,
  elements: sampleElements,
}

export const Main = () => {
  return (
    <Inspect deviceScreen={deviceScreen} />
  )
}

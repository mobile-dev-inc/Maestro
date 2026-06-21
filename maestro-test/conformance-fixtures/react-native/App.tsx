/**
 * Maestro driver-conformance React Native fixture.
 *
 * The native MainActivity passes the launched `route` (from the intent extra) as an initial prop;
 * the Router renders the matching screen. Per-screen events are emitted to the MAESTRO_FIXTURE
 * logcat oracle via the native FixtureEmitter bridge.
 *
 * @format
 */

import React from 'react';
import Router from './src/Router';

type Props = {route?: string};

function App({route}: Props) {
  return <Router route={route ?? 'TapScreen'} />;
}

export default App;

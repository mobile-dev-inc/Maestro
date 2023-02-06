import React from 'react';
import DeprecatedInspectPage from './DeprecatedInspectPage';
import InteractPage from './InteractPage';
import MockPage from './MockPage';

function App() {
  switch (window.location.pathname) {
    case '/inspect':
      return <DeprecatedInspectPage />
    
    case '/mock':
      return <MockPage />

    case '/interact':
    default:
      return <InteractPage />
  }
}

export default App;

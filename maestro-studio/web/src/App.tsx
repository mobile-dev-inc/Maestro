import React from 'react';
import DeprecatedInspectPage from './DeprecatedInspectPage';
import InteractPage from './InteractPage';

function App() {
  if (window.location.pathname === '/inspect') {
    return (
      <DeprecatedInspectPage />
    )
  }
  return (
    <InteractPage />
  );
}

export default App;

import React from 'react';
import Main from './Main';
import InteractPage from './InteractPage';

function App() {
  if (window.location.pathname === '/interact') {
    return (
      <InteractPage />
    )
  }
  return (
    <Main />
  );
}

export default App;

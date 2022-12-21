import React from 'react';
import Main from './Main';
import ReplView from './ReplView';

function App() {
  if (window.location.pathname === '/interact') {
    return (
      <ReplView />
    )
  }
  return (
    <Main />
  );
}

export default App;

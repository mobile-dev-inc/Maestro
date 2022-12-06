import React, { useEffect, useState } from 'react';
import './App.css';

function App() {
  const [hierarchy, setHierarchy] = useState<string>()
  useEffect(() => {
    (async () => {
      const response = await fetch('/api/hierarchy')
      const hierarch = await response.text()
      setHierarchy(hierarch)
    })()
  }, [])
  return (
    <div className="App">
      {hierarchy}
    </div>
  );
}

export default App;

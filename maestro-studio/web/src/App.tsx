import React, { useEffect, useState } from 'react';
import './App.css';

type TreeNode = {
  attributes: {[key: string]: string}
  children: TreeNode[]
  clickable: boolean | undefined
  enabled: boolean | undefined
  focused: boolean | undefined
  checked: boolean | undefined
  selected: boolean | undefined
}

type Hierarchy = {
  screenshot: string
  tree: TreeNode
}

function App() {
  const [hierarchy, setHierarchy] = useState<Hierarchy>()
  useEffect(() => {
    (async () => {
      const response = await fetch('/api/hierarchy')
      const hierarchy: Hierarchy = await response.json()
      setHierarchy(hierarchy)
    })()
  }, [])
  if (!hierarchy) {
    return (
      <div>Loading...</div>
    )
  }
  return (
    <div className="App flex h-full">
      <img className="h-full" src={hierarchy.screenshot} alt="screenshot"/>
      <p className="overflow-scroll">{JSON.stringify(hierarchy.tree)}</p>
    </div>
  );
}

export default App;

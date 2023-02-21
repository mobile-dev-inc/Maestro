import React from 'react';
import { Routes, Route, Navigate } from "react-router-dom"
import DeprecatedInspectPage from './DeprecatedInspectPage';
import Header from './Header';
import InteractPage from './InteractPage';
import MockPage from './MockPage';

const App = () => (
  <div className='flex flex-col h-screen overflow-hidden dark:bg-slate-800'>
    <Header />
    <div className="overflow-hidden h-full">
      <Routes>
        <Route path="inspect" element={ <DeprecatedInspectPage/> } />
        <Route path="mock" element={ <MockPage/> } />
        <Route path="interact" element={ <InteractPage/> } />
        <Route path="*" element={<Navigate to="/interact" replace />} />
      </Routes>
    </div>
  </div>
)

export default App;

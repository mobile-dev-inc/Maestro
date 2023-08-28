import React from "react";
import { Routes, Route, Navigate } from "react-router-dom";
import Header from "./components/common/Header";
import InteractPage from "./pages/InteractPage";
import { AuthProvider } from "./context/AuthContext";

const App = () => (
  <div className="flex flex-col h-screen overflow-hidden dark:bg-slate-900">
    <AuthProvider>
      <Header />
      <div className="overflow-hidden h-full">
        <Routes>
          <Route path="interact" element={<InteractPage />} />
          <Route path="*" element={<Navigate to="/interact" replace />} />
        </Routes>
      </div>
    </AuthProvider>
  </div>
);

export default App;


// Load D3 globally first for plugins
import './d3-global';
// Setup global variables for UMD plugins FIRST (before any other imports that might use React)
import './setupGlobals';

import React from 'react';
import ReactDOM from 'react-dom/client';
import App from './App';
import { AuthProvider } from './contexts/AuthContexts';

// Prevent service worker registration in VSCode webview context
// Service workers are not supported in VSCode webviews
if ('serviceWorker' in navigator) {
  // Override the register method to prevent registration attempts
  const originalRegister = navigator.serviceWorker.register;
  navigator.serviceWorker.register = function() {
    console.warn('[OntoCode] Service worker registration blocked in VSCode webview context');
    return Promise.reject(new Error('Service workers are not supported in VSCode webviews'));
  };
}

const rootElement = document.getElementById('root');
if (!rootElement) {
  throw new Error("Could not find root element to mount to");
}

const root = ReactDOM.createRoot(rootElement);
root.render(
  <React.StrictMode>
    <AuthProvider>
      <App />
    </AuthProvider>
  </React.StrictMode>
);

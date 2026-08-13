// Prevent service worker registration in VSCode webview context FIRST
// Service workers are not supported in VSCode webviews
// This MUST be before any imports to prevent third-party libraries from registering service workers
if ('serviceWorker' in navigator) {
  try {
    // Completely remove serviceWorker from navigator
    delete (navigator as any).serviceWorker;
    
    // Override with a completely silent frozen mock
    Object.defineProperty(navigator, 'serviceWorker', {
      value: Object.freeze({
        register: () => Promise.resolve({
          installing: null,
          waiting: null,
          active: null,
          addEventListener: () => {},
          removeEventListener: () => {},
          dispatchEvent: () => false,
          unregister: () => Promise.resolve(true),
          update: () => Promise.resolve()
        } as any),
        getRegistration: () => Promise.resolve(undefined),
        getRegistrations: () => Promise.resolve([]),
        ready: new Promise(() => {}), // Never resolves
        addEventListener: () => {},
        removeEventListener: () => {},
        dispatchEvent: () => false,
        controller: null
      }),
      configurable: false,
      writable: false,
      enumerable: false
    });
  } catch (e) {
    // Fallback: silently override methods
    if (navigator.serviceWorker) {
      try {
        (navigator.serviceWorker as any).register = () => Promise.resolve({});
        (navigator.serviceWorker as any).getRegistration = () => Promise.resolve(undefined);
        (navigator.serviceWorker as any).getRegistrations = () => Promise.resolve([]);
      } catch (err) {
        // Last resort: do nothing
      }
    }
  }
}

// Load D3 globally first for plugins
import './d3-global';
// Setup global variables for UMD plugins FIRST (before any other imports that might use React)
import './setupGlobals';

// Install the browser-mode bridge BEFORE any React component mounts.
// In VS Code Desktop this is a no-op (window.vscode already exists).
import { installBrowserBridge } from './utils/vscodeBridge';
installBrowserBridge();


if ('serviceWorker' in navigator) {
  try {

    delete (navigator as any).serviceWorker;

    Object.defineProperty(navigator, 'serviceWorker', {
      value: Object.freeze({
        register: () => Promise.resolve({
          installing: null,
          waiting: null,
          active: null,
          addEventListener: () => {},
          removeEventListener: () => {},
          dispatchEvent: () => false,
          unregister: () => Promise.resolve(true),
          update: () => Promise.resolve()
        } as any),
        getRegistration: () => Promise.resolve(undefined),
        getRegistrations: () => Promise.resolve([]),
        ready: new Promise(() => {}), // Never resolves
        addEventListener: () => {},
        removeEventListener: () => {},
        dispatchEvent: () => false,
        controller: null
      }),
      configurable: false,
      writable: false,
      enumerable: false
    });
  } catch (e) {

    if (navigator.serviceWorker) {
      try {
        (navigator.serviceWorker as any).register = () => Promise.resolve({});
        (navigator.serviceWorker as any).getRegistration = () => Promise.resolve(undefined);
        (navigator.serviceWorker as any).getRegistrations = () => Promise.resolve([]);
      } catch (err) {
        // Last resort: do nothing
      }
    }
  }
}

import './d3-global';

import './setupGlobals';

import { installBrowserBridge } from './utils/vscodeBridge';
installBrowserBridge();

import React from 'react';
import ReactDOM from 'react-dom/client';
import App from './App';
import { AuthProvider } from './contexts/AuthContexts';
import ErrorBoundary from './components/ErrorBoundary';

const rootElement = document.getElementById('root');
if (!rootElement) {
  throw new Error("Could not find root element to mount to");
}

const root = ReactDOM.createRoot(rootElement);
root.render(
  <React.StrictMode>
    <ErrorBoundary>
      <AuthProvider>
        <App />
      </AuthProvider>
    </ErrorBoundary>
  </React.StrictMode>
);

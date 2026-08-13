

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

(function() {
  'use strict';
  const originalError = console.error;
  const originalWarn = console.warn;
  const originalLog = console.log;
  console.error = function(...args) {
    const msg = String(args[0] || '');
    if (msg.includes('service worker') || msg.includes('ServiceWorker') ||
        msg.includes('Could not register') || msg.includes('InvalidStateError') ||
        msg.includes('Webview fatal error') || msg.includes('Failed to register')) {
      return;
    }
    originalError.apply(console, args);
  };
  console.warn = function(...args) {
    const msg = String(args[0] || '');
    if (msg.includes('service worker') || msg.includes('ServiceWorker')) {
      return;
    }
    originalWarn.apply(console, args);
  };
  console.log = function(...args) {
    const msg = String(args[0] || '');
    if (msg.includes('Webview fatal error') && msg.includes('service worker')) {
      return;
    }
    originalLog.apply(console, args);
  };
  if ('serviceWorker' in navigator) {
    try {
      const safeServiceWorker = {
          register: () => Promise.resolve({
            installing: null,
            waiting: null,
            active: null,
            addEventListener: () => {},
            removeEventListener: () => {},
            unregister: () => Promise.resolve(true),
            update: () => Promise.resolve()
          }),
          getRegistration: () => Promise.resolve(undefined),
          getRegistrations: () => Promise.resolve([]),
          ready: new Promise(() => {}),
          addEventListener: () => {},
          removeEventListener: () => {},
          controller: null
      };
      try {
           Object.defineProperty(navigator, 'serviceWorker', {
            value: safeServiceWorker,
            configurable: true,
            writable: true
          });
      } catch(e) { console.error("Could not override serviceWorker property", e); }
    } catch (e) {
      try {
        if (navigator.serviceWorker) {
            navigator.serviceWorker.register = () => Promise.resolve({});
            navigator.serviceWorker.getRegistration = () => Promise.resolve(undefined);
            navigator.serviceWorker.getRegistrations = () => Promise.resolve([]);
        }
      } catch (err) {
      }
    }
  }
  window.addEventListener('unhandledrejection', function(event) {
    if (event.reason) {
      const msg = String(event.reason.message || event.reason || '');
      if (msg.includes('service worker') || msg.includes('ServiceWorker') ||
          msg.includes('Could not register') || msg.includes('InvalidStateError')) {
        event.preventDefault();
        event.stopPropagation();
        return false;
      }
    }
  }, true);
  window.addEventListener('error', function(event) {
    if (event.message) {
      const msg = String(event.message);
      if (msg.includes('service worker') || msg.includes('ServiceWorker') ||
          msg.includes('Could not register') || msg.includes('InvalidStateError')) {
        event.preventDefault();
        event.stopPropagation();
        return false;
      }
    }
  }, true);
})();

/**
 * Setup global variables for UMD plugins
 * This file explicitly imports and exposes libraries that plugins need
 */

import React from 'react';
import ReactDOM from 'react-dom/client';

// Import lucide-react - we need to ensure this isn't tree-shaken
// Using dynamic access to prevent static analysis from removing unused exports
import * as LucideIcons from 'lucide-react';
import { authenticatedFetch, getAuthHeaders } from './utils/authenticatedFetch';

// Expose React globally for UMD plugins
(window as any).React = React;
(window as any).ReactDOM = ReactDOM;

// Expose lucide-react globally for UMD plugins
// Force the entire module to be included by accessing it dynamically
const lucide = LucideIcons;
(window as any).LucideReact = lucide;

// Force Vite/Rollup to include lucide-react by using it
// This prevents tree-shaking since we're dynamically accessing properties
if (typeof window !== 'undefined' && lucide) {
  // Access some icons to prevent dead code elimination
  const iconKeys = Object.keys(lucide);
  console.log(`[Globals] Exposed ${iconKeys.length} Lucide exports to window.LucideReact`);
  
  // Verify some commonly used icons exist
  const testIcons = ['Check', 'X', 'AlertCircle', 'Loader2', 'Play', 'Save'];
  const missing = testIcons.filter(icon => !(lucide as any)[icon]);
  if (missing.length > 0) {
    console.warn('[Globals] Missing Lucide icons:', missing);
  }
}

export { React, ReactDOM, lucide as LucideReact };

// Authenticated fetch for UMD plugins (Change Assistant, Graph View, etc.)
(window as any).authenticatedFetch = authenticatedFetch;
(window as any).getAuthHeaders = getAuthHeaders;



import React from 'react';
import ReactDOM from 'react-dom/client';

import * as LucideIcons from 'lucide-react';
import { authenticatedFetch, getAuthHeaders } from './utils/authenticatedFetch';

(window as any).React = React;
(window as any).ReactDOM = ReactDOM;

const lucide = LucideIcons;
(window as any).LucideReact = lucide;

if (typeof window !== 'undefined' && lucide) {

  const iconKeys = Object.keys(lucide);

  const testIcons = ['Check', 'X', 'AlertCircle', 'Loader2', 'Play', 'Save'];
  const missing = testIcons.filter(icon => !(lucide as any)[icon]);
  if (missing.length > 0) {
    console.warn('[Globals] Missing Lucide icons:', missing);
  }
}

export { React, ReactDOM, lucide as LucideReact };

(window as any).authenticatedFetch = authenticatedFetch;
(window as any).getAuthHeaders = getAuthHeaders;

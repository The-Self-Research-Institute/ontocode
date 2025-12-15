/**
 * D3 Global Setup
 * Imports D3 from node_modules and exposes it globally for plugins
 */
import * as d3 from 'd3';

// Expose D3 globally for plugin components
(window as any).d3 = d3;

console.log('[D3] Loaded from bundle and exposed globally');

/**
 * ForceAtlas2 layout for the WebGL engine — the Gephi-quality layout no mainstream
 * JS graph tool ships as default. Runs in a web worker (graphology supervisor);
 * falls back to chunked synchronous iterations where blob workers are blocked (CSP).
 */

import { useEffect } from 'react';
import type Graph from 'graphology';
import forceAtlas2 from 'graphology-layout-forceatlas2';
import FA2LayoutSupervisor from 'graphology-layout-forceatlas2/worker';
import noverlap from 'graphology-layout-noverlap';

export function useFA2Layout(graph: Graph | null, enabled: boolean): void {
  useEffect(() => {
    if (!graph || !enabled || graph.order === 0) return;

    const settings = forceAtlas2.inferSettings(graph);
    // Give the sim time proportional to size, capped — FA2 never stops on its own.
    const runMs = Math.min(10000, 1500 + graph.order * 2);

    let supervisor: FA2LayoutSupervisor | null = null;
    let timer: ReturnType<typeof setTimeout> | null = null;
    let frame: number | null = null;

    const settle = () => {
      // Post-pass: push apart any residual overlaps (reads the size attribute)
      try {
        noverlap.assign(graph, { maxIterations: 60, settings: { margin: 4, ratio: 1.1, expansion: 1.15 } });
      } catch { /* layout polish only — never fatal */ }
    };

    try {
      supervisor = new FA2LayoutSupervisor(graph, { settings });
      supervisor.start();
      timer = setTimeout(() => {
        supervisor?.stop();
        settle();
      }, runMs);
    } catch {
      // Blob workers blocked (webview CSP) — chunked sync fallback keeps the UI alive
      let remaining = Math.min(600, 100 + graph.order);
      const step = () => {
        if (remaining <= 0) {
          settle();
          return;
        }
        forceAtlas2.assign(graph, { iterations: 10, settings });
        remaining -= 10;
        frame = requestAnimationFrame(step);
      };
      frame = requestAnimationFrame(step);
    }

    return () => {
      if (timer != null) clearTimeout(timer);
      if (frame != null) cancelAnimationFrame(frame);
      supervisor?.kill();
    };
  }, [graph, enabled]);
}

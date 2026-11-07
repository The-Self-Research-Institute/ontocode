

import { useEffect } from 'react';
import type Graph from 'graphology';
import forceAtlas2 from 'graphology-layout-forceatlas2';
import FA2LayoutSupervisor from 'graphology-layout-forceatlas2/worker';
import noverlap from 'graphology-layout-noverlap';

export function useFA2Layout(
  graph: Graph | null,
  enabled: boolean,
  getViewportPx?: () => number
): void {
  useEffect(() => {
    if (!graph || !enabled || graph.order === 0) return;

    const settings = forceAtlas2.inferSettings(graph);

    const runMs = Math.min(20000, 1500 + graph.order * 3);
    settings.strongGravityMode = false;
    settings.gravity = 0.05;
    settings.scalingRatio = graph.order > 400 ? 30 : 20;
    settings.adjustSizes = true;

    let supervisor: FA2LayoutSupervisor | null = null;
    let timer: ReturnType<typeof setTimeout> | null = null;
    let frame: number | null = null;

    const unitsPerPixel = () => {
      const viewport = getViewportPx?.() ?? 0;
      if (!viewport) return 1;
      let minX = Infinity, maxX = -Infinity, minY = Infinity, maxY = -Infinity;
      graph.forEachNode((_n, a) => {
        if (a.x < minX) minX = a.x;
        if (a.x > maxX) maxX = a.x;
        if (a.y < minY) minY = a.y;
        if (a.y > maxY) maxY = a.y;
      });
      const extent = Math.max(maxX - minX, maxY - minY);
      return extent > 0 ? Math.max(1, extent / viewport) : 1;
    };

    const settle = () => {

      try {
        const scale = unitsPerPixel();
        noverlap.assign(graph, {
          maxIterations: 80,
          settings: { margin: 4 * scale, ratio: 1.1 * scale, expansion: 1.15 }
        });
      } catch { /* layout polish only — never fatal */ }
    };

    let workerCheck: ReturnType<typeof setTimeout> | null = null;

    const runOnMainThread = () => {
      let remaining = Math.min(600, 100 + graph.order);
      const step = () => {
        if (remaining <= 0) {
          frame = null;
          settle();
          return;
        }
        forceAtlas2.assign(graph, { iterations: 10, settings });
        remaining -= 10;
        frame = requestAnimationFrame(step);
      };
      frame = requestAnimationFrame(step);
    };

    const sampleIds = graph.nodes().slice(0, 20);
    const startPositions = sampleIds.map(id => {
      const a = graph.getNodeAttributes(id);
      return { id, x: a.x, y: a.y };
    });

    try {
      supervisor = new FA2LayoutSupervisor(graph, { settings });
      supervisor.start();
      timer = setTimeout(() => {
        supervisor?.stop();
        settle();
      }, runMs);
      workerCheck = setTimeout(() => {
        workerCheck = null;
        const moved = startPositions.some(p => {
          if (!graph.hasNode(p.id)) return false;
          const a = graph.getNodeAttributes(p.id);
          return a.x !== p.x || a.y !== p.y;
        });
        if (moved) return;
        if (timer != null) {
          clearTimeout(timer);
          timer = null;
        }
        supervisor?.kill();
        supervisor = null;
        runOnMainThread();
      }, 500);
    } catch {
      supervisor = null;
      runOnMainThread();
    }

    return () => {
      if (workerCheck != null) clearTimeout(workerCheck);
      if (timer != null) clearTimeout(timer);
      if (frame != null) cancelAnimationFrame(frame);
      supervisor?.kill();
    };
  }, [graph, enabled, getViewportPx]);
}

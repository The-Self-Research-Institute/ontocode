import { useEffect, useRef, useState } from "react";
import type React from "react";

export function useResizablePanelWidth(initialWidth: number, minWidth: number, maxWidth: number) {
  const [width, setWidth] = useState(initialWidth);
  const widthRef = useRef(initialWidth);
  const panelRef = useRef<HTMLDivElement | null>(null);
  const draggingRef = useRef(false);

  const onDragStart = (e: React.MouseEvent) => {
    e.preventDefault();
    draggingRef.current = true;
    document.body.style.cursor = "col-resize";
    document.body.style.userSelect = "none";
  };

  useEffect(() => {
    const handleMouseMove = (e: MouseEvent) => {
      if (!draggingRef.current) return;
      const next = Math.min(maxWidth, Math.max(minWidth, window.innerWidth - e.clientX));
      widthRef.current = next;
      if (panelRef.current) panelRef.current.style.width = `${next}px`;
    };
    const handleMouseUp = () => {
      if (!draggingRef.current) return;
      draggingRef.current = false;
      document.body.style.cursor = "";
      document.body.style.userSelect = "";
      setWidth(widthRef.current);
    };
    window.addEventListener("mousemove", handleMouseMove);
    window.addEventListener("mouseup", handleMouseUp);
    return () => {
      window.removeEventListener("mousemove", handleMouseMove);
      window.removeEventListener("mouseup", handleMouseUp);
    };
  }, [minWidth, maxWidth]);

  return { width, panelRef, onDragStart };
}

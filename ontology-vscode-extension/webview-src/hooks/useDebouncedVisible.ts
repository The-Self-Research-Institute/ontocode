import { useEffect, useRef, useState } from "react";

type DebouncedVisibleOptions = {
  /** Wait before showing (avoids flash on sub-ms loads). Default 150ms. */
  showDelayMs?: number;
  /** Minimum time visible once shown. Default 350ms. */
  minVisibleMs?: number;
};

/**
 * Delays showing a transient UI element and enforces a minimum visible duration
 * so loaders and banners do not flicker.
 */
export function useDebouncedVisible(
  active: boolean,
  { showDelayMs = 150, minVisibleMs = 350 }: DebouncedVisibleOptions = {},
): boolean {
  const [visible, setVisible] = useState(false);
  const visibleSinceRef = useRef<number | null>(null);
  const showTimerRef = useRef<ReturnType<typeof setTimeout> | undefined>(undefined);
  const hideTimerRef = useRef<ReturnType<typeof setTimeout> | undefined>(undefined);

  useEffect(() => {
    clearTimeout(showTimerRef.current);
    clearTimeout(hideTimerRef.current);

    if (active) {
      showTimerRef.current = setTimeout(() => {
        visibleSinceRef.current = Date.now();
        setVisible(true);
      }, showDelayMs);
      return () => clearTimeout(showTimerRef.current);
    }

    if (!visible) {
      return;
    }

    const elapsed = visibleSinceRef.current
      ? Date.now() - visibleSinceRef.current
      : minVisibleMs;
    const remaining = Math.max(0, minVisibleMs - elapsed);
    hideTimerRef.current = setTimeout(() => {
      setVisible(false);
      visibleSinceRef.current = null;
    }, remaining);

    return () => clearTimeout(hideTimerRef.current);
  }, [active, showDelayMs, minVisibleMs, visible]);

  return visible;
}

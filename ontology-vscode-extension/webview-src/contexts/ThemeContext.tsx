import React, { createContext, useContext, useState, useEffect, ReactNode } from 'react';

export type ThemeMode = 'light' | 'dark' | 'auto';

export interface ThemeColors {
  accent: string;
  success: string;
  warning: string;
  error: string;
  info: string;
}

export interface Theme {
  mode: ThemeMode;
  colors: ThemeColors;
}

interface ThemeContextType {
  theme: Theme;
  actualMode: 'light' | 'dark';
  setThemeMode: (mode: ThemeMode) => void;
  updateColors: (colors: Partial<ThemeColors>) => void;
  resetToDefault: () => void;
}

function hexToRgb(hex: string): { r: number; g: number; b: number } {
  const h = hex.replace('#', '');
  const bigint = parseInt(h.length === 3 ? h.split('').map(c => c + c).join('') : h, 16);
  return { r: (bigint >> 16) & 255, g: (bigint >> 8) & 255, b: bigint & 255 };
}

function srgbToLin(c: number): number {
  c /= 255;
  return c <= 0.04045 ? c / 12.92 : Math.pow((c + 0.055) / 1.055, 2.4);
}

function luminance(hex: string): number {
  const { r, g, b } = hexToRgb(hex);
  const R = srgbToLin(r), G = srgbToLin(g), B = srgbToLin(b);
  return 0.2126 * R + 0.7152 * G + 0.0722 * B;
}

function contrastRatio(hex1: string, hex2: string): number {
  const L1 = luminance(hex1);
  const L2 = luminance(hex2);
  const lighter = Math.max(L1, L2);
  const darker = Math.min(L1, L2);
  return (lighter + 0.05) / (darker + 0.05);
}

function pickOnColor(accentHex: string): string {
  const white = '#FFFFFF';
  const black = '#111111';
  return contrastRatio(accentHex, white) >= contrastRatio(accentHex, black) ? white : black;
}

function adjustAccentForMode(hex: string, mode: 'light' | 'dark'): string {
  const { r, g, b } = hexToRgb(hex);

  const rNorm = r / 255, gNorm = g / 255, bNorm = b / 255;
  const max = Math.max(rNorm, gNorm, bNorm);
  const min = Math.min(rNorm, gNorm, bNorm);
  let h = 0, s = 0, l = (max + min) / 2;

  if (max !== min) {
    const d = max - min;
    s = l > 0.5 ? d / (2 - max - min) : d / (max + min);

    switch (max) {
      case rNorm: h = ((gNorm - bNorm) / d + (gNorm < bNorm ? 6 : 0)) / 6; break;
      case gNorm: h = ((bNorm - rNorm) / d + 2) / 6; break;
      case bNorm: h = ((rNorm - gNorm) / d + 4) / 6; break;
    }
  }

  if (mode === 'dark') {

    l = Math.min(0.75, l + 0.15);
  } else {

    l = Math.max(0.4, l - 0.1);
  }

  const hslToRgb = (h: number, s: number, l: number) => {
    let r, g, b;

    if (s === 0) {
      r = g = b = l;
    } else {
      const hue2rgb = (p: number, q: number, t: number) => {
        if (t < 0) t += 1;
        if (t > 1) t -= 1;
        if (t < 1/6) return p + (q - p) * 6 * t;
        if (t < 1/2) return q;
        if (t < 2/3) return p + (q - p) * (2/3 - t) * 6;
        return p;
      };

      const q = l < 0.5 ? l * (1 + s) : l + s - l * s;
      const p = 2 * l - q;
      r = hue2rgb(p, q, h + 1/3);
      g = hue2rgb(p, q, h);
      b = hue2rgb(p, q, h - 1/3);
    }

    return {
      r: Math.round(r * 255),
      g: Math.round(g * 255),
      b: Math.round(b * 255)
    };
  };

  const rgb = hslToRgb(h, s, l);
  return `#${((1 << 24) + (rgb.r << 16) + (rgb.g << 8) + rgb.b).toString(16).slice(1)}`;
}

const defaultLightColors: ThemeColors = {
  accent: '#3b82f6',      // blue-600
  success: '#10b981',     // green-500
  warning: '#f59e0b',     // amber-500
  error: '#ef4444',       // red-500
  info: '#3b82f6',        // blue-500
};

const defaultDarkColors: ThemeColors = {
  accent: '#60a5fa',      // blue-400 (brighter for dark mode)
  success: '#34d399',     // green-400
  warning: '#fbbf24',     // amber-400
  error: '#f87171',       // red-400
  info: '#60a5fa',        // blue-400
};

const ThemeContext = createContext<ThemeContextType | undefined>(undefined);

export const ThemeProvider: React.FC<{ children: ReactNode }> = ({ children }) => {
  const [themeMode, setThemeModeState] = useState<ThemeMode>('dark');
  const [customLightColors, setCustomLightColors] = useState<Partial<ThemeColors>>({});
  const [customDarkColors, setCustomDarkColors] = useState<Partial<ThemeColors>>({});

  const getActualMode = (): 'light' | 'dark' => {
    if (themeMode === 'auto') {

      if (window.matchMedia && window.matchMedia('(prefers-color-scheme: dark)').matches) {
        return 'dark';
      }
      return 'light';
    }
    return themeMode;
  };

  const actualMode = getActualMode();

  const getThemeColors = (): ThemeColors => {
    const baseColors = actualMode === 'dark' ? defaultDarkColors : defaultLightColors;
    const customColors = actualMode === 'dark' ? customDarkColors : customLightColors;
    return { ...baseColors, ...customColors };
  };

  const theme: Theme = {
    mode: themeMode,
    colors: getThemeColors(),
  };

  useEffect(() => {
    const savedMode = localStorage.getItem('ontocode-theme-mode') as ThemeMode;
    const savedLightColors = localStorage.getItem('ontocode-theme-light-colors');
    const savedDarkColors = localStorage.getItem('ontocode-theme-dark-colors');

    const oldColors = localStorage.getItem('ontocode-theme-colors');
    if (oldColors) {
      localStorage.removeItem('ontocode-theme-colors');
    }

    if (savedMode) {
      setThemeModeState(savedMode);
    }

    if (savedLightColors) {
      try {
        setCustomLightColors(JSON.parse(savedLightColors));
      } catch (e) {
        console.error('Failed to parse saved light theme colors:', e);
      }
    }

    if (savedDarkColors) {
      try {
        setCustomDarkColors(JSON.parse(savedDarkColors));
      } catch (e) {
        console.error('Failed to parse saved dark theme colors:', e);
      }
    }
  }, []);

  useEffect(() => {
    const root = document.documentElement;
    const colors = getThemeColors();

    const adjustedAccent = adjustAccentForMode(colors.accent, actualMode);
    const onAccent = pickOnColor(adjustedAccent);

    const accentRgb = hexToRgb(adjustedAccent);
    const accentTint = `rgba(${accentRgb.r}, ${accentRgb.g}, ${accentRgb.b}, ${actualMode === 'dark' ? '0.12' : '0.08'})`;
    const accentHover = `rgba(${accentRgb.r}, ${accentRgb.g}, ${accentRgb.b}, ${actualMode === 'dark' ? '0.12' : '0.08'})`;
    const accentPressed = `rgba(${accentRgb.r}, ${accentRgb.g}, ${accentRgb.b}, ${actualMode === 'dark' ? '0.20' : '0.16'})`;

    root.style.setProperty('--accent', adjustedAccent);
    root.style.setProperty('--on-accent', onAccent);
    root.style.setProperty('--accent-tint', accentTint);
    root.style.setProperty('--accent-hover', accentHover);
    root.style.setProperty('--accent-pressed', accentPressed);

    const adjustedSuccess = adjustAccentForMode(colors.success, actualMode);
    const adjustedWarning = adjustAccentForMode(colors.warning, actualMode);
    const adjustedError = adjustAccentForMode(colors.error, actualMode);
    const adjustedInfo = adjustAccentForMode(colors.info, actualMode);

    root.style.setProperty('--success', adjustedSuccess);
    root.style.setProperty('--on-success', pickOnColor(adjustedSuccess));
    root.style.setProperty('--warning', adjustedWarning);
    root.style.setProperty('--on-warning', pickOnColor(adjustedWarning));
    root.style.setProperty('--error', adjustedError);
    root.style.setProperty('--on-error', pickOnColor(adjustedError));
    root.style.setProperty('--info', adjustedInfo);
    root.style.setProperty('--on-info', pickOnColor(adjustedInfo));

    if (actualMode === 'dark') {
      root.classList.add('dark');
    } else {
      root.classList.remove('dark');
    }
  }, [actualMode, customLightColors, customDarkColors]);

  useEffect(() => {
    if (themeMode !== 'auto') return;

    const mediaQuery = window.matchMedia('(prefers-color-scheme: dark)');
    const handler = () => {

      setThemeModeState('auto');
    };

    mediaQuery.addEventListener('change', handler);
    return () => mediaQuery.removeEventListener('change', handler);
  }, [themeMode]);

  const setThemeMode = (mode: ThemeMode) => {
    setThemeModeState(mode);
    localStorage.setItem('ontocode-theme-mode', mode);
  };

  const updateColors = (colors: Partial<ThemeColors>) => {

    if (actualMode === 'dark') {
      const newColors = { ...customDarkColors, ...colors };
      setCustomDarkColors(newColors);
      localStorage.setItem('ontocode-theme-dark-colors', JSON.stringify(newColors));
    } else {
      const newColors = { ...customLightColors, ...colors };
      setCustomLightColors(newColors);
      localStorage.setItem('ontocode-theme-light-colors', JSON.stringify(newColors));
    }
  };

  const resetToDefault = () => {

    if (actualMode === 'dark') {
      setCustomDarkColors({});
      localStorage.removeItem('ontocode-theme-dark-colors');
    } else {
      setCustomLightColors({});
      localStorage.removeItem('ontocode-theme-light-colors');
    }
  };

  return (
    <ThemeContext.Provider value={{ theme, actualMode, setThemeMode, updateColors, resetToDefault }}>
      {children}
    </ThemeContext.Provider>
  );
};

export const useTheme = (): ThemeContextType => {
  const context = useContext(ThemeContext);
  if (!context) {
    throw new Error('useTheme must be used within a ThemeProvider');
  }
  return context;
};

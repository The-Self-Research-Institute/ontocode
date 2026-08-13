import React, { useState } from 'react';
import { useTheme, ThemeMode, ThemeColors } from '../contexts/ThemeContext';
import { Sun, Moon, Monitor, Palette, RotateCcw, X } from 'lucide-react';

interface ThemeSettingsProps {
  isOpen: boolean;
  onClose: () => void;
}

const ThemeSettings: React.FC<ThemeSettingsProps> = ({ isOpen, onClose }) => {
  const { theme, actualMode, setThemeMode, updateColors, resetToDefault } = useTheme();
  const [activeTab, setActiveTab] = useState<'mode' | 'colors'>('mode');

  if (!isOpen) return null;

  const colorOptions: Array<{ key: keyof ThemeColors; label: string; description: string }> = [
    { key: 'accent', label: 'Accent', description: 'Primary brand color (automatically adjusted for readability)' },
    { key: 'success', label: 'Success', description: 'Success states and confirmations' },
    { key: 'warning', label: 'Warning', description: 'Warning states and alerts' },
    { key: 'error', label: 'Error', description: 'Error states and destructive actions' },
    { key: 'info', label: 'Info', description: 'Informational messages' },
  ];

  const handleColorChange = (key: keyof ThemeColors, value: string) => {
    updateColors({ [key]: value });
  };

  return (
    <div
      className="fixed inset-0 bg-black/50 flex items-center justify-center z-50"
      onMouseDown={(e) => {
        if (e.target === e.currentTarget && e.button === 0) onClose();
      }}
    >
      <div
        className="rounded-2xl shadow-2xl w-full max-w-3xl max-h-[85vh] overflow-hidden flex flex-col"
        style={{ backgroundColor: 'var(--bg)' }}
        onClick={(e) => e.stopPropagation()}
      >
        {}
        <div className="p-6 border-b flex items-center justify-between" style={{ borderColor: 'var(--border)' }}>
          <div className="flex items-center gap-3">
            <div className="w-10 h-10 rounded-lg bg-gradient-to-br from-purple-500 to-indigo-600 flex items-center justify-center">
              <Palette size={20} className="text-white" />
            </div>
            <div>
              <h2 className="text-xl font-bold" style={{ color: 'var(--text-primary)' }}>Theme Settings</h2>
              <p className="text-sm" style={{ color: 'var(--text-secondary)' }}>Customize your workspace appearance</p>
            </div>
          </div>
          <button
            onClick={onClose}
            className="p-2 rounded-lg transition-colors hover-bg-overlay"
            style={{
              color: 'var(--text-secondary)'
            }}
          >
            <X size={20} />
          </button>
        </div>

        {}
        <div className="flex border-b px-6" style={{ borderColor: 'var(--border)' }}>
          <button
            onClick={() => setActiveTab('mode')}
            className="px-4 py-3 text-sm font-medium transition-colors relative"
            style={{
              color: activeTab === 'mode' ? 'var(--accent)' : 'var(--text-secondary)'
            }}
          >
            Theme Mode
            {activeTab === 'mode' && (
              <div className="absolute bottom-0 left-0 right-0 h-0.5" style={{ backgroundColor: 'var(--accent)' }} />
            )}
          </button>
          <button
            onClick={() => setActiveTab('colors')}
            className="px-4 py-3 text-sm font-medium transition-colors relative"
            style={{
              color: activeTab === 'colors' ? 'var(--accent)' : 'var(--text-secondary)'
            }}
          >
            Custom Colors
            {activeTab === 'colors' && (
              <div className="absolute bottom-0 left-0 right-0 h-0.5" style={{ backgroundColor: 'var(--accent)' }} />
            )}
          </button>
        </div>

        {}
        <div className="flex-1 overflow-y-auto p-6">
          {activeTab === 'mode' && (
            <div className="space-y-4">
              <p className="text-sm mb-6" style={{ color: 'var(--text-secondary)' }}>
                Choose your preferred theme mode. Auto mode follows your system settings.
              </p>

              <div className="grid grid-cols-1 md:grid-cols-3 gap-4">
                {}
                <button
                  onClick={() => setThemeMode('light')}
                  className="p-6 rounded-xl border-2 transition-all"
                  style={{
                    borderColor: theme.mode === 'light' ? 'var(--accent)' : 'var(--border)',
                    backgroundColor: theme.mode === 'light' ? 'var(--accent-tint)' : 'transparent'
                  }}
                >
                  <div className="flex flex-col items-center gap-3">
                    <div className="w-12 h-12 rounded-full flex items-center justify-center"
                      style={{ backgroundColor: theme.mode === 'light' ? 'var(--accent)' : 'var(--surface-2)' }}
                    >
                      <Sun size={24} style={{ color: theme.mode === 'light' ? 'var(--on-accent)' : 'var(--text-secondary)' }} />
                    </div>
                    <div className="text-center">
                      <div className="font-semibold" style={{ color: 'var(--text-primary)' }}>Light</div>
                      <div className="text-xs mt-1" style={{ color: 'var(--text-secondary)' }}>Always use light theme</div>
                    </div>
                  </div>
                </button>

                {}
                <button
                  onClick={() => setThemeMode('dark')}
                  className="p-6 rounded-xl border-2 transition-all"
                  style={{
                    borderColor: theme.mode === 'dark' ? 'var(--accent)' : 'var(--border)',
                    backgroundColor: theme.mode === 'dark' ? 'var(--accent-tint)' : 'transparent'
                  }}
                >
                  <div className="flex flex-col items-center gap-3">
                    <div className="w-12 h-12 rounded-full flex items-center justify-center"
                      style={{ backgroundColor: theme.mode === 'dark' ? 'var(--accent)' : 'var(--surface-2)' }}
                    >
                      <Moon size={24} style={{ color: theme.mode === 'dark' ? 'var(--on-accent)' : 'var(--text-secondary)' }} />
                    </div>
                    <div className="text-center">
                      <div className="font-semibold" style={{ color: 'var(--text-primary)' }}>Dark</div>
                      <div className="text-xs mt-1" style={{ color: 'var(--text-secondary)' }}>Always use dark theme</div>
                    </div>
                  </div>
                </button>

                {}
                <button
                  onClick={() => setThemeMode('auto')}
                  className="p-6 rounded-xl border-2 transition-all"
                  style={{
                    borderColor: theme.mode === 'auto' ? 'var(--accent)' : 'var(--border)',
                    backgroundColor: theme.mode === 'auto' ? 'var(--accent-tint)' : 'transparent'
                  }}
                >
                  <div className="flex flex-col items-center gap-3">
                    <div className="w-12 h-12 rounded-full flex items-center justify-center"
                      style={{ backgroundColor: theme.mode === 'auto' ? 'var(--accent)' : 'var(--surface-2)' }}
                    >
                      <Monitor size={24} style={{ color: theme.mode === 'auto' ? 'var(--on-accent)' : 'var(--text-secondary)' }} />
                    </div>
                    <div className="text-center">
                      <div className="font-semibold" style={{ color: 'var(--text-primary)' }}>Auto</div>
                      <div className="text-xs mt-1" style={{ color: 'var(--text-secondary)' }}>Follow system settings</div>
                    </div>
                  </div>
                </button>
              </div>

              <div className="mt-6 p-4 rounded-lg" style={{ backgroundColor: 'var(--info-tint)', borderColor: 'var(--info)', border: '1px solid' }}>
                <div className="flex items-start gap-3">
                  <div className="w-5 h-5 rounded-full flex items-center justify-center flex-shrink-0 mt-0.5" style={{ backgroundColor: 'var(--info)' }}>
                    <span className="text-white text-xs font-bold">i</span>
                  </div>
                  <div>
                    <div className="font-medium text-sm" style={{ color: 'var(--text-primary)' }}>Current Mode</div>
                    <div className="text-sm mt-1" style={{ color: 'var(--text-secondary)' }}>
                      {theme.mode === 'auto'
                        ? `Auto mode is currently showing ${actualMode} theme based on your system settings.`
                        : `Using ${actualMode} theme.`
                      }
                    </div>
                  </div>
                </div>
              </div>
            </div>
          )}

          {activeTab === 'colors' && (
            <div className="space-y-4">
              <div className="flex items-center justify-between mb-6">
                <div>
                  <p className="text-sm" style={{ color: 'var(--text-secondary)' }}>
                    Customize semantic colors for the {actualMode} theme.
                  </p>
                  <p className="text-xs mt-1" style={{ color: 'var(--text-tertiary)' }}>
                    Colors are automatically adjusted for optimal contrast and readability.
                  </p>
                </div>
                <button
                  onClick={resetToDefault}
                  className="flex items-center gap-2 px-3 py-1.5 text-sm rounded-lg transition-colors"
                  style={{
                    color: 'var(--accent)',
                    backgroundColor: 'var(--accent-tint)'
                  }}
                >
                  <RotateCcw size={14} />
                  Reset
                </button>
              </div>

              <div className="grid grid-cols-1 gap-4">
                {colorOptions.map((option) => (
                  <div key={option.key} className="p-4 rounded-lg" style={{ backgroundColor: 'var(--surface-1)' }}>
                    <div className="flex items-center justify-between mb-3">
                      <div className="flex-1">
                        <div className="font-medium text-sm flex items-center gap-2" style={{ color: 'var(--text-primary)' }}>
                          {option.label}
                          {option.key === 'accent' && (
                            <span className="text-xs px-2 py-0.5 rounded" style={{
                              backgroundColor: 'var(--accent-tint)',
                              color: 'var(--accent)'
                            }}>
                              Primary
                            </span>
                          )}
                        </div>
                        <div className="text-xs mt-0.5" style={{ color: 'var(--text-secondary)' }}>{option.description}</div>
                      </div>
                      <div
                        className="w-12 h-12 rounded-lg border-2 shadow-inner"
                        style={{
                          backgroundColor: theme.colors[option.key],
                          borderColor: 'var(--border)'
                        }}
                      />
                    </div>
                    <input
                      type="color"
                      value={theme.colors[option.key]}
                      onChange={(e) => handleColorChange(option.key, e.target.value)}
                      className="w-full h-10 rounded cursor-pointer"
                      style={{ borderColor: 'var(--border)', border: '1px solid' }}
                    />
                  </div>
                ))}
              </div>

              <div className="mt-6 p-4 rounded-lg" style={{
                backgroundColor: 'var(--warning-tint)',
                borderColor: 'var(--warning)',
                border: '1px solid'
              }}>
                <div className="flex items-start gap-3">
                  <div className="w-5 h-5 rounded-full flex items-center justify-center flex-shrink-0 mt-0.5" style={{ backgroundColor: 'var(--warning)' }}>
                    <span className="text-white text-xs font-bold">!</span>
                  </div>
                  <div>
                    <div className="font-medium text-sm" style={{ color: 'var(--text-primary)' }}>Smart Color System</div>
                    <div className="text-sm mt-1" style={{ color: 'var(--text-secondary)' }}>
                      Colors are saved separately for light and dark modes. The accent color is automatically adjusted for optimal visibility.
                    </div>
                  </div>
                </div>
              </div>

              {}
              <button
                onClick={() => {
                  if (confirm('Reset all color customizations for both light and dark modes?')) {
                    localStorage.removeItem('ontocode-theme-light-colors');
                    localStorage.removeItem('ontocode-theme-dark-colors');
                    localStorage.removeItem('ontocode-theme-colors'); // old storage
                    onClose();

                    setTimeout(() => window.location.reload(), 100);
                  }
                }}
                className="mt-4 w-full px-4 py-2 text-sm font-medium rounded-lg transition-colors"
                style={{
                  color: 'var(--error)',
                  backgroundColor: 'var(--error-tint)',
                  borderColor: 'var(--error)',
                  border: '1px solid'
                }}
              >
                Reset All Customizations (Light & Dark)
              </button>
            </div>
          )}
        </div>

        {}
        <div className="p-6 border-t flex justify-between items-center" style={{ borderColor: 'var(--border)' }}>
          <div className="text-xs" style={{ color: 'var(--text-secondary)' }}>
            Currently customizing: <strong style={{ color: 'var(--text-primary)' }}>{actualMode === 'dark' ? 'Dark' : 'Light'} mode</strong>
          </div>
          <button
            onClick={onClose}
            className="px-6 py-2 text-sm font-medium rounded-lg transition-colors hover-bg-surface-3"
            style={{
              color: 'var(--text-primary)',
              backgroundColor: 'var(--surface-2)'
            }}
          >
            Close
          </button>
        </div>
      </div>
    </div>
  );
};

export default ThemeSettings;

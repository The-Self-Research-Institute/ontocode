import React from "react";
import { Sparkles, Check, Package, AlertCircle, Loader2, RefreshCw, Download } from "lucide-react";

export interface PluginPlaceholderProps {
  pluginId: string;
  pluginName: string;
  description: string;
  icon: React.ReactNode;
  features: string[];
  accentColor: string;
  onInstall: () => void;
  onRetryLoad: () => void;
  isInstalled: boolean;
  isLoading: boolean;
  error?: string | null;
}

export const PluginPlaceholder: React.FC<PluginPlaceholderProps> = ({
  pluginName,
  description,
  icon,
  features,
  accentColor,
  onInstall,
  onRetryLoad,
  isInstalled,
  isLoading,
  error,
}) => {
  return (
    <div
      className="h-full flex items-center justify-center p-8"
      style={{ backgroundColor: "var(--bg)", color: "var(--text-primary)" }}
    >
      <div className="max-w-2xl w-full">
        <div
          className="rounded-2xl shadow-xl overflow-hidden"
          style={{ backgroundColor: "var(--surface-1)", border: "1px solid var(--border)" }}
        >
          <div className={`bg-gradient-to-r ${accentColor} p-8 text-white relative overflow-hidden`}>
            <div className="absolute inset-0 opacity-10">
              <div className="absolute -right-10 -top-10 w-40 h-40 border-2 border-white rounded-full" />
              <div className="absolute -right-5 -bottom-5 w-32 h-32 border-2 border-white rounded-full" />
              <div className="absolute left-1/4 top-1/2 w-20 h-20 border border-white rounded-full" />
            </div>
            <div className="relative flex items-start gap-5">
              <div className="flex-shrink-0 w-16 h-16 bg-white/20 backdrop-blur-sm rounded-xl flex items-center justify-center shadow-lg">
                {icon}
              </div>
              <div className="flex-1">
                <h2 className="text-2xl font-bold mb-2">{pluginName}</h2>
                <p className="text-white/90 text-sm leading-relaxed">{description}</p>
              </div>
            </div>
          </div>

          <div className="p-8">
            <div className="mb-8">
              <h3 className="text-sm font-semibold uppercase tracking-wider mb-4 flex items-center gap-2 text-secondary">
                <Sparkles size={16} className="text-accent" />
                Key Features
              </h3>
              <div className="grid grid-cols-2 gap-3">
                {features?.map((feature, index) => (
                  <div
                    key={index}
                    className="flex items-center gap-3 p-3 rounded-lg transition-all hover-overlay"
                    style={{ backgroundColor: "var(--surface-2)", border: "1px solid var(--border)" }}
                  >
                    <div className="w-6 h-6 rounded-full bg-accent flex items-center justify-center flex-shrink-0">
                      <Check size={12} className="text-white" />
                    </div>
                    <span className="text-sm font-medium text-primary">{feature}</span>
                  </div>
                ))}
              </div>
            </div>

            <div className="pt-6" style={{ borderTop: "1px solid var(--divider)" }}>
              {error ? (
                <div
                  className="mb-4 p-4 rounded-xl flex items-start gap-3"
                  style={{ backgroundColor: "var(--error-tint)", border: "1px solid var(--error)" }}
                >
                  <AlertCircle size={20} className="flex-shrink-0 mt-0.5" style={{ color: "var(--error)" }} />
                  <div className="flex-1">
                    <p className="text-sm font-medium text-red-800">Failed to load plugin</p>
                    <p className="text-xs text-red-600 mt-1">{error}</p>
                  </div>
                </div>
              ) : isLoading ? (
                <div
                  className="mb-4 p-4 rounded-xl flex items-center gap-3"
                  style={{ backgroundColor: "var(--info-tint)", border: "1px solid var(--info)" }}
                >
                  <Loader2 size={20} className="animate-spin" style={{ color: "var(--info)" }} />
                  <div>
                    <p className="text-sm font-medium text-blue-800">Loading plugin...</p>
                    <p className="text-xs text-blue-600 mt-0.5">Downloading and initializing components</p>
                  </div>
                </div>
              ) : isInstalled ? (
                <div
                  className="mb-4 p-4 rounded-xl flex items-start gap-3"
                  style={{ backgroundColor: "var(--warning-tint)", border: "1px solid var(--warning)" }}
                >
                  <AlertCircle size={20} className="flex-shrink-0 mt-0.5" style={{ color: "var(--warning)" }} />
                  <div>
                    <p className="text-sm font-medium text-amber-800">Plugin installed but not loaded</p>
                    <p className="text-xs text-amber-600 mt-1">Click the button below to load the plugin</p>
                  </div>
                </div>
              ) : (
                <div
                  className="mb-4 p-4 rounded-xl flex items-start gap-3"
                  style={{ backgroundColor: "var(--surface-2)", border: "1px solid var(--border)" }}
                >
                  <Package size={20} className="flex-shrink-0 mt-0.5 text-tertiary" />
                  <div>
                    <p className="text-sm font-medium text-gray-700">Plugin not installed</p>
                    <p className="text-xs text-gray-500 mt-1">Install from the marketplace to unlock these features</p>
                  </div>
                </div>
              )}

              <div className="flex gap-3">
                {isInstalled ? (
                  <button
                    onClick={onRetryLoad}
                    disabled={isLoading}
                    className={`flex-1 px-6 py-3 rounded-xl font-semibold text-white shadow-lg transition-all flex items-center justify-center gap-2 ${
                      isLoading
                        ? "bg-gray-400 cursor-not-allowed"
                        : "bg-gradient-to-r from-purple-600 to-indigo-600 hover:from-purple-700 hover:to-indigo-700 hover:shadow-xl hover:-translate-y-0.5"
                    }`}
                  >
                    {isLoading ? (
                      <>
                        <Loader2 size={18} className="animate-spin" />
                        Loading...
                      </>
                    ) : (
                      <>
                        <RefreshCw size={18} />
                        Load Plugin
                      </>
                    )}
                  </button>
                ) : (
                  <button
                    onClick={onInstall}
                    className="flex-1 px-6 py-3 rounded-xl font-semibold text-white bg-gradient-to-r from-purple-600 to-indigo-600 hover:from-purple-700 hover:to-indigo-700 shadow-lg hover:shadow-xl transition-all hover:-translate-y-0.5 flex items-center justify-center gap-2"
                  >
                    <Download size={18} />
                    Install from Marketplace
                  </button>
                )}
              </div>
            </div>
          </div>
        </div>

        <p className="text-center text-xs text-gray-700 mt-4">
          Tip: Access all plugins from the <span className="font-medium">Settings → Plugin Marketplace</span>
        </p>
      </div>
    </div>
  );
};

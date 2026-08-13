

import React, { useEffect, useState } from "react";
import { X, Download, Clock, RotateCcw } from "lucide-react";
import { getApiBaseUrl } from "../config/deploymentConfig";

interface PluginVersion {
  pluginId: string;
  version: string;
  publishedAt?: string;
  downloads?: number;
  changelog?: string;
}

interface PluginVersionHistoryProps {
  pluginId: string;
  pluginName: string;
  installedVersion?: string;
  onClose: () => void;
  onInstallVersion: (pluginId: string, version: string) => Promise<void>;

  maxVersions?: number;
}

export const PluginVersionHistory: React.FC<PluginVersionHistoryProps> = ({
  pluginId,
  pluginName,
  installedVersion,
  onClose,
  onInstallVersion,
  maxVersions = 5,
}) => {
  const [versions, setVersions] = useState<PluginVersion[]>([]);
  const [loading, setLoading] = useState(false);
  const [installing, setInstalling] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    const fetchVersions = async () => {
      setLoading(true);
      setError(null);
      try {
        const token = localStorage.getItem("authToken");
        const headers: HeadersInit = { "Content-Type": "application/json" };
        if (token) headers["Authorization"] = `Bearer ${token}`;

        const res = await fetch(`${getApiBaseUrl()}/api/plugins/${pluginId}/versions`, { headers });
        if (!res.ok) throw new Error(`Failed to fetch versions (HTTP ${res.status})`);

        const data = await res.json();
        setVersions(Array.isArray(data) ? data : []);
      } catch (e) {
        console.error("[PluginVersionHistory] Failed to load versions:", e);
        setError(e instanceof Error ? e.message : "Unknown error");
      } finally {
        setLoading(false);
      }
    };

    fetchVersions();
  }, [pluginId]);

  const handleRollback = async (version: string) => {
    setInstalling(version);
    try {
      await onInstallVersion(pluginId, version);
      onClose();
    } catch (e) {
      console.error("[PluginVersionHistory] Failed to install version:", e);
      setError(e instanceof Error ? e.message : "Install failed");
    } finally {
      setInstalling(null);
    }
  };

  const displayVersions = versions.slice(0, maxVersions);

  return (
    <div className="fixed inset-0 bg-black bg-opacity-50 flex items-center justify-center z-[60]">
      <div className="bg-white rounded-lg shadow-2xl max-w-2xl w-full mx-4 max-h-[80vh] flex flex-col">
        <div className="border-b border-gray-200 px-6 py-4 flex items-center justify-between">
          <div className="flex items-center gap-2">
            <Clock className="w-5 h-5 text-purple-600" />
            <h2 className="text-lg font-semibold text-black">Version History — {pluginName}</h2>
          </div>
          <button onClick={onClose} className="text-gray-400 hover:text-gray-600">
            <X size={20} />
          </button>
        </div>

        <div className="flex-1 overflow-y-auto p-6">
          {loading && (
            <div className="text-center py-8 text-gray-600">Loading versions...</div>
          )}

          {error && (
            <div className="bg-red-50 text-red-700 p-3 rounded mb-4 text-sm">{error}</div>
          )}

          {!loading && !error && displayVersions.length === 0 && (
            <div className="text-center py-8 text-gray-500">No version history available</div>
          )}

          {!loading && displayVersions.length > 0 && (
            <div className="space-y-3">
              <p className="text-xs text-gray-600 mb-2">
                Showing {displayVersions.length} of {versions.length} versions.
                {installedVersion && (
                  <> Currently installed: <span className="font-semibold">v{installedVersion}</span></>
                )}
              </p>
              {displayVersions.map((v, idx) => {
                const isInstalled = v.version === installedVersion;
                const isLatest = idx === 0;
                return (
                  <div
                    key={v.version}
                    className={`border rounded-lg p-4 flex items-start justify-between gap-4 ${
                      isInstalled ? "border-green-300 bg-green-50" : "border-gray-200"
                    }`}
                  >
                    <div className="flex-1">
                      <div className="flex items-center gap-2 mb-1">
                        <span className="font-semibold text-black">v{v.version}</span>
                        {isLatest && (
                          <span className="bg-blue-100 text-blue-700 px-2 py-0.5 rounded text-xs">Latest</span>
                        )}
                        {isInstalled && (
                          <span className="bg-green-100 text-green-700 px-2 py-0.5 rounded text-xs">Installed</span>
                        )}
                      </div>
                      {v.publishedAt && (
                        <div className="text-xs text-gray-600">
                          Published: {new Date(v.publishedAt).toLocaleString()}
                        </div>
                      )}
                      {typeof v.downloads === "number" && (
                        <div className="text-xs text-gray-600">
                          Downloads: {v.downloads.toLocaleString()}
                        </div>
                      )}
                      {v.changelog && (
                        <div className="text-xs text-gray-700 mt-2 whitespace-pre-wrap">{v.changelog}</div>
                      )}
                    </div>
                    <div className="flex flex-col gap-2">
                      {!isInstalled && (
                        <button
                          onClick={() => handleRollback(v.version)}
                          disabled={!!installing}
                          className="px-3 py-1.5 bg-purple-600 text-white rounded text-xs font-medium hover:bg-purple-700 disabled:opacity-50 flex items-center gap-1"
                        >
                          {isLatest ? <Download size={12} /> : <RotateCcw size={12} />}
                          {installing === v.version
                            ? "Installing..."
                            : isLatest
                              ? "Install Latest"
                              : "Rollback to This"}
                        </button>
                      )}
                    </div>
                  </div>
                );
              })}
            </div>
          )}
        </div>

        <div className="border-t border-gray-200 px-6 py-3 flex justify-end">
          <button
            onClick={onClose}
            className="px-4 py-2 bg-gray-200 text-gray-800 rounded hover:bg-gray-300 text-sm font-medium"
          >
            Close
          </button>
        </div>
      </div>
    </div>
  );
};

import React, { useEffect, useState } from "react";
import { Download, RefreshCw, X } from "lucide-react";
import { isDesktop } from "../utils/desktop";

type UpdateStatus = {
  status: string;
  currentVersion?: string;
  availableVersion?: string | null;
  percent?: number;
  error?: string | null;
};

export const DesktopUpdateBanner: React.FC = () => {
  const [update, setUpdate] = useState<UpdateStatus | null>(null);
  const [dismissed, setDismissed] = useState(false);
  const [installing, setInstalling] = useState(false);

  useEffect(() => {
    if (!isDesktop()) return;
    const api = (window as any).electronAPI;
    if (!api?.onUpdateStatus) return;

    api.updateGetStatus?.().then((s: UpdateStatus) => {
      if (s) setUpdate(s);
    });

    const unsubscribe = api.onUpdateStatus((status: UpdateStatus) => {
      setUpdate(status);
      if (status.status === "available" || status.status === "downloaded") {
        setDismissed(false);
      }
    });
    return unsubscribe;
  }, []);

  if (!isDesktop() || dismissed || !update) return null;

  const showBanner = ["available", "downloading", "downloaded"].includes(update.status);
  if (!showBanner) return null;

  const version = update.availableVersion || "";
  const isReady = update.status === "downloaded";
  const isDownloading = update.status === "downloading";

  const handleDownload = async () => {
    try {
      await (window as any).electronAPI?.updateDownload?.();
    } catch {
      /* banner shows error via status event */
    }
  };

  const handleInstall = async () => {
    setInstalling(true);
    try {
      await (window as any).electronAPI?.updateInstall?.();
    } finally {
      setInstalling(false);
    }
  };

  return (
    <div className="fixed top-0 left-0 right-0 z-[9999] flex items-center justify-center px-4 py-2 bg-indigo-600 text-white text-sm shadow-lg">
      <div className="flex flex-wrap items-center justify-center gap-3 max-w-4xl w-full">
        <Download size={16} className="flex-shrink-0" />
        <span>
          {isReady
            ? `OntoCode ${version} is ready. Restart to update.`
            : isDownloading
              ? `Downloading OntoCode ${version}… ${update.percent ?? 0}%`
              : `A new version of OntoCode is available (${version}).`}
        </span>
        {isReady ? (
          <button
            type="button"
            onClick={handleInstall}
            disabled={installing}
            className="inline-flex items-center gap-1.5 rounded-md bg-white/20 hover:bg-white/30 px-3 py-1 text-xs font-medium transition-colors"
          >
            <RefreshCw size={12} className={installing ? "animate-spin" : ""} />
            Restart to update
          </button>
        ) : !isDownloading ? (
          <button
            type="button"
            onClick={handleDownload}
            className="inline-flex items-center gap-1.5 rounded-md bg-white/20 hover:bg-white/30 px-3 py-1 text-xs font-medium transition-colors"
          >
            Download update
          </button>
        ) : null}
        <button
          type="button"
          onClick={() => setDismissed(true)}
          className="p-1 rounded hover:bg-white/20 transition-colors"
          aria-label="Dismiss update notification"
        >
          <X size={14} />
        </button>
      </div>
    </div>
  );
};

export default DesktopUpdateBanner;

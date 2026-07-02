import React, { useEffect, useState } from "react";
import { Download, Monitor, CheckCircle, ArrowLeft, Cpu, HardDrive, MemoryStick } from "lucide-react";
import { getGatewayUrl } from "../config/deploymentConfig";
import { OntoCodeLogo } from "./OntoCodeLogo";
import { AppVersionBadge } from "./AppVersionBadge";

const RELEASE_BASE = `${getGatewayUrl()}/api/downloads`;
const PLATFORM = "windows-x64";

type ReleaseInfo = {
  version: string;
  filename: string;
  size: number;
  releaseNotes: string;
  publishedAt: string;
  downloadUrl: string;
};

type SystemRequirements = {
  os: string;
  ram: string;
  disk: string;
  display: string;
  java: string;
  network: string;
};

const FEATURES = [
  "Full offline editing — no internet required",
  "84,000+ class ontologies (GO-plus tested)",
  "Peer-to-peer sync via Syncthing — no cloud account needed",
  "OWLAPI-powered instant class navigation",
  "All reasoners: HermiT, Openllet, ELK",
  "SPARQL query interface",
  "Plugin marketplace support",
  "Free for personal use",
];

function formatBytes(bytes: number): string {
  if (!bytes || bytes <= 0) return "";
  const units = ["B", "KB", "MB", "GB"];
  let value = bytes;
  let i = 0;
  while (value >= 1024 && i < units.length - 1) {
    value /= 1024;
    i += 1;
  }
  return `${value.toFixed(i === 0 ? 0 : 1)} ${units[i]}`;
}

/** Visitor OS for download analytics (separate from installer platform). */
function detectClientOs(): string {
  const ua = navigator.userAgent.toLowerCase();
  const platform = (
    (navigator as Navigator & { userAgentData?: { platform?: string } }).userAgentData?.platform
    || navigator.platform
    || ""
  ).toLowerCase();

  if (/android/.test(ua)) return "android";
  if (/iphone|ipad|ipod/.test(ua)) return "ios";
  if (/mac|darwin/.test(ua) || platform.includes("mac")) return "macos";
  if (/win/.test(ua) || platform.includes("win")) return "windows";
  if (/linux/.test(ua) || platform.includes("linux")) return "linux";
  return "unknown";
}

interface Props {
  onBack?: () => void;
}

export const DesktopDownloadPage: React.FC<Props> = ({ onBack }) => {
  const [release, setRelease] = useState<ReleaseInfo | null>(null);
  const [requirements, setRequirements] = useState<SystemRequirements | null>(null);
  const [loading, setLoading] = useState(true);
  const [downloading, setDownloading] = useState(false);

  useEffect(() => {
    let cancelled = false;
    const clientOs = detectClientOs();
    (async () => {
      try {
        await fetch(
          `${RELEASE_BASE}/track?platform=${PLATFORM}&event=page_view&clientOs=${encodeURIComponent(clientOs)}`,
          { method: "POST" },
        );
      } catch {
        // Non-blocking analytics
      }
      try {
        const res = await fetch(`${RELEASE_BASE}/info`);
        if (!res.ok) throw new Error("Failed to load release info");
        const data = await res.json();
        if (cancelled) return;
        const win = data?.latest?.[PLATFORM];
        if (win) setRelease(win);
        if (data?.systemRequirements) setRequirements(data.systemRequirements);
      } catch {
        if (!cancelled) {
          setRelease({
            version: "1.1.0",
            filename: "OntoCode-Setup.exe",
            size: 0,
            releaseNotes: "",
            publishedAt: "",
            downloadUrl: `${RELEASE_BASE}/${PLATFORM}`,
          });
        }
      } finally {
        if (!cancelled) setLoading(false);
      }
    })();
    return () => { cancelled = true; };
  }, []);

  const handleDownload = () => {
    setDownloading(true);
    const clientOs = detectClientOs();
    window.location.href = `${RELEASE_BASE}/${PLATFORM}?clientOs=${encodeURIComponent(clientOs)}`;
    setTimeout(() => setDownloading(false), 3000);
  };

  const versionLabel = release?.version || "…";

  return (
    <div className="min-h-screen bg-gradient-to-br from-slate-900 via-purple-950 to-slate-900 text-white">
      <div className="border-b border-white/10 bg-white/5 backdrop-blur-sm">
        <div className="max-w-5xl mx-auto px-6 py-4 flex items-center justify-between">
          <div className="flex items-center gap-3">
            {onBack && (
              <button
                onClick={onBack}
                className="flex items-center gap-1.5 text-sm text-white/60 hover:text-white transition-colors"
              >
                <ArrowLeft size={16} /> Back to app
              </button>
            )}
          </div>
          <div className="flex items-center gap-2">
            <AppVersionBadge variant="dark" />
            <span className="text-xs text-white/40">Installer v{versionLabel}</span>
            {release?.releaseNotes && (
              <span className="text-xs text-white/50 max-w-xs truncate hidden sm:inline">
                {release.releaseNotes}
              </span>
            )}
          </div>
        </div>
      </div>

      <div className="max-w-5xl mx-auto px-6 py-12">
        <div className="text-center mb-12">
          <div className="flex justify-center mb-6">
            <OntoCodeLogo size={80} rounded className="shadow-2xl shadow-purple-500/20" />
          </div>
          <div className="inline-flex items-center gap-2 bg-purple-500/20 border border-purple-500/30 rounded-full px-4 py-1.5 text-sm text-purple-300 mb-6">
            <Cpu size={14} /> Desktop Edition — Free Download (Windows)
          </div>
          <h1 className="text-4xl font-bold mb-4 bg-gradient-to-r from-white to-white/70 bg-clip-text text-transparent">
            OntoCode Desktop
          </h1>
          <p className="text-lg text-white/60 max-w-2xl mx-auto">
            Full OWL ontology editor — works completely offline. No account required.
            Import, edit, reason and query ontologies up to millions of triples.
          </p>
          <p className="text-sm text-white/40 mt-3">
            macOS and Linux builds are coming soon. Windows 10/11 is available now.
          </p>
        </div>

        <div className="bg-white/5 border border-white/10 rounded-2xl p-8 mb-8 backdrop-blur-sm">
          <div className="flex items-start justify-between mb-6">
            <div>
              <h2 className="text-xl font-semibold mb-1 flex items-center gap-2">
                <Monitor size={22} className="text-blue-400" />
                Windows
              </h2>
              <p className="text-sm text-white/50">
                {requirements?.os || "Windows 10 or later (64-bit x64 or ARM64)"}
              </p>
            </div>
            <Monitor size={32} className="text-white/30" />
          </div>

          <button
            type="button"
            onClick={handleDownload}
            disabled={loading || downloading}
            className="w-full flex items-center justify-between p-4 rounded-xl transition-all group bg-purple-600 hover:bg-purple-500 border border-purple-500 disabled:opacity-60"
          >
            <div className="flex items-center gap-3">
              <Download size={18} className="text-white" />
              <div className="text-left">
                <div className="text-sm font-medium text-white">
                  {loading ? "Loading…" : `Download for Windows (v${versionLabel})`}
                </div>
                <div className="text-xs text-white/70">
                  {release?.size ? formatBytes(release.size) : "Installer"} · In-app updates included
                </div>
              </div>
            </div>
            <span className="text-xs bg-white/20 px-2 py-0.5 rounded-full text-white/80">
              {downloading ? "Starting…" : "Recommended"}
            </span>
          </button>

          <p className="text-xs text-white/30 mt-4 text-center">
            No sign-up required · Free to use · Uninstall via Windows Settings → Apps
          </p>
        </div>

        {requirements && (
          <div className="bg-white/5 border border-white/10 rounded-2xl p-6 mb-8">
            <h3 className="text-lg font-semibold mb-4">System requirements</h3>
            <div className="grid sm:grid-cols-2 gap-4 text-sm">
              <div className="flex gap-3 p-3 rounded-lg bg-white/5">
                <Monitor size={18} className="text-purple-400 mt-0.5 flex-shrink-0" />
                <div>
                  <div className="font-medium text-white/90">Operating system</div>
                  <div className="text-white/60">{requirements.os}</div>
                </div>
              </div>
              <div className="flex gap-3 p-3 rounded-lg bg-white/5">
                <MemoryStick size={18} className="text-purple-400 mt-0.5 flex-shrink-0" />
                <div>
                  <div className="font-medium text-white/90">Memory</div>
                  <div className="text-white/60">{requirements.ram}</div>
                </div>
              </div>
              <div className="flex gap-3 p-3 rounded-lg bg-white/5">
                <HardDrive size={18} className="text-purple-400 mt-0.5 flex-shrink-0" />
                <div>
                  <div className="font-medium text-white/90">Disk space</div>
                  <div className="text-white/60">{requirements.disk}</div>
                </div>
              </div>
              <div className="flex gap-3 p-3 rounded-lg bg-white/5">
                <Cpu size={18} className="text-purple-400 mt-0.5 flex-shrink-0" />
                <div>
                  <div className="font-medium text-white/90">Java &amp; network</div>
                  <div className="text-white/60">{requirements.java}</div>
                  <div className="text-white/50 text-xs mt-1">{requirements.network}</div>
                </div>
              </div>
            </div>
            <p className="text-xs text-white/40 mt-4">{requirements.display}</p>
          </div>
        )}

        <div className="grid grid-cols-2 md:grid-cols-4 gap-4 mb-12">
          {FEATURES.map((f) => (
            <div key={f} className="flex items-start gap-2 p-3 bg-white/5 rounded-lg border border-white/5">
              <CheckCircle size={14} className="text-purple-400 mt-0.5 flex-shrink-0" />
              <span className="text-xs text-white/70 leading-relaxed">{f}</span>
            </div>
          ))}
        </div>

        <div className="text-center text-xs text-white/35 max-w-xl mx-auto">
          Download counts use a privacy-friendly hashed IP (never stored in plain text) to help us
          understand demand. No personal data is collected from the installer.
        </div>
      </div>
    </div>
  );
};

export default DesktopDownloadPage;

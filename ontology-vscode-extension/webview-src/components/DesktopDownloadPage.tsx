import React, { useEffect, useState } from "react";
import { Download, Monitor, Terminal, CheckCircle, ArrowLeft, Cpu, HardDrive, MemoryStick } from "lucide-react";
import { getGatewayUrl } from "../config/deploymentConfig";
import { isRealVSCode } from "../utils/desktop";
import { OntoCodeLogo } from "./OntoCodeLogo";
import { AppVersionBadge } from "./AppVersionBadge";

const RELEASE_BASE = `${getGatewayUrl()}/api/downloads`;

type PlatformKey = "windows-x64" | "linux-x64";

const PLATFORM_META: Record<PlatformKey, { label: string; icon: typeof Monitor; os: string }> = {
  "windows-x64": { label: "Windows", icon: Monitor, os: "Windows 10 or later (64-bit x64 or ARM64)" },
  "linux-x64": { label: "Linux", icon: Terminal, os: "Ubuntu 20.04+ or equivalent (AppImage — runs on most distros)" },
};

/** Best default platform guess before /info tells us what's actually available. */
function defaultPlatform(): PlatformKey {
  return detectClientOs() === "linux" ? "linux-x64" : "windows-x64";
}

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
  const [platform, setPlatform] = useState<PlatformKey>(defaultPlatform);
  const [availablePlatforms, setAvailablePlatforms] = useState<PlatformKey[]>(["windows-x64"]);
  const [releases, setReleases] = useState<Partial<Record<PlatformKey, ReleaseInfo>>>({});
  const [linuxDeb, setLinuxDeb] = useState<ReleaseInfo | null>(null);
  const [linuxArm64, setLinuxArm64] = useState<ReleaseInfo | null>(null);
  const [linuxFlatpak, setLinuxFlatpak] = useState<ReleaseInfo | null>(null);
  const [requirements, setRequirements] = useState<SystemRequirements | null>(null);
  const [loading, setLoading] = useState(true);
  const [downloading, setDownloading] = useState(false);

  useEffect(() => {
    let cancelled = false;
    const clientOs = detectClientOs();
    (async () => {
      try {
        await fetch(
          `${RELEASE_BASE}/track?platform=${platform}&event=page_view&clientOs=${encodeURIComponent(clientOs)}`,
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
        const found = (Object.keys(PLATFORM_META) as PlatformKey[]).filter((p) => data?.latest?.[p]);
        if (found.length) {
          setAvailablePlatforms(found);
          const byPlatform: Partial<Record<PlatformKey, ReleaseInfo>> = {};
          found.forEach((p) => { byPlatform[p] = data.latest[p]; });
          setReleases(byPlatform);
          if (!found.includes(platform)) setPlatform(found[0]);
        }
        if (data?.latest?.["linux-deb"]) setLinuxDeb(data.latest["linux-deb"]);
        // linux-arm64 / linux-flatpak are secondary Linux package options
        // alongside the primary AppImage (same pattern as .deb).
        if (data?.latest?.["linux-arm64"]) setLinuxArm64(data.latest["linux-arm64"]);
        if (data?.latest?.["linux-flatpak"]) setLinuxFlatpak(data.latest["linux-flatpak"]);
        if (data?.systemRequirements) setRequirements(data.systemRequirements);
      } catch {
        if (!cancelled) {
          setReleases({
            "windows-x64": {
              version: "1.1.0",
              filename: "OntoCode Studio-Setup.exe",
              size: 0,
              releaseNotes: "",
              publishedAt: "",
              downloadUrl: `${RELEASE_BASE}/windows-x64`,
            },
          });
        }
      } finally {
        if (!cancelled) setLoading(false);
      }
    })();
    return () => { cancelled = true; };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  const release = releases[platform] ?? null;

  const openExternal = (url: string) => {
    // VS Code webviews are sandboxed iframes — a programmatic window.location.href
    // navigation to an external URL is silently blocked (unlike a real <a> click,
    // which the webview host intercepts). Route through the extension host instead.
    // Note: window.vscode alone isn't enough to detect this — the plain-browser
    // bridge (vscodeBridge.ts) installs a same-named shim so browser code keeps
    // working, so isRealVSCode() is needed to tell the two apart.
    if (isRealVSCode()) {
      window.vscode!.postMessage({ type: "openExternalUrl", url });
    } else {
      window.location.href = url;
    }
  };

  const handleDownload = () => {
    setDownloading(true);
    const clientOs = detectClientOs();
    openExternal(`${RELEASE_BASE}/${platform}?clientOs=${encodeURIComponent(clientOs)}`);
    setTimeout(() => setDownloading(false), 3000);
  };

  const handleDebDownload = () => {
    openExternal(`${RELEASE_BASE}/linux-deb?clientOs=${encodeURIComponent(detectClientOs())}`);
  };

  const handleArm64Download = () => {
    openExternal(`${RELEASE_BASE}/linux-arm64?clientOs=${encodeURIComponent(detectClientOs())}`);
  };

  const handleFlatpakDownload = () => {
    openExternal(`${RELEASE_BASE}/linux-flatpak?clientOs=${encodeURIComponent(detectClientOs())}`);
  };

  const versionLabel = release?.version || "…";
  const PlatformIcon = PLATFORM_META[platform].icon;

  return (
    <div className="relative min-h-screen text-white">
      {/* Fixed, viewport-pinned background layer — NOT the box-height-dependent
          background this div used to carry directly. This page must always render
          dark/violet regardless of the app's light/dark theme (it's a marketing/
          download page, not themed app chrome), and a background tied to this div's
          own content height can be outrun by taller content inside html/body/#root's
          independently-scrolling containers (see index.css), letting the theme's
          light body background show through past wherever this div's box ended —
          exactly what made the feature checklist unreadable in light mode. A fixed
          layer covers the full viewport unconditionally, at any scroll position. */}
      <div className="fixed inset-0 -z-10 bg-gradient-to-br from-slate-900 via-purple-950 to-slate-900" />
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
            <Cpu size={14} /> Desktop Edition — Free Download ({PLATFORM_META[platform].label})
          </div>
          <h1 className="text-4xl font-bold mb-4 bg-gradient-to-r from-white to-white/70 bg-clip-text text-transparent">
            OntoCode Studio
          </h1>
          <p className="text-lg text-white/60 max-w-2xl mx-auto">
            Full OWL ontology editor — works completely offline. No account required.
            Import, edit, reason and query ontologies up to millions of triples.
          </p>
          <p className="text-sm text-white/40 mt-3">
            macOS builds are coming soon. Windows and Linux are available now.
          </p>

          {availablePlatforms.length > 1 && (
            <div className="inline-flex mt-6 bg-white/5 border border-white/10 rounded-full p-1">
              {availablePlatforms.map((p) => (
                <button
                  key={p}
                  type="button"
                  onClick={() => setPlatform(p)}
                  className={`flex items-center gap-1.5 px-4 py-1.5 rounded-full text-sm transition-colors ${
                    platform === p ? "bg-purple-600 text-white" : "text-white/50 hover:text-white/80"
                  }`}
                >
                  {PLATFORM_META[p].icon === Monitor ? <Monitor size={14} /> : <Terminal size={14} />}
                  {PLATFORM_META[p].label}
                </button>
              ))}
            </div>
          )}
        </div>

        <div className="bg-white/5 border border-white/10 rounded-2xl p-8 mb-8 backdrop-blur-sm">
          <div className="flex items-start justify-between mb-6">
            <div>
              <h2 className="text-xl font-semibold mb-1 flex items-center gap-2">
                <PlatformIcon size={22} className="text-blue-400" />
                {PLATFORM_META[platform].label}
              </h2>
              <p className="text-sm text-white/50">
                {PLATFORM_META[platform].os}
              </p>
            </div>
            <PlatformIcon size={32} className="text-white/30" />
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
                  {loading ? "Loading…" : `Download for ${PLATFORM_META[platform].label} (v${versionLabel})`}
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

          {platform === "linux-x64" && linuxDeb && (
            <button
              type="button"
              onClick={handleDebDownload}
              className="w-full flex items-center justify-center gap-2 p-2.5 mt-3 rounded-xl text-xs text-white/50 hover:text-white/80 border border-white/10 hover:border-white/20 transition-colors"
            >
              <Download size={12} />
              Prefer a .deb package? Download for Debian/Ubuntu (v{linuxDeb.version})
            </button>
          )}

          {platform === "linux-x64" && linuxFlatpak && (
            <button
              type="button"
              onClick={handleFlatpakDownload}
              className="w-full flex items-center justify-center gap-2 p-2.5 mt-3 rounded-xl text-xs text-white/50 hover:text-white/80 border border-white/10 hover:border-white/20 transition-colors"
            >
              <Download size={12} />
              Prefer Flatpak? Download the Flatpak package (v{linuxFlatpak.version})
            </button>
          )}

          {platform === "linux-x64" && linuxArm64 && (
            <button
              type="button"
              onClick={handleArm64Download}
              className="w-full flex items-center justify-center gap-2 p-2.5 mt-3 rounded-xl text-xs text-white/50 hover:text-white/80 border border-white/10 hover:border-white/20 transition-colors"
            >
              <Download size={12} />
              On ARM64 hardware? Download the ARM64 AppImage (v{linuxArm64.version})
            </button>
          )}

          <p className="text-xs text-white/30 mt-4 text-center">
            No sign-up required · Free to use ·{" "}
            {platform === "windows-x64" ? "Uninstall via Windows Settings → Apps" : "Uninstall by removing the AppImage / package"}
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

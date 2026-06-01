import React, { useEffect, useState } from "react";
import { Download, Monitor, Apple, Terminal, CheckCircle, ArrowLeft, ExternalLink, Cpu } from "lucide-react";

const RELEASE_BASE = "https://github.com/kkpranesh/ontocode/releases/latest/download";
const RELEASES_PAGE = "https://github.com/kkpranesh/ontocode/releases";

const OS_OPTIONS = [
  {
    id: "windows",
    label: "Windows",
    icon: Monitor,
    color: "#0078d4",
    bg: "#eff6ff",
    versions: [
      { arch: "x64", label: "Windows 64-bit (Installer)", file: "OntoCode-Setup-1.0.0-x64.exe", primary: true },
      { arch: "arm64", label: "Windows ARM64 (Installer)", file: "OntoCode-Setup-1.0.0-arm64.exe", primary: false },
    ],
    requirements: "Windows 10 or later",
  },
  {
    id: "mac",
    label: "macOS",
    icon: Apple,
    color: "#555",
    bg: "#f5f5f7",
    versions: [
      { arch: "arm64", label: "macOS Apple Silicon (M1/M2/M3)", file: "OntoCode-1.0.0-arm64.dmg", primary: true },
      { arch: "x64", label: "macOS Intel", file: "OntoCode-1.0.0-x64.dmg", primary: false },
    ],
    requirements: "macOS 12 (Monterey) or later",
  },
  {
    id: "linux",
    label: "Linux",
    icon: Terminal,
    color: "#e95420",
    bg: "#fff5f0",
    versions: [
      { arch: "x64", label: "Linux AppImage (x86_64)", file: "OntoCode-1.0.0-x86_64.AppImage", primary: true },
      { arch: "x64-deb", label: "Debian / Ubuntu (.deb)", file: "ontocode_1.0.0_amd64.deb", primary: false },
    ],
    requirements: "Ubuntu 20.04+ or compatible",
  },
];

const FEATURES = [
  "Full offline editing — no internet required",
  "84,000+ class ontologies (GO-plus tested)",
  "Peer-to-peer sync via Syncthing — no cloud account needed",
  "OWLAPI-powered instant class navigation",
  "All reasoners: HermiT, Openllet, ELK",
  "SPARQL query interface with Fuseki",
  "Plugin marketplace support",
  "Free for personal use",
];

function detectOS(): string {
  const ua = navigator.userAgent.toLowerCase();
  if (ua.includes("mac")) return "mac";
  if (ua.includes("win")) return "windows";
  return "linux";
}

interface Props {
  onBack?: () => void;
}

export const DesktopDownloadPage: React.FC<Props> = ({ onBack }) => {
  const [detectedOS, setDetectedOS] = useState<string>("windows");
  const [selectedOS, setSelectedOS] = useState<string | null>(null);

  useEffect(() => {
    const os = detectOS();
    setDetectedOS(os);
    setSelectedOS(os);
  }, []);

  const activeOS = selectedOS || detectedOS;
  const activeOption = OS_OPTIONS.find((o) => o.id === activeOS) || OS_OPTIONS[0];

  return (
    <div className="min-h-screen bg-gradient-to-br from-slate-900 via-purple-950 to-slate-900 text-white">
      {/* Header */}
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
            <span className="text-xs text-white/40">v1.0.0</span>
            <a
              href={RELEASES_PAGE}
              target="_blank"
              rel="noopener noreferrer"
              className="flex items-center gap-1 text-xs text-purple-300 hover:text-purple-200 transition-colors"
            >
              Release notes <ExternalLink size={11} />
            </a>
          </div>
        </div>
      </div>

      <div className="max-w-5xl mx-auto px-6 py-12">
        {/* Hero */}
        <div className="text-center mb-12">
          <div className="inline-flex items-center gap-2 bg-purple-500/20 border border-purple-500/30 rounded-full px-4 py-1.5 text-sm text-purple-300 mb-6">
            <Cpu size={14} /> Desktop Edition — Free Download
          </div>
          <h1 className="text-4xl font-bold mb-4 bg-gradient-to-r from-white to-white/70 bg-clip-text text-transparent">
            OntoCode Desktop
          </h1>
          <p className="text-lg text-white/60 max-w-2xl mx-auto">
            Full OWL ontology editor — works completely offline. No account required.
            Import, edit, reason and query ontologies up to millions of triples.
          </p>
        </div>

        {/* OS Selector */}
        <div className="flex justify-center gap-3 mb-8">
          {OS_OPTIONS.map((os) => {
            const Icon = os.icon;
            const isActive = activeOS === os.id;
            return (
              <button
                key={os.id}
                onClick={() => setSelectedOS(os.id)}
                className={`flex items-center gap-2 px-5 py-2.5 rounded-xl text-sm font-medium transition-all border ${
                  isActive
                    ? "bg-purple-600 border-purple-500 text-white shadow-lg shadow-purple-900/50"
                    : "bg-white/5 border-white/10 text-white/60 hover:bg-white/10 hover:text-white"
                }`}
              >
                <Icon size={16} />
                {os.label}
                {os.id === detectedOS && !isActive && (
                  <span className="text-[10px] text-purple-400 font-normal">detected</span>
                )}
              </button>
            );
          })}
        </div>

        {/* Download Card */}
        <div className="bg-white/5 border border-white/10 rounded-2xl p-8 mb-8 backdrop-blur-sm">
          <div className="flex items-start justify-between mb-6">
            <div>
              <h2 className="text-xl font-semibold mb-1">{activeOption.label}</h2>
              <p className="text-sm text-white/50">{activeOption.requirements}</p>
            </div>
            {os => <activeOption.icon size={32} className="text-white/30" />}
          </div>

          <div className="space-y-3">
            {activeOption.versions.map((v) => (
              <a
                key={v.arch}
                href={`${RELEASE_BASE}/${v.file}`}
                className={`flex items-center justify-between p-4 rounded-xl transition-all group ${
                  v.primary
                    ? "bg-purple-600 hover:bg-purple-500 border border-purple-500"
                    : "bg-white/5 hover:bg-white/10 border border-white/10"
                }`}
              >
                <div className="flex items-center gap-3">
                  <Download size={18} className={v.primary ? "text-white" : "text-white/50 group-hover:text-white"} />
                  <div>
                    <div className={`text-sm font-medium ${v.primary ? "text-white" : "text-white/70 group-hover:text-white"}`}>
                      {v.label}
                    </div>
                    <div className="text-xs text-white/40">{v.file}</div>
                  </div>
                </div>
                {v.primary && (
                  <span className="text-xs bg-white/20 px-2 py-0.5 rounded-full text-white/80">Recommended</span>
                )}
              </a>
            ))}
          </div>

          <p className="text-xs text-white/30 mt-4 text-center">
            No sign-up required · Free to use · Java 17 bundled
          </p>
        </div>

        {/* Features grid */}
        <div className="grid grid-cols-2 md:grid-cols-4 gap-4 mb-12">
          {FEATURES.map((f) => (
            <div key={f} className="flex items-start gap-2 p-3 bg-white/5 rounded-lg border border-white/5">
              <CheckCircle size={14} className="text-purple-400 mt-0.5 flex-shrink-0" />
              <span className="text-xs text-white/70 leading-relaxed">{f}</span>
            </div>
          ))}
        </div>

        {/* All downloads */}
        <div className="text-center">
          <p className="text-sm text-white/40 mb-3">Looking for a different version?</p>
          <a
            href={RELEASES_PAGE}
            target="_blank"
            rel="noopener noreferrer"
            className="inline-flex items-center gap-2 text-sm text-purple-300 hover:text-purple-200 border border-purple-500/30 hover:border-purple-400/50 px-4 py-2 rounded-lg transition-all"
          >
            View all releases on GitHub <ExternalLink size={14} />
          </a>
        </div>
      </div>
    </div>
  );
};

export default DesktopDownloadPage;

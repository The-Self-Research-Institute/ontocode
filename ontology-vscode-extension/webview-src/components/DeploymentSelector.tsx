import React, { useState } from 'react';
import { Cloud, Server, Check, Bug } from 'lucide-react';
import ReportIssueModal from './ReportIssueModal';

interface DeploymentSelectorProps {
    onSelect: (deploymentType: 'self-hosted' | 'cloud') => void;
}

const DeploymentSelector: React.FC<DeploymentSelectorProps> = ({ onSelect }) => {
    const [selected, setSelected] = useState<'self-hosted' | 'cloud' | null>(null);
    const [isConfirming, setIsConfirming] = useState(false);
    const [isReportIssueModalOpen, setIsReportIssueModalOpen] = useState(false);

    const handleSelect = (type: 'self-hosted' | 'cloud') => {
        setSelected(type);
    };

    const handleConfirm = () => {
        if (selected) {
            setIsConfirming(true);
            setTimeout(() => {
                onSelect(selected);
            }, 300);
        }
    };

    return (
      <div className="min-h-screen min-h-[100dvh] bg-gradient-to-br from-slate-900 via-purple-900 to-slate-900 flex items-center justify-center p-4 pb-24 sm:pb-4 overflow-y-auto">
        <div className="absolute inset-0 overflow-hidden pointer-events-none">
          <div className="absolute -top-40 -right-40 w-80 h-80 bg-purple-500 rounded-full mix-blend-multiply filter blur-xl opacity-20 animate-pulse"></div>
          <div className="absolute -bottom-40 -left-40 w-80 h-80 bg-indigo-500 rounded-full mix-blend-multiply filter blur-xl opacity-20 animate-pulse delay-1000"></div>
        </div>

        <div className="relative bg-white/10 backdrop-blur-xl border border-white/20 rounded-2xl shadow-2xl p-5 sm:p-8 w-full max-w-4xl mx-auto mb-16 sm:mb-0">
          <div className="text-center mb-6 sm:mb-8">
            <h2 className="text-2xl sm:text-3xl font-bold text-white mb-2">Choose Your Deployment Environment</h2>
            <p className="text-gray-300">Select the environment you want to work with</p>
          </div>

          <div className="grid grid-cols-1 md:grid-cols-2 gap-4 sm:gap-6 mb-6 sm:mb-8">
            {}
            <button
              onClick={() => handleSelect("self-hosted")}
              className={`relative group p-6 rounded-xl transition-all duration-300 ${
                selected === "self-hosted"
                  ? "bg-purple-500/30 border-2 border-purple-400 shadow-lg shadow-purple-500/30"
                  : "bg-white/5 border-2 border-white/10 hover:border-white/30 hover:bg-white/10"
              }`}
            >
              <div className="flex flex-col items-center text-center space-y-4">
                <div
                  className={`p-4 rounded-full transition-all ${
                    selected === "self-hosted" ? "bg-purple-500/30" : "bg-white/10 group-hover:bg-white/20"
                  }`}
                >
                  <Server size={48} className={selected === "self-hosted" ? "text-purple-300" : "text-gray-300"} />
                </div>

                <div>
                  <h3 className="text-xl font-semibold text-white mb-2">Self-Hosted</h3>
                  <p className="text-sm text-gray-300 mb-3">Connect to your local OntoCode Studio instance</p>
                  <div className="text-xs text-gray-400 space-y-1">
                    <div className="flex items-center justify-center gap-1">
                      <Check size={14} className="text-green-400" />
                      <span>Full data control</span>
                    </div>
                    <div className="flex items-center justify-center gap-1">
                      <Check size={14} className="text-green-400" />
                      <span>No internet required</span>
                    </div>
                    <div className="flex items-center justify-center gap-1">
                      <Check size={14} className="text-green-400" />
                      <span>localhost:80</span>
                    </div>
                  </div>
                </div>

                {selected === "self-hosted" && (
                  <div className="absolute top-3 right-3">
                    <div className="bg-purple-500 rounded-full p-1">
                      <Check size={16} className="text-white" />
                    </div>
                  </div>
                )}
              </div>
            </button>

            {}
            <button
              onClick={() => handleSelect("cloud")}
              className={`relative group p-6 rounded-xl transition-all duration-300 ${
                selected === "cloud"
                  ? "bg-blue-500/30 border-2 border-blue-400 shadow-lg shadow-blue-500/30"
                  : "bg-white/5 border-2 border-white/10 hover:border-white/30 hover:bg-white/10"
              }`}
            >
              <div className="flex flex-col items-center text-center space-y-4">
                <div
                  className={`p-4 rounded-full transition-all ${
                    selected === "cloud" ? "bg-blue-500/30" : "bg-white/10 group-hover:bg-white/20"
                  }`}
                >
                  <Cloud size={48} className={selected === "cloud" ? "text-blue-300" : "text-gray-300"} />
                </div>

                <div>
                  <h3 className="text-xl font-semibold text-white mb-2">Cloud</h3>
                  <p className="text-sm text-gray-300 mb-3">Connect to hosted OntoCode Studio on AWS</p>
                  <div className="text-xs text-gray-400 space-y-1">
                    <div className="flex items-center justify-center gap-1">
                      <Check size={14} className="text-green-400" />
                      <span>Access anywhere</span>
                    </div>
                    <div className="flex items-center justify-center gap-1">
                      <Check size={14} className="text-green-400" />
                      <span>High availability</span>
                    </div>
                    <div className="flex items-center justify-center gap-1">
                      <Check size={14} className="text-green-400" />
                      <span>AWS EC2</span>
                    </div>
                  </div>
                </div>

                {selected === "cloud" && (
                  <div className="absolute top-3 right-3">
                    <div className="bg-blue-500 rounded-full p-1">
                      <Check size={16} className="text-white" />
                    </div>
                  </div>
                )}
              </div>
            </button>
          </div>

          <button
            onClick={handleConfirm}
            disabled={!selected || isConfirming}
            className={`w-full py-3 px-6 rounded-lg font-semibold text-white transition-all duration-300 ${
              selected && !isConfirming
                ? "bg-gradient-to-r from-purple-600 to-blue-600 hover:from-purple-700 hover:to-blue-700 shadow-lg"
                : "bg-gray-600/50 cursor-not-allowed"
            }`}
          >
            {isConfirming
              ? "Connecting..."
              : selected
                ? `Continue with ${selected === "self-hosted" ? "Self-Hosted" : "Cloud"}`
                : "Select a deployment option"}
          </button>

          <p className="text-xs text-gray-400 text-center mt-4">You can change this later in settings</p>
        </div>

        {}
        <button
          onClick={() => setIsReportIssueModalOpen(true)}
          className="fixed bottom-4 right-4 left-4 sm:left-auto inline-flex items-center justify-center gap-1.5 px-3 py-2 sm:py-1.5 rounded-lg bg-white/10 hover:bg-white/20 border border-white/20 text-xs font-medium text-white transition-colors backdrop-blur-sm z-50 max-w-[10rem] sm:max-w-none ml-auto"
          title="Report an issue"
        >
          <Bug size={14} />
          <span className="sm:hidden">Issue</span>
          <span className="hidden sm:inline">Report Issue</span>
        </button>

        {isReportIssueModalOpen && (
          <ReportIssueModal onClose={() => setIsReportIssueModalOpen(false)} />
        )}
      </div>
    );
};

export default DeploymentSelector;

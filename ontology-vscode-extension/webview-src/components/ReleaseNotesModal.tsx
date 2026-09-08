import React, { useEffect, useState } from "react";
import { X, Sparkles, Calendar, PlayCircle } from "lucide-react";
import { RELEASE_NOTES } from "../utils/releaseNotes";
import { isDesktop } from "../utils/desktop";

interface ReleaseNotesModalProps {
  isOpen: boolean;
  onClose: () => void;
}

const normalizeVersion = (v: string) => v.replace(/^v/i, "").trim();

export const ReleaseNotesModal: React.FC<ReleaseNotesModalProps> = ({ isOpen, onClose }) => {
  const [currentVersion, setCurrentVersion] = useState("");

  useEffect(() => {
    if (!isOpen) return;
    import("../utils/appVersion")
      .then(({ getAppVersion }) => getAppVersion())
      .then((v) => setCurrentVersion(normalizeVersion(v || "")))
      .catch(() => setCurrentVersion(""));
  }, [isOpen]);

  if (!isOpen) return null;

  return (
    <div className="fixed inset-0 bg-black bg-opacity-60 backdrop-blur-sm flex items-center justify-center z-50 p-4">
      <div className="bg-white rounded-xl shadow-2xl max-w-2xl w-full max-h-[90vh] overflow-hidden flex flex-col">
        {/* Header */}
        <div className="bg-gradient-to-r from-purple-600 to-indigo-600 px-6 py-5 flex items-center justify-between">
          <div className="flex items-center gap-3">
            <div className="bg-white bg-opacity-20 p-2 rounded-lg">
              <Sparkles className="text-white" size={26} />
            </div>
            <div>
              <h2 className="text-2xl font-bold text-white">Release Notes</h2>
              <p className="text-purple-100 text-sm mt-1">What&apos;s new in OntoCode Studio</p>
            </div>
          </div>
          <button
            onClick={onClose}
            className="text-white hover:bg-white hover:bg-opacity-20 p-2 rounded-lg transition-colors"
            aria-label="Close release notes"
          >
            <X size={24} />
          </button>
        </div>

        {/* Content */}
        <div className="flex-1 min-h-0 overflow-y-auto px-6 py-6 space-y-8">
          {RELEASE_NOTES.length === 0 && (
            <p className="text-sm text-gray-500 text-center py-8">No release notes available.</p>
          )}

          {RELEASE_NOTES.map((note) => {
            const isCurrent =
              !!currentVersion && normalizeVersion(note.version) === currentVersion;
            return (
              <div key={note.version} className="space-y-3">
                <div className="flex flex-wrap items-center gap-3">
                  <span className="text-lg font-bold text-gray-900">
                    v{!isDesktop() && note.webVersion ? note.webVersion : note.version}
                  </span>
                  {isCurrent && (
                    <span className="inline-flex items-center px-2.5 py-0.5 rounded-full text-xs font-semibold bg-green-100 text-green-800 border border-green-300">
                      Current
                    </span>
                  )}
                  <span className="inline-flex items-center gap-1 text-xs text-gray-500">
                    <Calendar size={13} />
                    {note.date}
                  </span>
                </div>

                {note.title && (
                  <p className="text-sm font-semibold text-purple-700">{note.title}</p>
                )}

                <ul className="space-y-1.5">
                  {note.highlights.map((h, i) => (
                    <li key={i} className="flex items-start gap-2 text-sm text-gray-700">
                      <span className="mt-1.5 h-1.5 w-1.5 flex-shrink-0 rounded-full bg-purple-400" />
                      <span>{h}</span>
                    </li>
                  ))}
                </ul>

                {note.videoUrl && note.videoUrl.trim() !== "" && (
                  <div className="mt-2 rounded-lg overflow-hidden border border-gray-200 bg-black">
                    <video
                      src={note.videoUrl}
                      poster={note.videoPoster}
                      controls
                      preload="metadata"
                      className="w-full max-h-72 bg-black"
                    >
                      <span className="text-white text-sm p-4 flex items-center gap-2">
                        <PlayCircle size={16} /> Your browser does not support embedded video.
                      </span>
                    </video>
                  </div>
                )}

                <div className="border-t border-gray-100 pt-2" />
              </div>
            );
          })}
        </div>

        {/* Footer */}
        <div className="border-t-2 border-gray-200 bg-gray-50 px-6 py-4 flex justify-end">
          <button
            onClick={onClose}
            className="px-6 py-2.5 text-sm font-semibold text-white bg-gradient-to-r from-purple-600 to-indigo-600 rounded-lg hover:from-purple-700 hover:to-indigo-700 transition-all shadow-lg"
          >
            Close
          </button>
        </div>
      </div>
    </div>
  );
};

export default ReleaseNotesModal;

import React from "react";
import { X, BookOpen } from "lucide-react";
import { OPEN_SOURCE_LIBRARIES } from "../utils/openSourceLibraries";

interface OpenSourceLicensesModalProps {
  isOpen: boolean;
  onClose: () => void;
}

export const OpenSourceLicensesModal: React.FC<OpenSourceLicensesModalProps> = ({
  isOpen,
  onClose,
}) => {
  if (!isOpen) return null;

  return (
    <div className="fixed inset-0 z-[10000] flex items-center justify-center bg-black/50 p-4">
      <div
        className="bg-white rounded-2xl shadow-2xl w-full max-w-2xl max-h-[85vh] flex flex-col"
        role="dialog"
        aria-labelledby="opensource-title"
      >
        <div className="flex items-center justify-between px-6 py-4 border-b border-gray-200">
          <div className="flex items-center gap-3">
            <div className="w-9 h-9 bg-indigo-100 rounded-lg flex items-center justify-center">
              <BookOpen className="w-5 h-5 text-indigo-600" />
            </div>
            <div>
              <h2 id="opensource-title" className="text-lg font-semibold text-gray-900">
                Open Source Libraries
              </h2>
              <p className="text-xs text-gray-500">
                OntoCode is built with the following open-source software
              </p>
            </div>
          </div>
          <button
            type="button"
            onClick={onClose}
            className="text-gray-400 hover:text-gray-600 p-1 rounded-lg hover:bg-gray-100"
            aria-label="Close"
          >
            <X size={20} />
          </button>
        </div>
        <div className="overflow-y-auto flex-1 px-6 py-4 space-y-4">
          {}
          <div className="bg-purple-50 border border-purple-200 rounded-lg px-4 py-3 text-sm text-purple-900">
            <span className="font-semibold">OntoCode VSCode Extension</span> is itself open-source software,
            released under the{" "}
            <span className="font-medium">GNU Affero General Public License v3 (AGPL-3.0-or-later)</span>.
            The source code will be published at{" "}
            <span className="font-medium">github.com/The-Self-Research-Institute/ontocode</span>{" "}
            when beta registration opens.
          </div>

          <table className="w-full text-sm">
            <thead>
              <tr className="text-left text-xs uppercase tracking-wide text-gray-500 border-b">
                <th className="pb-2 pr-4 font-medium">Library</th>
                <th className="pb-2 pr-4 font-medium">License</th>
                <th className="pb-2 font-medium">Used in</th>
              </tr>
            </thead>
            <tbody>
              {OPEN_SOURCE_LIBRARIES.map((lib) => (
                <tr key={lib.name} className="border-b border-gray-50">
                  <td className="py-2.5 pr-4 font-medium text-gray-900">
                    {lib.url ? (
                      <a
                        href={lib.url}
                        target="_blank"
                        rel="noopener noreferrer"
                        className="text-indigo-600 hover:text-indigo-800 hover:underline"
                      >
                        {lib.name}
                      </a>
                    ) : (
                      lib.name
                    )}
                  </td>
                  <td className="py-2.5 pr-4 text-gray-600">{lib.license}</td>
                  <td className="py-2.5 text-gray-500">{lib.usedIn}</td>
                </tr>
              ))}
            </tbody>
          </table>
          <p className="text-xs text-gray-400">
            Full dependency details will be available in{" "}
            <span className="font-medium text-gray-500">OPEN_SOURCE_LIBRARIES.md</span>{" "}
            in the repository when it is published.
          </p>
        </div>
      </div>
    </div>
  );
};

export default OpenSourceLicensesModal;

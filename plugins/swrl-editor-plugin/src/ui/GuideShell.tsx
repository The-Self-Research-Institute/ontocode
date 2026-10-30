import React, { useEffect } from 'react';
import { BookOpen, X } from 'lucide-react';

interface GuideShellProps {
  isOpen: boolean;
  onClose: () => void;
  title: string;
  testId?: string;
  children: React.ReactNode;
}

export const GuideSectionLabel: React.FC<{ children: React.ReactNode }> = ({ children }) => (
  <h4 className="text-xs font-semibold text-gray-500 uppercase tracking-wide mb-2">{children}</h4>
);

export const GuideExample: React.FC<{ children: React.ReactNode }> = ({ children }) => (
  <div className="font-mono text-xs bg-gray-50 border border-gray-200 rounded-md px-2.5 py-1.5 text-gray-800 break-words">{children}</div>
);

export const GuideCard: React.FC<{ title: string; id?: string; example?: React.ReactNode; children: React.ReactNode }> = ({ title, id, example, children }) => (
  <div id={id} className="border border-gray-200 rounded-lg p-3">
    <h5 className="text-sm font-semibold text-gray-800 mb-1.5">{title}</h5>
    <p className="text-sm text-gray-700 mb-2">{children}</p>
    {example && <GuideExample>{example}</GuideExample>}
  </div>
);

export const GuideShell: React.FC<GuideShellProps> = ({ isOpen, onClose, title, testId, children }) => {
  useEffect(() => {
    if (!isOpen) return;
    const onKey = (e: KeyboardEvent) => {
      if (e.key === 'Escape') onClose();
    };
    document.addEventListener('keydown', onKey);
    return () => document.removeEventListener('keydown', onKey);
  }, [isOpen, onClose]);

  if (!isOpen) return null;

  return (
    <div
      className="fixed inset-0 bg-black bg-opacity-50 flex items-center justify-center z-50"
      data-testid={testId}
      onMouseDown={e => {
        if (e.target === e.currentTarget && e.button === 0) onClose();
      }}
    >
      <div className="bg-white rounded-lg shadow-xl max-w-xl w-full mx-4 flex flex-col max-h-[85vh]" role="dialog" aria-modal="true" aria-label={title} onClick={e => e.stopPropagation()}>
        <div className="p-4 border-b border-gray-200 flex items-center justify-between flex-shrink-0">
          <div className="flex items-center gap-2">
            <BookOpen size={20} className="text-purple-600" />
            <h3 className="text-lg font-semibold text-black">{title}</h3>
          </div>
          <button onClick={onClose} className="p-1 rounded hover:bg-gray-100 text-gray-500" title="Close" aria-label="Close the guide" data-testid={testId ? `${testId}-close` : undefined} autoFocus>
            <X size={20} />
          </button>
        </div>
        <div className="flex-1 overflow-y-auto p-4 space-y-5">{children}</div>
      </div>
    </div>
  );
};

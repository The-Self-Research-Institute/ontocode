import React, { useEffect, useRef, useState } from 'react';

export type DraftCopyPhase = 'idle' | 'import-blocked' | 'copying' | 'ready' | 'failed';

interface DraftCopyModalProps {
  phase: DraftCopyPhase;
  tripleCount?: number;
  onCancel: () => void;
}

function estimateCopySeconds(tripleCount: number): string {
  if (tripleCount < 5_000) return 'a few seconds';
  if (tripleCount < 50_000) return 'about 10–30 seconds';
  if (tripleCount < 200_000) return 'about a minute';
  return 'a few minutes';
}

const DraftCopyModal: React.FC<DraftCopyModalProps> = ({ phase, tripleCount, onCancel }) => {
  const [dots, setDots] = useState('');
  const intervalRef = useRef<ReturnType<typeof setInterval> | null>(null);

  useEffect(() => {
    if (phase === 'copying') {
      intervalRef.current = setInterval(() => {
        setDots(d => (d.length >= 3 ? '' : d + '.'));
      }, 500);
    } else {
      if (intervalRef.current) clearInterval(intervalRef.current);
      setDots('');
    }
    return () => { if (intervalRef.current) clearInterval(intervalRef.current); };
  }, [phase]);

  if (phase === 'idle') return null;

  const estimate = tripleCount !== undefined ? estimateCopySeconds(tripleCount) : null;

  return (
    <div className="fixed inset-0 bg-black bg-opacity-50 flex items-center justify-center z-50">
      <div className="bg-white rounded-lg shadow-xl p-6 max-w-sm w-full mx-4">
        {phase === 'import-blocked' && (
          <>
            <div className="flex items-center gap-3 mb-4">
              <span className="text-2xl">⚠️</span>
              <h3 className="text-base font-semibold text-gray-900">Import In Progress</h3>
            </div>
            <p className="text-sm text-gray-600 mb-5">
              A file import is currently running for this project. Please wait until it finishes before switching to Draft Mode — switching now would copy an incomplete ontology.
            </p>
            <div className="flex justify-end">
              <button
                onClick={onCancel}
                className="px-4 py-2 text-sm font-medium bg-gray-100 hover:bg-gray-200 text-gray-700 rounded-md"
              >
                OK, I'll wait
              </button>
            </div>
          </>
        )}

        {phase === 'copying' && (
          <>
            <div className="flex items-center gap-3 mb-4">
              <div className="w-5 h-5 border-2 border-blue-500 border-t-transparent rounded-full animate-spin flex-shrink-0" />
              <h3 className="text-base font-semibold text-gray-900">Creating your draft{dots}</h3>
            </div>
            <p className="text-sm text-gray-600 mb-2">
              Copying the shared ontology into your private workspace.
              {estimate && <> This usually takes <strong>{estimate}</strong> for your ontology size.</>}
            </p>
            {tripleCount !== undefined && (
              <p className="text-xs text-gray-400 mb-4">{tripleCount.toLocaleString()} triples to copy</p>
            )}
            <div className="w-full bg-gray-100 rounded-full h-1.5">
              <div className="bg-blue-500 h-1.5 rounded-full animate-pulse w-2/3" />
            </div>
          </>
        )}

        {phase === 'ready' && (
          <>
            <div className="flex items-center gap-3 mb-4">
              <span className="text-2xl">✅</span>
              <h3 className="text-base font-semibold text-gray-900">Draft Ready</h3>
            </div>
            <p className="text-sm text-gray-600">
              Your private draft is ready. Changes you make here won't affect the shared ontology until you publish.
            </p>
          </>
        )}

        {phase === 'failed' && (
          <>
            <div className="flex items-center gap-3 mb-4">
              <span className="text-2xl">❌</span>
              <h3 className="text-base font-semibold text-gray-900">Draft Copy Failed</h3>
            </div>
            <p className="text-sm text-gray-600 mb-5">
              Something went wrong while creating your draft. You've been kept in Public mode. Please try again.
            </p>
            <div className="flex justify-end">
              <button
                onClick={onCancel}
                className="px-4 py-2 text-sm font-medium bg-gray-100 hover:bg-gray-200 text-gray-700 rounded-md"
              >
                Dismiss
              </button>
            </div>
          </>
        )}
      </div>
    </div>
  );
};

export default DraftCopyModal;

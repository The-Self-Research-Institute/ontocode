import React, { useEffect, useRef, useState } from 'react';
import { rt } from './resultsTheme';
import apiClient from './apiClient';
import type { InferredAxiom } from './types';
import { EXPLAIN_TEXT, describeFailure, describeJob, toExplainFact } from './explainText';
import type { ExplainDisplay } from './explainText';

type Phase = 'idle' | 'running' | 'done' | 'error' | 'unavailable';

export const ExplainDetail: React.FC<{ projectId: string; axiom: InferredAxiom; draftMode?: boolean }> = ({ projectId, axiom, draftMode = false }) => {
  const [phase, setPhase] = useState<Phase>('idle');
  const [display, setDisplay] = useState<ExplainDisplay | null>(null);
  const [failure, setFailure] = useState('');
  const controller = useRef<AbortController | null>(null);
  const alive = useRef(true);

  useEffect(() => {
    alive.current = true;
    return () => {
      alive.current = false;
      controller.current?.abort();
    };
  }, []);

  useEffect(() => {
    controller.current?.abort();
    controller.current = null;
    setPhase('idle');
    setDisplay(null);
    setFailure('');
  }, [axiom, projectId, draftMode]);

  const fact = toExplainFact(axiom);
  if (!fact) return null;

  const start = async () => {
    const ctl = new AbortController();
    controller.current = ctl;
    setPhase('running');
    setDisplay(null);
    setFailure('');
    try {
      const job = await apiClient.explainInference(projectId, fact, { draft: draftMode, signal: ctl.signal });
      if (!alive.current || ctl.signal.aborted) return;
      setDisplay(describeJob(job));
      setPhase('done');
    } catch (e) {
      if (!alive.current || ctl.signal.aborted || (e as Error)?.name === 'AbortError') return;
      const f = describeFailure(e);
      setFailure(f.text);
      setPhase(f.kind === 'unavailable' ? 'unavailable' : 'error');
    }
  };

  const cancel = () => {
    controller.current?.abort();
    controller.current = null;
    setDisplay({ kind: 'info', headline: EXPLAIN_TEXT.cancelled, items: [] });
    setPhase('idle');
  };

  return (
    <div className="space-y-2">
      <div aria-live="polite" className="space-y-1">
        {phase === 'running' && <div className="text-xs" style={rt.label}>{EXPLAIN_TEXT.working}</div>}
        {phase === 'error' && <div className="text-xs rounded p-2" style={rt.bannerError}>{failure}</div>}
        {display && phase !== 'running' && (
          <div className="text-xs space-y-1" style={rt.value}>
            <div className={display.kind === 'explained' ? 'font-medium' : ''}>{display.headline}</div>
            {display.items.length > 0 && (
              <ol className="list-decimal pl-5 space-y-0.5">
                {display.items.map((item, i) => (
                  <li key={i}>{item}</li>
                ))}
              </ol>
            )}
            {display.note && <div style={rt.label}>{display.note}</div>}
          </div>
        )}
      </div>
      {phase === 'running' && (
        <button type="button" onClick={cancel} className="text-xs font-medium" style={rt.link}>{EXPLAIN_TEXT.cancel}</button>
      )}
      {(phase === 'idle' || phase === 'error') && (
        <button type="button" onClick={start} className="text-xs font-medium" style={rt.link}>{EXPLAIN_TEXT.button}</button>
      )}
    </div>
  );
};

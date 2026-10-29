import React from 'react';
import { Loader2, Play, Trash2, Zap } from 'lucide-react';
import { rt, btn } from './resultsTheme';
import { formatCount } from './ruleModel';
import type { ExecutionResponse } from './types';

interface ControlPanelProps {
  result: ExecutionResponse | null;
  isExecuting: boolean;
  enabledCount: number;
  selectedCount: number;
  onRunAll: () => void;
  onRunSelected: () => void;
  onClear: () => void;
}

function skippedLine(s: NonNullable<ExecutionResponse['skippedRules']>[number]): string {
  if (typeof s === 'string') return s;
  const name = s.ruleName || s.name || s.ruleId || 'A rule';
  return s.reason ? `${name}: ${s.reason}` : name;
}

export function ControlPanel({ result, isExecuting, enabledCount, selectedCount, onRunAll, onRunSelected, onClear }: ControlPanelProps) {
  const skipped = result?.skippedRules ?? [];
  const versions = Object.entries(result?.engineVersions ?? {});
  const runAllOff = isExecuting || enabledCount === 0;
  const runSelectedOff = isExecuting || selectedCount === 0;
  const clearOff = isExecuting || !result;
  const statusText = result ? (result.statusLabel || result.status || (result.success ? 'Completed' : 'Failed')) : null;

  return (
    <div className="flex-1 overflow-y-auto p-4 space-y-3" data-testid="control-panel">
      <p className="text-sm" style={rt.label}>
        Running the rules works out new facts from them (inferred facts) without changing what is typed into the ontology (asserted facts).
      </p>
      <div className="flex flex-wrap items-center gap-2">
        <button onClick={onRunAll} disabled={runAllOff} className="px-4 py-1.5 text-sm rounded-lg flex items-center gap-2" style={btn(runAllOff, true)}>
          {isExecuting ? <Loader2 size={14} className="animate-spin" /> : <Zap size={14} />} Run all
        </button>
        <button onClick={onRunSelected} disabled={runSelectedOff} className="px-4 py-1.5 text-sm rounded-lg flex items-center gap-2" style={btn(runSelectedOff)} title="Tick rules in the rule list to run only those">
          <Play size={14} /> Run selected ({selectedCount})
        </button>
        <button onClick={onClear} disabled={clearOff} className="px-4 py-1.5 text-sm rounded-lg flex items-center gap-2" style={btn(clearOff)}>
          <Trash2 size={14} /> Clear results
        </button>
      </div>

      {isExecuting && (
        <div className="flex items-center gap-2 text-sm" style={rt.muted}><Loader2 size={14} className="animate-spin" /> Running the rules…</div>
      )}

      {!isExecuting && !result && (
        <p className="text-sm" style={rt.muted} data-testid="control-empty">Nothing has been run yet.</p>
      )}

      {!isExecuting && result && (
        <div className="p-3 rounded-lg text-sm space-y-1" style={result.success ? rt.card : rt.bannerError} data-testid="control-summary">
          <div className="font-semibold">Last run: {statusText}</div>
          {!result.success && result.errorMessage && <div>{result.errorMessage}</div>}
          <div>{formatCount(result.totalRulesExecuted)} rule{result.totalRulesExecuted === 1 ? '' : 's'} run in {formatCount(result.executionTimeMs)} ms</div>
          <div>
            {formatCount(result.inferredAxiomsCount)} inferred fact{result.inferredAxiomsCount === 1 ? '' : 's'}
            {result.counts ? ` (${formatCount(result.counts.conclusions)} conclusions, ${formatCount(result.counts.structure)} structure)` : ''}
          </div>
          {typeof result.rounds === 'number' && <div>{formatCount(result.rounds)} round{result.rounds === 1 ? '' : 's'}</div>}
          {versions.length > 0 && <div>Engine: {versions.map(([k, val]) => `${k} ${val}`).join(', ')}</div>}
          {skipped.length > 0 && (
            <div data-testid="control-skipped">
              <div className="font-semibold mt-2">{formatCount(skipped.length)} rule{skipped.length === 1 ? ' was' : 's were'} skipped</div>
              <ul className="list-disc pl-5">
                {skipped.map((s, i) => <li key={i}>{skippedLine(s)}</li>)}
              </ul>
            </div>
          )}
        </div>
      )}
    </div>
  );
}

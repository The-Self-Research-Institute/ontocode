import React, { useState } from 'react';
import { Loader2, Play, Trash2, Zap } from 'lucide-react';
import { rt, btn } from './resultsTheme';
import {
  formatCount,
  inferredSummaryText,
  inferredTotal,
  isSqwrlRule,
  ranAtText,
  ruleRunOutcome,
  ruleTitle,
  runScopeText,
  skippedInfo,
  skippedListText,
} from './ruleModel';
import type { ExecutionResponse, SwrlRule } from './types';

interface ControlPanelProps {
  result: ExecutionResponse | null;
  isExecuting: boolean;
  enabledCount: number;
  selectedCount: number;
  onRunAll: () => void;
  onRunSelected: () => void;
  onClear: () => void;
  rules?: SwrlRule[];
  selectedIds?: Set<string>;
  onToggleRule?: (ruleId: string) => void;
  onSelectAllEnabled?: () => void;
  onSelectNone?: () => void;
  ranAt?: number | null;
}

const STATE_LABEL = { enabled: 'Enabled', disabled: 'Disabled', sqwrl: 'SQWRL query' } as const;

export function ControlPanel({
  result, isExecuting, enabledCount, selectedCount, onRunAll, onRunSelected, onClear,
  rules = [], selectedIds = new Set<string>(), onToggleRule, onSelectAllEnabled, onSelectNone, ranAt = null,
}: ControlPanelProps) {
  const [showSkipped, setShowSkipped] = useState(true);
  const skipped = skippedInfo(result ?? {});
  const versions = Object.entries(result?.engineVersions ?? {});
  const runAllOff = isExecuting || enabledCount === 0;
  const runSelectedOff = isExecuting || selectedCount === 0;
  const clearOff = isExecuting || !result;
  const statusText = result ? (result.statusLabel || result.status || (result.success ? 'Completed' : 'Failed')) : null;
  const warned = !!result && result.success && skipped.length > 0;
  const mentionsWarnings = /warning/i.test(`${result?.statusLabel ?? ''} ${result?.status ?? ''}`);
  const when = ranAtText(ranAt, Date.now());
  const hint = isExecuting ? 'Wait for the current run to finish.' : selectedCount === 0 ? 'Tick rules below to run only those.' : '';

  return (
    <div className="flex-1 overflow-y-auto p-4 space-y-3" data-testid="control-panel">
      <p className="text-sm" style={rt.label}>
        Running the rules works out new facts from them (inferred facts) without changing what is typed into the ontology (asserted facts).
      </p>
      <div className="flex flex-wrap items-center gap-2">
        <button onClick={onRunAll} disabled={runAllOff} className="px-4 py-1.5 text-sm rounded-lg flex items-center gap-2" style={btn(runAllOff, true)}>
          {isExecuting ? <Loader2 size={14} className="animate-spin" /> : <Zap size={14} />} Run all enabled rules ({enabledCount})
        </button>
        <button
          onClick={onRunSelected}
          disabled={runSelectedOff}
          className="px-4 py-1.5 text-sm rounded-lg flex items-center gap-2"
          style={btn(runSelectedOff)}
          title="Tick rules in the list below to run only those"
          aria-describedby={hint ? 'control-hint' : undefined}
        >
          <Play size={14} /> Run selected ({selectedCount})
        </button>
        <button onClick={onClear} disabled={clearOff} className="px-4 py-1.5 text-sm rounded-lg flex items-center gap-2" style={btn(clearOff)}>
          <Trash2 size={14} /> Clear results
        </button>
      </div>
      {hint && <p id="control-hint" className="text-xs" style={rt.muted} data-testid="control-hint">{hint}</p>}

      {rules.length > 0 && (
        <section aria-label="Rules" data-testid="control-rules">
          <div className="flex items-center gap-3 text-xs mb-1" style={rt.label}>
            <span>{formatCount(rules.length)} rule{rules.length === 1 ? '' : 's'}</span>
            <button type="button" onClick={onSelectAllEnabled} className="underline" style={rt.link}>Select all enabled</button>
            <button type="button" onClick={onSelectNone} className="underline" style={rt.link}>Select none</button>
          </div>
          <ul className="rounded-lg overflow-hidden" style={rt.panel} aria-label="Rules to run">
            {rules.map(rule => {
              const sqwrl = isSqwrlRule(rule);
              const state = !rule.enabled ? 'disabled' : sqwrl ? 'sqwrl' : 'enabled';
              const checked = selectedIds.has(rule.id);
              const selectable = state === 'enabled';
              const { title, tooltip } = ruleTitle(rule);
              const outcome = ruleRunOutcome(rule, result);
              const inputId = `control-rule-check-${rule.id}`;
              return (
                <li
                  key={rule.id}
                  className="flex items-center gap-2 px-3 py-2 text-sm"
                  style={{ ...rt.rowBorder, opacity: selectable ? 1 : 0.6 }}
                  data-testid={`control-rule-${rule.id}`}
                >
                  <input
                    id={inputId}
                    type="checkbox"
                    checked={checked}
                    disabled={(!selectable && !checked) || isExecuting}
                    onChange={() => onToggleRule?.(rule.id)}
                  />
                  <label htmlFor={inputId} className="flex-1 min-w-0 truncate" style={rt.value} title={tooltip}>{title}</label>
                  <span className="px-1.5 py-0.5 text-[10px] rounded-full whitespace-nowrap" style={rt.chip}>{STATE_LABEL[state]}</span>
                  {outcome && (
                    <span className="text-xs" style={rt.label} data-testid={`control-outcome-${rule.id}`}>{outcome.text}</span>
                  )}
                </li>
              );
            })}
          </ul>
        </section>
      )}

      {isExecuting && (
        <div className="flex items-center gap-2 text-sm" style={rt.muted}><Loader2 size={14} className="animate-spin" /> Running the rules…</div>
      )}

      {!isExecuting && !result && (
        <p className="text-sm" style={rt.muted} data-testid="control-empty">Nothing has been run yet.</p>
      )}

      {!isExecuting && result && (
        <div className="p-3 rounded-lg text-sm space-y-1" style={result.success ? rt.card : rt.bannerError} data-testid="control-summary">
          <div className="font-semibold">Last run: {statusText}</div>
          <div data-testid="control-scope">{runScopeText(result)}{when ? ` ${when}` : ''}</div>
          {!result.success && result.errorMessage && <div>{result.errorMessage}</div>}
          {warned && !mentionsWarnings && (
            <div data-testid="control-warning">Completed with warnings: {formatCount(skipped.length)} rule{skipped.length === 1 ? ' was' : 's were'} skipped.</div>
          )}
          <div>{formatCount(result.totalRulesExecuted)} rule{result.totalRulesExecuted === 1 ? '' : 's'} run in {formatCount(result.executionTimeMs)} ms</div>
          <div data-testid="control-inferred" data-total={inferredTotal(result)}>{inferredSummaryText(result)}</div>
          {typeof result.rounds === 'number' && <div>{formatCount(result.rounds)} round{result.rounds === 1 ? '' : 's'}</div>}
          {versions.length > 0 && <div>Engine: {versions.map(([k, val]) => `${k} ${val}`).join(', ')}</div>}
          {skipped.length > 0 && (
            <div data-testid="control-skipped">
              <button
                type="button"
                onClick={() => setShowSkipped(o => !o)}
                aria-expanded={showSkipped}
                aria-controls="control-skipped-list"
                className="font-semibold mt-2 underline"
              >
                {formatCount(skipped.length)} rule{skipped.length === 1 ? ' was' : 's were'} skipped
              </button>
              {showSkipped && (
                <ul id="control-skipped-list" className="list-disc pl-5">
                  {skipped.map((s, i) => <li key={i}>{skippedListText(s)}</li>)}
                </ul>
              )}
            </div>
          )}
        </div>
      )}
    </div>
  );
}

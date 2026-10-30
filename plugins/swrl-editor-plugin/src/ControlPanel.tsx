import React, { useEffect, useRef, useState } from 'react';
import { Database, Loader2, Play, PlusCircle, Trash2, Zap } from 'lucide-react';
import apiClient from './apiClient';
import { rt, btn } from './resultsTheme';
import {
  ADD_INFERRED_CAP,
  LOAD_STEP_NOTE,
  addConfirmText,
  formatCount,
  inferredPlan,
  loadLogLines,
  runLogLines,
  transferSummaryPath,
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
import type { TransferSummary } from './ruleModel';

export interface ControlSession {
  lines: string[];
  loaded: boolean;
  added: ExecutionResponse | null;
  append: (lines: string[]) => void;
  clearLog: () => void;
  setLoaded: (loaded: boolean) => void;
  setAdded: (run: ExecutionResponse | null) => void;
}

const MAX_LOG_LINES = 500;

export function useControlSession(resetKey: string): ControlSession {
  const [lines, setLines] = useState<string[]>([]);
  const [loaded, setLoaded] = useState(false);
  const [added, setAdded] = useState<ExecutionResponse | null>(null);
  useEffect(() => {
    setLines([]);
    setLoaded(false);
    setAdded(null);
  }, [resetKey]);
  return {
    lines,
    loaded,
    added,
    append: more => setLines(prev => [...prev, ...more].slice(-MAX_LOG_LINES)),
    clearLog: () => setLines([]),
    setLoaded,
    setAdded,
  };
}

export type AddOutcome = { ok: boolean; message: string; added?: number };

interface ControlPanelProps {
  projectId?: string;
  draftMode?: boolean;
  session?: ControlSession;
  onAddInferred?: (includeStructure: boolean) => Promise<AddOutcome>;
  result: ExecutionResponse | null;
  isExecuting: boolean;
  enabledCount: number;
  selectedCount: number;
  onRunAll: () => void | Promise<ExecutionResponse | null | void>;
  onRunSelected: () => void | Promise<ExecutionResponse | null | void>;
  onClear: () => void;
  rules?: SwrlRule[];
  selectedIds?: Set<string>;
  onToggleRule?: (ruleId: string) => void;
  onSelectAllEnabled?: () => void;
  onSelectNone?: () => void;
  ranAt?: number | null;
}

const STATE_LABEL = { enabled: 'Enabled', disabled: 'Disabled', sqwrl: 'SQWRL query' } as const;

function stepError(e: any, what: string): string {
  if (e?.status === 429) return 'The rule engine is busy. Try again in a moment.';
  if (e?.status === 403 && e?.data?.code === 'PUBLIC_EDIT_DISABLED') return 'Editing the public ontology is turned off, so nothing was added.';
  return e?.data?.message || e?.message || what;
}

export function ControlPanel({
  projectId = '', draftMode = false, session, onAddInferred,
  result, isExecuting, enabledCount, selectedCount, onRunAll, onRunSelected, onClear,
  rules = [], selectedIds = new Set<string>(), onToggleRule, onSelectAllEnabled, onSelectNone, ranAt = null,
}: ControlPanelProps) {
  const ownSession = useControlSession(projectId);
  const log = session ?? ownSession;
  const [stepBusy, setStepBusy] = useState<'load' | 'add' | null>(null);
  const stepRef = useRef(false);
  const runRef = useRef(false);
  const [confirming, setConfirming] = useState(false);
  const [includeStructure, setIncludeStructure] = useState(false);
  const logRef = useRef<HTMLPreElement | null>(null);
  const [showSkipped, setShowSkipped] = useState(true);
  const skipped = skippedInfo(result ?? {});
  const versions = Object.entries(result?.engineVersions ?? {});
  const runScopeCount = selectedCount > 0 ? selectedCount : enabledCount;
  const anyStepBusy = stepBusy !== null;
  const loadOff = isExecuting || anyStepBusy;
  const runOff = isExecuting || anyStepBusy || !log.loaded || runScopeCount === 0;
  const alreadyAdded = !!result && log.added === result;
  const addOff = isExecuting || anyStepBusy || !onAddInferred || !result || !result.success || result.executionMode === 'test'
    || inferredPlan(result, false).total === 0 || alreadyAdded;
  const runSelectedOff = isExecuting || anyStepBusy || selectedCount === 0;
  const clearOff = isExecuting || !result;
  const plan = result ? inferredPlan(result, includeStructure) : null;
  const statusText = result ? (result.statusLabel || result.status || (result.success ? 'Completed' : 'Failed')) : null;
  const warned = !!result && result.success && skipped.length > 0;
  const mentionsWarnings = /warning/i.test(`${result?.statusLabel ?? ''} ${result?.status ?? ''}`);
  const when = ranAtText(ranAt, Date.now());
  useEffect(() => {
    const el = logRef.current;
    if (el) el.scrollTop = el.scrollHeight;
  }, [log.lines.length]);

  const startStep = (kind: 'load' | 'add') => {
    if (stepRef.current) return false;
    stepRef.current = true;
    setStepBusy(kind);
    return true;
  };

  const endStep = () => {
    stepRef.current = false;
    setStepBusy(null);
  };

  const handleLoad = async () => {
    if (!startStep('load')) return;
    log.append(['Checking the ontology and the rules...']);
    try {
      const summary = await apiClient.get<TransferSummary>(transferSummaryPath(projectId, draftMode));
      log.append(loadLogLines(summary));
      log.setLoaded(true);
    } catch (e: any) {
      log.append([`The ontology and rules could not be checked: ${stepError(e, 'Please try again.')}`]);
    } finally {
      endStep();
    }
  };

  const handleRun = async (selectedOnly: boolean) => {
    if (stepRef.current || runRef.current) return;
    runRef.current = true;
    const ticked = selectedOnly || selectedCount > 0;
    log.append([ticked ? 'Running the ticked rules...' : 'Running all enabled rules...']);
    try {
      const run = await (ticked ? onRunSelected() : onRunAll());
      log.append(run ? runLogLines(run) : ['The rules could not be run. Try again.']);
    } finally {
      runRef.current = false;
    }
  };

  const handleAdd = async () => {
    if (!onAddInferred || !result || !startStep('add')) return;
    setConfirming(false);
    log.append(['Adding the inferred facts to the ontology...']);
    const target = result;
    try {
      const outcome = await onAddInferred(includeStructure);
      if (outcome.ok) log.setAdded(target);
      log.append([outcome.message]);
    } catch (e: any) {
      log.append([`The inferred facts could not be added: ${stepError(e, 'Please try again.')}`]);
    } finally {
      endStep();
    }
  };

  const hint = isExecuting ? 'Wait for the current run to finish.' : selectedCount === 0 ? 'Tick rules below to run only those.' : '';

  return (
    <div className="flex-1 overflow-y-auto p-4 space-y-3" data-testid="control-panel">
      <p className="text-sm" style={rt.label}>
        Work through the three steps in order: load the ontology and rules, run the rules, then add the inferred facts to the ontology if you want to keep them.
      </p>
      <p className="text-xs" style={rt.muted} data-testid="control-steps-note">{LOAD_STEP_NOTE}</p>
      <div className="flex flex-wrap items-center gap-2" data-testid="control-steps">
        <button
          onClick={handleLoad}
          disabled={loadOff}
          className="px-4 py-1.5 text-sm rounded-lg flex items-center gap-2"
          style={btn(loadOff, !log.loaded)}
          title={`Step 1 (Protege: OWL+SWRL->Drools). Checks the ontology and the rules and counts what will be sent to the rule engine. ${LOAD_STEP_NOTE}`}
        >
          {stepBusy === 'load' ? <Loader2 size={14} className="animate-spin" /> : <Database size={14} />} Load ontology and rules
        </button>
        <button
          onClick={() => handleRun(false)}
          disabled={runOff}
          className="px-4 py-1.5 text-sm rounded-lg flex items-center gap-2"
          style={btn(runOff, log.loaded)}
          title="Step 2 (Protege: Run Drools). Runs the rule engine over all enabled rules, or over the ticked rules if you ticked some. Needs step 1 first."
        >
          {isExecuting ? <Loader2 size={14} className="animate-spin" /> : <Zap size={14} />} Run rules ({formatCount(runScopeCount)})
        </button>
        <button
          onClick={() => setConfirming(true)}
          disabled={addOff || confirming}
          className="px-4 py-1.5 text-sm rounded-lg flex items-center gap-2"
          style={btn(addOff || confirming)}
          title="Step 3 (Protege: Drools->OWL). Saves the inferred facts in the ontology as ordinary statements. Needs a finished run."
        >
          {stepBusy === 'add' ? <Loader2 size={14} className="animate-spin" /> : <PlusCircle size={14} />} Add inferred facts to ontology
        </button>
        <button
          onClick={() => handleRun(true)}
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
      {confirming && plan && (
        <div role="alertdialog" aria-label="Add inferred facts" className="p-3 rounded-lg text-sm space-y-2" style={rt.bannerWarning} data-testid="add-confirm">
          <p data-testid="add-confirm-text">{addConfirmText(plan, ADD_INFERRED_CAP)}</p>
          <label className="flex items-center gap-2">
            <input type="checkbox" checked={includeStructure} disabled={plan.relationships === 0} onChange={e => setIncludeStructure(e.target.checked)} />
            <span>Also add class relationships ({formatCount(plan.relationships)}), such as one class being a kind of another</span>
          </label>
          <div className="flex gap-2">
            <button onClick={handleAdd} className="px-3 py-1.5 rounded-lg font-medium" style={rt.buttonPrimary}>Yes, add them</button>
            <button onClick={() => setConfirming(false)} className="px-3 py-1.5 rounded-lg" style={rt.button}>Cancel</button>
          </div>
        </div>
      )}
      <section aria-label="Log" data-testid="control-log-section">
        <div className="flex items-center justify-between text-xs mb-1" style={rt.label}>
          <span>Log</span>
          <button type="button" onClick={log.clearLog} disabled={log.lines.length === 0} className="underline" style={rt.link}>Clear log</button>
        </div>
        <pre
          ref={logRef}
          className="p-2 text-xs rounded-lg overflow-y-auto whitespace-pre-wrap font-mono"
          style={{ ...rt.code, height: '10rem' }}
          role="log"
          aria-live="polite"
          data-testid="control-log"
        >{log.lines.length === 0 ? 'Nothing has happened yet. Start with Load ontology and rules.' : log.lines.join('\n')}</pre>
      </section>
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

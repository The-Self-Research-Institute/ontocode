import React, { useEffect, useRef, useState } from 'react';
import { AlertTriangle, Check, CheckCircle2, Loader2, PlusCircle, Search, XCircle, Zap } from 'lucide-react';
import apiClient from './apiClient';
import { rt, btn } from './resultsTheme';
import { FOCUS_CSS, PopMenu } from './ui/PopMenu';
import {
  ADD_INFERRED_CAP,
  addConfirmText,
  formatCount,
  inferredPlan,
  inferredSplit,
  inferredTotal,
  isSqwrlRule,
  loadLogLines,
  ranAtText,
  ruleRunOutcome,
  ruleTitle,
  runLogLines,
  skippedInfo,
  skippedListText,
  transferSummaryPath,
} from './ruleModel';
import { addButtonLabel, addDisabledReason, checkSummaryText, factsFoundText, plural, runButtonLabel, runTailText, runTone, scopeLine } from './controlModel';
import type { ExecutionResponse, SwrlRule } from './types';
import type { TransferSummary } from './ruleModel';

export interface ControlSession {
  lines: string[];
  loaded: boolean;
  checkSummary: string;
  added: ExecutionResponse | null;
  append: (lines: string[]) => void;
  clearLog: () => void;
  setLoaded: (loaded: boolean) => void;
  setCheckSummary: (text: string) => void;
  setAdded: (run: ExecutionResponse | null) => void;
}

const MAX_LOG_LINES = 500;
const SEARCH_THRESHOLD = 8;

export function useControlSession(resetKey: string): ControlSession {
  const [lines, setLines] = useState<string[]>([]);
  const [loaded, setLoaded] = useState(false);
  const [checkSummary, setCheckSummary] = useState('');
  const [added, setAdded] = useState<ExecutionResponse | null>(null);
  useEffect(() => {
    setLines([]);
    setLoaded(false);
    setCheckSummary('');
    setAdded(null);
  }, [resetKey]);
  return {
    lines,
    loaded,
    checkSummary,
    added,
    append: more => setLines(prev => [...prev, ...more].slice(-MAX_LOG_LINES)),
    clearLog: () => setLines([]),
    setLoaded,
    setCheckSummary,
    setAdded,
  };
}

export type AddOutcome = { ok: boolean; message: string; added?: number };

export interface FactsSlotState {
  listOpen: boolean;
  section: 'rules' | 'standard';
  openList: () => void;
}

interface RunPanelProps {
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
  onOpenEditor?: () => void;
  onOpenGuide?: () => void;
  renderFacts?: (state: FactsSlotState) => React.ReactNode;
}

function stepError(e: any, what: string): string {
  if (e?.status === 429) return 'The rule engine is busy. Try again in a moment.';
  if (e?.status === 403 && e?.data?.code === 'PUBLIC_EDIT_DISABLED') return 'Editing the public ontology is turned off, so nothing was added.';
  return e?.data?.message || e?.message || what;
}

function LinkButton({ children, onClick, disabled, expanded, testId, controls }: { children: React.ReactNode; onClick?: () => void; disabled?: boolean; expanded?: boolean; testId?: string; controls?: string }) {
  return (
    <button
      type="button"
      onClick={onClick}
      disabled={disabled}
      aria-expanded={expanded}
      aria-controls={controls}
      data-testid={testId}
      className="swrl-focus text-sm px-1 py-0.5"
      style={{ ...rt.link, opacity: disabled ? 0.5 : 1, cursor: disabled ? 'not-allowed' : 'pointer' }}
    >
      {children}
    </button>
  );
}

export function ControlPanel({
  projectId = '', draftMode = false, session, onAddInferred,
  result, isExecuting, enabledCount, selectedCount, onRunAll, onRunSelected, onClear,
  rules = [], selectedIds = new Set<string>(), onToggleRule, onSelectAllEnabled, onSelectNone, ranAt = null,
  onOpenEditor, onOpenGuide, renderFacts,
}: RunPanelProps) {
  const ownSession = useControlSession(projectId);
  const log = session ?? ownSession;
  const [stepBusy, setStepBusy] = useState<'load' | 'add' | null>(null);
  const stepRef = useRef(false);
  const runRef = useRef(false);
  const [confirming, setConfirming] = useState(false);
  const [includeStructure, setIncludeStructure] = useState(false);
  const logRef = useRef<HTMLPreElement | null>(null);
  const [showSkipped, setShowSkipped] = useState(false);
  const [showTech, setShowTech] = useState(false);
  const [showRules, setShowRules] = useState(false);
  const [listOpen, setListOpen] = useState(false);
  const [view, setView] = useState<'rules' | 'standard'>('rules');
  const [filter, setFilter] = useState('');
  const [addNotice, setAddNotice] = useState<{ ok: boolean; text: string } | null>(null);
  const skipped = skippedInfo(result ?? {});
  const versions = Object.entries(result?.engineVersions ?? {});
  const runnable = rules.length > 0 ? rules.filter(r => r.enabled && !isSqwrlRule(r)).length : enabledCount;
  const anyStepBusy = stepBusy !== null;
  const runOff = isExecuting || anyStepBusy || runnable === 0;
  const alreadyAdded = !!result && log.added === result;
  const planTotal = result ? inferredPlan(result, false).total : 0;
  const isTest = result?.executionMode === 'test';
  const addReason = addDisabledReason({ hasResult: !!result, success: !!result?.success, test: isTest, total: planTotal, alreadyAdded });
  const addOff = isExecuting || anyStepBusy || !onAddInferred || !result || !!addReason;
  const plan = result ? inferredPlan(result, includeStructure) : null;
  const tone = result ? runTone(result.success, skipped.length) : 'ok';
  const rawStatus = result ? (result.statusLabel || result.status || '') : '';
  const warnText = /warning/i.test(rawStatus) ? rawStatus : 'Completed with warnings';
  const when = ranAtText(ranAt, Date.now());
  const split = result ? inferredSplit(result) : null;
  const factCount = result ? (split ? split.facts : inferredTotal(result)) : 0;
  const standardCount = split ? split.relationships : 0;
  const rulesRun = result?.executedRuleNames?.length ?? result?.totalRulesExecuted ?? 0;
  const scope = scopeLine(runnable, selectedCount);
  const query = filter.trim().toLowerCase();
  const shownRules = query ? rules.filter(r => `${r.ruleName} ${r.ruleText ?? ''}`.toLowerCase().includes(query)) : rules;
  const showSearch = rules.length > SEARCH_THRESHOLD;
  const selectedRunnable = rules.filter(r => selectedIds.has(r.id)).length || selectedCount;

  useEffect(() => {
    setListOpen(false);
    setView('rules');
    setConfirming(false);
    setShowSkipped(false);
    setAddNotice(null);
  }, [result]);

  useEffect(() => {
    const el = logRef.current;
    if (el) el.scrollTop = el.scrollHeight;
  }, [log.lines.length, showTech]);

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

  const handleCheck = async () => {
    if (!startStep('load')) return;
    log.append(['Checking the ontology and the rules...']);
    try {
      const summary = await apiClient.get<TransferSummary>(transferSummaryPath(projectId, draftMode));
      log.append(loadLogLines(summary));
      log.setLoaded(true);
      log.setCheckSummary(checkSummaryText(summary));
    } catch (e: any) {
      log.setCheckSummary('');
      log.append([`The ontology and rules could not be checked: ${stepError(e, 'Please try again.')}`]);
    } finally {
      endStep();
    }
  };

  const handleRun = async () => {
    if (stepRef.current || runRef.current || runOff) return;
    runRef.current = true;
    const ticked = selectedCount > 0;
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
      setAddNotice({ ok: outcome.ok, text: outcome.message });
    } catch (e: any) {
      const text = `The inferred facts could not be added: ${stepError(e, 'Please try again.')}`;
      log.append([text]);
      setAddNotice({ ok: false, text });
    } finally {
      endStep();
    }
  };

  const StatusIcon = tone === 'fail' ? XCircle : tone === 'warn' ? AlertTriangle : CheckCircle2;
  const statusColor = tone === 'fail' ? 'var(--error)' : tone === 'warn' ? 'var(--warning)' : 'var(--success)';
  const runLabel = isExecuting ? 'Running…' : runButtonLabel(selectedCount);
  const nextStep = !result ? '' : !result.success ? '' : isTest ? 'This was only a test. Nothing was changed.' : factCount === 0 ? 'No new facts. Try ticking more rules, or see Standard reasoning.' : alreadyAdded ? 'These facts are now in your ontology.' : 'Add them to your ontology to keep them.';
  const checkNow = () => {
    setShowTech(true);
    handleCheck();
  };
  const detailsMenu = (
    <PopMenu
      testId="details-menu"
      align="right"
      width="15rem"
      label="Details"
      className="px-3 py-1.5 text-sm rounded-lg"
      style={rt.button}
      items={[
        { id: 'technical', label: 'Technical details', description: 'The log of what happened', onClick: () => setShowTech(true) },
        { id: 'check', label: 'Check what will be sent', description: 'Counts only, optional', onClick: checkNow, disabled: isExecuting || anyStepBusy },
        ...(result ? [
          { id: 'again', label: 'Run again', onClick: handleRun, disabled: runOff },
          { id: 'clear', label: 'Clear results', onClick: onClear, disabled: isExecuting },
        ] : []),
        { id: 'explained', label: 'How facts are explained', description: 'Open the guide', onClick: () => onOpenGuide?.(), disabled: !onOpenGuide },
      ]}
    />
  );

  return (
    <div className="flex-1 overflow-y-auto p-4 space-y-4 min-w-0" data-testid="control-panel">
      <style>{FOCUS_CSS}</style>

      <section aria-label="Run the rules" className="rounded-lg p-4 space-y-3" style={rt.panel} data-testid="control-run-section">
        <div className="flex flex-wrap items-center justify-between gap-4">
          <div data-testid="control-scope-line">
            <span className="font-semibold">{scope.head}</span>
            {scope.note && <span className="ml-2 text-sm" style={rt.muted}>{scope.note}</span>}
          </div>
          <div className="flex items-center gap-3">
            {rules.length > 0 && (
              <LinkButton onClick={() => setShowRules(o => !o)} expanded={showRules} controls="control-rules-list" testId="control-choose-rules">
                {showRules ? 'Hide the rule list' : 'Choose which rules'}
              </LinkButton>
            )}
            {!result && detailsMenu}
          </div>
        </div>

        {rules.length === 0 && (
          <div className="text-sm space-y-2" data-testid="control-no-rules">
            <p style={rt.label}>No rules yet. Create one on the Rules tab, or import an Excel file.</p>
            {onOpenEditor && (
              <button type="button" onClick={onOpenEditor} className="swrl-focus px-3 py-1.5 text-sm rounded-lg" style={rt.button}>Go to the Rules tab</button>
            )}
          </div>
        )}

        {showRules && rules.length > 0 && (
          <div id="control-rules-list" className="rounded-lg overflow-hidden" style={rt.card} data-testid="control-rules">
            <div className="flex flex-wrap items-center justify-between gap-3 px-3 py-2" style={rt.panelHeader}>
              <span className="text-sm font-medium" data-testid="control-selected-count">{formatCount(selectedRunnable)} of {formatCount(runnable)} selected</span>
              <div className="flex items-center gap-1">
                <LinkButton onClick={onSelectAllEnabled} disabled={isExecuting || runnable === 0}>Select all</LinkButton>
                <LinkButton onClick={onSelectNone} disabled={isExecuting || selectedCount === 0}>Select none</LinkButton>
              </div>
            </div>
            {showSearch && (
              <div className="flex items-center gap-2 px-3 py-2" style={rt.rowBorder}>
                <Search size={14} style={rt.muted} aria-hidden="true" />
                <input
                  type="search"
                  value={filter}
                  onChange={e => setFilter(e.target.value)}
                  placeholder="Search rules"
                  aria-label="Search rules"
                  className="swrl-focus flex-1 min-w-0 px-2 py-1 text-sm rounded"
                  style={rt.input}
                  data-testid="control-rule-search"
                />
              </div>
            )}
            <ul className="overflow-y-auto" style={{ maxHeight: '15rem' }} aria-label="Rules to run">
              {shownRules.length === 0 && <li className="px-3 py-2 text-sm" style={rt.muted}>No rules match your search.</li>}
              {shownRules.map(rule => {
                const sqwrl = isSqwrlRule(rule);
                const state = !rule.enabled ? 'disabled' : sqwrl ? 'sqwrl' : 'normal';
                const checked = selectedIds.has(rule.id);
                const selectable = state === 'normal';
                const { title, tooltip } = ruleTitle(rule);
                const text = (rule.ruleText || '').replace(/\s+/g, ' ').trim();
                const secondary = text && title !== text && !title.endsWith('...') ? text : '';
                const outcome = state === 'normal' ? ruleRunOutcome(rule, result) : null;
                const marker = state === 'disabled' ? 'Disabled' : state === 'sqwrl' ? 'SQWRL query' : '';
                const inputId = `control-rule-check-${rule.id}`;
                return (
                  <li
                    key={rule.id}
                    className="flex items-center gap-2 px-3 py-1.5 text-sm"
                    style={{ ...rt.rowBorder, opacity: selectable ? 1 : 0.6 }}
                    data-testid={`control-rule-${rule.id}`}
                  >
                    <input
                      id={inputId}
                      type="checkbox"
                      className="swrl-focus shrink-0"
                      checked={checked}
                      disabled={(!selectable && !checked) || isExecuting}
                      onChange={() => onToggleRule?.(rule.id)}
                    />
                    <label htmlFor={inputId} className="flex-1 min-w-0 truncate" style={rt.value} title={secondary ? `${tooltip}: ${text}` : tooltip}>
                      <span>{title}</span>
                      {secondary && <span className="ml-2 text-xs font-mono" style={rt.muted}>{secondary}</span>}
                    </label>
                    {marker && <span className="shrink-0 text-xs whitespace-nowrap" style={rt.muted} data-testid={`control-state-${rule.id}`}>{marker}</span>}
                    {outcome && (
                      <span
                        className="flex items-center gap-1 text-xs min-w-0 shrink-0"
                        style={outcome.kind === 'ran' ? rt.label : { color: outcome.kind === 'failed' ? 'var(--error)' : 'var(--warning)' }}
                        title={outcome.text}
                        data-testid={`control-outcome-${rule.id}`}
                      >
                        {outcome.kind === 'ran' && <Check size={12} aria-hidden="true" className="shrink-0" />}
                        <span>{outcome.kind === 'ran' ? 'Ran' : outcome.kind === 'failed' ? 'Could not run' : 'Skipped'}</span>
                      </span>
                    )}
                  </li>
                );
              })}
            </ul>
          </div>
        )}

        {!result && rules.length > 0 && (
          <div className="flex flex-wrap items-center gap-4">
            <button
              type="button"
              onClick={handleRun}
              disabled={runOff}
              className="swrl-focus px-6 py-3 text-base font-semibold rounded-lg flex items-center justify-center gap-2"
              style={{ ...btn(runOff, true), minWidth: '14rem' }}
              data-testid="control-run"
            >
              {isExecuting ? <Loader2 size={16} className="animate-spin" aria-hidden="true" /> : <Zap size={16} aria-hidden="true" />}
              {runLabel}
            </button>
            <span className="text-sm" style={rt.muted} data-testid="control-helper">Nothing is saved until you add the results to your ontology.</span>
          </div>
        )}
      </section>

      {result && (
        <section aria-label="Results" className="space-y-3" data-testid="control-summary">
          <div className="sticky z-10 space-y-3 pb-3 bg-gray-50" style={{ top: '-1rem', paddingTop: '1rem' }} data-testid="control-sticky">
            <div
              className="rounded-lg p-4 flex items-start gap-3"
              style={tone === 'fail' ? rt.bannerError : tone === 'warn' ? rt.bannerNote : rt.bannerSuccess}
              role="status"
              aria-live="polite"
              data-testid="control-status"
            >
              <StatusIcon size={22} className="shrink-0 mt-1" style={{ color: statusColor }} aria-hidden="true" />
              <div className="min-w-0 flex-1">
                {result.success ? (
                  <div className="flex flex-wrap items-baseline gap-2" data-testid="control-inferred" data-total={inferredTotal(result)}>
                    <span className="text-3xl font-semibold" style={{ fontVariantNumeric: 'tabular-nums' }} data-testid="control-stat-facts">{formatCount(factCount)}</span>
                    <span className="font-semibold" data-testid="control-status-text">{factsFoundText(factCount).replace(/^[\d,]+ /, '')}</span>
                    <span className="text-sm" style={rt.label} data-testid="control-scope">{isTest ? 'from testing one rule' : runTailText(rulesRun, result.executionTimeMs, when)}</span>
                  </div>
                ) : (
                  <div>
                    <div className="font-semibold" data-testid="control-status-text">{result.statusLabel || 'Failed'}</div>
                    <div className="text-sm" style={rt.label} data-testid="control-scope">{when}</div>
                    {result.errorMessage && <div className="mt-1 text-sm">{result.errorMessage}</div>}
                  </div>
                )}
                {tone === 'warn' && <div className="text-xs mt-1" style={rt.label} data-testid="control-warn-label">{warnText}</div>}
                {nextStep && <div className="text-sm mt-1" style={rt.label} data-testid="control-next-step">{nextStep}</div>}
              </div>
            </div>

            {skipped.length > 0 && (
              <div className="rounded-lg px-4 py-2 text-sm" style={rt.bannerWarning} data-testid="control-skipped">
                <span>{skipped.length === 1 ? '1 rule was skipped.' : `${formatCount(skipped.length)} rules were skipped.`}</span>{' '}
                <button
                  type="button"
                  onClick={() => setShowSkipped(o => !o)}
                  aria-expanded={showSkipped}
                  aria-controls="control-skipped-list"
                  className="swrl-focus underline font-medium"
                >
                  {showSkipped ? 'Hide' : 'See why'}
                </button>
                {showSkipped && (
                  <ul id="control-skipped-list" className="list-disc pl-5 pt-1 space-y-0.5 text-xs">
                    {skipped.map((s, i) => <li key={i}>{skippedListText(s)}</li>)}
                  </ul>
                )}
              </div>
            )}

            <div className="flex flex-wrap items-center gap-3">
              {result.success && (
                <button
                  type="button"
                  onClick={() => setConfirming(true)}
                  disabled={addOff || confirming}
                  className="swrl-focus px-4 py-2 text-sm font-semibold rounded-lg flex items-center gap-2"
                  style={btn(addOff || confirming, true)}
                  aria-describedby={addReason ? 'control-add-reason' : undefined}
                  data-testid="control-add"
                >
                  {stepBusy === 'add' ? <Loader2 size={14} className="animate-spin" aria-hidden="true" /> : <PlusCircle size={14} aria-hidden="true" />}
                  {addButtonLabel(planTotal)}
                </button>
              )}
              {result.success && planTotal > 0 && !isTest && (
                <LinkButton onClick={() => setListOpen(true)} disabled={isExecuting} testId="control-choose-add">Choose which to add</LinkButton>
              )}
              <span className="ml-auto">{detailsMenu}</span>
            </div>
            {addNotice && (
              <div className="rounded-lg px-4 py-2 text-sm" style={addNotice.ok ? rt.bannerSuccess : rt.bannerError} role={addNotice.ok ? 'status' : 'alert'} data-testid="control-add-notice">{addNotice.text}</div>
            )}
            {addReason && result.success && <p id="control-add-reason" className="text-xs" style={rt.muted} data-testid="control-add-reason">{addReason}</p>}

            {confirming && plan && (
              <div role="alertdialog" aria-label="Add inferred facts" className="p-3 rounded-lg text-sm space-y-2" style={rt.bannerWarning} data-testid="add-confirm">
                <p data-testid="add-confirm-text">{addConfirmText(plan, ADD_INFERRED_CAP)}</p>
                <label className="flex items-center gap-2">
                  <input type="checkbox" className="swrl-focus" checked={includeStructure} disabled={plan.relationships === 0} onChange={e => setIncludeStructure(e.target.checked)} />
                  <span>Also add class relationships ({formatCount(plan.relationships)}), such as one class being a kind of another</span>
                </label>
                <div className="flex gap-2">
                  <button onClick={handleAdd} className="swrl-focus px-3 py-1.5 rounded-lg font-medium" style={rt.buttonPrimary}>Yes, add them</button>
                  <button onClick={() => setConfirming(false)} className="swrl-focus px-3 py-1.5 rounded-lg" style={rt.button}>Cancel</button>
                </div>
              </div>
            )}
          </div>

          {result.success && (
            <div className="inline-flex rounded-lg overflow-hidden" role="group" aria-label="Which new facts to show" style={{ border: '1px solid var(--border)' }} data-testid="facts-view">
              <button
                type="button"
                onClick={() => setView('rules')}
                aria-pressed={view === 'rules'}
                className="swrl-focus px-4 py-1.5 text-sm font-medium"
                style={view === 'rules' ? rt.chipOn : rt.button}
                data-testid="facts-view-rules"
              >
                From your rules ({formatCount(factCount)})
              </button>
              <button
                type="button"
                onClick={() => {
                  setView('standard');
                  setListOpen(true);
                }}
                aria-pressed={view === 'standard'}
                className="swrl-focus px-4 py-1.5 text-sm font-medium"
                style={view === 'standard' ? rt.chipOn : rt.button}
                data-testid="facts-view-standard"
              >
                Standard reasoning ({formatCount(standardCount)})
              </button>
            </div>
          )}

          {renderFacts?.({ listOpen, section: view, openList: () => setListOpen(true) })}
        </section>
      )}

      {showTech && (
        <section aria-label="Technical details" className="rounded-lg p-3 space-y-3" style={rt.panel} data-testid="control-log-section">
          <div className="flex items-center justify-between">
            <h3 className="text-sm font-semibold">Technical details ({formatCount(log.lines.length)} {log.lines.length === 1 ? 'line' : 'lines'})</h3>
            <LinkButton onClick={() => setShowTech(false)} testId="control-details-toggle">Hide</LinkButton>
          </div>
          {(stepBusy === 'load' || log.checkSummary) && (
            <p className="text-sm" style={rt.label} data-testid="control-check-summary">{stepBusy === 'load' ? 'Checking…' : log.checkSummary}</p>
          )}
          {result && (typeof result.rounds === 'number' || versions.length > 0) && (
            <p className="text-xs" style={rt.label} data-testid="control-engine">
              {[
                typeof result.rounds === 'number' ? plural(result.rounds, 'round', 'rounds') : '',
                versions.length > 0 ? `Engine: ${versions.map(([k, val]) => `${k} ${val}`).join(', ')}` : '',
              ].filter(Boolean).join(' · ')}
            </p>
          )}
          <div>
            <div className="flex items-center justify-between text-xs mb-1" style={rt.label}>
              <span>Log</span>
              <LinkButton onClick={log.clearLog} disabled={log.lines.length === 0}>Clear log</LinkButton>
            </div>
            <pre
              ref={logRef}
              className="p-2 text-xs rounded-lg overflow-y-auto whitespace-pre-wrap font-mono"
              style={{ ...rt.code, height: '10rem' }}
              role="log"
              aria-live="polite"
              data-testid="control-log"
            >{log.lines.length === 0 ? 'Nothing has happened yet. Press Run rules to start.' : log.lines.join('\n')}</pre>
          </div>
        </section>
      )}
    </div>
  );
}

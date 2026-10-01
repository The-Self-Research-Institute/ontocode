import React, { useCallback, useEffect, useMemo, useState } from 'react';
import { FileText, Redo2, Undo2 } from 'lucide-react';
import { ChangeEntry, ListItem, OperationResponse, OperationTarget, RollbackEvent, SourceFilter } from './types';
import { redoTarget } from './targets';
import { buildTargetIndex, cardDomId, groupByDay, groupChangeSets, targetSetKey } from './groupChangeSets';
import { filterItems } from './matchChangeSets';
import { formatTime } from './timeFormat';
import { dispatchRollbackEvent } from './rollbackEvent';
import { useExpandedSets } from './useExpandedSets';
import { CHANGE_SET_CSS } from './changeSetStyles';
import ChangeSetCard from './ChangeSetCard';
import UndoPreviewDialog from './UndoPreviewDialog';

interface Props {
  projectId: string;
  entries: ChangeEntry[];
  searchQuery: string;
  sourceFilter: SourceFilter;
  onChanged: () => void;
  onOpenDetails: (changeId: string) => void;
}

function stillUndone(entries: ChangeEntry[], auditId: string): boolean {
  return entries.some(e => (e.reverted && e.revertedAuditId === auditId)
    || (e.subChanges || []).some(sc => sc.reverted && sc.revertedAuditId === auditId));
}

interface RollbackRowProps {
  event: RollbackEvent;
  entries: ChangeEntry[];
  onRedo: (t: OperationTarget) => void;
  onShow?: () => void;
}

function RollbackRow({ event, entries, onRedo, onShow }: RollbackRowProps) {
  const redo = event.direction === 'REDO';
  const canRedo = !redo && !!event.auditId && stillUndone(entries, event.auditId);
  const text = (
    <>
      {redo ? <Redo2 size={12} aria-hidden="true" /> : <Undo2 size={12} aria-hidden="true" />}
      <span className="cs-rollback-what">{event.description || (redo ? 'Redo' : 'Undo')}</span>
      {event.targetTitle && <span className="cs-rollback-target">· {event.targetTitle}</span>}
      <span>· {event.author} · {formatTime(event.timestamp)}</span>
    </>
  );
  return (
    <div className="cs-rollback-row">
      {onShow
        ? <button type="button" className="cs-rollback-link" onClick={onShow} title="Show this change">{text}</button>
        : <span className="cs-rollback-text">{text}</span>}
      {canRedo && (
        <button type="button" className="cs-btn is-small cs-rollback-redo"
          onClick={() => onRedo(redoTarget(event.auditId as string, event.description || 'this undo', {}))}>
          <Redo2 size={12} aria-hidden="true" />Redo
        </button>
      )}
    </div>
  );
}

function EmptyState({ filtered }: { filtered: boolean }) {
  return (
    <div className="cs-empty">
      <FileText size={40} style={{ margin: '0 auto 8px', opacity: 0.5 }} aria-hidden="true" />
      <p>{filtered ? 'No changes match your filters' : 'No changes found'}</p>
    </div>
  );
}

const ChangeSetList: React.FC<Props> = ({ projectId, entries, searchQuery, sourceFilter, onChanged, onOpenDetails }) => {
  const items = useMemo(() => groupChangeSets(entries), [entries]);
  const filtered = useMemo(() => filterItems(items, searchQuery, sourceFilter), [items, searchQuery, sourceFilter]);
  const sections = useMemo(() => groupByDay(filtered.items), [filtered]);
  const targetIndex = useMemo(() => buildTargetIndex(filtered.items), [filtered]);
  const newestKey = items.find(i => i.kind === 'set')?.key ?? null;
  const { isExpanded, toggle, expand } = useExpandedSets(projectId, newestKey, searchQuery, filtered.matchedKeys);
  const [flashKey, setFlashKey] = useState<string | null>(null);
  const showSet = useCallback((key: string) => {
    expand(key);
    setFlashKey(key);
  }, [expand]);
  useEffect(() => {
    if (!flashKey) return;
    const frame = requestAnimationFrame(() => {
      document.getElementById(cardDomId(flashKey))?.scrollIntoView({ behavior: 'smooth', block: 'start' });
    });
    const timer = setTimeout(() => setFlashKey(null), 1800);
    return () => {
      cancelAnimationFrame(frame);
      clearTimeout(timer);
    };
  }, [flashKey]);
  const [target, setTarget] = useState<OperationTarget | null>(null);
  const close = useCallback(() => setTarget(null), []);
  const handleApplied = useCallback((applied: OperationTarget, data: OperationResponse) => {
    setTarget(null);
    dispatchRollbackEvent(projectId, applied, data);
    onChanged();
  }, [projectId, onChanged]);

  const renderItem = (item: ListItem) => {
    if (item.kind === 'rollback') {
      const key = targetSetKey(item.event, targetIndex);
      return <RollbackRow key={item.key} event={item.event} entries={entries} onRedo={setTarget}
        onShow={key ? () => showSet(key) : undefined} />;
    }
    return <ChangeSetCard key={item.key} set={item.set} expanded={isExpanded(item.key)} onToggle={() => toggle(item.key)}
      onUndo={setTarget} onDetails={onOpenDetails} highlighted={flashKey === item.key} />;
  };

  return (
    <div className="cs-root">
      <style>{CHANGE_SET_CSS}</style>
      {sections.length === 0 && <EmptyState filtered={items.length > 0} />}
      {sections.map(section => (
        <section key={section.label} aria-label={section.label}>
          <h3 className="cs-day">{section.label}</h3>
          {section.items.map(renderItem)}
        </section>
      ))}
      {target && (
        <UndoPreviewDialog projectId={projectId} target={target} onClose={close} onApplied={handleApplied} onRefresh={onChanged} />
      )}
    </div>
  );
};

export default ChangeSetList;

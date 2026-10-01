import React, { useState } from 'react';
import { Sparkles } from 'lucide-react';
import { ChangeSet, OperationTarget, RollbackEvent } from './types';
import { cardDomId, ENTITY_CAP, flattensEntity, setSubtitle, undoneNote } from './groupChangeSets';
import { setTarget } from './targets';
import { formatTime } from './timeFormat';
import ChangeSetEntityRow from './ChangeSetEntityRow';
import ChangeSetPropertyRow from './ChangeSetPropertyRow';
import RowTools from './RowTools';

interface Props {
  set: ChangeSet;
  expanded: boolean;
  onToggle: () => void;
  onUndo: (target: OperationTarget) => void;
  onDetails: (changeId: string) => void;
  highlighted?: boolean;
}

export function SourceBadge({ ai }: { ai: boolean }) {
  if (!ai) return <span className="cs-badge">Manual edit</span>;
  return <span className="cs-badge is-ai"><Sparkles size={12} aria-hidden="true" />AI edit</span>;
}

export function historyLine(event: RollbackEvent): string {
  const verb = event.direction === 'REDO' ? 'Redo' : 'Undo';
  const what = event.description ? ` · ${event.description}` : '';
  return `${verb} by ${event.author} · ${formatTime(event.timestamp)}${what}`;
}

function SetTitle({ set }: { set: ChangeSet }) {
  if (!set.heading) return <span className="cs-title">{set.title}</span>;
  return (
    <span className="cs-title">
      {set.heading.verb} <span className="cs-name">{set.heading.name}</span>
    </span>
  );
}

function HeaderActions({ set, onUndo, onDetails }: Pick<Props, 'set' | 'onUndo' | 'onDetails'>) {
  const redo = set.fullyUndone ? setTarget(set, 'REDO') : null;
  const undo = !set.fullyUndone && set.remaining > 0 ? setTarget(set, 'UNDO') : null;
  const single = set.entries.length === 1 ? set.entries[0] : null;
  return (
    <div className="cs-actions">
      {single && (
        <RowTools label={set.title} canUndo={false} onUndo={() => undefined} alwaysVisible
          onDetails={() => onDetails(single.id)} commentCount={single.commentCount} />
      )}
      {undo && (
        <button type="button" className="cs-btn" onClick={() => onUndo(undo)}>
          {set.remaining > 1 ? `Undo all ${set.remaining}` : 'Undo'}
        </button>
      )}
      {redo && <button type="button" className="cs-btn" onClick={() => onUndo(redo)}>Redo</button>}
    </div>
  );
}

function CardBody({ set, onUndo, onDetails }: Pick<Props, 'set' | 'onUndo' | 'onDetails'>) {
  const [showAll, setShowAll] = useState(false);
  if (flattensEntity(set)) {
    const entity = set.entities[0];
    return (
      <div className="cs-guide">
        {entity.properties.map(p => (
          <ChangeSetPropertyRow key={p.key} entity={entity} property={p} parentUndone={entity.undone} onUndo={onUndo} />
        ))}
      </div>
    );
  }
  const visible = showAll ? set.entities : set.entities.slice(0, ENTITY_CAP);
  const hidden = set.entities.length - visible.length;
  return (
    <div className="cs-guide">
      {visible.map(entity => <ChangeSetEntityRow key={entity.entry.id} entity={entity} onUndo={onUndo} onDetails={onDetails} />)}
      {hidden > 0 && <button type="button" className="cs-link" onClick={() => setShowAll(true)}>Show {hidden} more</button>}
    </div>
  );
}

const ChangeSetCard: React.FC<Props> = ({ set, expanded, onToggle, onUndo, onDetails, highlighted }) => {
  const bodyId = `cs-body-${set.key.replace(/[^a-zA-Z0-9_-]/g, '_')}`;
  return (
    <div id={cardDomId(set.key)} className={`cs-card${set.fullyUndone ? ' is-undone' : ''}${highlighted ? ' is-flash' : ''}`}>
      <div className="cs-head">
        <button type="button" className="cs-toggle" aria-expanded={expanded} aria-controls={expanded ? bodyId : undefined} onClick={onToggle}>
          <span className="cs-chevron" aria-hidden="true">{expanded ? '▾' : '▸'}</span>
          <span className="cs-head-text">
            <span className="cs-title-line"><SourceBadge ai={set.source === 'AI'} /><SetTitle set={set} /></span>
            {set.heading?.detail && <span className="cs-sub">{set.heading.detail}</span>}
            <span className="cs-sub">{setSubtitle(set)}</span>
            {set.fullyUndone && <span className="cs-sub">{undoneNote(set.undoneBy, set.undoneAt)}</span>}
          </span>
        </button>
        <HeaderActions set={set} onUndo={onUndo} onDetails={onDetails} />
      </div>
      {expanded && (
        <div className="cs-body" id={bodyId}>
          <CardBody set={set} onUndo={onUndo} onDetails={onDetails} />
          {set.history.length > 0 && (
            <div className="cs-history">{set.history.map(h => <span key={h.id}>{historyLine(h)}</span>)}</div>
          )}
        </div>
      )}
    </div>
  );
};

export default ChangeSetCard;

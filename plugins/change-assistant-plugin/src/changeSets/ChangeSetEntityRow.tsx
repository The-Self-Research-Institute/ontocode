import React from 'react';
import { EntityRow, OperationTarget } from './types';
import { entityRedoTarget, entityTarget } from './targets';
import { isSingleLine, undoneNote } from './groupChangeSets';
import ChangeSetPropertyRow from './ChangeSetPropertyRow';
import RowTools from './RowTools';

interface Props {
  entity: EntityRow;
  onUndo: (target: OperationTarget) => void;
  onDetails: (changeId: string) => void;
  showDetails?: boolean;
}

const ChangeSetEntityRow: React.FC<Props> = ({ entity, onUndo, onDetails, showDetails = true }) => {
  if (isSingleLine(entity)) {
    return (
      <div className="cs-entity">
        <ChangeSetPropertyRow entity={entity} property={entity.properties[0]} parentUndone={entity.undone}
          onUndo={onUndo} onDetails={onDetails} singleLine />
      </div>
    );
  }
  const target = entity.undone ? null : entityTarget(entity);
  const redo = entity.undone ? entityRedoTarget(entity) : null;
  const hasConflict = (entity.entry.conflicts?.length || 0) > 0;
  return (
    <div className="cs-entity">
      <div className={`cs-row${entity.undone ? ' is-undone' : ''}`}>
        <div className="cs-row-main">
          <span className="cs-label" title={entity.entry.entityUri}>{entity.label}</span>
          <span className="cs-badge is-quiet">{entity.badge}</span>
          {hasConflict && <span className="cs-badge">Conflict</span>}
        </div>
        {entity.undone && <span className="cs-note">{undoneNote(entity.undoneBy, entity.undoneAt)}</span>}
        <RowTools label={entity.label} canUndo={!!target} onUndo={() => target && onUndo(target)}
          onDetails={showDetails ? () => onDetails(entity.entry.id) : undefined} commentCount={entity.entry.commentCount}
          onRedo={redo ? () => onUndo(redo) : undefined} />
      </div>
      {entity.properties.length > 0 && (
        <div className="cs-guide">
          {entity.properties.map(p => (
            <ChangeSetPropertyRow key={p.key} entity={entity} property={p} parentUndone={entity.undone} onUndo={onUndo} />
          ))}
        </div>
      )}
    </div>
  );
};

export default ChangeSetEntityRow;

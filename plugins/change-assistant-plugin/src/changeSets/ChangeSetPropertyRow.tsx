import React from 'react';
import { EntityRow, OperationTarget, PropertyRow } from './types';
import { propertyRedoTarget, propertyTarget } from './targets';
import { undoneNote } from './groupChangeSets';
import RowTools from './RowTools';

interface Props {
  entity: EntityRow;
  property: PropertyRow;
  parentUndone: boolean;
  onUndo: (target: OperationTarget) => void;
  onDetails?: (changeId: string) => void;
  singleLine?: boolean;
}

const ChangeSetPropertyRow: React.FC<Props> = ({ entity, property, parentUndone, onUndo, onDetails, singleLine }) => {
  const undone = property.undone || parentUndone;
  const target = undone ? null : propertyTarget(entity, property);
  const undoLabel = singleLine ? entity.label : `${property.label} on ${entity.label}`;
  const redo = property.undone && (!parentUndone || singleLine) ? propertyRedoTarget(entity, property, undoLabel) : null;
  return (
    <div className={`cs-row${undone ? ' is-undone' : ''}`}>
      <div className="cs-row-main">
        {singleLine && <span className="cs-badge is-quiet">{entity.badge}</span>}
        <span className="cs-prop-name">{property.label}</span>
        {singleLine && <><span className="cs-sep">·</span><span className="cs-label">{entity.label}</span></>}
        <span className={`cs-sign ${property.addition ? 'is-add' : 'is-del'}`} aria-label={property.addition ? 'Added' : 'Removed'}>
          {property.addition ? '+' : '−'}
        </span>
        <span className="cs-value" title={property.rawValue}>{property.value || '(empty)'}</span>
      </div>
      {undone && property.undone && <span className="cs-note">{undoneNote(property.undoneBy, property.undoneAt)}</span>}
      <RowTools
        label={undoLabel}
        canUndo={!!target}
        onUndo={() => target && onUndo(target)}
        onDetails={singleLine && onDetails ? () => onDetails(entity.entry.id) : undefined}
        commentCount={singleLine ? entity.entry.commentCount : undefined}
        onRedo={redo ? () => onUndo(redo) : undefined}
      />
    </div>
  );
};

export default ChangeSetPropertyRow;

import React from 'react';
import { Eye, MessageSquare, Redo2, Undo2 } from 'lucide-react';

interface Props {
  label: string;
  canUndo: boolean;
  onUndo: () => void;
  onDetails?: () => void;
  commentCount?: number;
  alwaysVisible?: boolean;
  onRedo?: () => void;
}

const RowTools: React.FC<Props> = ({ label, canUndo, onUndo, onDetails, commentCount, alwaysVisible, onRedo }) => {
  const visible = alwaysVisible ? ' is-visible' : '';
  return (
    <span className="cs-row-tools">
      {onDetails && (
        <button type="button" className={`cs-icon-btn${visible}`} onClick={onDetails}
          aria-label={`Details and comments for ${label}`} title="Details and comments">
          {commentCount ? <><MessageSquare size={13} />{commentCount}</> : <Eye size={14} />}
        </button>
      )}
      {onRedo && (
        <button type="button" className="cs-btn is-small" onClick={onRedo} aria-label={`Redo ${label}`} title="Put this back">
          <Redo2 size={12} aria-hidden="true" />Redo
        </button>
      )}
      {canUndo && (
        <button type="button" className={`cs-icon-btn${visible}`} onClick={onUndo} aria-label={`Undo ${label}`} title="Undo">
          <Undo2 size={14} />
        </button>
      )}
    </span>
  );
};

export default RowTools;

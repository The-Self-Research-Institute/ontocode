import React from 'react';
import { Loader2, Play } from 'lucide-react';
import { PopMenu } from './ui/PopMenu';

const MORE_ACTIONS_CLASS = 'px-4 py-2 text-sm font-medium text-gray-600 bg-gray-100 rounded-lg hover:bg-gray-200';
const TRY_IT_CLASS = 'swrl-focus px-4 py-2 text-sm font-medium text-purple-600 bg-purple-50 rounded-lg hover:bg-purple-100 disabled:opacity-50 flex items-center gap-2';

interface RuleMoreActionsProps {
  onClone: () => void;
  onDelete?: () => void;
  cloneDisabled?: boolean;
  deleteDisabled?: boolean;
}

export const RuleMoreActions: React.FC<RuleMoreActionsProps> = ({ onClone, onDelete, cloneDisabled = false, deleteDisabled = false }) => (
  <PopMenu
    testId="more-actions"
    openUp
    align="right"
    width="12rem"
    label="More actions"
    className={MORE_ACTIONS_CLASS}
    items={[
      { id: 'clone', label: 'Clone', description: 'Make a copy under a new name', onClick: onClone, disabled: cloneDisabled },
      ...(onDelete ? [{ id: 'delete', label: 'Delete', danger: true, onClick: onDelete, disabled: deleteDisabled }] : []),
    ]}
  />
);

interface TryItButtonProps {
  onClick: () => void;
  disabled: boolean;
  busy: boolean;
}

export const TryItButton: React.FC<TryItButtonProps> = ({ onClick, disabled, busy }) => (
  <button onClick={onClick} disabled={disabled} title="Run only this rule to see what it finds. Nothing is saved." className={TRY_IT_CLASS}>
    {busy ? <Loader2 size={14} className="animate-spin" /> : <Play size={14} />} {busy ? 'Trying…' : 'Try it'}
  </button>
);

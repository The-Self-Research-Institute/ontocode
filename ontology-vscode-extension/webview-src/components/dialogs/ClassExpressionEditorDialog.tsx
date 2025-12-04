import React from 'react';
import ClassExpressionDialog from './ClassExpressionDialog';
import type { RestrictionData } from './ClassExpressionDialog';
import type { TreeNode, Property } from '../../types';

interface ClassExpressionEditorDialogProps {
  isOpen: boolean;
  onClose: () => void;
  onConfirm: (expression: string, restrictionData?: RestrictionData) => void;
  title?: string;
  initialValue?: string;
  projectId: string;
  classHierarchy?: TreeNode[];
  properties?: Property[];
  dataProperties?: Property[];
  onToggleNode?: (nodeId: string) => void;
  externalExpandedNodes?: Set<string>;
}

/**
 * ClassExpressionEditorDialog - Wrapper around ClassExpressionDialog
 *
 * This is a compatibility wrapper that maintains the old API while using
 * the unified ClassExpressionDialog implementation under the hood.
 *
 * @deprecated Consider using ClassExpressionDialog directly for new code
 */
const ClassExpressionEditorDialog: React.FC<ClassExpressionEditorDialogProps> = ({
  isOpen,
  onClose,
  onConfirm,
  title = "Edit Equivalent Class Expression",
  initialValue = "",
  projectId,
  classHierarchy = [],
  properties = [],
  dataProperties = [],
  onToggleNode,
  externalExpandedNodes = new Set()
}) => {
  // Convert Set to Array for ClassExpressionDialog
  const expandedNodesArray = Array.from(externalExpandedNodes);

  return (
    <ClassExpressionDialog
      isOpen={isOpen}
      onClose={onClose}
      onConfirm={onConfirm}
      title={title}
      initialValue={initialValue}
      projectId={projectId}
      classHierarchy={classHierarchy}
      objectProperties={properties}
      dataProperties={dataProperties}
      onToggleNode={onToggleNode}
      expandedNodes={expandedNodesArray}
    />
  );
};

export default ClassExpressionEditorDialog;

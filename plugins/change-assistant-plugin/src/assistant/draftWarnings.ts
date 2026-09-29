import { ChangeWarning } from './types';

function classNameWarnings(label: string): ChangeWarning[] {
  const warnings: ChangeWarning[] = [];
  if (label[0] !== label[0].toUpperCase()) {
    warnings.push({
      type: 'naming',
      severity: 'warning',
      message: 'Class names should start with an uppercase letter',
      suggestion: `Consider renaming to "${label[0].toUpperCase()}${label.slice(1)}"`
    });
  }
  if (label.includes(' ')) {
    warnings.push({
      type: 'naming',
      severity: 'info',
      message: 'Class names typically use CamelCase without spaces',
      suggestion: `Consider using "${label.replace(/\s+/g, '')}"`
    });
  }
  return warnings;
}

function propertyNameWarnings(label: string): ChangeWarning[] {
  if (label[0] === label[0].toLowerCase()) return [];
  return [{
    type: 'naming',
    severity: 'warning',
    message: 'Property names should start with a lowercase letter',
    suggestion: `Consider renaming to "${label[0].toLowerCase()}${label.slice(1)}"`
  }];
}

export function generateWarnings(draft: any): ChangeWarning[] {
  const warnings: ChangeWarning[] = [];
  const opType = draft.operationType || '';
  const label = draft.operationData?.label || '';
  if (label && opType.includes('Class')) warnings.push(...classNameWarnings(label));
  if (label && opType.includes('Property')) warnings.push(...propertyNameWarnings(label));
  if (opType === 'deleteClass') {
    warnings.push({
      type: 'structure',
      severity: 'warning',
      message: 'Deleting a class may affect dependent axioms and individuals',
      suggestion: 'Review dependencies before confirming deletion'
    });
  }
  return warnings;
}

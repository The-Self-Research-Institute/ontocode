import type { ChangeStatus } from './types';

export const STATUS_FILTERS: Array<{ value: ChangeStatus | 'all'; label: string }> = [
  { value: 'all', label: 'All Status' },
  { value: 'reverted', label: 'Reverted' },
  { value: 'conflicted', label: 'Conflicted' }
];

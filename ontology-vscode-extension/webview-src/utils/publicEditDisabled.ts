export const PUBLIC_EDIT_DISABLED_CODE = 'PUBLIC_EDIT_DISABLED';
export const PUBLIC_EDIT_DISABLED_EVENT = 'ontocode:public-edit-disabled';

export function isPublicEditDisabled(status: number | undefined, data: any): boolean {
  return status === 403 && data?.code === PUBLIC_EDIT_DISABLED_CODE;
}

export function flagPublicEditDisabled(err: any): void {
  err.reason = 'draftRequired';
  err.publicEditNotified = true;
  if (typeof window !== 'undefined') {
    window.dispatchEvent(new CustomEvent(PUBLIC_EDIT_DISABLED_EVENT));
  }
}

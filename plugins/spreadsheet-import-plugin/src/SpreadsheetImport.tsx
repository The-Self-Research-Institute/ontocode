import React from 'react';
import SpreadsheetImportPanel from './components/spreadsheet-import/SpreadsheetImportPanel';
import { setApiClient, type HostApiClient } from './services/apiClient';

export interface SpreadsheetImportProps {
  projectId: string;
  context?: { apiClient?: HostApiClient };
  userId?: string;
  username?: string;
  draftMode?: boolean;
  onImported?: () => void;
  onViewOnlyAction?: () => void;
  onDraftRequired?: () => void;
  isDraftEditor?: boolean;
}

const SpreadsheetImport: React.FC<SpreadsheetImportProps> = ({
  projectId,
  context,
  userId,
  username,
  draftMode,
  onImported,
  onViewOnlyAction,
  onDraftRequired,
  isDraftEditor,
}) => {
  setApiClient(context?.apiClient);

  return (
    <SpreadsheetImportPanel
      projectId={projectId}
      userId={userId}
      username={username}
      draftMode={draftMode}
      onImported={onImported}
      onViewOnlyAction={onViewOnlyAction}
      onDraftRequired={onDraftRequired}
      isDraftEditor={isDraftEditor}
    />
  );
};

export default SpreadsheetImport;

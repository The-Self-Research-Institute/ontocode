import React from 'react';
import { AlertCircle, FileText, X } from 'lucide-react';

interface DuplicateFileDialogProps {
  isOpen: boolean;
  fileName: string;
  existingFileInfo?: {
    projectId: string;
    status: string;
    lastUpdated: string;
  };
  onReplace: () => void;
  onCreateCopy: () => void;
  onCancel: () => void;
}

export const DuplicateFileDialog: React.FC<DuplicateFileDialogProps> = ({
  isOpen,
  fileName,
  existingFileInfo,
  onReplace,
  onCreateCopy,
  onCancel
}) => {
  if (!isOpen) return null;

  return (
    <div className="fixed inset-0 z-[70] flex items-center justify-center">
      {/* Backdrop */}
      <div 
        className="absolute inset-0 bg-black/50 backdrop-blur-sm"
        onClick={onCancel}
      />
      
      {/* Dialog */}
      <div className="relative bg-white dark:bg-gray-800 rounded-lg shadow-xl max-w-md w-full mx-4 p-6 z-[71]">
        {/* Header */}
        <div className="flex items-start justify-between mb-4">
          <div className="flex items-center gap-3">
            <div className="p-2 bg-amber-100 dark:bg-amber-900/30 rounded-lg">
              <AlertCircle className="w-6 h-6 text-amber-600 dark:text-amber-400" />
            </div>
            <div>
              <h3 className="text-lg font-semibold text-gray-900 dark:text-white">
                Duplicate File Detected
              </h3>
              <p className="text-sm text-gray-500 dark:text-gray-400">
                A file with this name already exists
              </p>
            </div>
          </div>
          <button
            onClick={onCancel}
            className="text-gray-400 hover:text-gray-600 dark:hover:text-gray-300"
          >
            <X className="w-5 h-5" />
          </button>
        </div>

        {/* File Info */}
        <div className="mb-6 p-4 bg-gray-50 dark:bg-gray-900/50 rounded-lg border border-gray-200 dark:border-gray-700">
          <div className="flex items-center gap-2 mb-2">
            <FileText className="w-4 h-4 text-gray-500" />
            <span className="font-medium text-gray-900 dark:text-white">{fileName}</span>
          </div>
          {existingFileInfo && (
            <div className="text-sm text-gray-600 dark:text-gray-400 space-y-1">
              <div>Status: <span className="font-medium">{existingFileInfo.status}</span></div>
              {existingFileInfo.lastUpdated && (
                <div>Last Updated: <span className="font-medium">
                  {new Date(existingFileInfo.lastUpdated).toLocaleString()}
                </span></div>
              )}
            </div>
          )}
        </div>

        {/* Message */}
        <p className="text-gray-700 dark:text-gray-300 mb-6">
          Would you like to replace the existing file or create a new copy?
        </p>

        {/* Actions */}
        <div className="flex flex-col gap-2">
          <button
            onClick={onReplace}
            className="w-full px-4 py-3 bg-blue-600 hover:bg-blue-700 text-white font-medium rounded-lg transition-colors flex items-center justify-center gap-2"
          >
            <svg className="w-5 h-5" fill="none" stroke="currentColor" viewBox="0 0 24 24">
              <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M4 4v5h.582m15.356 2A8.001 8.001 0 004.582 9m0 0H9m11 11v-5h-.581m0 0a8.003 8.003 0 01-15.357-2m15.357 2H15" />
            </svg>
            Replace Existing File
          </button>
          
          <button
            onClick={onCreateCopy}
            className="w-full px-4 py-3 bg-green-600 hover:bg-green-700 text-white font-medium rounded-lg transition-colors flex items-center justify-center gap-2"
          >
            <svg className="w-5 h-5" fill="none" stroke="currentColor" viewBox="0 0 24 24">
              <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M8 16H6a2 2 0 01-2-2V6a2 2 0 012-2h8a2 2 0 012 2v2m-6 12h8a2 2 0 002-2v-8a2 2 0 00-2-2h-8a2 2 0 00-2 2v8a2 2 0 002 2z" />
            </svg>
            Create a Copy
          </button>
          
          <button
            onClick={onCancel}
            className="w-full px-4 py-3 bg-gray-200 hover:bg-gray-300 dark:bg-gray-700 dark:hover:bg-gray-600 text-gray-700 dark:text-gray-300 font-medium rounded-lg transition-colors"
          >
            Cancel
          </button>
        </div>

        {/* Info Note */}
        <div className="mt-4 p-3 bg-blue-50 dark:bg-blue-900/20 rounded-lg border border-blue-200 dark:border-blue-800">
          <p className="text-xs text-blue-800 dark:text-blue-300">
            <strong>Replace:</strong> Overwrites the existing file with the new content.<br/>
            <strong>Create Copy:</strong> Saves as a new file with a suffix (e.g., "ontology-copy-1.owl").
          </p>
        </div>
      </div>
    </div>
  );
};

export default DuplicateFileDialog;

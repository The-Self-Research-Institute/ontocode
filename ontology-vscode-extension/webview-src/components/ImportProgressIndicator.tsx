import React, { useState, useRef, useEffect } from 'react';
import { Loader2, CheckCircle, XCircle, Upload, AlertCircle, GripVertical } from 'lucide-react';
import { sanitizeImportMessage } from '../utils/importStatusText';

export interface ImportStatus {
  type: 'IMPORT_STARTED' | 'IMPORT_PROGRESS' | 'IMPORT_COMPLETED' | 'IMPORT_FAILED' | 'IDLE';
  status: string;
  statusMessage: string;
  filename?: string;
  progress?: number;
  metadata?: any;
}

interface ImportProgressIndicatorProps {
  importStatus: ImportStatus;
  onClick?: () => void;
}

export const ImportProgressIndicator: React.FC<ImportProgressIndicatorProps> = ({
  importStatus,
  onClick
}) => {
  const [showCompleted, setShowCompleted] = useState(false);
  const hideTimerRef = useRef<ReturnType<typeof setTimeout> | null>(null);

  useEffect(() => {
    if (importStatus.type === 'IMPORT_COMPLETED') {
      setShowCompleted(true);
      hideTimerRef.current = setTimeout(() => setShowCompleted(false), 3000);
    } else if (importStatus.type !== 'IDLE') {
      setShowCompleted(false);
    }
    return () => { if (hideTimerRef.current) clearTimeout(hideTimerRef.current); };
  }, [importStatus.type]);

  if (importStatus.type === 'IDLE') {
    return null;
  }
  if (importStatus.status === 'COMPLETED' && !showCompleted) {
    return null;
  }

  const getStatusIcon = () => {
    switch (importStatus.type) {
      case 'IMPORT_STARTED':
      case 'IMPORT_PROGRESS':
        return <Loader2 size={16} className="animate-spin text-blue-500" />;
      case 'IMPORT_COMPLETED':
        return <CheckCircle size={16} className="text-green-500" />;
      case 'IMPORT_FAILED':
        return <XCircle size={16} className="text-red-500" />;
      default:
        return <Upload size={16} className="text-gray-400" />;
    }
  };

  const getStatusColor = () => {
    switch (importStatus.type) {
      case 'IMPORT_STARTED':
      case 'IMPORT_PROGRESS':
        return 'bg-blue-50 border-blue-200 text-blue-800';
      case 'IMPORT_COMPLETED':
        return 'bg-green-50 border-green-200 text-green-800';
      case 'IMPORT_FAILED':
        return 'bg-red-50 border-red-200 text-red-800';
      default:
        return 'bg-gray-50 border-gray-200 text-gray-800';
    }
  };

  const getStatusText = () => {
    switch (importStatus.type) {
      case 'IMPORT_STARTED':
        return 'Starting import...';
      case 'IMPORT_PROGRESS':
        return sanitizeImportMessage(importStatus.statusMessage) || 'Importing...';
      case 'IMPORT_COMPLETED':
        return 'Import completed';
      case 'IMPORT_FAILED':
        return 'Import failed';
      default:
        return sanitizeImportMessage(importStatus.statusMessage) || 'Processing...';
    }
  };

  return (
    <div
      className={`flex items-center gap-2 px-3 py-1.5 rounded-md border ${getStatusColor()} cursor-pointer hover:opacity-80 transition-opacity`}
      onClick={onClick}
      title={sanitizeImportMessage(importStatus.statusMessage)}
    >
      {getStatusIcon()}
      <div className="flex flex-col">
        <span className="text-xs font-medium">{getStatusText()}</span>
        {importStatus.filename && (
          <span className="text-xs opacity-70">{importStatus.filename}</span>
        )}
        {importStatus.progress !== undefined && (
          <div className="w-32 h-1 bg-white bg-opacity-30 rounded-full overflow-hidden mt-1">
            <div
              className="h-full bg-blue-500 transition-all duration-300"
              style={{ width: `${importStatus.progress}%` }}
            />
          </div>
        )}
      </div>
    </div>
  );
};

// Compact version for menu bar
export const ImportProgressBadge: React.FC<ImportProgressIndicatorProps> = ({
  importStatus,
  onClick
}) => {

  if (importStatus.type === 'IDLE' || importStatus.status === 'COMPLETED') {
    return null;
  }

  const getIcon = () => {
    switch (importStatus.type) {
      case 'IMPORT_STARTED':
      case 'IMPORT_PROGRESS':
        return <Loader2 size={18} className="animate-spin" />;
      case 'IMPORT_COMPLETED':
        return <CheckCircle size={18} />;
      case 'IMPORT_FAILED':
        return <AlertCircle size={18} />;
      default:
        return <Upload size={18} />;
    }
  };

  const getColor = () => {
    switch (importStatus.type) {
      case 'IMPORT_STARTED':
      case 'IMPORT_PROGRESS':
        return 'text-blue-500 bg-blue-50';
      case 'IMPORT_COMPLETED':
        return 'text-green-500 bg-green-50';
      case 'IMPORT_FAILED':
        return 'text-red-500 bg-red-50';
      default:
        return 'text-gray-500 bg-gray-50';
    }
  };

  return (
    <button
      onClick={onClick}
      className={`relative p-2 rounded-md hover:bg-opacity-80 transition-colors ${getColor()}`}
      title={sanitizeImportMessage(importStatus.statusMessage)}
    >
      {getIcon()}
      {importStatus.type === 'IMPORT_PROGRESS' && importStatus.progress !== undefined && (
        <span className="absolute -top-1 -right-1 bg-blue-500 text-white text-xs rounded-full w-5 h-5 flex items-center justify-center font-bold">
          {Math.round(importStatus.progress)}
        </span>
      )}
    </button>
  );
};

// Draggable toast-style notification
export const ImportProgressToast: React.FC<{
  importStatus: ImportStatus;
  onDismiss?: () => void;
  visible?: boolean;
}> = ({ importStatus, onDismiss, visible = true }) => {
  const [position, setPosition] = useState({ x: 16, y: window.innerHeight - 200 });
  const [isDragging, setIsDragging] = useState(false);
  const [dragOffset, setDragOffset] = useState({ x: 0, y: 0 });
  const toastRef = useRef<HTMLDivElement>(null);

  useEffect(() => {
    if (!isDragging) return;

    const handleMouseMove = (e: MouseEvent) => {
      setPosition({
        x: e.clientX - dragOffset.x,
        y: e.clientY - dragOffset.y
      });
    };

    const handleMouseUp = () => {
      setIsDragging(false);
    };

    document.addEventListener('mousemove', handleMouseMove);
    document.addEventListener('mouseup', handleMouseUp);

    return () => {
      document.removeEventListener('mousemove', handleMouseMove);
      document.removeEventListener('mouseup', handleMouseUp);
    };
  }, [isDragging, dragOffset]);

  const handleMouseDown = (e: React.MouseEvent) => {
    if (toastRef.current) {
      const rect = toastRef.current.getBoundingClientRect();
      setDragOffset({
        x: e.clientX - rect.left,
        y: e.clientY - rect.top
      });
      setIsDragging(true);
    }
  };

  if (importStatus.type === 'IDLE' || !visible) {
    return null;
  }

  const getIcon = () => {
    switch (importStatus.type) {
      case 'IMPORT_STARTED':
      case 'IMPORT_PROGRESS':
        return <Loader2 size={20} className="animate-spin text-blue-500" />;
      case 'IMPORT_COMPLETED':
        return <CheckCircle size={20} className="text-green-500" />;
      case 'IMPORT_FAILED':
        return <XCircle size={20} className="text-red-500" />;
      default:
        return <Upload size={20} className="text-gray-500" />;
    }
  };

  return (
    <div
      ref={toastRef}
      className="fixed z-50"
      style={{
        left: `${position.x}px`,
        top: `${position.y}px`,
        cursor: isDragging ? 'grabbing' : 'grab'
      }}
    >
      <div className="bg-white rounded-lg shadow-2xl border border-gray-200 min-w-[300px] max-w-[400px]">
        {/* Drag handle */}
        <div
          className="flex items-center gap-2 px-3 py-2 border-b border-gray-200 bg-gray-50 rounded-t-lg cursor-grab active:cursor-grabbing"
          onMouseDown={handleMouseDown}
        >
          <GripVertical size={16} className="text-gray-400" />
          <span className="text-xs font-medium text-gray-600">Import Progress</span>
          {onDismiss && importStatus.type !== 'IMPORT_PROGRESS' && (
            <button
              onClick={onDismiss}
              className="ml-auto text-gray-400 hover:text-gray-600"
            >
              ×
            </button>
          )}
        </div>

        {/* Content */}
        <div className="p-4">
          <div className="flex items-start gap-3">
            <div className="flex-shrink-0 mt-0.5">
              {getIcon()}
            </div>
            <div className="flex-1">
              <h4 className="text-sm font-semibold text-gray-900 mb-1">
                {importStatus.type === 'IMPORT_STARTED' && 'Import Started'}
                {importStatus.type === 'IMPORT_PROGRESS' && 'Importing...'}
                {importStatus.type === 'IMPORT_COMPLETED' && 'Import Completed'}
                {importStatus.type === 'IMPORT_FAILED' && 'Import Failed'}
              </h4>
              <p className="text-xs text-gray-600 mb-2">
                {importStatus.statusMessage}
              </p>
              {importStatus.filename && (
                <p className="text-xs text-gray-500 mb-2">
                  File: {importStatus.filename}
                </p>
              )}
              {importStatus.metadata && importStatus.type === 'IMPORT_COMPLETED' && (
                <div className="text-xs text-gray-500 space-y-1">
                  {importStatus.metadata.classCount !== undefined && (
                    <div>Classes: {importStatus.metadata.classCount}</div>
                  )}
                  {importStatus.metadata.tripleCount !== undefined && (
                    <div>Triples: {importStatus.metadata.tripleCount}</div>
                  )}
                </div>
              )}
              {importStatus.progress !== undefined && importStatus.type === 'IMPORT_PROGRESS' && (
                <div className="mt-2">
                  <div className="flex items-center justify-between text-xs text-gray-600 mb-1">
                    <span>Progress</span>
                    <span>{Math.round(importStatus.progress)}%</span>
                  </div>
                  <div className="w-full h-2 bg-gray-200 rounded-full overflow-hidden">
                    <div
                      className="h-full bg-blue-500 transition-all duration-300"
                      style={{ width: `${importStatus.progress}%` }}
                    />
                  </div>
                </div>
              )}
            </div>
          </div>
        </div>
      </div>
    </div>
  );
};

export default ImportProgressIndicator;

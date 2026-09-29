import React from 'react';
import { AlertTriangle, Info, Lightbulb, XCircle } from 'lucide-react';
import { ChangeWarning } from './types';

function warningClass(severity: ChangeWarning['severity']): string {
  return severity === 'error' ? 'bg-red-50 border-red-200' :
    severity === 'warning' ? 'bg-orange-50 border-orange-200' :
    'bg-blue-50 border-blue-200';
}

const SeverityIcon: React.FC<{ severity: ChangeWarning['severity'] }> = ({ severity }) => (
  severity === 'error' ? (
    <XCircle className="w-4 h-4 text-red-500 flex-shrink-0 mt-0.5" />
  ) : severity === 'warning' ? (
    <AlertTriangle className="w-4 h-4 text-orange-500 flex-shrink-0 mt-0.5" />
  ) : (
    <Info className="w-4 h-4 text-blue-500 flex-shrink-0 mt-0.5" />
  )
);

const DraftWarningList: React.FC<{ warnings: ChangeWarning[] }> = ({ warnings }) => (
  <div className="mt-3 space-y-2">
    {warnings.map((warning, idx) => (
      <div key={idx} className={`p-2 rounded border ${warningClass(warning.severity)}`}>
        <div className="flex items-start gap-2">
          <SeverityIcon severity={warning.severity} />
          <div className="flex-1">
            <p className="text-xs font-medium">{warning.message}</p>
            {warning.suggestion && (
              <p className="text-xs text-gray-600 mt-1">
                <Lightbulb className="w-3 h-3 inline mr-1" />
                {warning.suggestion}
              </p>
            )}
          </div>
        </div>
      </div>
    ))}
  </div>
);

export default DraftWarningList;

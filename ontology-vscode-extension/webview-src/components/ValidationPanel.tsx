import React, { useState, useEffect } from 'react';
import { Shield, AlertCircle, AlertTriangle, Info, CheckCircle, RefreshCw, Loader2, ChevronDown, ChevronRight } from 'lucide-react';
import apiClient from '../services/apiClient';

interface ValidationPanelProps {
  projectId: string;
}

interface ValidationIssue {
  severity: string;
  message: string;
  details: string[];
  detailCount: number;
}

interface ValidationResult {
  success: boolean;
  hasErrors: boolean;
  totalIssues: number;
  summary: string;
  errors: ValidationIssue[];
  warnings: ValidationIssue[];
  info: ValidationIssue[];
  errorCount: number;
  warningCount: number;
  infoCount: number;
}

const ValidationPanel: React.FC<ValidationPanelProps> = ({ projectId }) => {
  const [isValidating, setIsValidating] = useState(false);
  const [result, setResult] = useState<ValidationResult | null>(null);
  const [expandedIssues, setExpandedIssues] = useState<Set<string>>(new Set());

  const toggleIssue = (issueId: string) => {
    const newExpanded = new Set(expandedIssues);
    if (newExpanded.has(issueId)) {
      newExpanded.delete(issueId);
    } else {
      newExpanded.add(issueId);
    }
    setExpandedIssues(newExpanded);
  };

  const runValidation = async () => {
    setIsValidating(true);
    try {
      const response = await apiClient.post<ValidationResult>(
        `/api/ontology/${projectId}/validate`
      );
      setResult(response);
      
      // Auto-expand errors
      if (response.errors && response.errors.length > 0) {
        const errorIds = response.errors.map((_, idx) => `error-${idx}`);
        setExpandedIssues(new Set(errorIds));
      }
      
    } catch (error: any) {
      setResult({
        success: false,
        hasErrors: true,
        totalIssues: 1,
        summary: 'Validation failed',
        errors: [{
          severity: 'ERROR',
          message: error.message || 'Validation request failed',
          details: [],
          detailCount: 0
        }],
        warnings: [],
        info: [],
        errorCount: 1,
        warningCount: 0,
        infoCount: 0
      });
    } finally {
      setIsValidating(false);
    }
  };

  useEffect(() => {
    if (projectId) {
      runValidation();
    }
  }, [projectId]);

  const IssueCard: React.FC<{
    issue: ValidationIssue;
    issueId: string;
    icon: React.ReactNode;
    colorClass: string;
  }> = ({ issue, issueId, icon, colorClass }) => {
    const isExpanded = expandedIssues.has(issueId);
    const hasDetails = issue.details && issue.details.length > 0;

    return (
      <div className={`border rounded-lg overflow-hidden ${colorClass}`}>
        <button
          onClick={() => hasDetails && toggleIssue(issueId)}
          className={`w-full flex items-start gap-3 p-4 text-left ${hasDetails ? 'hover:bg-opacity-70 cursor-pointer' : 'cursor-default'}`}
        >
          <div className="flex-shrink-0 mt-0.5">
            {icon}
          </div>
          <div className="flex-1 min-w-0">
            <p className="font-medium text-gray-800">{issue.message}</p>
            {issue.detailCount > 0 && (
              <p className="text-sm text-gray-600 mt-1">
                {issue.detailCount} item{issue.detailCount > 1 ? 's' : ''} affected
              </p>
            )}
          </div>
          {hasDetails && (
            <div className="flex-shrink-0">
              {isExpanded ? <ChevronDown size={20} /> : <ChevronRight size={20} />}
            </div>
          )}
        </button>
        
        {isExpanded && hasDetails && (
          <div className="px-4 pb-4 pt-0 bg-white bg-opacity-50">
            <div className="space-y-1 max-h-60 overflow-y-auto">
              {issue.details.map((detail, idx) => (
                <div key={idx} className="text-sm text-gray-700 py-1 px-3 bg-white rounded">
                  • {detail}
                </div>
              ))}
            </div>
          </div>
        )}
      </div>
    );
  };

  return (
    <div className="h-full flex flex-col bg-white">
      {/* Header */}
      <header className="p-6 border-b border-gray-200 bg-gradient-to-r from-blue-50 to-cyan-50">
        <div className="flex items-center justify-between mb-4">
          <div>
            <h1 className="text-2xl font-bold text-gray-800 flex items-center gap-2">
              <Shield className="text-blue-600" size={32} />
              Ontology Validation
            </h1>
            <p className="text-sm text-gray-600 mt-1">
              Check structure, naming conventions, and best practices
            </p>
          </div>
          
          <button
            onClick={runValidation}
            disabled={isValidating}
            className="flex items-center gap-2 px-6 py-3 bg-blue-600 text-white rounded-lg hover:bg-blue-700 disabled:bg-blue-300 transition-colors shadow-lg"
          >
            {isValidating ? (
              <>
                <Loader2 size={20} className="animate-spin" />
                Validating...
              </>
            ) : (
              <>
                <RefreshCw size={20} />
                Run Validation
              </>
            )}
          </button>
        </div>

        {/* Summary Cards */}
        {result && (
          <div className="grid grid-cols-3 gap-4">
            <div className={`p-4 rounded-lg ${result.errorCount > 0 ? 'bg-red-50 border border-red-200' : 'bg-gray-50'}`}>
              <div className="flex items-center gap-2 text-red-600 mb-1">
                <AlertCircle size={20} />
                <span className="text-sm font-medium">Errors</span>
              </div>
              <p className="text-2xl font-bold text-gray-800">{result.errorCount}</p>
            </div>
            
            <div className={`p-4 rounded-lg ${result.warningCount > 0 ? 'bg-yellow-50 border border-yellow-200' : 'bg-gray-50'}`}>
              <div className="flex items-center gap-2 text-yellow-600 mb-1">
                <AlertTriangle size={20} />
                <span className="text-sm font-medium">Warnings</span>
              </div>
              <p className="text-2xl font-bold text-gray-800">{result.warningCount}</p>
            </div>
            
            <div className={`p-4 rounded-lg ${result.infoCount > 0 ? 'bg-blue-50 border border-blue-200' : 'bg-gray-50'}`}>
              <div className="flex items-center gap-2 text-blue-600 mb-1">
                <Info size={20} />
                <span className="text-sm font-medium">Info</span>
              </div>
              <p className="text-2xl font-bold text-gray-800">{result.infoCount}</p>
            </div>
          </div>
        )}
      </header>

      {/* Results */}
      <div className="flex-1 overflow-y-auto p-6">
        {result && (
          <div className="space-y-6">
            {/* Overall Status */}
            <div className={`p-4 rounded-lg border-2 ${
              result.hasErrors 
                ? 'bg-red-50 border-red-300' 
                : 'bg-green-50 border-green-300'
            }`}>
              <div className="flex items-center gap-3">
                {result.hasErrors ? (
                  <AlertCircle size={24} className="text-red-600" />
                ) : (
                  <CheckCircle size={24} className="text-green-600" />
                )}
                <div>
                  <h3 className="font-bold text-gray-800">
                    {result.hasErrors ? 'Validation Failed' : 'Validation Passed'}
                  </h3>
                  <p className="text-sm text-gray-700">{result.summary}</p>
                </div>
              </div>
            </div>

            {/* Errors */}
            {result.errors && result.errors.length > 0 && (
              <div>
                <h2 className="text-lg font-bold text-gray-800 mb-3 flex items-center gap-2">
                  <AlertCircle className="text-red-600" size={24} />
                  Errors ({result.errors.length})
                </h2>
                <div className="space-y-2">
                  {result.errors.map((error, idx) => (
                    <IssueCard
                      key={idx}
                      issue={error}
                      issueId={`error-${idx}`}
                      icon={<AlertCircle size={20} className="text-red-600" />}
                      colorClass="bg-red-50 border-red-200"
                    />
                  ))}
                </div>
              </div>
            )}

            {/* Warnings */}
            {result.warnings && result.warnings.length > 0 && (
              <div>
                <h2 className="text-lg font-bold text-gray-800 mb-3 flex items-center gap-2">
                  <AlertTriangle className="text-yellow-600" size={24} />
                  Warnings ({result.warnings.length})
                </h2>
                <div className="space-y-2">
                  {result.warnings.map((warning, idx) => (
                    <IssueCard
                      key={idx}
                      issue={warning}
                      issueId={`warning-${idx}`}
                      icon={<AlertTriangle size={20} className="text-yellow-600" />}
                      colorClass="bg-yellow-50 border-yellow-200"
                    />
                  ))}
                </div>
              </div>
            )}

            {/* Info */}
            {result.info && result.info.length > 0 && (
              <div>
                <h2 className="text-lg font-bold text-gray-800 mb-3 flex items-center gap-2">
                  <Info className="text-blue-600" size={24} />
                  Information ({result.info.length})
                </h2>
                <div className="space-y-2">
                  {result.info.map((info, idx) => (
                    <IssueCard
                      key={idx}
                      issue={info}
                      issueId={`info-${idx}`}
                      icon={<Info size={20} className="text-blue-600" />}
                      colorClass="bg-blue-50 border-blue-200"
                    />
                  ))}
                </div>
              </div>
            )}
          </div>
        )}

        {/* Loading State */}
        {isValidating && !result && (
          <div className="flex items-center justify-center h-64">
            <div className="text-center">
              <Loader2 size={48} className="animate-spin text-blue-600 mx-auto mb-4" />
              <p className="text-gray-600">Running validation checks...</p>
            </div>
          </div>
        )}

        {/* Empty State */}
        {!result && !isValidating && (
          <div className="flex items-center justify-center h-64 text-gray-400">
            <div className="text-center">
              <Shield size={64} className="mx-auto mb-4 opacity-20" />
              <p className="text-lg font-medium">No validation results yet</p>
              <p className="text-sm mt-2">Click "Run Validation" to check your ontology</p>
            </div>
          </div>
        )}
      </div>
    </div>
  );
};

export default ValidationPanel;
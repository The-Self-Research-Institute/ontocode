import React, { useState, useEffect } from 'react';
import { AlertTriangle, CheckCircle, XCircle, RefreshCw, Loader2, ChevronDown, ChevronRight } from 'lucide-react';
import apiClient from '../services/apiClient';
import type { ValidationResult } from '../types';

interface ValidationPanelProps {
  projectId: string;
  validationResult: ValidationResult | null;
  onValidate: () => void;
}

const ValidationPanel: React.FC<ValidationPanelProps> = ({ projectId, validationResult, onValidate }) => {
  const [isValidating, setIsValidating] = useState(false);
  const [expandedSections, setExpandedSections] = useState<Set<string>>(new Set(['orphanClasses']));

  const toggleSection = (section: string) => {
    const newExpanded = new Set(expandedSections);
    if (newExpanded.has(section)) {
      newExpanded.delete(section);
    } else {
      newExpanded.add(section);
    }
    setExpandedSections(newExpanded);
  };

  const handleValidate = async () => {
    setIsValidating(true);
    try {
      await onValidate();
    } finally {
      setIsValidating(false);
    }
  };

  const renderIssueSection = (
    title: string,
    sectionKey: string,
    issues: string[] | undefined,
    severity: 'error' | 'warning' | 'info',
    icon: React.ReactNode
  ) => {
    const count = issues?.length || 0;
    const isExpanded = expandedSections.has(sectionKey);
    
    const severityColors = {
      error: 'bg-red-50 border-red-200 text-red-800',
      warning: 'bg-yellow-50 border-yellow-200 text-yellow-800',
      info: 'bg-blue-50 border-blue-200 text-blue-800'
    };

    return (
      <div className={`border rounded-lg ${count > 0 ? severityColors[severity] : 'bg-gray-50 border-gray-200'}`}>
        <button
          onClick={() => toggleSection(sectionKey)}
          className="w-full p-4 flex items-center justify-between hover:bg-black/5 transition-colors"
        >
          <div className="flex items-center gap-3">
            {icon}
            <div className="text-left">
              <h3 className="font-semibold">{title}</h3>
              <p className="text-sm opacity-75">
                {count === 0 ? 'No issues found' : `${count} issue${count !== 1 ? 's' : ''} found`}
              </p>
            </div>
          </div>
          <div className="flex items-center gap-2">
            {count > 0 && (
              <span className="px-3 py-1 bg-white/50 rounded-full text-sm font-medium">
                {count}
              </span>
            )}
            {isExpanded ? <ChevronDown size={20} /> : <ChevronRight size={20} />}
          </div>
        </button>
        
        {isExpanded && count > 0 && (
          <div className="px-4 pb-4 space-y-2 max-h-64 overflow-y-auto">
            {issues?.map((issue, index) => (
              <div
                key={index}
                className="p-3 bg-white rounded border border-current/20 text-sm font-mono"
              >
                {issue.split('#').pop() || issue}
              </div>
            ))}
          </div>
        )}
      </div>
    );
  };

  return (
    <div className="h-full flex flex-col bg-white">
      <header className="p-6 border-b border-gray-200 bg-gradient-to-r from-purple-50 to-indigo-50">
        <div className="flex items-center justify-between mb-4">
          <div>
            <h1 className="text-2xl font-bold text-gray-800 flex items-center gap-2">
              {validationResult?.isValid ? (
                <CheckCircle className="text-green-600" size={32} />
              ) : validationResult ? (
                <AlertTriangle className="text-yellow-600" size={32} />
              ) : (
                <XCircle className="text-gray-400" size={32} />
              )}
              Ontology Validation
            </h1>
            <p className="text-sm text-gray-600 mt-1">
              {validationResult
                ? validationResult.isValid
                  ? 'Your ontology passed all validation checks'
                  : 'Some issues were found in your ontology'
                : 'Run validation to check your ontology for common issues'}
            </p>
          </div>
          
          <button
            onClick={handleValidate}
            disabled={isValidating}
            className="flex items-center gap-2 px-6 py-3 bg-purple-600 text-white rounded-lg hover:bg-purple-700 disabled:bg-purple-300 disabled:cursor-not-allowed transition-colors shadow-lg"
          >
            {isValidating ? (
              <>
                <Loader2 size={20} className="animate-spin" />
                <span className="font-medium">Validating...</span>
              </>
            ) : (
              <>
                <RefreshCw size={20} />
                <span className="font-medium">Run Validation</span>
              </>
            )}
          </button>
        </div>
        
        {validationResult && (
          <div className="grid grid-cols-4 gap-4 mt-4">
            <div className="bg-white p-4 rounded-lg shadow-sm border border-gray-200">
              <p className="text-sm text-gray-600">Orphan Classes</p>
              <p className="text-2xl font-bold text-gray-800 mt-1">
                {validationResult.orphanClasses?.length || 0}
              </p>
            </div>
            <div className="bg-white p-4 rounded-lg shadow-sm border border-gray-200">
              <p className="text-sm text-gray-600">Unused Properties</p>
              <p className="text-2xl font-bold text-gray-800 mt-1">
                {validationResult.unusedProperties?.length || 0}
              </p>
            </div>
            <div className="bg-white p-4 rounded-lg shadow-sm border border-gray-200">
              <p className="text-sm text-gray-600">Missing Labels</p>
              <p className="text-2xl font-bold text-gray-800 mt-1">
                {validationResult.missingLabels?.length || 0}
              </p>
            </div>
            <div className="bg-white p-4 rounded-lg shadow-sm border border-gray-200">
              <p className="text-sm text-gray-600">Circular Dependencies</p>
              <p className="text-2xl font-bold text-gray-800 mt-1">
                {validationResult.circularDependencies?.length || 0}
              </p>
            </div>
          </div>
        )}
      </header>

      <div className="flex-1 overflow-y-auto p-6 space-y-4">
        {!validationResult ? (
          <div className="flex items-center justify-center h-full text-gray-400">
            <div className="text-center">
              <XCircle size={64} className="mx-auto mb-4 opacity-20" />
              <p className="text-lg font-medium">No validation results yet</p>
              <p className="text-sm mt-2">Click "Run Validation" to check your ontology</p>
            </div>
          </div>
        ) : (
          <>
            {renderIssueSection(
              'Orphan Classes',
              'orphanClasses',
              validationResult.orphanClasses,
              'warning',
              <AlertTriangle size={24} className="text-yellow-600" />
            )}
            
            {renderIssueSection(
              'Unused Properties',
              'unusedProperties',
              validationResult.unusedProperties,
              'info',
              <AlertTriangle size={24} className="text-blue-600" />
            )}
            
            {renderIssueSection(
              'Missing Labels',
              'missingLabels',
              validationResult.missingLabels,
              'warning',
              <AlertTriangle size={24} className="text-yellow-600" />
            )}
            
            {renderIssueSection(
              'Circular Dependencies',
              'circularDependencies',
              validationResult.circularDependencies,
              'error',
              <XCircle size={24} className="text-red-600" />
            )}
          </>
        )}
      </div>
    </div>
  );
};

export default ValidationPanel;
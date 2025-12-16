import React, { useState, useEffect, useCallback, useRef, useMemo } from 'react';
import { 
  Plus, Trash2, Play, Save, Check, X, AlertCircle, Loader2, 
  ChevronDown, ChevronRight, ChevronUp, Copy, Download, Upload, BarChart2, 
  BookOpen, Search, ToggleLeft, ToggleRight, Zap, FileText,
  Info, Code, List, Filter, RefreshCw, Settings, LayoutTemplate,
  HelpCircle, Maximize2, Minimize2, Eye, EyeOff
} from 'lucide-react';
import apiClient from './apiClient';
import type { SwrlRule, ValidationResult as SwrlValidationResult, ExecutionResponse, PluginContext, BuiltInCategory, InferredAxiom } from './types';

// ============================================================================
// CONSTANTS & HELPERS
// ============================================================================

const RULE_TEMPLATES = [
  { name: 'Class Membership', template: 'Person(?p) ^ hasAge(?p, ?age) ^ swrlb:greaterThanOrEqual(?age, 18) -> Adult(?p)', description: 'Classify individuals based on property values' },
  { name: 'Property Transfer', template: 'hasParent(?x, ?y) ^ hasBrother(?y, ?z) -> hasUncle(?x, ?z)', description: 'Infer a relationship based on a chain of properties' },
  { name: 'Math Calculation', template: 'Item(?i) ^ hasPrice(?i, ?p) ^ swrlb:multiply(?tax, ?p, 0.08) -> hasTax(?i, ?tax)', description: 'Calculate a value using math built-ins' },
  { name: 'String Matching', template: 'Person(?p) ^ hasName(?p, ?name) ^ swrlb:startsWith(?name, "Dr.") -> Doctor(?p)', description: 'Classify based on string patterns' },
  { name: 'Temporal Relation', template: 'Event(?e1) ^ Event(?e2) ^ hasTime(?e1, ?t1) ^ hasTime(?e2, ?t2) ^ temporal:before(?t1, ?t2) -> Precedes(?e1, ?e2)', description: 'Infer temporal order of events' },
];

const SWRL_BUILTINS_QUICK = [
  { category: 'Compare', items: ['swrlb:equal', 'swrlb:notEqual', 'swrlb:lessThan', 'swrlb:greaterThan', 'swrlb:lessThanOrEqual', 'swrlb:greaterThanOrEqual'] },
  { category: 'Math', items: ['swrlb:add', 'swrlb:subtract', 'swrlb:multiply', 'swrlb:divide', 'swrlb:abs', 'swrlb:round'] },
  { category: 'String', items: ['swrlb:stringConcat', 'swrlb:contains', 'swrlb:startsWith', 'swrlb:endsWith', 'swrlb:matches'] },
];

const SWRL_BUILTINS: BuiltInCategory[] = [
  {
    prefix: 'swrlb',
    name: 'Core Comparisons',
    description: 'Standard SWRL built-in predicates for comparisons',
    builtIns: [
      { name: 'equal', fullName: 'swrlb:equal', description: 'True if arguments are equal', signature: 'swrlb:equal(?x, ?y)', example: 'swrlb:equal(?age, 18)' },
      { name: 'notEqual', fullName: 'swrlb:notEqual', description: 'True if arguments are not equal', signature: 'swrlb:notEqual(?x, ?y)', example: 'swrlb:notEqual(?x, ?y)' },
      { name: 'lessThan', fullName: 'swrlb:lessThan', description: 'True if first arg < second arg', signature: 'swrlb:lessThan(?x, ?y)', example: 'swrlb:lessThan(?age, 18)' },
      { name: 'lessThanOrEqual', fullName: 'swrlb:lessThanOrEqual', description: 'True if first arg <= second arg', signature: 'swrlb:lessThanOrEqual(?x, ?y)', example: 'swrlb:lessThanOrEqual(?score, 100)' },
      { name: 'greaterThan', fullName: 'swrlb:greaterThan', description: 'True if first arg > second arg', signature: 'swrlb:greaterThan(?x, ?y)', example: 'swrlb:greaterThan(?age, 18)' },
      { name: 'greaterThanOrEqual', fullName: 'swrlb:greaterThanOrEqual', description: 'True if first arg >= second arg', signature: 'swrlb:greaterThanOrEqual(?x, ?y)', example: 'swrlb:greaterThanOrEqual(?score, 50)' },
    ]
  },
  {
    prefix: 'swrlb',
    name: 'Math Operations',
    description: 'Standard arithmetic operations',
    builtIns: [
      { name: 'add', fullName: 'swrlb:add', description: 'Binds first arg to sum of remaining args', signature: 'swrlb:add(?result, ?x, ?y)', example: 'swrlb:add(?total, ?a, ?b)' },
      { name: 'subtract', fullName: 'swrlb:subtract', description: 'Binds first arg to difference', signature: 'swrlb:subtract(?result, ?x, ?y)', example: 'swrlb:subtract(?diff, ?x, ?y)' },
      { name: 'multiply', fullName: 'swrlb:multiply', description: 'Binds first arg to product', signature: 'swrlb:multiply(?result, ?x, ?y)', example: 'swrlb:multiply(?product, ?a, ?b)' },
      { name: 'divide', fullName: 'swrlb:divide', description: 'Binds first arg to quotient', signature: 'swrlb:divide(?result, ?x, ?y)', example: 'swrlb:divide(?ratio, ?x, ?y)' },
      { name: 'mod', fullName: 'swrlb:mod', description: 'Binds first arg to remainder', signature: 'swrlb:mod(?result, ?x, ?y)', example: 'swrlb:mod(?rem, ?x, 2)' },
      { name: 'pow', fullName: 'swrlb:pow', description: 'Binds first arg to x^y', signature: 'swrlb:pow(?result, ?x, ?y)', example: 'swrlb:pow(?square, ?x, 2)' },
      { name: 'abs', fullName: 'swrlb:abs', description: 'Binds first arg to absolute value', signature: 'swrlb:abs(?result, ?x)', example: 'swrlb:abs(?pos, ?val)' },
      { name: 'ceiling', fullName: 'swrlb:ceiling', description: 'Rounds up to nearest integer', signature: 'swrlb:ceiling(?result, ?x)', example: 'swrlb:ceiling(?ceil, ?val)' },
      { name: 'floor', fullName: 'swrlb:floor', description: 'Rounds down to nearest integer', signature: 'swrlb:floor(?result, ?x)', example: 'swrlb:floor(?floor, ?val)' },
      { name: 'round', fullName: 'swrlb:round', description: 'Rounds to nearest integer', signature: 'swrlb:round(?result, ?x)', example: 'swrlb:round(?rounded, ?val)' },
    ]
  },
  {
    prefix: 'swrlb',
    name: 'String Operations',
    description: 'String manipulation and matching',
    builtIns: [
      { name: 'stringConcat', fullName: 'swrlb:stringConcat', description: 'Concatenates strings', signature: 'swrlb:stringConcat(?result, ?s1, ?s2)', example: 'swrlb:stringConcat(?full, ?first, ?last)' },
      { name: 'stringLength', fullName: 'swrlb:stringLength', description: 'Returns string length', signature: 'swrlb:stringLength(?result, ?s)', example: 'swrlb:stringLength(?len, ?name)' },
      { name: 'substring', fullName: 'swrlb:substring', description: 'Extracts substring', signature: 'swrlb:substring(?result, ?s, ?start, ?len)', example: 'swrlb:substring(?sub, ?s, 0, 3)' },
      { name: 'contains', fullName: 'swrlb:contains', description: 'True if string contains substring', signature: 'swrlb:contains(?s, ?sub)', example: 'swrlb:contains(?name, "John")' },
      { name: 'containsIgnoreCase', fullName: 'swrlb:containsIgnoreCase', description: 'True if string contains substring (case-insensitive)', signature: 'swrlb:containsIgnoreCase(?s, ?sub)', example: 'swrlb:containsIgnoreCase(?name, "john")' },
      { name: 'startsWith', fullName: 'swrlb:startsWith', description: 'True if string starts with prefix', signature: 'swrlb:startsWith(?s, ?prefix)', example: 'swrlb:startsWith(?name, "Dr.")' },
      { name: 'endsWith', fullName: 'swrlb:endsWith', description: 'True if string ends with suffix', signature: 'swrlb:endsWith(?s, ?suffix)', example: 'swrlb:endsWith(?email, ".edu")' },
      { name: 'upperCase', fullName: 'swrlb:upperCase', description: 'Converts to uppercase', signature: 'swrlb:upperCase(?result, ?s)', example: 'swrlb:upperCase(?upper, ?name)' },
      { name: 'lowerCase', fullName: 'swrlb:lowerCase', description: 'Converts to lowercase', signature: 'swrlb:lowerCase(?result, ?s)', example: 'swrlb:lowerCase(?lower, ?name)' },
      { name: 'matches', fullName: 'swrlb:matches', description: 'True if string matches regex', signature: 'swrlb:matches(?s, ?pattern)', example: 'swrlb:matches(?email, ".*@.*\\\\.com")' },
      { name: 'replace', fullName: 'swrlb:replace', description: 'Replaces pattern in string', signature: 'swrlb:replace(?result, ?s, ?pattern, ?replacement)', example: 'swrlb:replace(?clean, ?s, " ", "_")' },
    ]
  },
  {
    prefix: 'swrlb',
    name: 'Date & Time',
    description: 'Temporal operations',
    builtIns: [
      { name: 'date', fullName: 'swrlb:date', description: 'Creates xsd:date', signature: 'swrlb:date(?result, ?y, ?m, ?d, ?tz)', example: 'swrlb:date(?d, 2024, 1, 15, "")' },
      { name: 'time', fullName: 'swrlb:time', description: 'Creates xsd:time', signature: 'swrlb:time(?result, ?h, ?m, ?s, ?tz)', example: 'swrlb:time(?t, 14, 30, 0, "")' },
      { name: 'dateTime', fullName: 'swrlb:dateTime', description: 'Creates xsd:dateTime', signature: 'swrlb:dateTime(?result, ?y, ?m, ?d, ?h, ?min, ?s, ?tz)', example: 'swrlb:dateTime(?dt, 2024, 1, 15, 14, 30, 0, "")' },
      { name: 'yearMonthDuration', fullName: 'swrlb:yearMonthDuration', description: 'Creates duration (years/months)', signature: 'swrlb:yearMonthDuration(?result, ?y, ?m)', example: 'swrlb:yearMonthDuration(?dur, 1, 6)' },
      { name: 'dayTimeDuration', fullName: 'swrlb:dayTimeDuration', description: 'Creates duration (days/time)', signature: 'swrlb:dayTimeDuration(?result, ?d, ?h, ?m, ?s)', example: 'swrlb:dayTimeDuration(?dur, 2, 12, 0, 0)' },
    ]
  },
  {
    prefix: 'swrlm',
    name: 'Advanced Math',
    description: 'Extended mathematical operations (Trig, Log, Exp)',
    builtIns: [
      { name: 'sqrt', fullName: 'swrlm:sqrt', description: 'Square root', signature: 'swrlm:sqrt(?result, ?x)', example: 'swrlm:sqrt(?root, ?val)' },
      { name: 'log', fullName: 'swrlm:log', description: 'Natural logarithm', signature: 'swrlm:log(?result, ?x)', example: 'swrlm:log(?ln, ?val)' },
      { name: 'log10', fullName: 'swrlm:log10', description: 'Base-10 logarithm', signature: 'swrlm:log10(?result, ?x)', example: 'swrlm:log10(?log, ?val)' },
      { name: 'exp', fullName: 'swrlm:exp', description: 'Exponential function (e^x)', signature: 'swrlm:exp(?result, ?x)', example: 'swrlm:exp(?e, ?val)' },
      { name: 'sin', fullName: 'swrlm:sin', description: 'Sine function (radians)', signature: 'swrlm:sin(?result, ?x)', example: 'swrlm:sin(?s, ?angle)' },
      { name: 'cos', fullName: 'swrlm:cos', description: 'Cosine function (radians)', signature: 'swrlm:cos(?result, ?x)', example: 'swrlm:cos(?c, ?angle)' },
      { name: 'tan', fullName: 'swrlm:tan', description: 'Tangent function (radians)', signature: 'swrlm:tan(?result, ?x)', example: 'swrlm:tan(?t, ?angle)' },
      { name: 'asin', fullName: 'swrlm:asin', description: 'Arc sine', signature: 'swrlm:asin(?result, ?x)', example: 'swrlm:asin(?angle, ?val)' },
      { name: 'acos', fullName: 'swrlm:acos', description: 'Arc cosine', signature: 'swrlm:acos(?result, ?x)', example: 'swrlm:acos(?angle, ?val)' },
      { name: 'atan', fullName: 'swrlm:atan', description: 'Arc tangent', signature: 'swrlm:atan(?result, ?x)', example: 'swrlm:atan(?angle, ?val)' },
      { name: 'toDegrees', fullName: 'swrlm:toDegrees', description: 'Convert radians to degrees', signature: 'swrlm:toDegrees(?result, ?rad)', example: 'swrlm:toDegrees(?deg, ?rad)' },
      { name: 'toRadians', fullName: 'swrlm:toRadians', description: 'Convert degrees to radians', signature: 'swrlm:toRadians(?result, ?deg)', example: 'swrlm:toRadians(?rad, ?deg)' },
      { name: 'eval', fullName: 'swrlm:eval', description: 'Evaluates math expression', signature: 'swrlm:eval(?result, "expr", ?vars...)', example: 'swrlm:eval(?r, "x^2 + y", ?x, ?y)' },
    ]
  },
  {
    prefix: 'temporal',
    name: 'Temporal Intervals',
    description: 'Allen temporal interval relations',
    builtIns: [
      { name: 'equals', fullName: 'temporal:equals', description: 'Intervals are equal', signature: 'temporal:equals(?i1, ?i2)', example: 'temporal:equals(?period1, ?period2)' },
      { name: 'before', fullName: 'temporal:before', description: 'First interval before second', signature: 'temporal:before(?i1, ?i2)', example: 'temporal:before(?start, ?end)' },
      { name: 'after', fullName: 'temporal:after', description: 'First interval after second', signature: 'temporal:after(?i1, ?i2)', example: 'temporal:after(?end, ?start)' },
      { name: 'meets', fullName: 'temporal:meets', description: 'First interval meets second', signature: 'temporal:meets(?i1, ?i2)', example: 'temporal:meets(?phase1, ?phase2)' },
      { name: 'overlaps', fullName: 'temporal:overlaps', description: 'Intervals overlap', signature: 'temporal:overlaps(?i1, ?i2)', example: 'temporal:overlaps(?event1, ?event2)' },
      { name: 'contains', fullName: 'temporal:contains', description: 'First contains second', signature: 'temporal:contains(?i1, ?i2)', example: 'temporal:contains(?parent, ?child)' },
      { name: 'during', fullName: 'temporal:during', description: 'First during second', signature: 'temporal:during(?i1, ?i2)', example: 'temporal:during(?meeting, ?workday)' },
      { name: 'starts', fullName: 'temporal:starts', description: 'First starts second', signature: 'temporal:starts(?i1, ?i2)', example: 'temporal:starts(?intro, ?presentation)' },
      { name: 'finishes', fullName: 'temporal:finishes', description: 'First finishes second', signature: 'temporal:finishes(?i1, ?i2)', example: 'temporal:finishes(?outro, ?presentation)' },
    ]
  },
  {
    prefix: 'sqwrl',
    name: 'SQWRL Query',
    description: 'Query result selection and aggregation',
    builtIns: [
      { name: 'select', fullName: 'sqwrl:select', description: 'Select variables for output', signature: 'sqwrl:select(?vars...)', example: 'sqwrl:select(?person, ?age)' },
      { name: 'selectDistinct', fullName: 'sqwrl:selectDistinct', description: 'Select distinct values', signature: 'sqwrl:selectDistinct(?vars...)', example: 'sqwrl:selectDistinct(?class)' },
      { name: 'count', fullName: 'sqwrl:count', description: 'Count matching results', signature: 'sqwrl:count(?var)', example: 'sqwrl:count(?person)' },
      { name: 'min', fullName: 'sqwrl:min', description: 'Minimum value', signature: 'sqwrl:min(?var)', example: 'sqwrl:min(?age)' },
      { name: 'max', fullName: 'sqwrl:max', description: 'Maximum value', signature: 'sqwrl:max(?var)', example: 'sqwrl:max(?salary)' },
      { name: 'sum', fullName: 'sqwrl:sum', description: 'Sum of values', signature: 'sqwrl:sum(?var)', example: 'sqwrl:sum(?amount)' },
      { name: 'avg', fullName: 'sqwrl:avg', description: 'Average of values', signature: 'sqwrl:avg(?var)', example: 'sqwrl:avg(?score)' },
      { name: 'orderBy', fullName: 'sqwrl:orderBy', description: 'Order results', signature: 'sqwrl:orderBy(?var)', example: 'sqwrl:orderBy(?name)' },
      { name: 'orderByDescending', fullName: 'sqwrl:orderByDescending', description: 'Order results descending', signature: 'sqwrl:orderByDescending(?var)', example: 'sqwrl:orderByDescending(?age)' },
      { name: 'limit', fullName: 'sqwrl:limit', description: 'Limit number of results', signature: 'sqwrl:limit(?n)', example: 'sqwrl:limit(10)' },
    ]
  }
];

const extractLocalName = (uri: string): string => {
  if (!uri) return uri;
  const cleanUri = uri.replace(/^<|>$/g, '');
  const hashIndex = cleanUri.lastIndexOf('#');
  if (hashIndex !== -1) return cleanUri.substring(hashIndex + 1);
  const slashIndex = cleanUri.lastIndexOf('/');
  if (slashIndex !== -1) return cleanUri.substring(slashIndex + 1);
  return uri;
};

// Debounce hook
function useDebounce<T>(value: T, delay: number): T {
  const [debounced, setDebounced] = useState(value);
  useEffect(() => { 
    const t = setTimeout(() => setDebounced(value), delay); 
    return () => clearTimeout(t); 
  }, [value, delay]);
  return debounced;
}

// ============================================================================
// CUSTOM DIALOG COMPONENT
// ============================================================================

const ConfirmDialog: React.FC<{
  isOpen: boolean;
  title: string;
  message: string;
  onConfirm: () => void;
  onCancel: () => void;
  confirmText?: string;
  danger?: boolean;
}> = ({ isOpen, title, message, onConfirm, onCancel, confirmText = 'Confirm', danger = false }) => {
  if (!isOpen) return null;
  
  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/50 backdrop-blur-sm">
      <div className="bg-white rounded-xl shadow-2xl max-w-md w-full mx-4 overflow-hidden animate-in fade-in zoom-in-95 duration-200">
        <div className="p-5 border-b border-gray-100">
          <h3 className="text-lg font-semibold text-gray-900">{title}</h3>
        </div>
        <div className="p-5">
          <p className="text-gray-600">{message}</p>
        </div>
        <div className="flex justify-end gap-3 p-4 bg-gray-50">
          <button
            onClick={onCancel}
            className="px-4 py-2 text-sm font-medium text-gray-700 bg-white border border-gray-300 rounded-lg hover:bg-gray-50 transition-colors"
          >
            Cancel
          </button>
          <button
            onClick={onConfirm}
            className={`px-4 py-2 text-sm font-medium text-white rounded-lg transition-colors ${
              danger ? 'bg-red-600 hover:bg-red-700' : 'bg-purple-600 hover:bg-purple-700'
            }`}
          >
            {confirmText}
          </button>
        </div>
      </div>
    </div>
  );
};

// ============================================================================
// RULE LIST ITEM COMPONENT
// ============================================================================

interface RuleListItemProps {
  rule: SwrlRule;
  isSelected: boolean;
  isChecked: boolean;
  onSelect: () => void;
  onToggle: () => void;
  onToggleEnabled: () => void;
  onDelete: () => void;
}

const RuleListItem: React.FC<RuleListItemProps> = ({ 
  rule, isSelected, isChecked, onSelect, onToggle, onToggleEnabled, onDelete 
}) => (
  <div
    className={`group relative flex items-start gap-3 p-3 cursor-pointer transition-all duration-150 border-l-3 ${
      isSelected 
        ? 'bg-purple-50 border-l-purple-500' 
        : isChecked 
          ? 'bg-green-50/50 border-l-green-400' 
          : 'border-l-transparent hover:bg-gray-50'
    }`}
    onClick={onSelect}
  >
    {/* Checkbox */}
    <div className="pt-0.5" onClick={e => e.stopPropagation()}>
      <input
        type="checkbox"
        checked={isChecked}
        onChange={onToggle}
        className="w-4 h-4 rounded border-gray-300 text-green-600 focus:ring-green-500 cursor-pointer"
      />
    </div>

    {/* Content */}
    <div className="flex-1 min-w-0">
      <div className="flex items-center gap-2">
        <span className={`font-medium text-sm truncate ${isSelected ? 'text-purple-900' : 'text-gray-800'}`}>
          {rule.ruleName}
        </span>
        {rule.category && (
          <span className="px-1.5 py-0.5 text-[10px] font-medium bg-gray-200 text-gray-600 rounded">
            {rule.category}
          </span>
        )}
      </div>
      <p className="text-xs text-gray-500 font-mono truncate mt-0.5">{rule.ruleText}</p>
    </div>

    {/* Actions */}
    <div className="flex items-center gap-1 shrink-0" onClick={e => e.stopPropagation()}>
      <button
        onClick={onToggleEnabled}
        className={`p-1.5 rounded-md transition-colors ${
          rule.enabled 
            ? 'bg-green-100 text-green-600 hover:bg-green-200' 
            : 'bg-gray-100 text-gray-400 hover:bg-gray-200'
        }`}
        title={rule.enabled ? 'Enabled - Click to disable' : 'Disabled - Click to enable'}
      >
        {rule.enabled ? <ToggleRight size={14} /> : <ToggleLeft size={14} />}
      </button>
      <button
        onClick={onDelete}
        className="p-1.5 rounded-md text-gray-400 hover:text-red-600 hover:bg-red-50 opacity-0 group-hover:opacity-100 transition-all"
        title="Delete rule"
      >
        <Trash2 size={14} />
      </button>
    </div>
  </div>
);

// ============================================================================
// QUICK INSERT PANEL
// ============================================================================

interface QuickInsertProps {
  onInsert: (text: string) => void;
  disabled?: boolean;
}

const QuickInsertPanel: React.FC<QuickInsertProps> = ({ onInsert, disabled }) => {
  const [expanded, setExpanded] = useState(false);
  const [showTemplates, setShowTemplates] = useState(false);

  return (
    <div className="bg-gray-50 border-t border-gray-200">
      {/* Quick symbols */}
      <div className="flex items-center gap-1 p-2 flex-wrap">
        <span className="text-xs text-gray-500 mr-2">Quick:</span>
        {['(?x)', '(?y)', ' ^ ', ' -> '].map(sym => (
          <button
            key={sym}
            onClick={() => onInsert(sym)}
            disabled={disabled}
            className="px-2 py-1 text-xs font-mono bg-white border border-gray-300 rounded hover:bg-purple-50 hover:border-purple-300 disabled:opacity-50 transition-colors"
          >
            {sym === ' ^ ' ? '∧ AND' : sym === ' -> ' ? '→ THEN' : sym}
          </button>
        ))}
        
        <div className="ml-auto flex items-center gap-1">
          <button
            onClick={() => { setShowTemplates(!showTemplates); setExpanded(false); }}
            className={`px-2 py-1 text-xs rounded flex items-center gap-1 transition-colors ${showTemplates ? 'bg-purple-100 text-purple-700' : 'text-purple-600 hover:bg-purple-50'}`}
          >
            <LayoutTemplate size={12} /> Templates
          </button>
          <button
            onClick={() => { setExpanded(!expanded); setShowTemplates(false); }}
            className={`px-2 py-1 text-xs rounded flex items-center gap-1 transition-colors ${expanded ? 'bg-purple-100 text-purple-700' : 'text-purple-600 hover:bg-purple-50'}`}
          >
            <Code size={12} /> Built-ins
          </button>
        </div>
      </div>

      {/* Templates */}
      {showTemplates && (
        <div className="p-2 pt-0 grid grid-cols-1 gap-1 max-h-40 overflow-y-auto">
          {RULE_TEMPLATES.map((t, i) => (
            <button
              key={i}
              onClick={() => { onInsert(t.template); setShowTemplates(false); }}
              disabled={disabled}
              className="text-left px-3 py-2 text-xs bg-white border border-gray-200 rounded hover:bg-purple-50 hover:border-purple-300 disabled:opacity-50 group"
            >
              <div className="font-medium text-gray-700 group-hover:text-purple-700">{t.name}</div>
              <div className="text-[10px] text-gray-500 truncate">{t.description}</div>
            </button>
          ))}
        </div>
      )}

      {/* Expanded built-ins */}
      {expanded && (
        <div className="p-2 pt-0 space-y-2">
          {SWRL_BUILTINS_QUICK.map(cat => (
            <div key={cat.category}>
              <div className="text-[10px] font-semibold text-gray-500 uppercase tracking-wide mb-1">{cat.category}</div>
              <div className="flex flex-wrap gap-1">
                {cat.items.map(item => (
                  <button
                    key={item}
                    onClick={() => onInsert(item + '(')}
                    disabled={disabled}
                    className="px-2 py-0.5 text-[11px] font-mono bg-white border border-gray-200 rounded hover:bg-purple-50 hover:border-purple-300 disabled:opacity-50"
                  >
                    {item.split(':')[1]}
                  </button>
                ))}
              </div>
            </div>
          ))}
        </div>
      )}
    </div>
  );
};

// ============================================================================
// RESULTS PANEL
// ============================================================================

interface ResultsPanelProps {
  results: ExecutionResponse | null;
  isExecuting: boolean;
}

// ============================================================================
// SQWRL QUERY PANEL
// ============================================================================

const SQWRLQueryPanel: React.FC<{ projectId: string; context: PluginContext }> = ({ projectId, context }) => {
  const [query, setQuery] = useState('Person(?p) ^ hasAge(?p, ?age) -> sqwrl:select(?p, ?age)');
  const [results, setResults] = useState<{ columnNames: string[], rows: Record<string, any>[], rowCount: number, executionTimeMs: number, success: boolean, errorMessage?: string } | null>(null);
  const [loading, setLoading] = useState(false);

  const execute = useCallback(async () => {
    setLoading(true);
    setResults(null);
    try {
      const res = await apiClient.post<{ 
        columnNames: string[], 
        rows: Record<string, any>[],
        rowCount: number,
        executionTimeMs: number,
        success: boolean,
        errorMessage?: string
      }>(
        `/api/swrl/${projectId}/sqwrl/query`, 
        { queryText: query }
      );
      setResults(res);
    } catch (e: any) {
      console.error(e);
      setResults({
        columnNames: [],
        rows: [],
        rowCount: 0,
        executionTimeMs: 0,
        success: false,
        errorMessage: e.message || 'Query execution failed'
      });
    } finally { setLoading(false); }
  }, [projectId, query]);

  // SQWRL query examples
  const queryExamples = [
    { label: 'All Persons with Age', query: 'Person(?p) ^ hasAge(?p, ?age) -> sqwrl:select(?p, ?age)' },
    { label: 'Adults (age > 18)', query: 'Person(?p) ^ hasAge(?p, ?age) ^ swrlb:greaterThan(?age, 18) -> sqwrl:select(?p, ?age) ^ sqwrl:orderBy(?age)' },
    { label: 'Count Persons', query: 'Person(?p) -> sqwrl:select(?p) ^ sqwrl:count(?p)' },
    { label: 'Average Age', query: 'Person(?p) ^ hasAge(?p, ?age) -> sqwrl:select(?p) ^ sqwrl:avg(?age)' },
    { label: 'Min/Max Age', query: 'Person(?p) ^ hasAge(?p, ?age) -> sqwrl:min(?age) ^ sqwrl:max(?age)' },
  ];

  return (
    <div className="flex flex-col h-full bg-gray-50">
      <div className="p-4 border-b border-gray-300 flex justify-between items-center">
        <h3 className="text-lg font-semibold text-gray-800">SQWRL Query</h3>
        {/* Example dropdown */}
        <select 
          onChange={(e) => e.target.value && setQuery(e.target.value)}
          className="text-sm border border-gray-300 rounded px-2 py-1 bg-white"
          defaultValue=""
        >
          <option value="">📚 Examples...</option>
          {queryExamples.map((ex, i) => (
            <option key={i} value={ex.query}>{ex.label}</option>
          ))}
        </select>
      </div>
      <div className="p-4 flex-grow flex flex-col gap-4">
        <textarea 
          className="w-full h-32 p-3 font-mono text-sm border-2 border-gray-400 rounded-lg bg-white focus:ring-2 focus:ring-purple-500 focus:border-purple-500 text-gray-900 placeholder-gray-500"
          value={query} 
          onChange={(e) => setQuery(e.target.value)} 
          placeholder="Enter SQWRL query… e.g., Person(?p) ^ hasAge(?p, ?age) -> sqwrl:select(?p, ?age)" 
        />
        <button onClick={execute} disabled={loading} className="w-full flex items-center justify-center gap-2 px-4 py-2 bg-purple-600 text-white rounded-lg hover:bg-purple-700 disabled:bg-purple-300 text-sm">
          {loading ? <Loader2 size={16} className="animate-spin" /> : <Play size={16} />} Execute Query
        </button>
        
        {/* Results Section */}
        <div className="flex-grow overflow-auto border-2 border-gray-300 rounded-lg bg-white">
          {!results ? (
            <div className="h-full flex flex-col items-center justify-center text-gray-500 p-4">
              <HelpCircle size={32} className="mb-2 opacity-50" />
              <p>Query results will appear here.</p>
              <p className="text-xs mt-2">Use sqwrl:select(?var) in the head to query data</p>
            </div>
          ) : !results.success ? (
            <div className="p-4 bg-red-50 text-red-700">
              <div className="flex items-center gap-2 font-semibold mb-2">
                <AlertCircle size={18} />
                Query Failed
              </div>
              <pre className="text-sm bg-red-100 p-2 rounded overflow-x-auto">{results.errorMessage}</pre>
            </div>
          ) : results.rows.length === 0 ? (
            <div className="h-full flex flex-col items-center justify-center text-gray-500 p-4">
              <AlertCircle size={32} className="mb-2 opacity-50" />
              <p>No results found</p>
              <p className="text-xs mt-1">Execution time: {results.executionTimeMs}ms</p>
            </div>
          ) : (
            <div>
              <div className="p-2 bg-green-50 border-b border-green-200 text-green-700 text-sm flex items-center justify-between">
                <span className="flex items-center gap-2">
                  <Check size={16} />
                  {results.rowCount} results in {results.executionTimeMs}ms
                </span>
              </div>
              <table className="min-w-full text-sm">
                <thead className="bg-gray-100 sticky top-0">
                  <tr>
                    <th className="p-2 text-left font-semibold text-gray-500 w-8">#</th>
                    {results.columnNames.map(c => (
                      <th key={c} className="p-2 text-left font-semibold text-gray-700">{c}</th>
                    ))}
                  </tr>
                </thead>
                <tbody className="divide-y divide-gray-200">
                  {results.rows.map((row, i) => (
                    <tr key={i} className="hover:bg-gray-50">
                      <td className="p-2 text-gray-400 text-xs">{i + 1}</td>
                      {results.columnNames.map(c => (
                        <td key={c} className="p-2 text-gray-800 whitespace-nowrap font-mono">{String(row[c] ?? '')}</td>
                      ))}
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )}
        </div>
      </div>
    </div>
  );
};

// ============================================================================
// RESULTS PANEL
// ============================================================================

interface ResultsPanelProps {
  results: ExecutionResponse | null;
  isExecuting: boolean;
}



// Helper function to parse and format axioms for better readability
const formatInferredAxiom = (axiom: InferredAxiom): { type: string; subject: string; predicate?: string; object?: string; formatted: string } => {
  const readable = axiom.readable || '';
  
  // Helper to clean up regex matches
  const clean = (s: string) => extractLocalName(s);
  
  // Parse ClassAssertion: ClassAssertion(<http://...#Adult> <http://...#Alice>)
  // Also handles: ClassAssertion(Adult Alice)
  const classAssertionMatch = readable.match(/ClassAssertion\s*\(\s*(?:<)?([^>\s)]+)(?:>)?\s+(?:<)?([^>\s)]+)(?:>)?\s*\)/i);
  if (classAssertionMatch) {
    const className = clean(classAssertionMatch[1]);
    const individual = clean(classAssertionMatch[2]);
    return {
      type: 'ClassAssertion',
      subject: individual,
      object: className,
      formatted: `${individual} → ${className}`
    };
  }
  
  // Parse ObjectPropertyAssertion: ObjectPropertyAssertion(<prop> <subj> <obj>)
  const objPropMatch = readable.match(/ObjectPropertyAssertion\s*\(\s*(?:<)?([^>\s)]+)(?:>)?\s+(?:<)?([^>\s)]+)(?:>)?\s+(?:<)?([^>\s)]+)(?:>)?\s*\)/i);
  if (objPropMatch) {
    const prop = clean(objPropMatch[1]);
    const subject = clean(objPropMatch[2]);
    const object = clean(objPropMatch[3]);
    return {
      type: 'ObjectPropertyAssertion',
      subject,
      predicate: prop,
      object,
      formatted: `${subject} ${prop} ${object}`
    };
  }
  
  // Parse DataPropertyAssertion: DataPropertyAssertion(<prop> <subj> "value"^^type)
  const dataPropMatch = readable.match(/DataPropertyAssertion\s*\(\s*(?:<)?([^>\s)]+)(?:>)?\s+(?:<)?([^>\s)]+)(?:>)?\s+(.+)\s*\)/i);
  if (dataPropMatch) {
    const prop = clean(dataPropMatch[1]);
    const subject = clean(dataPropMatch[2]);
    let value = dataPropMatch[3];
    // Clean up typed literals
    value = value.replace(/"\^\^.*$/, '').replace(/^"|"$/g, '');
    return {
      type: 'DataPropertyAssertion',
      subject,
      predicate: prop,
      object: value,
      formatted: `${subject} ${prop} = ${value}`
    };
  }

  // Parse SubClassOf: SubClassOf(<subClass> <superClass>)
  const subClassMatch = readable.match(/SubClassOf\s*\(\s*(?:<)?([^>\s)]+)(?:>)?\s+(?:<)?([^>\s)]+)(?:>)?\s*\)/i);
  if (subClassMatch) {
    const subClass = clean(subClassMatch[1]);
    const superClass = clean(subClassMatch[2]);
    return {
      type: 'SubClassOf',
      subject: subClass,
      object: superClass,
      formatted: `${subClass} ⊆ ${superClass}`
    };
  }

  // Parse EquivalentClasses: EquivalentClasses(<class1> <class2> ...)
  const equivClassMatch = readable.match(/EquivalentClasses\s*\(\s*<?([^>\s]+)>?/i);
  if (equivClassMatch) {
    const cls = clean(equivClassMatch[1]);
    return {
      type: 'EquivalentClasses',
      subject: cls,
      formatted: `Equivalent: ${cls}`
    };
  }

  // Parse SameIndividual: SameIndividual(<ind1> <ind2> ...)
  const sameIndMatch = readable.match(/SameIndividual\s*\(\s*<?([^>\s]+)>?/i);
  if (sameIndMatch) {
    const ind = clean(sameIndMatch[1]);
    return {
      type: 'SameIndividual',
      subject: ind,
      formatted: `Same: ${ind}`
    };
  }

  // Parse Domain/Range - handles both ObjectPropertyDomain/Range and DataPropertyDomain/Range
  const domainRangeMatch = readable.match(/(Data|Object)Property(Domain|Range)\s*\(\s*<?([^>\s]+)>?\s+<?([^>\s]+)>?\s*\)/i);
  if (domainRangeMatch) {
    const propType = domainRangeMatch[1]; // Data or Object
    const constraint = domainRangeMatch[2]; // Domain or Range
    const prop = clean(domainRangeMatch[3]);
    const cls = clean(domainRangeMatch[4]);
    return {
      type: `${propType}Property${constraint}`,
      subject: prop,
      object: cls,
      formatted: `${prop} ${constraint.toLowerCase()}: ${cls}`
    };
  }

  // Parse EquivalentObjectProperties: EquivalentObjectProperties(<prop1> <prop2> ...)
  const equivObjPropsMatch = readable.match(/EquivalentObjectProperties\s*\(\s*<?([^>\s]+)>?\s+<?([^>\s]+)>?/i);
  if (equivObjPropsMatch) {
    const prop1 = clean(equivObjPropsMatch[1]);
    const prop2 = clean(equivObjPropsMatch[2]);
    return {
      type: 'EquivalentObjectProperties',
      subject: prop1,
      object: prop2,
      formatted: `${prop1} ≡ ${prop2}`
    };
  }

  // Parse EquivalentDataProperties: EquivalentDataProperties(<prop1> <prop2> ...)
  const equivDataPropsMatch = readable.match(/EquivalentDataProperties\s*\(\s*<?([^>\s]+)>?\s+<?([^>\s]+)>?/i);
  if (equivDataPropsMatch) {
    const prop1 = clean(equivDataPropsMatch[1]);
    const prop2 = clean(equivDataPropsMatch[2]);
    return {
      type: 'EquivalentDataProperties',
      subject: prop1,
      object: prop2,
      formatted: `${prop1} ≡ ${prop2}`
    };
  }

  // Parse SubObjectPropertyOf: SubObjectPropertyOf(<subProp> <superProp>)
  const subObjPropMatch = readable.match(/SubObjectPropertyOf\s*\(\s*<?([^>\s]+)>?\s+<?([^>\s]+)>?\s*\)/i);
  if (subObjPropMatch) {
    const subProp = clean(subObjPropMatch[1]);
    const superProp = clean(subObjPropMatch[2]);
    return {
      type: 'SubObjectPropertyOf',
      subject: subProp,
      object: superProp,
      formatted: `${subProp} ⊆ ${superProp}`
    };
  }

  // Parse SubDataPropertyOf: SubDataPropertyOf(<subProp> <superProp>)
  const subDataPropMatch = readable.match(/SubDataPropertyOf\s*\(\s*<?([^>\s]+)>?\s+<?([^>\s]+)>?\s*\)/i);
  if (subDataPropMatch) {
    const subProp = clean(subDataPropMatch[1]);
    const superProp = clean(subDataPropMatch[2]);
    return {
      type: 'SubDataPropertyOf',
      subject: subProp,
      object: superProp,
      formatted: `${subProp} ⊆ ${superProp}`
    };
  }

  // Parse Declaration: Declaration(Class(<class>)) or Declaration(ObjectProperty(<prop>)) etc.
  const declarationMatch = readable.match(/Declaration\s*\(\s*(Class|ObjectProperty|DataProperty|NamedIndividual|AnnotationProperty)\s*\(\s*<?([^>\s)]+)>?\s*\)\s*\)/i);
  if (declarationMatch) {
    const entityType = declarationMatch[1];
    const entity = clean(declarationMatch[2]);
    return {
      type: 'Declaration',
      subject: entity,
      object: entityType,
      formatted: `${entityType}: ${entity}`
    };
  }

  // Parse InverseObjectProperties: InverseObjectProperties(<prop1> <prop2>)
  const inversePropsMatch = readable.match(/InverseObjectProperties\s*\(\s*<?([^>\s]+)>?\s+<?([^>\s]+)>?\s*\)/i);
  if (inversePropsMatch) {
    const prop1 = clean(inversePropsMatch[1]);
    const prop2 = clean(inversePropsMatch[2]);
    return {
      type: 'InverseObjectProperties',
      subject: prop1,
      object: prop2,
      formatted: `${prop1} ↔ ${prop2}`
    };
  }

  // Parse FunctionalObjectProperty: FunctionalObjectProperty(<prop>)
  const funcObjPropMatch = readable.match(/FunctionalObjectProperty\s*\(\s*<?([^>\s]+)>?\s*\)/i);
  if (funcObjPropMatch) {
    const prop = clean(funcObjPropMatch[1]);
    return {
      type: 'FunctionalObjectProperty',
      subject: prop,
      formatted: `${prop} (functional)`
    };
  }

  // Parse TransitiveObjectProperty: TransitiveObjectProperty(<prop>)
  const transitiveMatch = readable.match(/TransitiveObjectProperty\s*\(\s*<?([^>\s]+)>?\s*\)/i);
  if (transitiveMatch) {
    const prop = clean(transitiveMatch[1]);
    return {
      type: 'TransitiveObjectProperty',
      subject: prop,
      formatted: `${prop} (transitive)`
    };
  }

  // Parse SymmetricObjectProperty: SymmetricObjectProperty(<prop>)
  const symmetricMatch = readable.match(/SymmetricObjectProperty\s*\(\s*<?([^>\s]+)>?\s*\)/i);
  if (symmetricMatch) {
    const prop = clean(symmetricMatch[1]);
    return {
      type: 'SymmetricObjectProperty',
      subject: prop,
      formatted: `${prop} (symmetric)`
    };
  }

  // Parse DisjointClasses: DisjointClasses(<class1> <class2> ...)
  const disjointMatch = readable.match(/DisjointClasses\s*\(\s*<?([^>\s]+)>?\s+<?([^>\s]+)>?/i);
  if (disjointMatch) {
    const cls1 = clean(disjointMatch[1]);
    const cls2 = clean(disjointMatch[2]);
    return {
      type: 'DisjointClasses',
      subject: cls1,
      object: cls2,
      formatted: `${cls1} ⊥ ${cls2}`
    };
  }
  
  // Fallback: just extract local names from any URIs in the readable string
  const simplified = readable.replace(/<[^>]+#([^>]+)>/g, '$1').replace(/<[^>]+\/([^>/]+)>/g, '$1');
  return {
    type: axiom.axiomType || 'Unknown',
    subject: simplified,
    formatted: simplified || readable
  };
};

// Group axioms by type for better organization
// Enhanced grouping with raw axiom data preserved for tooltips
interface GroupedAxiomItem {
  formatted: string;
  subject: string;
  object?: string;
  rawReadable: string;
  axiomType: string;
}

const groupAxiomsByType = (axioms: InferredAxiom[]): Map<string, GroupedAxiomItem[]> => {
  const groups = new Map<string, GroupedAxiomItem[]>();
  
  axioms.forEach(axiom => {
    const parsed = formatInferredAxiom(axiom);
    const type = parsed.type;
    if (!groups.has(type)) {
      groups.set(type, []);
    }
    groups.get(type)!.push({
      formatted: parsed.formatted,
      subject: parsed.subject || '(unknown)',
      object: parsed.object,
      rawReadable: axiom.readable || axiom.description || '',
      axiomType: axiom.axiomType || type
    });
  });
  
  return groups;
};

// Axiom type icons and colors for better visual distinction
const getAxiomTypeStyle = (type: string): { icon: string; bgColor: string; textColor: string; borderColor: string } => {
  const styles: Record<string, { icon: string; bgColor: string; textColor: string; borderColor: string }> = {
    'ClassAssertion': { icon: '🏷️', bgColor: 'bg-blue-50', textColor: 'text-blue-800', borderColor: 'border-blue-200' },
    'ObjectPropertyAssertion': { icon: '🔗', bgColor: 'bg-green-50', textColor: 'text-green-800', borderColor: 'border-green-200' },
    'DataPropertyAssertion': { icon: '📊', bgColor: 'bg-amber-50', textColor: 'text-amber-800', borderColor: 'border-amber-200' },
    'SubClassOf': { icon: '⊆', bgColor: 'bg-purple-50', textColor: 'text-purple-800', borderColor: 'border-purple-200' },
    'EquivalentClasses': { icon: '≡', bgColor: 'bg-indigo-50', textColor: 'text-indigo-800', borderColor: 'border-indigo-200' },
    'EquivalentObjectProperties': { icon: '⇔', bgColor: 'bg-teal-50', textColor: 'text-teal-800', borderColor: 'border-teal-200' },
    'SubObjectPropertyOf': { icon: '⊑', bgColor: 'bg-cyan-50', textColor: 'text-cyan-800', borderColor: 'border-cyan-200' },
    'ObjectPropertyDomain': { icon: '🎯', bgColor: 'bg-rose-50', textColor: 'text-rose-800', borderColor: 'border-rose-200' },
    'ObjectPropertyRange': { icon: '🎪', bgColor: 'bg-pink-50', textColor: 'text-pink-800', borderColor: 'border-pink-200' },
    'DataPropertyDomain': { icon: '📍', bgColor: 'bg-orange-50', textColor: 'text-orange-800', borderColor: 'border-orange-200' },
    'DataPropertyRange': { icon: '📐', bgColor: 'bg-lime-50', textColor: 'text-lime-800', borderColor: 'border-lime-200' },
    'SameIndividual': { icon: '👥', bgColor: 'bg-violet-50', textColor: 'text-violet-800', borderColor: 'border-violet-200' },
    'DifferentIndividuals': { icon: '👤', bgColor: 'bg-fuchsia-50', textColor: 'text-fuchsia-800', borderColor: 'border-fuchsia-200' },
  };
  return styles[type] || { icon: '📄', bgColor: 'bg-gray-50', textColor: 'text-gray-800', borderColor: 'border-gray-200' };
};

// Individual axiom card component with enhanced display
const AxiomCard: React.FC<{ item: GroupedAxiomItem; index: number }> = ({ item, index }) => {
  const [showTooltip, setShowTooltip] = useState(false);
  const displaySubject = item.subject || item.formatted || `Item ${index + 1}`;
  
  return (
    <div 
      className="relative group"
      onMouseEnter={() => setShowTooltip(true)}
      onMouseLeave={() => setShowTooltip(false)}
    >
      <div className="inline-flex items-center gap-2 px-3 py-2 bg-white border-2 border-gray-200 rounded-lg text-sm hover:border-purple-300 hover:shadow-sm transition-all cursor-default min-w-[80px]">
        <span className="font-semibold text-gray-900 truncate max-w-[200px]" title={displaySubject}>
          {displaySubject}
        </span>
        {item.object && (
          <>
            <span className="text-purple-500 font-bold">→</span>
            <span className="font-semibold text-purple-700 truncate max-w-[200px]" title={item.object}>
              {item.object}
            </span>
          </>
        )}
      </div>
      {/* Tooltip with full axiom */}
      {showTooltip && item.rawReadable && (
        <div className="absolute z-50 bottom-full left-0 mb-2 p-3 bg-gray-900 text-white text-xs rounded-lg shadow-xl max-w-md whitespace-pre-wrap break-all">
          <div className="font-semibold mb-1 text-purple-300">{item.axiomType}</div>
          <div className="font-mono text-gray-200">{item.rawReadable}</div>
          <div className="absolute bottom-0 left-4 transform translate-y-1/2 rotate-45 w-2 h-2 bg-gray-900"></div>
        </div>
      )}
    </div>
  );
};

const ResultsPanel: React.FC<ResultsPanelProps> = ({ results, isExecuting }) => {
  const [showInferredAxioms, setShowInferredAxioms] = useState(true);
  const [viewMode, setViewMode] = useState<'grouped' | 'table' | 'raw'>('grouped');
  const [expandedGroups, setExpandedGroups] = useState<Set<string>>(new Set());
  const [searchFilter, setSearchFilter] = useState('');
  const [showWarning, setShowWarning] = useState(false);

  const toggleGroup = (type: string) => {
    const newExpanded = new Set(expandedGroups);
    if (newExpanded.has(type)) {
      newExpanded.delete(type);
    } else {
      newExpanded.add(type);
    }
    setExpandedGroups(newExpanded);
  };

  const expandAll = () => {
    if (results) {
      const allTypes = new Set(results.inferredAxioms.map(ax => formatInferredAxiom(ax).type));
      setExpandedGroups(allTypes);
    }
  };

  const collapseAll = () => {
    setExpandedGroups(new Set());
  };

  if (!results) return (
    <div className="p-8 text-center text-gray-400">
      <AlertCircle size={48} className="mx-auto mb-4 opacity-50" />
      <p className="text-lg">Run rule execution to see results.</p>
      <p className="text-sm mt-2">Click "Execute All Rules" to run SWRL inference</p>
    </div>
  );

  // Filter axioms by search term
  const filteredAxioms = searchFilter.trim() 
    ? results.inferredAxioms.filter(ax => {
        const searchLower = searchFilter.toLowerCase();
        const readable = (ax.readable || ax.description || '').toLowerCase();
        const parsed = formatInferredAxiom(ax);
        return readable.includes(searchLower) || 
               parsed.subject?.toLowerCase().includes(searchLower) ||
               parsed.object?.toLowerCase().includes(searchLower) ||
               parsed.type?.toLowerCase().includes(searchLower);
      })
    : results.inferredAxioms;

  const groupedAxioms = groupAxiomsByType(filteredAxioms);
  
  // Highlight SWRL-specific inferences (ClassAssertion = individuals classified by rules)
  const classAssertions = results.inferredAxioms.filter(ax => {
    const parsed = formatInferredAxiom(ax);
    return parsed.type === 'ClassAssertion';
  });
  
  const hasSwrlResults = classAssertions.length > 0;

  return (
    <div className="h-full overflow-y-auto">
    <div className="p-4 space-y-4">
      {/* Execution Summary Card */}
      <div className="bg-gradient-to-r from-white to-gray-50 rounded-xl border-2 border-gray-200 p-5 shadow-sm">
        <h3 className="font-bold text-gray-800 mb-4 text-lg flex items-center gap-2">
          <BarChart2 size={20} className="text-purple-600" />
          Execution Summary
          {results.executionMode && (
            <span className={`text-xs px-2 py-0.5 rounded-full ${results.executionMode === 'selected' ? 'bg-blue-100 text-blue-700' : 'bg-green-100 text-green-700'}`}>
              {results.executionMode === 'selected' ? 'Selected Rules' : 'All Rules'}
            </span>
          )}
        </h3>
        <div className="grid grid-cols-4 gap-4">
          <div className="bg-white rounded-lg p-3 border border-gray-100 shadow-sm">
            <div className="text-xs text-gray-500 uppercase tracking-wide mb-1">Status</div>
            <div className={`text-lg font-bold flex items-center gap-2 ${results.success ? 'text-green-600' : 'text-red-600'}`}>
              {results.success ? <Check size={20} /> : <X size={20} />}
              {results.success ? 'Success' : 'Failed'}
            </div>
          </div>
          <div className="bg-white rounded-lg p-3 border border-gray-100 shadow-sm">
            <div className="text-xs text-gray-500 uppercase tracking-wide mb-1">Time</div>
            <div className="text-lg font-bold text-gray-800">{results.executionTimeMs}ms</div>
          </div>
          <div className="bg-white rounded-lg p-3 border border-gray-100 shadow-sm">
            <div className="text-xs text-gray-500 uppercase tracking-wide mb-1">Rules Run</div>
            <div className="text-lg font-bold text-gray-800">{results.totalRulesExecuted}</div>
          </div>
          <div className="bg-white rounded-lg p-3 border border-gray-100 shadow-sm">
            <div className="text-xs text-gray-500 uppercase tracking-wide mb-1">Inferred</div>
            <div className="text-lg font-bold text-purple-600">{results.inferredAxiomsCount}</div>
          </div>
        </div>
        
        {/* Show executed rule names if available */}
        {results.executedRuleNames && results.executedRuleNames.length > 0 && (
          <div className="mt-4 pt-4 border-t border-gray-200">
            <div className="text-xs text-gray-500 uppercase tracking-wide mb-2">Executed Rules</div>
            <div className="flex flex-wrap gap-2">
              {results.executedRuleNames.map((name, idx) => (
                <span key={idx} className="inline-flex items-center gap-1 px-2 py-1 bg-purple-50 text-purple-700 text-xs rounded-full border border-purple-200">
                  <Check size={12} className="text-green-500" />
                  {name}
                </span>
              ))}
            </div>
          </div>
        )}
      </div>

      {/* SWRL Rule Results - ClassAssertions (individuals classified by rules) */}
      {results.success && hasSwrlResults && (
        <div className="bg-gradient-to-r from-green-50 to-emerald-50 rounded-xl border-2 border-green-300 overflow-hidden shadow-md">
          <div className="p-4 border-b border-green-200 flex justify-between items-center">
            <h3 className="font-bold text-green-800 text-lg flex items-center gap-2">
              <span className="text-2xl">🎯</span>
              SWRL Rule Results
              <span className="text-sm font-normal text-green-600">({classAssertions.length} individuals classified)</span>
            </h3>
          </div>
          <div className="p-4">
            <div className="flex flex-wrap gap-2">
              {classAssertions.map((ax, i) => {
                const parsed = formatInferredAxiom(ax);
                return (
                  <div
                    key={i}
                    className="px-3 py-2 bg-white rounded-lg border border-green-200 shadow-sm hover:shadow-md transition-all cursor-default"
                    title={ax.readable || ax.description}
                  >
                    <span className="font-bold text-green-700">{parsed.subject}</span>
                    <span className="mx-2 text-gray-400">→</span>
                    <span className="font-bold text-emerald-600">{parsed.object}</span>
                  </div>
                );
              })}
            </div>
          </div>
        </div>
      )}

      {/* No SWRL Results Warning */}
      {results.success && !hasSwrlResults && results.inferredAxioms.length > 0 && (
        <div className="bg-yellow-50 border-2 border-yellow-300 rounded-xl overflow-hidden">
          <div 
            className="p-4 flex items-start gap-3 cursor-pointer hover:bg-yellow-100/50 transition-colors"
            onClick={() => setShowWarning(!showWarning)}
          >
            <AlertCircle size={24} className="text-yellow-600 mt-0.5 flex-shrink-0" />
            <div className="flex-1">
              <div className="flex justify-between items-center">
                <div className="font-bold text-yellow-800">No SWRL ClassAssertion Results</div>
                {showWarning ? <ChevronUp size={20} className="text-yellow-600" /> : <ChevronDown size={20} className="text-yellow-600" />}
              </div>
              {!showWarning && (
                 <div className="text-sm text-yellow-700 mt-1">
                   {results.inferredAxioms.length} general OWL inferences found. Click to see details.
                 </div>
              )}
            </div>
          </div>
          
          {showWarning && (
            <div className="px-4 pb-4 pl-12">
              <div className="text-sm text-yellow-700 mt-1">
                Your SWRL rule should create ClassAssertion axioms (e.g., "Emma → Adult"). 
                The {results.inferredAxioms.length} inferences below are general OWL reasoning results, not from SWRL rules.
                <br /><br />
                <strong>Most Common Issue:</strong>
                <div className="mt-2 p-3 bg-yellow-100 rounded-lg border border-yellow-200">
                  <span className="font-bold text-yellow-900">The target class doesn't exist!</span>
                  <p className="text-yellow-800 mt-1">
                    If your rule is <code className="bg-yellow-200 px-1 rounded">Person(?p) → Adult(?p)</code>, 
                    you need to <strong>first create the "Adult" class</strong> in your ontology's Classes tab.
                  </p>
                </div>
                <br />
                <strong>Other possible reasons:</strong>
                <ul className="list-disc list-inside mt-1 space-y-1">
                  <li>No individuals match the rule conditions (e.g., no persons with age ≥ 18)</li>
                  <li>Missing data properties (hasAge) on Person individuals</li>
                  <li>The rule has syntax errors in the body</li>
                </ul>
              </div>
            </div>
          )}
        </div>
      )}

      {/* Error Message */}
      {!results.success && results.errorMessage && (
        <div className="bg-red-50 border-2 border-red-200 rounded-xl p-4">
          <div className="flex items-start gap-3">
            <AlertCircle size={24} className="text-red-600 mt-0.5 flex-shrink-0" />
            <div>
              <div className="font-bold text-red-800 text-lg">Execution Failed</div>
              <div className="text-sm text-red-700 mt-2 font-mono bg-red-100 p-2 rounded">{results.errorMessage}</div>
            </div>
          </div>
        </div>
      )}

      {/* Inferred Axioms Section */}
      {results.success && results.inferredAxioms.length > 0 && (
        <div className="bg-white rounded-xl border-2 border-gray-200 overflow-hidden shadow-sm">
          {/* Header with controls */}
          <div className="p-4 bg-gradient-to-r from-purple-50 to-white border-b-2 border-gray-100 flex flex-col gap-3">
            <div className="flex justify-between items-center">
              <h3 className="font-bold text-gray-800 text-lg flex items-center gap-2">
                <span className="text-purple-600">📚</span>
                All Inferred Axioms 
                <span className="text-sm font-normal text-gray-500">
                  ({searchFilter ? `${filteredAxioms.length} of ${results.inferredAxiomsCount}` : results.inferredAxiomsCount} total, {groupedAxioms.size} types)
                </span>
              </h3>
              <div className="flex items-center gap-3">
                {/* View mode toggle */}
                <div className="flex items-center gap-1 bg-gray-100 rounded-lg p-1">
                  <button
                    onClick={() => setViewMode('grouped')}
                    className={`px-3 py-1.5 text-xs font-medium rounded transition-all ${viewMode === 'grouped' ? 'bg-white shadow text-purple-700' : 'text-gray-600 hover:text-gray-800'}`}
                    title="Grouped by axiom type"
                  >
                    📊 Grouped
                  </button>
                  <button
                    onClick={() => setViewMode('table')}
                    className={`px-3 py-1.5 text-xs font-medium rounded transition-all ${viewMode === 'table' ? 'bg-white shadow text-purple-700' : 'text-gray-600 hover:text-gray-800'}`}
                    title="Table view"
                  >
                    📋 Table
                  </button>
                  <button
                    onClick={() => setViewMode('raw')}
                    className={`px-3 py-1.5 text-xs font-medium rounded transition-all ${viewMode === 'raw' ? 'bg-white shadow text-purple-700' : 'text-gray-600 hover:text-gray-800'}`}
                    title="Raw axiom format"
                  >
                    📝 Raw
                  </button>
                </div>
                {/* Expand/Collapse buttons for grouped view */}
                {viewMode === 'grouped' && (
                  <div className="flex gap-1">
                    <button onClick={expandAll} className="px-2 py-1 text-xs text-purple-600 hover:bg-purple-50 rounded" title="Expand all">
                      <Maximize2 size={14} />
                    </button>
                    <button onClick={collapseAll} className="px-2 py-1 text-xs text-purple-600 hover:bg-purple-50 rounded" title="Collapse all">
                      <Minimize2 size={14} />
                    </button>
                  </div>
                )}
                {/* Show/Hide toggle */}
                <button
                  onClick={() => setShowInferredAxioms(!showInferredAxioms)}
                className="flex items-center gap-1 px-3 py-1.5 text-sm text-purple-600 hover:bg-purple-50 rounded-lg transition-colors"
              >
                {showInferredAxioms ? <EyeOff size={16} /> : <Eye size={16} />}
                {showInferredAxioms ? 'Hide' : 'Show'}
              </button>
            </div>
          </div>
          {/* Search Filter */}
          {showInferredAxioms && (
            <div className="px-4 pb-3">
              <div className="relative">
                <Search size={16} className="absolute left-3 top-1/2 -translate-y-1/2 text-gray-400" />
                <input
                  type="text"
                  placeholder="Search axioms... (e.g., Adult, Person, hasAge)"
                  value={searchFilter}
                  onChange={(e) => setSearchFilter(e.target.value)}
                  className="w-full pl-10 pr-8 py-2 text-sm border border-gray-200 rounded-lg focus:outline-none focus:ring-2 focus:ring-purple-300 focus:border-purple-400"
                />
                {searchFilter && (
                  <button
                    onClick={() => setSearchFilter('')}
                    className="absolute right-3 top-1/2 -translate-y-1/2 text-gray-400 hover:text-gray-600"
                  >
                    <X size={14} />
                  </button>
                )}
              </div>
              {searchFilter && (
                <div className="mt-2 text-xs text-gray-500">
                  Found {filteredAxioms.length} axioms matching "{searchFilter}"
                </div>
              )}
            </div>
          )}
          </div>

          {/* Grouped View */}
          {showInferredAxioms && viewMode === 'grouped' && (
            <div className="max-h-[70vh] overflow-y-auto">
              {Array.from(groupedAxioms.entries()).map(([type, items]) => {
                const style = getAxiomTypeStyle(type);
                const isExpanded = expandedGroups.has(type);
                return (
                  <div key={type} className="border-b border-gray-100 last:border-b-0">
                    {/* Group header - clickable to expand/collapse */}
                    <button
                      onClick={() => toggleGroup(type)}
                      className={`w-full p-4 flex items-center justify-between hover:bg-gray-50 transition-colors ${style.bgColor}`}
                    >
                      <div className="flex items-center gap-3">
                        <span className="text-xl">{style.icon}</span>
                        <span className={`text-sm font-bold ${style.textColor} px-3 py-1 rounded-full ${style.bgColor} border ${style.borderColor}`}>
                          {type}
                        </span>
                        <span className="text-sm text-gray-600 font-medium">
                          {items.length} inferred
                        </span>
                      </div>
                      <ChevronRight size={20} className={`text-gray-400 transition-transform ${isExpanded ? 'rotate-90' : ''}`} />
                    </button>
                    {/* Group content */}
                    {isExpanded && (
                      <div className="p-4 pt-2 bg-white">
                        <div className="flex flex-wrap gap-2">
                          {items.map((item, i) => (
                            <AxiomCard key={i} item={item} index={i} />
                          ))}
                        </div>
                      </div>
                    )}
                  </div>
                );
              })}
            </div>
          )}

          {/* Table View */}
          {showInferredAxioms && viewMode === 'table' && (
            <div className="max-h-[70vh] overflow-auto">
              <table className="w-full text-sm">
                <thead className="bg-gray-50 sticky top-0">
                  <tr>
                    <th className="px-4 py-3 text-left font-semibold text-gray-700 border-b">#</th>
                    <th className="px-4 py-3 text-left font-semibold text-gray-700 border-b">Type</th>
                    <th className="px-4 py-3 text-left font-semibold text-gray-700 border-b">Subject</th>
                    <th className="px-4 py-3 text-left font-semibold text-gray-700 border-b">Relation</th>
                    <th className="px-4 py-3 text-left font-semibold text-gray-700 border-b">Object</th>
                  </tr>
                </thead>
                <tbody className="divide-y divide-gray-100">
                  {filteredAxioms.map((ax, i) => {
                    const parsed = formatInferredAxiom(ax);
                    const style = getAxiomTypeStyle(parsed.type);
                    return (
                      <tr key={i} className="hover:bg-gray-50" title={ax.readable}>
                        <td className="px-4 py-2 text-gray-500 font-mono text-xs">{i + 1}</td>
                        <td className="px-4 py-2">
                          <span className={`inline-flex items-center gap-1 text-xs font-semibold px-2 py-1 rounded ${style.bgColor} ${style.textColor}`}>
                            {style.icon} {parsed.type}
                          </span>
                        </td>
                        <td className="px-4 py-2 font-medium text-gray-900 max-w-[200px] truncate">{parsed.subject || '-'}</td>
                        <td className="px-4 py-2 text-purple-500 font-bold text-center">{parsed.predicate || '→'}</td>
                        <td className="px-4 py-2 font-medium text-purple-700 max-w-[200px] truncate">{parsed.object || '-'}</td>
                      </tr>
                    );
                  })}
                </tbody>
              </table>
            </div>
          )}

          {/* Raw View */}
          {showInferredAxioms && viewMode === 'raw' && (
            <div className="max-h-[70vh] overflow-y-auto">
              {filteredAxioms.map((ax, i) => {
                const style = getAxiomTypeStyle(ax.axiomType);
                return (
                  <div key={i} className={`p-4 border-b border-gray-100 hover:bg-gray-50 ${i % 2 === 0 ? 'bg-white' : 'bg-gray-50/50'}`}>
                    <div className="flex items-center gap-2 mb-2">
                      <span className="text-gray-400 font-mono text-xs">#{i + 1}</span>
                      <span className={`inline-flex items-center gap-1 text-xs font-semibold px-2 py-1 rounded ${style.bgColor} ${style.textColor} border ${style.borderColor}`}>
                        {style.icon} {ax.axiomType}
                      </span>
                    </div>
                    <pre className="text-sm text-gray-800 font-mono bg-gray-100 p-3 rounded-lg overflow-x-auto whitespace-pre-wrap break-all">{ax.readable}</pre>
                  </div>
                );
              })}
            </div>
          )}
        </div>
      )}

      {/* No Results Message */}
      {results.success && results.inferredAxiomsCount === 0 && (
        <div className="bg-gradient-to-r from-yellow-50 to-amber-50 border-2 border-yellow-200 rounded-xl p-6 text-center">
          <div className="text-4xl mb-3">🔍</div>
          <p className="text-yellow-800 font-semibold text-lg">No New Axioms Inferred</p>
          <p className="text-yellow-700 text-sm mt-2">
            The rules executed successfully, but no new knowledge was derived.
            This may happen if the conditions aren't met or axioms already exist.
          </p>
        </div>
      )}
    </div>
    </div>
  );
};

// ============================================================================
// MAIN COMPONENT
// ============================================================================

interface SWRLEditorProps {
  projectId: string;
  context?: PluginContext;
}

export const SWRLEditor: React.FC<SWRLEditorProps> = ({ projectId, context }) => {

  // State
  const [rules, setRules] = useState<SwrlRule[]>([]);
  const [selectedRule, setSelectedRule] = useState<SwrlRule | null>(null);
  const [selectedRuleIds, setSelectedRuleIds] = useState<Set<string>>(new Set());
  const [isLoading, setIsLoading] = useState(true);
  const [isEditing, setIsEditing] = useState(false);
  const [isSaving, setIsSaving] = useState(false);
  const [isExecuting, setIsExecuting] = useState(false);
  const [executionResult, setExecutionResult] = useState<ExecutionResponse | null>(null);
  const [validationResult, setValidationResult] = useState<SwrlValidationResult | null>(null);
  const [activePanel, setActivePanel] = useState<'editor' | 'results' | 'reference' | 'query'>('editor');
  const [expandedBuiltInCategory, setExpandedBuiltInCategory] = useState<string | null>(null);
  const [confirmDialog, setConfirmDialog] = useState<{ isOpen: boolean; ruleName: string; ruleId: string }>({ isOpen: false, ruleName: '', ruleId: '' });
  const [searchQuery, setSearchQuery] = useState('');

  // Form state
  const [editForm, setEditForm] = useState({
    ruleName: '',
    ruleText: '',
    comment: '',
    category: '',
    enabled: true
  });

  const fileInputRef = useRef<HTMLInputElement>(null);
  const textareaRef = useRef<HTMLTextAreaElement>(null);

  // Debounced validation
  const debouncedRuleText = useDebounce(editForm.ruleText, 500);

  // Load rules
  const loadRules = useCallback(async () => {
    setIsLoading(true);
    try {
      const res = await apiClient.get<{ content: SwrlRule[] }>(`/api/swrl/${projectId}/rules`);
      setRules(res.content || []);
    } catch (e) {
      console.error('Failed to load rules:', e);
    } finally {
      setIsLoading(false);
    }
  }, [projectId]);

  useEffect(() => { loadRules(); }, [loadRules]);

  // Validate rule
  useEffect(() => {
    if (!debouncedRuleText.trim() || !isEditing) {
      setValidationResult(null);
      return;
    }

    const validate = async () => {
      try {
        const res = await apiClient.post<SwrlValidationResult>(
          `/api/swrl/${projectId}/rules/validate`,
          { ruleText: debouncedRuleText }
        );
        setValidationResult(res);
      } catch (e) {
        setValidationResult({ valid: false, errorMessage: 'Validation failed' });
      }
    };
    validate();
  }, [debouncedRuleText, projectId, isEditing]);

  // Filtered rules
  const filteredRules = useMemo(() => {
    if (!searchQuery.trim()) return rules;
    const q = searchQuery.toLowerCase();
    return rules.filter(r => 
      r.ruleName.toLowerCase().includes(q) ||
      r.ruleText.toLowerCase().includes(q) ||
      (r.category || '').toLowerCase().includes(q)
    );
  }, [rules, searchQuery]);

  const enabledCount = rules.filter(r => r.enabled).length;

  // Handlers
  const handleSelectRule = (rule: SwrlRule) => {
    setSelectedRule(rule);
    setEditForm({
      ruleName: rule.ruleName,
      ruleText: rule.ruleText,
      comment: rule.comment || '',
      category: rule.category || '',
      enabled: rule.enabled
    });
    setIsEditing(false);
    setValidationResult(null);
    setActivePanel('editor');
  };

  const handleNewRule = () => {
    setSelectedRule(null);
    setEditForm({
      ruleName: 'New Rule',
      ruleText: '',
      comment: '',
      category: '',
      enabled: true
    });
    setIsEditing(true);
    setValidationResult(null);
    setActivePanel('editor');
  };

  const handleSave = async () => {
    if (!editForm.ruleName.trim() || !editForm.ruleText.trim()) return;
    setIsSaving(true);

    try {
      if (selectedRule) {
        const res = await apiClient.put<SwrlRule>(
          `/api/swrl/${projectId}/rules/${selectedRule.id}`,
          editForm
        );
        setRules(rules.map(r => r.id === res.id ? res : r));
        setSelectedRule(res);
      } else {
        const res = await apiClient.post<SwrlRule>(
          `/api/swrl/${projectId}/rules`,
          editForm
        );
        setRules([...rules, res]);
        setSelectedRule(res);
      }
      setIsEditing(false);
    } catch (e) {
      console.error('Save failed:', e);
    } finally {
      setIsSaving(false);
    }
  };

  const handleDelete = async () => {
    if (!confirmDialog.ruleId) return;
    try {
      await apiClient.delete(`/api/swrl/${projectId}/rules/${confirmDialog.ruleId}`);
      setRules(rules.filter(r => r.id !== confirmDialog.ruleId));
      if (selectedRule?.id === confirmDialog.ruleId) {
        setSelectedRule(null);
        setEditForm({ ruleName: '', ruleText: '', comment: '', category: '', enabled: true });
      }
      setSelectedRuleIds(prev => {
        const next = new Set(prev);
        next.delete(confirmDialog.ruleId);
        return next;
      });
    } catch (e) {
      console.error('Delete failed:', e);
    } finally {
      setConfirmDialog({ isOpen: false, ruleName: '', ruleId: '' });
    }
  };

  const handleToggleEnabled = async (rule: SwrlRule) => {
    try {
      const res = await apiClient.put<SwrlRule>(
        `/api/swrl/${projectId}/rules/${rule.id}`,
        { enabled: !rule.enabled }
      );
      setRules(rules.map(r => r.id === rule.id ? res : r));
      if (selectedRule?.id === rule.id) {
        setSelectedRule(res);
        setEditForm(prev => ({ ...prev, enabled: res.enabled }));
      }
    } catch (e) {
      console.error('Toggle failed:', e);
    }
  };

  const toggleRuleSelection = (ruleId: string) => {
    setSelectedRuleIds(prev => {
      const next = new Set(prev);
      if (next.has(ruleId)) next.delete(ruleId);
      else next.add(ruleId);
      return next;
    });
  };

  const handleExecuteAll = async () => {
    setIsExecuting(true);
    setExecutionResult(null);
    setActivePanel('results');

    try {
      const res = await apiClient.post<ExecutionResponse>(`/api/swrl/${projectId}/execute`);
      setExecutionResult(res);
    } catch (e) {
      console.error('Execution failed:', e);
      setExecutionResult({
        success: false,
        errorMessage: 'Execution failed',
        executionTimeMs: 0,
        totalRulesExecuted: 0,
        inferredAxiomsCount: 0,
        inferredAxioms: []
      } as ExecutionResponse);
    } finally {
      setIsExecuting(false);
    }
  };

  const handleExecuteSelected = async () => {
    if (selectedRuleIds.size === 0) return;
    setIsExecuting(true);
    setExecutionResult(null);
    setActivePanel('results');

    try {
      const ruleIds = Array.from(selectedRuleIds);
      const res = await apiClient.post<ExecutionResponse>(
        `/api/swrl/${projectId}/execute/selected`,
        { ruleIds }
      );
      setExecutionResult(res);
    } catch (e) {
      console.error('Execution failed:', e);
    } finally {
      setIsExecuting(false);
    }
  };

  const handleTestRule = async () => {
    if (!selectedRule) return;
    setIsExecuting(true);
    setExecutionResult(null);
    setActivePanel('results');

    try {
      const res = await apiClient.post<ExecutionResponse>(
        `/api/swrl/${projectId}/rules/${selectedRule.id}/test`
      );
      setExecutionResult(res);
    } catch (e) {
      console.error('Test failed:', e);
    } finally {
      setIsExecuting(false);
    }
  };

  const insertAtCursor = (text: string) => {
    if (!textareaRef.current) {
      setEditForm(prev => ({ ...prev, ruleText: prev.ruleText + text }));
      return;
    }
    const el = textareaRef.current;
    const start = el.selectionStart;
    const end = el.selectionEnd;
    const newText = editForm.ruleText.slice(0, start) + text + editForm.ruleText.slice(end);
    setEditForm(prev => ({ ...prev, ruleText: newText }));
    setTimeout(() => {
      el.focus();
      el.setSelectionRange(start + text.length, start + text.length);
    }, 0);
  };

  const handleExport = async () => {
    try {
      const res = await apiClient.get<SwrlRule[]>(`/api/swrl/${projectId}/rules/export`);
      const blob = new Blob([JSON.stringify(res, null, 2)], { type: 'application/json' });
      const url = URL.createObjectURL(blob);
      const a = document.createElement('a');
      a.href = url;
      a.download = `swrl-rules-${projectId}.json`;
      a.click();
      URL.revokeObjectURL(url);
    } catch (e) {
      console.error('Export failed:', e);
    }
  };

  const handleImport = async (e: React.ChangeEvent<HTMLInputElement>) => {
    const file = e.target.files?.[0];
    if (!file) return;

    const reader = new FileReader();
    reader.onload = async (ev) => {
      try {
        const content = JSON.parse(ev.target?.result as string);
        await apiClient.post(`/api/swrl/${projectId}/rules/import`, content);
        loadRules();
      } catch (err) {
        console.error('Import failed:', err);
      }
    };
    reader.readAsText(file);
    if (fileInputRef.current) fileInputRef.current.value = '';
  };

  return (
    <div className="h-full flex flex-col bg-gray-50">
      {/* Header */}
      <header className="bg-white border-b border-gray-200 px-4 py-3">
        <div className="flex items-center justify-between">
          <div>
            <h1 className="text-lg font-bold text-gray-900">SWRL Rule Editor</h1>
            <p className="text-xs text-gray-500">{enabledCount} of {rules.length} rules enabled</p>
          </div>
          <div className="flex items-center gap-2">
            {/* Tools */}
            <div className="flex items-center gap-1 border-r border-gray-200 pr-2 mr-2">
              <button onClick={handleExport} className="p-2 text-gray-500 hover:bg-gray-100 rounded-lg" title="Export">
                <Download size={16} />
              </button>
              <button onClick={() => fileInputRef.current?.click()} className="p-2 text-gray-500 hover:bg-gray-100 rounded-lg" title="Import">
                <Upload size={16} />
              </button>
              <input ref={fileInputRef} type="file" accept=".json" onChange={handleImport} className="hidden" />
              <button onClick={loadRules} className="p-2 text-gray-500 hover:bg-gray-100 rounded-lg" title="Refresh">
                <RefreshCw size={16} />
              </button>
            </div>

            {/* Execute buttons */}
            {selectedRuleIds.size > 0 && (
              <button
                onClick={handleExecuteSelected}
                disabled={isExecuting}
                className="flex items-center gap-2 px-3 py-1.5 bg-green-600 text-white text-sm rounded-lg hover:bg-green-700 disabled:bg-green-300"
              >
                {isExecuting ? <Loader2 size={14} className="animate-spin" /> : <Play size={14} />}
                Run {selectedRuleIds.size} Selected
              </button>
            )}
            <button
              onClick={handleExecuteAll}
              disabled={isExecuting || enabledCount === 0}
              className="flex items-center gap-2 px-4 py-1.5 bg-purple-600 text-white text-sm rounded-lg hover:bg-purple-700 disabled:bg-purple-300"
            >
              {isExecuting ? <Loader2 size={14} className="animate-spin" /> : <Zap size={14} />}
              Execute All ({enabledCount})
            </button>
          </div>
        </div>
      </header>

      {/* Main Content */}
      <div className="flex-1 flex overflow-hidden">
        {/* Left: Rule List */}
        <aside className="w-72 bg-white border-r border-gray-200 flex flex-col">
          {/* New Rule + Search */}
          <div className="p-3 border-b border-gray-100 space-y-2">
            <button
              onClick={handleNewRule}
              className="w-full flex items-center justify-center gap-2 px-3 py-2 bg-purple-600 text-white text-sm font-medium rounded-lg hover:bg-purple-700"
            >
              <Plus size={16} /> New Rule
            </button>
            <div className="relative">
              <Search className="absolute left-3 top-1/2 -translate-y-1/2 w-4 h-4 text-gray-400" />
              <input
                type="text"
                placeholder="Search rules..."
                value={searchQuery}
                onChange={e => setSearchQuery(e.target.value)}
                className="w-full pl-9 pr-3 py-1.5 text-sm border border-gray-200 rounded-lg focus:outline-none focus:ring-2 focus:ring-purple-200"
              />
            </div>
            {/* Selection controls */}
            {rules.length > 0 && (
              <div className="flex items-center gap-1 text-xs">
                <button 
                  onClick={() => setSelectedRuleIds(new Set(rules.map(r => r.id)))}
                  className="px-2 py-1 text-purple-600 hover:bg-purple-50 rounded"
                >
                  All
                </button>
                <button 
                  onClick={() => setSelectedRuleIds(new Set(rules.filter(r => r.enabled).map(r => r.id)))}
                  className="px-2 py-1 text-purple-600 hover:bg-purple-50 rounded"
                >
                  Enabled
                </button>
                <button 
                  onClick={() => setSelectedRuleIds(new Set())}
                  className="px-2 py-1 text-gray-500 hover:bg-gray-100 rounded"
                >
                  None
                </button>
              </div>
            )}
          </div>

          {/* Rule List */}
          <div className="flex-1 overflow-y-auto">
            {isLoading ? (
              <div className="flex items-center justify-center p-8">
                <Loader2 className="w-8 h-8 text-purple-500 animate-spin" />
              </div>
            ) : filteredRules.length === 0 ? (
              <div className="p-6 text-center text-gray-400">
                {searchQuery ? 'No matching rules' : 'No rules yet'}
              </div>
            ) : (
              <div className="divide-y divide-gray-100">
                {filteredRules.map(rule => (
                  <RuleListItem
                    key={rule.id}
                    rule={rule}
                    isSelected={selectedRule?.id === rule.id}
                    isChecked={selectedRuleIds.has(rule.id)}
                    onSelect={() => handleSelectRule(rule)}
                    onToggle={() => toggleRuleSelection(rule.id)}
                    onToggleEnabled={() => handleToggleEnabled(rule)}
                    onDelete={() => setConfirmDialog({ isOpen: true, ruleName: rule.ruleName, ruleId: rule.id })}
                  />
                ))}
              </div>
            )}
          </div>
        </aside>

        {/* Right: Editor/Results */}
        <main className="flex-1 flex flex-col overflow-hidden">
          {/* Tab Bar */}
          <div className="flex items-center gap-1 px-4 py-2 bg-white border-b border-gray-200">
            <button
              onClick={() => setActivePanel('editor')}
              className={`flex items-center gap-2 px-4 py-1.5 text-sm font-medium rounded-lg transition-colors ${
                activePanel === 'editor' 
                  ? 'bg-purple-100 text-purple-700' 
                  : 'text-gray-600 hover:bg-gray-100'
              }`}
            >
              <Code size={14} /> Editor
            </button>
            <button
              onClick={() => setActivePanel('results')}
              className={`flex items-center gap-2 px-4 py-1.5 text-sm font-medium rounded-lg transition-colors ${
                activePanel === 'results' 
                  ? 'bg-purple-100 text-purple-700' 
                  : 'text-gray-600 hover:bg-gray-100'
              }`}
            >
              <BarChart2 size={14} /> Results
              {executionResult && (
                <span className={`ml-1 px-1.5 py-0.5 text-[10px] rounded-full ${
                  executionResult.success ? 'bg-green-100 text-green-700' : 'bg-red-100 text-red-700'
                }`}>
                  {executionResult.inferredAxiomsCount}
                </span>
              )}
            </button>
            <button
              onClick={() => setActivePanel('reference')}
              className={`flex items-center gap-2 px-4 py-1.5 text-sm font-medium rounded-lg transition-colors ${
                activePanel === 'reference' 
                  ? 'bg-purple-100 text-purple-700' 
                  : 'text-gray-600 hover:bg-gray-100'
              }`}
            >
              <BookOpen size={14} /> Reference
            </button>
            <button
              onClick={() => setActivePanel('query')}
              className={`flex items-center gap-2 px-4 py-1.5 text-sm font-medium rounded-lg transition-colors ${
                activePanel === 'query' 
                  ? 'bg-purple-100 text-purple-700' 
                  : 'text-gray-600 hover:bg-gray-100'
              }`}
            >
              <Search size={14} /> SQWRL Query
            </button>
          </div>

          {/* Panel Content */}
          {activePanel === 'editor' ? (
            <div className="flex-1 overflow-y-auto p-4">
              {!selectedRule && !isEditing ? (
                <div className="h-full flex items-center justify-center">
                  <div className="text-center max-w-sm">
                    <div className="w-20 h-20 bg-gray-100 rounded-full flex items-center justify-center mx-auto mb-4">
                      <FileText className="w-10 h-10 text-gray-400" />
                    </div>
                    <h3 className="font-semibold text-gray-700 mb-2">Select a Rule</h3>
                    <p className="text-sm text-gray-500 mb-4">
                      Choose a rule from the list to view or edit, or create a new one.
                    </p>
                    <button
                      onClick={handleNewRule}
                      className="inline-flex items-center gap-2 px-4 py-2 bg-purple-600 text-white text-sm rounded-lg hover:bg-purple-700"
                    >
                      <Plus size={16} /> Create New Rule
                    </button>
                  </div>
                </div>
              ) : (
                <div className="max-w-3xl mx-auto space-y-4">
                  {/* Rule Name */}
                  <div className="bg-white rounded-xl border border-gray-200 p-4">
                    <div className="flex items-center justify-between mb-3">
                      <label className="text-sm font-semibold text-gray-700">Rule Name</label>
                      {!isEditing && selectedRule && (
                        <button
                          onClick={() => setIsEditing(true)}
                          className="px-3 py-1 text-xs font-medium text-purple-600 bg-purple-50 rounded-lg hover:bg-purple-100"
                        >
                          Edit
                        </button>
                      )}
                    </div>
                    <input
                      type="text"
                      value={editForm.ruleName}
                      onChange={e => setEditForm(prev => ({ ...prev, ruleName: e.target.value }))}
                      disabled={!isEditing}
                      className="w-full px-4 py-2.5 text-base font-medium border border-gray-200 rounded-lg focus:outline-none focus:ring-2 focus:ring-purple-200 disabled:bg-gray-50 disabled:text-gray-600"
                      placeholder="Enter rule name"
                    />
                    
                    {/* Category & Status */}
                    <div className="grid grid-cols-2 gap-4 mt-4">
                      <div>
                        <label className="text-xs font-medium text-gray-500 mb-1 block">Category</label>
                        <input
                          type="text"
                          value={editForm.category}
                          onChange={e => setEditForm(prev => ({ ...prev, category: e.target.value }))}
                          disabled={!isEditing}
                          className="w-full px-3 py-2 text-sm border border-gray-200 rounded-lg focus:outline-none focus:ring-2 focus:ring-purple-200 disabled:bg-gray-50"
                          placeholder="e.g., Classification"
                        />
                      </div>
                      <div>
                        <label className="text-xs font-medium text-gray-500 mb-1 block">Status</label>
                        <button
                          onClick={() => isEditing && setEditForm(prev => ({ ...prev, enabled: !prev.enabled }))}
                          disabled={!isEditing}
                          className={`w-full px-3 py-2 text-sm font-medium rounded-lg border flex items-center gap-2 transition-colors ${
                            editForm.enabled
                              ? 'bg-green-50 border-green-200 text-green-700'
                              : 'bg-gray-50 border-gray-200 text-gray-500'
                          } ${!isEditing ? 'cursor-not-allowed' : 'cursor-pointer hover:shadow-sm'}`}
                        >
                          {editForm.enabled ? <ToggleRight size={16} /> : <ToggleLeft size={16} />}
                          {editForm.enabled ? 'Enabled' : 'Disabled'}
                        </button>
                      </div>
                    </div>
                  </div>

                  {/* Rule Expression */}
                  <div className="bg-white rounded-xl border border-gray-200 overflow-hidden">
                    <div className="flex items-center justify-between px-4 py-3 bg-gray-50 border-b border-gray-200">
                      <label className="text-sm font-semibold text-gray-700">SWRL Expression</label>
                      {/* Template dropdown */}
                      <div className="relative group">
                        <button className="px-3 py-1 text-xs font-medium text-gray-600 bg-white border border-gray-200 rounded-lg hover:bg-gray-50 flex items-center gap-1">
                          <BookOpen size={12} /> Templates
                          <ChevronDown size={12} />
                        </button>
                        <div className="absolute right-0 top-full mt-1 w-72 bg-white border border-gray-200 rounded-lg shadow-xl z-50 hidden group-hover:block">
                          {RULE_TEMPLATES.map((t, i) => (
                            <button
                              key={i}
                              onClick={() => setEditForm(prev => ({ ...prev, ruleText: t.template }))}
                              className="w-full text-left px-3 py-2 text-sm hover:bg-purple-50 border-b border-gray-100 last:border-0"
                            >
                              <div className="font-medium text-gray-800">{t.name}</div>
                              <div className="text-xs text-gray-500 truncate">{t.template}</div>
                            </button>
                          ))}
                        </div>
                      </div>
                    </div>
                    
                    <textarea
                      ref={textareaRef}
                      value={editForm.ruleText}
                      onChange={e => setEditForm(prev => ({ ...prev, ruleText: e.target.value }))}
                      disabled={!isEditing}
                      className="w-full h-32 px-4 py-3 font-mono text-sm resize-none focus:outline-none disabled:bg-gray-50 text-gray-900 disabled:text-gray-700"
                      placeholder="Person(?p) ^ hasAge(?p, ?age) ^ swrlb:greaterThan(?age, 18) -> Adult(?p)"
                    />

                    {/* Validation - only show when editing */}
                    {isEditing && validationResult && (
                      <div className={`px-4 py-2 flex items-center gap-2 text-sm ${
                        validationResult.valid ? 'bg-green-50 text-green-700' : 'bg-red-50 text-red-700'
                      }`}>
                        {validationResult.valid ? <Check size={14} /> : <AlertCircle size={14} />}
                        {validationResult.valid ? 'Valid syntax' : validationResult.errorMessage}
                      </div>
                    )}

                    {/* Quick Insert */}
                    <QuickInsertPanel onInsert={insertAtCursor} disabled={!isEditing} />
                  </div>

                  {/* Description */}
                  <div className="bg-white rounded-xl border border-gray-200 p-4">
                    <label className="text-sm font-semibold text-gray-700 mb-2 block">Description</label>
                    <textarea
                      value={editForm.comment}
                      onChange={e => setEditForm(prev => ({ ...prev, comment: e.target.value }))}
                      disabled={!isEditing}
                      className="w-full h-20 px-3 py-2 text-sm border border-gray-200 rounded-lg resize-none focus:outline-none focus:ring-2 focus:ring-purple-200 disabled:bg-gray-50"
                      placeholder="Add notes or documentation..."
                    />
                  </div>

                  {/* Action Buttons */}
                  {isEditing && (
                    <div className="flex items-center gap-3 pt-2">
                      <button
                        onClick={() => setConfirmDialog({ isOpen: true, ruleName: selectedRule?.ruleName || '', ruleId: selectedRule?.id || '' })}
                        disabled={!selectedRule}
                        className="px-4 py-2 text-sm font-medium text-red-600 bg-red-50 rounded-lg hover:bg-red-100 disabled:opacity-50 flex items-center gap-2"
                      >
                        <Trash2 size={14} /> Delete
                      </button>
                      <div className="flex-1" />
                      <button
                        onClick={handleTestRule}
                        disabled={!selectedRule || !editForm.ruleText.trim()}
                        className="px-4 py-2 text-sm font-medium text-purple-600 bg-purple-50 rounded-lg hover:bg-purple-100 disabled:opacity-50 flex items-center gap-2"
                      >
                        <Play size={14} /> Test
                      </button>
                      <button
                        onClick={() => {
                          setIsEditing(false);
                          if (selectedRule) handleSelectRule(selectedRule);
                        }}
                        className="px-4 py-2 text-sm font-medium text-gray-600 bg-gray-100 rounded-lg hover:bg-gray-200"
                      >
                        Cancel
                      </button>
                      <button
                        onClick={handleSave}
                        disabled={!editForm.ruleName.trim() || !editForm.ruleText.trim() || isSaving}
                        className="px-5 py-2 text-sm font-medium text-white bg-purple-600 rounded-lg hover:bg-purple-700 disabled:bg-purple-300 flex items-center gap-2"
                      >
                        {isSaving ? <Loader2 size={14} className="animate-spin" /> : <Save size={14} />}
                        Save
                      </button>
                    </div>
                  )}
                </div>
              )}
            </div>
          ) : activePanel === 'results' ? (
            <ResultsPanel results={executionResult} isExecuting={isExecuting} />
          ) : activePanel === 'query' ? (
            <SQWRLQueryPanel projectId={projectId} context={context!} />
          ) : (
            <div className="flex-1 overflow-y-auto p-4 space-y-3">
              <div className="text-sm text-gray-600 mb-4">
                <p>SWRL Built-in predicates based on <a href="https://github.com/protegeproject/swrlapi" target="_blank" rel="noopener noreferrer" className="text-purple-600 hover:underline">SWRLAPI</a>. Click a built-in to insert it into your rule.</p>
              </div>
              {SWRL_BUILTINS.map(category => (
                <div key={category.name} className="border border-gray-200 rounded-lg overflow-hidden">
                  <button
                    onClick={() => setExpandedBuiltInCategory(expandedBuiltInCategory === category.name ? null : category.name)}
                    className="w-full flex items-center justify-between p-3 bg-gray-50 hover:bg-gray-100 text-left transition-colors"
                  >
                    <div>
                      <span className="font-semibold text-gray-800">{category.name}</span>
                      <span className="text-xs text-purple-600 ml-2 font-mono">{category.prefix}:</span>
                    </div>
                    {expandedBuiltInCategory === category.name ? <ChevronDown size={16} /> : <ChevronRight size={16} />}
                  </button>
                  {expandedBuiltInCategory === category.name && (
                    <div className="divide-y divide-gray-100 max-h-96 overflow-y-auto bg-white">
                      {category.builtIns.map(builtin => (
                        <div
                          key={builtin.name}
                          onClick={() => {
                            if (isEditing) {
                              setEditForm(prev => ({
                                ...prev,
                                ruleText: prev.ruleText + (prev.ruleText ? ' ^ ' : '') + builtin.signature
                              }));
                              setActivePanel('editor');
                            }
                          }}
                          className={`p-3 hover:bg-purple-50 transition-colors ${isEditing ? 'cursor-pointer' : 'cursor-default'}`}
                        >
                          <div className="flex items-center justify-between mb-1">
                            <code className="text-sm font-mono bg-purple-100 text-purple-800 px-2 py-0.5 rounded border border-purple-200">{builtin.fullName}</code>
                            {isEditing && <span className="text-xs text-gray-400">Click to insert</span>}
                          </div>
                          <p className="text-xs text-gray-600 mb-1">{builtin.description}</p>
                          <div className="flex items-center gap-2">
                            <span className="text-xs font-mono text-gray-500 bg-gray-100 px-2 py-0.5 rounded">{builtin.signature}</span>
                            {builtin.example && (
                              <span className="text-xs text-gray-400">Ex: <code className="bg-gray-50 px-1 rounded">{builtin.example}</code></span>
                            )}
                          </div>
                        </div>
                      ))}
                    </div>
                  )}
                </div>
              ))}
            </div>
          )}
        </main>
      </div>

      {/* Confirm Dialog */}
      <ConfirmDialog
        isOpen={confirmDialog.isOpen}
        title="Delete Rule"
        message={`Are you sure you want to delete "${confirmDialog.ruleName}"? This action cannot be undone.`}
        onConfirm={handleDelete}
        onCancel={() => setConfirmDialog({ isOpen: false, ruleName: '', ruleId: '' })}
        confirmText="Delete"
        danger
      />
    </div>
  );
};

export default SWRLEditor;

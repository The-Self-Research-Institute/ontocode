import React, { useState, useEffect, useCallback, useRef, useMemo } from 'react';
import { 
  Plus, Trash2, Play, Save, Check, X, AlertCircle, Loader2, 
  ChevronDown, ChevronRight, ChevronUp, Copy, Download, Upload, BarChart2, 
  BookOpen, Search, ToggleLeft, ToggleRight, Zap, FileText,
  Info, Code, List, Filter, RefreshCw, Settings, LayoutTemplate,
  HelpCircle, Maximize2, Minimize2, Eye, EyeOff, Sparkles
} from 'lucide-react';
import apiClient from './apiClient';
import { ExplainPanel, WhyNotPanel } from './ExplainPanel';
import { useUndoRefresh } from './useUndoRefresh';
import RuleBuilder from './RuleBuilder';
import { rt, btn } from './resultsTheme';
import type { SwrlRule, UnmigratedRule, ValidationResult as SwrlValidationResult, ExecutionResponse, PluginContext, BuiltInCategory, InferredAxiom, AxiomsPageResponse } from './types';
import { withDraft, normalizeRulesResponse, unmigratedNotice, hasDisabledRule, isImportedRule, sourceLabel, localCopyBody, ruleWriteBody, friendlyRuleError, applySavedRule, importSessionFor, importSummaryLine, importButtonLabel, canRunImport, importOutcomeNotice, staleImportReport, importErrorMessage, isImportFile, skippedRowsView, isPagedRun, derivedSummary, formatCount, nextOffset, previousOffset, pageRangeText, pageCacheKey, axiomsPagePath, toggleSelection, addToSelection, allSelected, isExpiredRunError, pageErrorMessage, RESULTS_PAGE_SIZE, EXPIRED_RUN_MESSAGE } from './ruleModel';
import type { AxiomKind } from './ruleModel';
import type { ImportReport, ImportSession } from './ruleModel';


function parseSwrlError(raw: string): { title: string; detail: string; hint?: string } {
  const builtInMatch = raw.match(/built-in (swrlb:\w+)/);
  const builtIn = builtInMatch ? builtInMatch[1] : null;

  const plainLiteralMatch = raw.match(/got "([^"]+)"\^\^rdf:PlainLiteral/);
  if (plainLiteralMatch) {
    return {
      title: `Type error${builtIn ? ` in ${builtIn}` : ''}`,
      detail: `Value "${plainLiteralMatch[1]}" has no datatype (stored as plain literal).`,
      hint: 'The property\'s range is declared as xsd:string or has no range. Change it to xsd:integer or xsd:decimal for numeric comparisons, and re-save the individual\'s value.',
    };
  }

  const stringTypeMatch = raw.match(/got "([^"]+)"\^\^xsd:string/);
  if (stringTypeMatch) {
    return {
      title: `Type error${builtIn ? ` in ${builtIn}` : ''}`,
      detail: `Value "${stringTypeMatch[1]}" is a string but a numeric type is required.`,
      hint: 'Change the property\'s range from xsd:string to xsd:integer or xsd:decimal.',
    };
  }

  const unsupportedMatch = raw.match(/unsupported argument type[^:]*expecting ([^,\]]+(?:,\s*[^,\]]+)*?)(?:,\s*or\s+[^,\]]+)?,?\s*got ([^\]]+)/);
  if (unsupportedMatch) {
    return {
      title: `Type mismatch${builtIn ? ` in ${builtIn}` : ''}`,
      detail: `Got ${unsupportedMatch[2].trim()}. Expected: ${unsupportedMatch[1].trim()}.`,
      hint: 'Check that the property\'s range declaration matches the type expected by the built-in.',
    };
  }

  if (raw.toLowerCase().includes('parse') || raw.toLowerCase().includes('syntax')) {
    return {
      title: 'Rule syntax error',
      detail: raw.split(':').pop()?.trim() ?? raw,
      hint: 'Check your SWRL expression for typos. Use the Templates panel for valid examples.',
    };
  }

  const inner = raw
    .replace(/^.*?TargetSWRLRuleEngineException[^:]*:\s*/i, '')
    .replace(/^.*?error running Drools rule engine[^:]*:\s*/i, '')
    .replace(/\[Error:.*?built-in exception thrown[^:]*:[^:]*:\s*/i, '')
    .replace(/\[Near\s*:\s*\{.*$/s, '')
    .replace(/\^\s*\[Line:.*$/s, '')
    .trim();
  return {
    title: 'Execution failed',
    detail: inner || raw.slice(0, 300),
  };
}

const RULE_TEMPLATES = [
  {
    name: 'Class Membership (numeric)',
    template: 'ClassName(?x) ^ dataProperty(?x, ?val) ^ swrlb:greaterThan(?val, 18) -> TargetClass(?x)',
    description: 'Classify by numeric data property — replace ClassName, dataProperty, TargetClass',
  },
  {
    name: 'Class Membership (string)',
    template: 'ClassName(?x) ^ dataProperty(?x, ?val) ^ swrlb:startsWith(?val, "prefix") -> TargetClass(?x)',
    description: 'Classify by string data property prefix — replace ClassName, dataProperty, TargetClass',
  },
  {
    name: 'Property chain (2 hops)',
    template: 'objProperty1(?x, ?y) ^ objProperty2(?y, ?z) -> inferredProperty(?x, ?z)',
    description: 'Infer a relationship through two object property hops',
  },
  {
    name: 'Sibling / co-member',
    template: 'objProperty(?x, ?shared) ^ objProperty(?y, ?shared) ^ swrlb:notEqual(?x, ?y) -> inferredProperty(?x, ?y)',
    description: 'Infer a relationship between two individuals that share a common value',
  },
  {
    name: 'Arithmetic on data property',
    template: 'ClassName(?x) ^ dataProperty(?x, ?a) ^ dataProperty2(?x, ?b) ^ swrlb:add(?result, ?a, ?b) -> resultProperty(?x, ?result)',
    description: 'Compute sum of two numeric properties — property range must be xsd:integer or xsd:decimal',
  },
  {
    name: 'String concat',
    template: 'ClassName(?x) ^ firstName(?x, ?f) ^ lastName(?x, ?l) ^ swrlb:stringConcat(?full, ?f, " ", ?l) -> fullName(?x, ?full)',
    description: 'Combine two string data properties into one',
  },
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

type OntologySchemaNames = {
  classes: Set<string>;
  objectProperties: Set<string>;
  dataProperties: Set<string>;
};

const BUILT_IN_PREFIXES = new Set(['swrlb', 'swrlm', 'sqwrl', 'temporal']);
const isBuiltinPredicate = (predicate: string) => {
  const prefix = predicate.includes(':') ? predicate.split(':')[0] : '';
  return BUILT_IN_PREFIXES.has(prefix);
};

const predicateLocalName = (predicate: string) => extractLocalName(predicate.split(':').pop() || predicate);

const extractTemplatePredicates = (template: string): string[] => {
  const predicates = new Set<string>();
  const atomRegex = /([A-Za-z_][\w:.-]*)\s*\(/g;
  let match: RegExpExecArray | null;

  while ((match = atomRegex.exec(template)) !== null) {
    const predicate = match[1];
    if (!predicate.startsWith('?') && !isBuiltinPredicate(predicate)) {
      predicates.add(predicateLocalName(predicate));
    }
  }

  return Array.from(predicates);
};

const templateMatchesOntology = (template: string, schema: OntologySchemaNames) => {
  const validPredicates = new Set([
    ...schema.classes,
    ...schema.objectProperties,
    ...schema.dataProperties,
  ]);

  return extractTemplatePredicates(template).every(predicate => validPredicates.has(predicate));
};

function useDebounce<T>(value: T, delay: number): T {
  const [debounced, setDebounced] = useState(value);
  useEffect(() => { 
    const t = setTimeout(() => setDebounced(value), delay); 
    return () => clearTimeout(t); 
  }, [value, delay]);
  return debounced;
}


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
    <div className="fixed inset-0 z-50 flex items-center justify-center backdrop-blur-sm" style={{ backgroundColor: 'var(--overlay)' }}>
      <div className="rounded-xl shadow-2xl max-w-md w-full mx-4 overflow-hidden animate-in fade-in zoom-in-95 duration-200" style={{ backgroundColor: 'var(--surface-1)' }}>
        <div className="p-5" style={{ borderBottom: '1px solid var(--border)' }}>
          <h3 className="text-lg font-semibold" style={{ color: 'var(--text-primary)' }}>{title}</h3>
        </div>
        <div className="p-5">
          <p style={{ color: 'var(--text-secondary)' }}>{message}</p>
        </div>
        <div className="flex justify-end gap-3 p-4" style={{ backgroundColor: 'var(--surface-2)' }}>
          <button
            onClick={onCancel}
            className="px-4 py-2 text-sm font-medium rounded-lg transition-colors"
            style={{ color: 'var(--text-primary)', backgroundColor: 'var(--surface-1)', border: '1px solid var(--border)' }}
          >
            Cancel
          </button>
          <button
            onClick={onConfirm}
            className="px-4 py-2 text-sm font-medium rounded-lg transition-colors"
            style={{
              backgroundColor: danger ? 'var(--error)' : '#8b5cf6',
              color: danger ? 'var(--on-error)' : '#ffffff'
            }}
          >
            {confirmText}
          </button>
        </div>
      </div>
    </div>
  );
};


interface RuleListItemProps {
  rule: SwrlRule;
  isSelected: boolean;
  isChecked: boolean;
  onSelect: () => void;
  onToggle: () => void;
  onToggleEnabled: () => void;
  onDelete: () => void;
  onMakeLocalCopy: () => void;
  isCopying: boolean;
}

const RuleListItem: React.FC<RuleListItemProps> = ({ 
  rule, isSelected, isChecked, onSelect, onToggle, onToggleEnabled, onDelete, onMakeLocalCopy, isCopying
}) => {
  const imported = isImportedRule(rule);
  return (
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
    <div className="pt-0.5" onClick={e => e.stopPropagation()}>
      <input
        type="checkbox"
        checked={isChecked}
        onChange={onToggle}
        className="w-4 h-4 rounded border-gray-300 text-green-600 focus:ring-green-500 cursor-pointer"
      />
    </div>

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
      {imported && (
        <div className="mt-1" onClick={e => e.stopPropagation()}>
          <div className="text-[11px] text-gray-600 truncate" title={sourceLabel(rule)}>{sourceLabel(rule)}</div>
          <button
            onClick={onMakeLocalCopy}
            disabled={isCopying}
            className="mt-0.5 text-xs text-purple-700 hover:underline disabled:opacity-50"
          >
            {isCopying ? 'Copying...' : 'Make a local copy'}
          </button>
          <div className="text-[10px] text-gray-400">Imported rules cannot be turned off here yet.</div>
        </div>
      )}
    </div>

    {!imported && (
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
        className="p-1.5 rounded-md text-gray-500 hover:text-red-600 hover:bg-red-50 opacity-60 group-hover:opacity-100 transition-all"
        title="Delete rule"
      >
        <Trash2 size={14} />
      </button>
    </div>
    )}
  </div>
  );
};


interface QuickInsertProps {
  onInsert: (text: string) => void;
  disabled?: boolean;
  dynamicTemplates?: typeof RULE_TEMPLATES;
  generalTemplates?: typeof RULE_TEMPLATES;
  schemaLoaded?: boolean;
}

const QuickInsertPanel: React.FC<QuickInsertProps> = ({
  onInsert,
  disabled,
  dynamicTemplates = [],
  generalTemplates = RULE_TEMPLATES,
  schemaLoaded = false
}) => {
  const [expanded, setExpanded] = useState(false);
  const [showTemplates, setShowTemplates] = useState(false);
  const [showGeneralTemplates, setShowGeneralTemplates] = useState(false);
  
  return (
    <div className="bg-gray-50 border-t border-gray-200">
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
            <LayoutTemplate size={12} /> Templates {dynamicTemplates.length > 0 && <span className="text-[10px] bg-green-500 text-white px-1 rounded">{dynamicTemplates.length}</span>}
          </button>
          <button
            onClick={() => { setExpanded(!expanded); setShowTemplates(false); }}
            className={`px-2 py-1 text-xs rounded flex items-center gap-1 transition-colors ${expanded ? 'bg-purple-100 text-purple-700' : 'text-purple-600 hover:bg-purple-50'}`}
          >
            <Code size={12} /> Built-ins
          </button>
        </div>
      </div>

      {showTemplates && (
        <div className="p-2 pt-0 space-y-2 max-h-80 overflow-y-auto">
          {dynamicTemplates.length > 0 ? (
            <div>
              <div className="text-[10px] font-semibold text-green-700 uppercase tracking-wide px-2 py-1 bg-green-50 rounded flex items-center justify-between">
                <div className="flex items-center gap-1">
                  <Sparkles size={10} /> Ontology-Based Templates ({dynamicTemplates.length})
                </div>
                <span className="text-[9px] text-green-600 normal-case">From your OWL file</span>
              </div>
              <div className="grid grid-cols-1 gap-1 mt-1">
                {dynamicTemplates.map((t, i) => (
                  <button
                    key={`dyn-${i}`}
                    onClick={() => { onInsert(t.template); setShowTemplates(false); }}
                    disabled={disabled}
                    className="text-left px-3 py-2 text-xs bg-green-50 border border-green-200 rounded hover:bg-green-100 hover:border-green-300 disabled:opacity-50 group transition-colors"
                  >
                    <div className="font-medium text-green-800 group-hover:text-green-900">{t.name}</div>
                    <div className="text-[10px] text-green-600 truncate">{t.description}</div>
                  </button>
                ))}
              </div>
              
              <div className="mt-3">
                <button
                  onClick={() => setShowGeneralTemplates(!showGeneralTemplates)}
                  className="w-full text-[10px] font-semibold text-purple-700 uppercase tracking-wide px-2 py-1 bg-purple-50 rounded flex items-center justify-between hover:bg-purple-100 transition-colors"
                >
                  <span>Syntax Patterns ({generalTemplates.length})</span>
                  {showGeneralTemplates ? <ChevronUp size={12} /> : <ChevronDown size={12} />}
                </button>
                {showGeneralTemplates && (
                  <div className="grid grid-cols-1 gap-1 mt-1">
                    {generalTemplates.length > 0 ? generalTemplates.map((t, i) => (
                      <button
                        key={i}
                        onClick={() => { onInsert(t.template); setShowTemplates(false); }}
                        disabled={disabled}
                        className="text-left px-3 py-2 text-xs bg-white border border-gray-200 rounded hover:bg-purple-50 hover:border-purple-300 disabled:opacity-50 group transition-colors"
                      >
                        <div className="font-medium text-gray-700 group-hover:text-purple-700">{t.name}</div>
                        <div className="text-[10px] text-gray-500 truncate">{t.description}</div>
                      </button>
                    )) : (
                      <div className="px-3 py-2 text-xs text-gray-500 bg-white border border-gray-200 rounded">
                        No general templates match this ontology schema yet.
                      </div>
                    )}
                  </div>
                )}
              </div>
            </div>
          ) : (
            <div>
              <div className="text-[10px] font-semibold text-purple-700 uppercase tracking-wide px-2 py-1 bg-purple-50 rounded">
                Syntax Patterns ({generalTemplates.length})
              </div>
              <div className="px-2 py-1.5 text-[10px] text-amber-800 bg-amber-50 border border-amber-200 rounded mt-1">
                ⚠ These are syntax blueprints. Replace <code className="bg-amber-100 px-0.5 rounded">ClassName</code>, <code className="bg-amber-100 px-0.5 rounded">dataProperty</code> etc. with names from your ontology before running.
              </div>
              <div className="grid grid-cols-1 gap-1 mt-1">
                {generalTemplates.map((t, i) => (
                  <button
                    key={i}
                    onClick={() => { onInsert(t.template); setShowTemplates(false); }}
                    disabled={disabled}
                    className="text-left px-3 py-2 text-xs bg-white border border-gray-200 rounded hover:bg-purple-50 hover:border-purple-300 disabled:opacity-50 group transition-colors"
                  >
                    <div className="font-medium text-gray-700 group-hover:text-purple-700">{t.name}</div>
                    <div className="text-[10px] text-gray-500 truncate">{t.description}</div>
                  </button>
                ))}
              </div>
            </div>
          )}
        </div>
      )}

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


interface ResultsPanelProps {
  results: ExecutionResponse | null;
  isExecuting: boolean;
}


const SQWRLQueryPanel: React.FC<{ projectId: string; context: PluginContext; draftMode?: boolean }> = ({ projectId, context, draftMode = false }) => {
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
        withDraft(`/api/swrl/${projectId}/sqwrl/query`, draftMode), 
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
  }, [projectId, query, draftMode]);

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
          className="w-full h-32 p-3 font-mono text-sm border-2 border-gray-400 rounded-lg bg-white focus:ring-2 focus:ring-purple-500 focus:border-purple-500 text-black placeholder-gray-500"
          value={query} 
          onChange={(e) => setQuery(e.target.value)} 
          placeholder="Enter SQWRL query… e.g., Person(?p) ^ hasAge(?p, ?age) -> sqwrl:select(?p, ?age)" 
        />
        <button onClick={execute} disabled={loading} className="w-full flex items-center justify-center gap-2 px-4 py-2 bg-purple-600 text-white rounded-lg hover:bg-purple-700 disabled:bg-purple-300 text-sm">
          {loading ? <Loader2 size={16} className="animate-spin" /> : <Play size={16} />} Execute Query
        </button>
        
        <div className="flex-grow overflow-auto border-2 border-gray-300 rounded-lg bg-white">
          {!results ? (
            <div className="h-full flex flex-col items-center justify-center text-gray-500 p-4">
              <HelpCircle size={32} className="mb-2 opacity-50" />
              <p>Query results will appear here.</p>
              <p className="text-xs mt-2">Use sqwrl:select(?var) in the head to query data</p>
            </div>
          ) : !results.success ? (
            <div className="p-4 bg-red-50 text-red-700">
              {(() => {
                const err = parseSwrlError(results.errorMessage || 'Query execution failed');
                return (
                  <>
                    <div className="flex items-center gap-2 font-semibold mb-1">
                      <AlertCircle size={18} />
                      {err.title}
                    </div>
                    <div className="text-sm mb-1">{err.detail}</div>
                    {err.hint && (
                      <div className="text-sm text-amber-800 bg-amber-50 border border-amber-200 rounded p-2 mt-2">
                        💡 {err.hint}
                      </div>
                    )}
                  </>
                );
              })()}
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


interface ResultsPanelProps {
  projectId: string;
  draftMode?: boolean;
  results: ExecutionResponse | null;
  isExecuting: boolean;
  onAdd?: (axioms: InferredAxiom[]) => Promise<{ ok: boolean; message: string }>;
  onRerun?: () => void;
}



const formatInferredAxiom = (axiom: InferredAxiom): { type: string; subject: string; predicate?: string; object?: string; formatted: string } => {
  const readable = axiom.readable || '';
  
  const clean = (s: string) => extractLocalName(s);
  
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
  
  const dataPropMatch = readable.match(/DataPropertyAssertion\s*\(\s*(?:<)?([^>\s)]+)(?:>)?\s+(?:<)?([^>\s)]+)(?:>)?\s+(.+)\s*\)/i);
  if (dataPropMatch) {
    const prop = clean(dataPropMatch[1]);
    const subject = clean(dataPropMatch[2]);
    let value = dataPropMatch[3];
    value = value.replace(/"\^\^.*$/, '').replace(/^"|"$/g, '');
    return {
      type: 'DataPropertyAssertion',
      subject,
      predicate: prop,
      object: value,
      formatted: `${subject} ${prop} = ${value}`
    };
  }

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

  const equivClassMatch = readable.match(/EquivalentClasses\s*\(\s*<?([^>\s]+)>?/i);
  if (equivClassMatch) {
    const cls = clean(equivClassMatch[1]);
    return {
      type: 'EquivalentClasses',
      subject: cls,
      formatted: `Equivalent: ${cls}`
    };
  }

  const sameIndMatch = readable.match(/SameIndividual\s*\(\s*<?([^>\s]+)>?/i);
  if (sameIndMatch) {
    const ind = clean(sameIndMatch[1]);
    return {
      type: 'SameIndividual',
      subject: ind,
      formatted: `Same: ${ind}`
    };
  }

  const domainRangeMatch = readable.match(/(Data|Object)Property(Domain|Range)\s*\(\s*<?([^>\s]+)>?\s+<?([^>\s]+)>?\s*\)/i);
  if (domainRangeMatch) {
    const propType = domainRangeMatch[1];
    const constraint = domainRangeMatch[2];
    const prop = clean(domainRangeMatch[3]);
    const cls = clean(domainRangeMatch[4]);
    return {
      type: `${propType}Property${constraint}`,
      subject: prop,
      object: cls,
      formatted: `${prop} ${constraint.toLowerCase()}: ${cls}`
    };
  }

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

  const funcObjPropMatch = readable.match(/FunctionalObjectProperty\s*\(\s*<?([^>\s]+)>?\s*\)/i);
  if (funcObjPropMatch) {
    const prop = clean(funcObjPropMatch[1]);
    return {
      type: 'FunctionalObjectProperty',
      subject: prop,
      formatted: `${prop} (functional)`
    };
  }

  const transitiveMatch = readable.match(/TransitiveObjectProperty\s*\(\s*<?([^>\s]+)>?\s*\)/i);
  if (transitiveMatch) {
    const prop = clean(transitiveMatch[1]);
    return {
      type: 'TransitiveObjectProperty',
      subject: prop,
      formatted: `${prop} (transitive)`
    };
  }

  const symmetricMatch = readable.match(/SymmetricObjectProperty\s*\(\s*<?([^>\s]+)>?\s*\)/i);
  if (symmetricMatch) {
    const prop = clean(symmetricMatch[1]);
    return {
      type: 'SymmetricObjectProperty',
      subject: prop,
      formatted: `${prop} (symmetric)`
    };
  }

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
  
  const simplified = readable.replace(/<[^>]+#([^>]+)>/g, '$1').replace(/<[^>]+\/([^>/]+)>/g, '$1');
  return {
    type: axiom.axiomType || 'Unknown',
    subject: simplified,
    formatted: simplified || readable
  };
};

interface GroupedAxiomItem {
  formatted: string;
  subject: string;
  object?: string;
  rawReadable: string;
  axiomType: string;
  key: string;
  selectable: boolean;
  axiom: InferredAxiom;
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
      axiomType: axiom.axiomType || type,
      key: axiom.description,
      selectable: axiom.category === 'conclusion',
      axiom
    });
  });
  
  return groups;
};

const AXIOM_ICONS: Record<string, string> = {
  ClassAssertion: '🏷️', ObjectPropertyAssertion: '🔗', DataPropertyAssertion: '📊', SubClassOf: '⊆', EquivalentClasses: '≡',
  EquivalentObjectProperties: '⇔', SubObjectPropertyOf: '⊑', ObjectPropertyDomain: '🎯', ObjectPropertyRange: '🎪',
  DataPropertyDomain: '📍', DataPropertyRange: '📐', SameIndividual: '👥', DifferentIndividuals: '👤',
};

const getAxiomTypeStyle = (type: string): { icon: string } => ({ icon: AXIOM_ICONS[type] || '📄' });

const canExplain = (ax: InferredAxiom): boolean => ax.category === 'conclusion' && !!ax.subjectIri && !!ax.predicateIri;

const AxiomCard: React.FC<{ item: GroupedAxiomItem; index: number; selected?: boolean; onToggle?: (key: string) => void; onWhy?: (axiom: InferredAxiom) => void }> = ({ item, index, selected, onToggle, onWhy }) => {
  const [showTooltip, setShowTooltip] = useState(false);
  const displaySubject = item.subject || item.formatted || `Item ${index + 1}`;
  
  return (
    <div 
      className="relative group"
      onMouseEnter={() => setShowTooltip(true)}
      onMouseLeave={() => setShowTooltip(false)}
    >
      <div className="inline-flex items-center gap-2 px-3 py-2 rounded-lg text-sm hover:shadow-sm transition-all cursor-default min-w-[80px]" style={rt.card}>
        {onToggle && item.selectable && (
          <input
            type="checkbox"
            checked={!!selected}
            onChange={() => onToggle(item.key)}
            aria-label="Select this conclusion"
          />
        )}
        <span className="font-semibold truncate max-w-[200px]" style={rt.value} title={displaySubject}>
          {displaySubject}
        </span>
        {item.object && (
          <>
            <span className="font-bold" style={rt.label}>→</span>
            <span className="font-semibold truncate max-w-[200px]" style={rt.value} title={item.object}>
              {item.object}
            </span>
          </>
        )}
        {onWhy && canExplain(item.axiom) && (
          <button onClick={() => onWhy(item.axiom)} className="text-xs font-medium whitespace-nowrap" style={rt.link}>Why?</button>
        )}
      </div>
      {showTooltip && item.rawReadable && (
        <div className="absolute z-50 bottom-full left-0 mb-2 p-3 text-xs rounded-lg shadow-xl max-w-md whitespace-pre-wrap break-all" style={rt.tooltip}>
          <div className="font-semibold mb-1">{item.axiomType}</div>
          <div className="font-mono">{item.rawReadable}</div>
          <div className="absolute bottom-0 left-4 transform translate-y-1/2 rotate-45 w-2 h-2" style={{ backgroundColor: 'var(--text-primary)' }}></div>
        </div>
      )}
    </div>
  );
};

const ResultsPanel: React.FC<ResultsPanelProps> = ({ projectId, results, isExecuting, onAdd, onRerun, draftMode = false }) => {
  const [explained, setExplained] = useState<InferredAxiom | null>(null);
  const toggleWhy = (ax: InferredAxiom) => setExplained(prev => (prev === ax ? null : ax));
  const [addState, setAddState] = useState<{ phase: 'idle' | 'confirm' | 'busy' | 'done' | 'error'; message?: string }>({ phase: 'idle' });
  const [showInferredAxioms, setShowInferredAxioms] = useState(true);
  const [viewMode, setViewMode] = useState<'grouped' | 'table' | 'raw'>('grouped');
  const [expandedGroups, setExpandedGroups] = useState<Set<string>>(new Set());
  const [searchFilter, setSearchFilter] = useState('');
  const [showWarning, setShowWarning] = useState(false);
  const [scopeChoice, setScopeChoice] = useState<'conclusions' | 'structure' | 'all' | null>(null);
  const [visibleLimit, setVisibleLimit] = useState(100);
  const [selectedMap, setSelectedMap] = useState<Map<string, InferredAxiom>>(new Map());
  const selectedKeys = useMemo(() => new Set(selectedMap.keys()), [selectedMap]);

  const paged = isPagedRun(results);
  const runId = results?.runId ?? '';
  const [tabChoice, setTabChoice] = useState<AxiomKind | null>(null);
  const [offset, setOffset] = useState(0);
  const [debouncedQuery, setDebouncedQuery] = useState('');
  const [pageCache, setPageCache] = useState<Record<string, AxiomsPageResponse>>({});
  const [pageLoading, setPageLoading] = useState(false);
  const [pageError, setPageError] = useState<string | null>(null);
  const [expired, setExpired] = useState(false);
  const lastShownRef = useRef<AxiomsPageResponse | null>(null);
  const pagedKind: AxiomKind = tabChoice ?? ((results?.counts?.conclusions ?? 0) > 0 || (results?.counts?.structure ?? 0) === 0 ? 'conclusions' : 'structure');
  const pageKey = pageCacheKey(runId, pagedKind, debouncedQuery.trim(), offset);
  const pageCached = pageCache[pageKey] !== undefined;

  useEffect(() => {
    setTabChoice(null);
    setOffset(0);
    setPageCache({});
    setPageError(null);
    setExpired(false);
    setSelectedMap(new Map());
    lastShownRef.current = null;
  }, [runId]);

  useEffect(() => {
    if (!paged) return;
    const t = setTimeout(() => {
      setDebouncedQuery(prev => {
        if (prev.trim() === searchFilter.trim()) return prev;
        setOffset(0);
        return searchFilter;
      });
    }, 300);
    return () => clearTimeout(t);
  }, [searchFilter, paged]);

  useEffect(() => {
    if (!paged || expired) return;
    if (pageCached) {
      setPageError(null);
      return;
    }
    let cancelled = false;
    setPageLoading(true);
    setPageError(null);
    apiClient
      .get<AxiomsPageResponse>(axiomsPagePath(projectId, runId, { offset, limit: RESULTS_PAGE_SIZE, kind: pagedKind, query: debouncedQuery }, draftMode))
      .then(res => {
        if (cancelled) return;
        const page: AxiomsPageResponse = { ...res, axioms: Array.isArray(res?.axioms) ? res.axioms : [], total: typeof res?.total === 'number' ? res.total : 0 };
        setPageCache(prev => ({ ...prev, [pageKey]: page }));
      })
      .catch(e => {
        if (cancelled) return;
        if (isExpiredRunError(e)) setExpired(true);
        else setPageError(pageErrorMessage(e));
      })
      .finally(() => {
        if (!cancelled) setPageLoading(false);
      });
    return () => { cancelled = true; };
  }, [paged, expired, pageKey, pageCached]);

  const currentPage = pageCache[pageKey] ?? lastShownRef.current;
  if (pageCache[pageKey]) lastShownRef.current = pageCache[pageKey];
  const pageAxioms: InferredAxiom[] = currentPage?.axioms ?? [];
  const pageTotal = currentPage?.total ?? 0;

  const toggleSelected = (key: string) => {
    const ax = pageAxioms.find(a => a.description === key) ?? results?.inferredAxioms.find(a => a.description === key);
    if (!ax) return;
    setSelectedMap(prev => toggleSelection(prev, ax));
  };

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
    <div className="p-8 text-center" style={rt.label}>
      <AlertCircle size={48} className="mx-auto mb-4 opacity-50" />
      <p className="text-lg">Run rule execution to see results.</p>
      <p className="text-sm mt-2">Click "Execute All Rules" to run SWRL inference</p>
    </div>
  );

  const conclusionTotal = results.inferredAxioms.filter(ax => ax.category === 'conclusion').length;
  const structureTotal = results.inferredAxioms.filter(ax => ax.category === 'structure').length;
  const scope = scopeChoice ?? (conclusionTotal > 0 ? 'conclusions' : 'all');
  const inScope = paged ? pageAxioms : results.inferredAxioms.filter(ax => {
    if (!ax.category || scope === 'all') return true;
    return scope === 'conclusions' ? ax.category === 'conclusion' : ax.category === 'structure';
  });
  const matchedAxioms = paged ? pageAxioms : searchFilter.trim()
    ? inScope.filter(ax => {
        const searchLower = searchFilter.toLowerCase();
        const readable = (ax.readable || ax.description || '').toLowerCase();
        const parsed = formatInferredAxiom(ax);
        return readable.includes(searchLower) ||
               parsed.subject?.toLowerCase().includes(searchLower) ||
               parsed.object?.toLowerCase().includes(searchLower) ||
               parsed.type?.toLowerCase().includes(searchLower);
      })
    : inScope;
  const filteredAxioms = matchedAxioms.slice(0, visibleLimit);
  const matchedConclusions = matchedAxioms.filter(ax => ax.category === 'conclusion');
  const selectedConclusions = Array.from(selectedMap.values());
  const selectedCount = selectedConclusions.length;
  const allShownSelected = allSelected(selectedMap, matchedConclusions);
  const selectAllShown = () => setSelectedMap(prev => addToSelection(prev, matchedConclusions));
  const clearSelection = () => setSelectedMap(new Map());
  const showList = showInferredAxioms && !expired;
  const pagerPrev = previousOffset(offset);
  const pagerNext = nextOffset(offset, pageTotal);

  const groupedAxioms = groupAxiomsByType(filteredAxioms);
  
  const classAssertions = results.inferredAxioms.filter(ax => {
    const parsed = formatInferredAxiom(ax);
    return parsed.type === 'ClassAssertion';
  });
  
  const hasSwrlResults = classAssertions.length > 0;

  return (
    <div className="h-full overflow-y-auto">
    <div className="p-4 space-y-4">
      <div className="rounded-xl p-5 shadow-sm" style={rt.panel}>
        <h3 className="font-bold mb-4 text-lg flex items-center gap-2" style={rt.heading}>
          <BarChart2 size={20} />
          Execution Summary
          {results.executionMode && (
            <span className="text-xs px-2 py-0.5 rounded-full" style={rt.chip}>
              {results.executionMode === 'selected' ? 'Selected Rules' : 'All Rules'}
            </span>
          )}
        </h3>
        <div className="grid grid-cols-4 gap-4">
          <div className="rounded-lg p-3 shadow-sm" style={rt.card}>
            <div className="text-xs uppercase tracking-wide mb-1" style={rt.label}>Status</div>
            <div className="text-lg font-bold flex items-center gap-2" style={rt.value}>
              {results.success ? <Check size={20} /> : <X size={20} />}
              {results.success ? 'Success' : 'Failed'}
            </div>
          </div>
          <div className="rounded-lg p-3 shadow-sm" style={rt.card}>
            <div className="text-xs uppercase tracking-wide mb-1" style={rt.label}>Time</div>
            <div className="text-lg font-bold" style={rt.value}>{results.executionTimeMs}ms</div>
          </div>
          <div className="rounded-lg p-3 shadow-sm" style={rt.card}>
            <div className="text-xs uppercase tracking-wide mb-1" style={rt.label}>Rules Run</div>
            <div className="text-lg font-bold" style={rt.value}>{results.totalRulesExecuted}</div>
          </div>
          <div className="rounded-lg p-3 shadow-sm" style={rt.card}>
            <div className="text-xs uppercase tracking-wide mb-1" style={rt.label}>Inferred</div>
            <div className="text-lg font-bold" style={rt.value}>{results.inferredAxiomsCount}</div>
          </div>
        </div>

        {results.executedRuleNames && results.executedRuleNames.length > 0 && (
          <div className="mt-4 pt-4" style={rt.divider}>
            <div className="text-xs uppercase tracking-wide mb-2" style={rt.label}>Executed Rules</div>
            <div className="flex flex-wrap gap-2">
              {results.executedRuleNames.map((name, idx) => (
                <span key={idx} className="inline-flex items-center gap-1 px-2 py-1 text-xs rounded-full" style={rt.chip}>
                  <Check size={12} style={{ color: 'var(--success)' }} />
                  {name}
                </span>
              ))}
            </div>
          </div>
        )}
      </div>

      {results.status && results.status !== 'COMPLETED' && (
        <div className="rounded-lg p-3 text-sm" style={rt.bannerWarning}>
          <div className="font-semibold">{results.statusLabel || results.status}</div>
          {results.message && <div className="mt-1">{results.message}</div>}
          {results.consistencyStatement && <div className="mt-1">{results.consistencyStatement}</div>}
        </div>
      )}
      {results.limitations && results.limitations.length > 0 && (
        <div className="rounded-lg p-3 text-sm" style={rt.bannerNote}>
          {results.limitations.map((text, i) => <div key={i}>{text}</div>)}
        </div>
      )}
      {results.success && hasSwrlResults && (
        <div className="rounded-xl overflow-hidden shadow-md" style={rt.bannerSuccess}>
          <div className="p-4 flex justify-between items-center" style={rt.rowBorder}>
            <h3 className="font-bold text-lg flex items-center gap-2" style={rt.heading}>
              <span className="text-2xl">🎯</span>
              SWRL Rule Results
              <span className="text-sm font-normal" style={rt.label}>({classAssertions.length} individuals classified)</span>
            </h3>
          </div>
          <div className="p-4">
            <div className="flex flex-wrap gap-2">
              {classAssertions.map((ax, i) => {
                const parsed = formatInferredAxiom(ax);
                return (
                  <div
                    key={i}
                    className="px-3 py-2 rounded-lg shadow-sm hover:shadow-md transition-all cursor-default" style={rt.card}
                    title={ax.readable || ax.description}
                  >
                    <span className="font-bold" style={rt.value}>{parsed.subject}</span>
                    <span className="mx-2" style={rt.label}>→</span>
                    <span className="font-bold" style={rt.value}>{parsed.object}</span>
                  </div>
                );
              })}
            </div>
          </div>
        </div>
      )}

      {results.success && !hasSwrlResults && results.inferredAxioms.length > 0 && (
        <div className="rounded-xl overflow-hidden" style={rt.bannerWarning}>
          <div 
            className="p-4 flex items-start gap-3 cursor-pointer"
            onClick={() => setShowWarning(!showWarning)}
          >
            <AlertCircle size={24} className="mt-0.5 flex-shrink-0" style={{ color: 'var(--warning)' }} />
            <div className="flex-1">
              <div className="flex justify-between items-center">
                <div className="font-bold" style={rt.heading}>No SWRL ClassAssertion Results</div>
                {showWarning ? <ChevronUp size={20} style={rt.value} /> : <ChevronDown size={20} style={rt.value} />}
              </div>
              {!showWarning && (
                 <div className="text-sm mt-1" style={rt.value}>
                   {results.inferredAxioms.length} general OWL inferences found. Click to see details.
                 </div>
              )}
            </div>
          </div>
          
          {showWarning && (
            <div className="px-4 pb-4 pl-12">
              <div className="text-sm mt-1" style={rt.value}>
                Your SWRL rule should create ClassAssertion axioms (e.g., "Emma → Adult"). 
                The {results.inferredAxioms.length} inferences below are general OWL reasoning results, not from SWRL rules.
                <br /><br />
                <strong>Most Common Issue:</strong>
                <div className="mt-2 p-3 rounded-lg" style={rt.card}>
                  <span className="font-bold">The target class doesn't exist!</span>
                  <p className="mt-1">
                    If your rule is <code className="px-1 rounded" style={rt.code}>Person(?p) → Adult(?p)</code>, 
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

      {!results.success && results.errorMessage && (() => {
        const err = parseSwrlError(results.errorMessage);
        return (
          <div className="rounded-xl p-4" style={rt.bannerError}>
            <div className="flex items-start gap-3">
              <AlertCircle size={24} className="mt-0.5 flex-shrink-0" style={{ color: 'var(--error)' }} />
              <div className="flex-1 min-w-0">
                <div className="font-bold text-lg" style={rt.heading}>{err.title}</div>
                <div className="text-sm mt-1" style={rt.value}>{err.detail}</div>
                {err.hint && (
                  <div className="text-sm mt-2 rounded p-2" style={rt.bannerWarning}>
                    💡 {err.hint}
                  </div>
                )}
              </div>
            </div>
          </div>
        );
      })()}

      {results.success && results.inferredAxioms.length > 0 && (
        <div className="rounded-xl overflow-hidden shadow-sm" style={rt.panel}>
          <div className="p-4 flex flex-col gap-3" style={rt.panelHeader}>
            <div className="flex justify-between items-center">
              <h3 className="font-bold text-lg flex items-center gap-2" style={rt.heading}>
                <span>📚</span>
                All Inferred Axioms
                <span className="text-sm font-normal" style={rt.label}>
                  ({paged ? formatCount(pageTotal) : matchedAxioms.length} matching{matchedAxioms.length > filteredAxioms.length ? `, showing ${filteredAxioms.length}` : ''}, {groupedAxioms.size} types)
                </span>
              </h3>
              <div className="flex items-center gap-3">
                <div className="flex items-center gap-1 rounded-lg p-1" style={{ backgroundColor: 'var(--surface-3)' }}>
                  <button
                    onClick={() => setViewMode('grouped')}
                    className="px-3 py-1.5 text-xs font-medium rounded transition-all"
                    style={viewMode === 'grouped' ? { ...rt.button, fontWeight: 700 } : { color: 'var(--text-secondary)' }}
                    title="Grouped by axiom type"
                  >
                    📊 Grouped
                  </button>
                  <button
                    onClick={() => setViewMode('table')}
                    className="px-3 py-1.5 text-xs font-medium rounded transition-all"
                    style={viewMode === 'table' ? { ...rt.button, fontWeight: 700 } : { color: 'var(--text-secondary)' }}
                    title="Table view"
                  >
                    📋 Table
                  </button>
                  <button
                    onClick={() => setViewMode('raw')}
                    className="px-3 py-1.5 text-xs font-medium rounded transition-all"
                    style={viewMode === 'raw' ? { ...rt.button, fontWeight: 700 } : { color: 'var(--text-secondary)' }}
                    title="Raw axiom format"
                  >
                    📝 Raw
                  </button>
                </div>
                {viewMode === 'grouped' && (
                  <div className="flex gap-1">
                    <button onClick={expandAll} className="px-2 py-1 text-xs rounded" style={rt.button} title="Expand all">
                      <Maximize2 size={14} />
                    </button>
                    <button onClick={collapseAll} className="px-2 py-1 text-xs rounded" style={rt.button} title="Collapse all">
                      <Minimize2 size={14} />
                    </button>
                  </div>
                )}
                <button
                  onClick={() => setShowInferredAxioms(!showInferredAxioms)}
                className="flex items-center gap-1 px-3 py-1.5 text-sm rounded-lg transition-colors" style={rt.button}
              >
                {showInferredAxioms ? <EyeOff size={16} /> : <Eye size={16} />}
                {showInferredAxioms ? 'Hide' : 'Show'}
              </button>
            </div>
          </div>
          {showInferredAxioms && (
            <div className="px-4 pb-3">
              <div className="relative">
                <Search size={16} className="absolute left-3 top-1/2 -translate-y-1/2" style={rt.label} />
                <input
                  type="text"
                  placeholder="Search axioms... (e.g., Adult, Person, hasAge)"
                  value={searchFilter}
                  onChange={(e) => setSearchFilter(e.target.value)}
                  className="w-full pl-10 pr-8 py-2 text-sm rounded-lg focus:outline-none focus:ring-2 focus:ring-purple-300" style={rt.input}
                />
                {searchFilter && (
                  <button
                    onClick={() => setSearchFilter('')}
                    className="absolute right-3 top-1/2 -translate-y-1/2" style={rt.label}
                  >
                    <X size={14} />
                  </button>
                )}
              </div>
              {searchFilter && (
                <div className="mt-2 text-xs" style={rt.label}>
                  Found {paged ? formatCount(pageTotal) : matchedAxioms.length} axioms matching "{searchFilter}"
                </div>
              )}
              {onAdd && (matchedConclusions.length > 0 || (paged && selectedCount > 0)) && (
                <div className="mt-3 flex flex-wrap items-center gap-2 text-xs">
                  {addState.phase === 'idle' || addState.phase === 'done' || addState.phase === 'error' ? (
                    <>
                      <button
                        onClick={selectAllShown}
                        disabled={allShownSelected}
                        className="px-3 py-1.5 rounded-lg" style={btn(allShownSelected)}
                      >
                        {paged ? 'Select all on this page' : 'Select all shown'} ({matchedConclusions.length})
                      </button>
                      <button
                        onClick={clearSelection}
                        disabled={selectedCount === 0}
                        className="px-3 py-1.5 rounded-lg" style={btn(selectedCount === 0)}
                      >
                        {paged ? 'Clear selection' : 'Clear'}
                      </button>
                      <span style={rt.value}>{selectedCount} selected</span>
                      <button
                        onClick={() => setAddState({ phase: 'confirm' })}
                        disabled={selectedCount === 0}
                        className="px-3 py-1.5 rounded-lg font-medium" style={btn(selectedCount === 0, true)}
                      >
                        Add {selectedCount} selected
                      </button>
                    </>
                  ) : addState.phase === 'confirm' ? (
                    <>
                      <span style={rt.value}>
                        This writes {selectedCount} fact{selectedCount === 1 ? '' : 's'} into the ontology as one change you can undo. Facts that are already there are skipped.
                      </span>
                      <button
                        onClick={async () => {
                          setAddState({ phase: 'busy' });
                          const outcome = await onAdd(selectedConclusions);
                          if (outcome.ok) setSelectedMap(new Map());
                          setAddState({ phase: outcome.ok ? 'done' : 'error', message: outcome.message });
                        }}
                        className="px-3 py-1.5 rounded-lg font-medium" style={rt.buttonPrimary}
                      >
                        Yes, add them
                      </button>
                      <button onClick={() => setAddState({ phase: 'idle' })} className="px-3 py-1.5 rounded-lg" style={rt.button}>Cancel</button>
                    </>
                  ) : (
                    <span style={rt.value}>Adding…</span>
                  )}
                  {addState.message && (
                    <span style={rt.value}>{addState.phase === 'error' ? '✗ ' : '✓ '}{addState.message}</span>
                  )}
                </div>
              )}
              {paged && results.counts && (
                <div className="mt-3 space-y-2 text-xs">
                  <div style={rt.value}>{derivedSummary(results.counts)}</div>
                  <div className="flex flex-wrap items-center gap-2">
                    {([['conclusions', `Rule conclusions (${formatCount(results.counts.conclusions)})`], ['structure', `Structure (${formatCount(results.counts.structure)})`]] as const).map(([key, label]) => (
                      <button
                        key={key}
                        onClick={() => { setTabChoice(key); setOffset(0); }}
                        className="px-3 py-1 rounded-full" style={pagedKind === key ? rt.chipOn : rt.button}
                      >
                        {label}
                      </button>
                    ))}
                  </div>
                </div>
              )}
              {!paged && conclusionTotal + structureTotal > 0 && results.conclusionCount !== undefined && (
                <div className="mt-3 flex flex-wrap items-center gap-2 text-xs">
                  {([['conclusions', `Rule conclusions (${results.conclusionCount})`], ['structure', `Ontology structure (${results.structureCount ?? structureTotal})`], ['all', 'Everything']] as const).map(([key, label]) => (
                    <button
                      key={key}
                      onClick={() => { setScopeChoice(key); setVisibleLimit(100); }}
                      className="px-3 py-1 rounded-full" style={scope === key ? rt.chipOn : rt.button}
                    >
                      {label}
                    </button>
                  ))}
                  {results.omittedTrivialCount ? <span style={rt.label}>{results.omittedTrivialCount} trivial same-individual facts hidden</span> : null}
                  {results.truncated ? <span style={rt.value}>Only the first {results.inferredAxioms.length} are listed; the rest are cut off.</span> : null}
                </div>
              )}
            </div>
          )}
          </div>

          {showList && viewMode === 'grouped' && (
            <div className="max-h-[70vh] overflow-y-auto">
              {Array.from(groupedAxioms.entries()).map(([type, items]) => {
                const style = getAxiomTypeStyle(type);
                const isExpanded = expandedGroups.has(type);
                return (
                  <div key={type} style={rt.rowBorder}>
                    <button
                      onClick={() => toggleGroup(type)}
                      className="w-full p-4 flex items-center justify-between transition-colors" style={{ backgroundColor: 'var(--surface-1)', color: 'var(--text-primary)' }}
                    >
                      <div className="flex items-center gap-3">
                        <span className="text-xl">{style.icon}</span>
                        <span className="text-sm font-bold px-3 py-1 rounded-full" style={rt.chip}>
                          {type}
                        </span>
                        <span className="text-sm font-medium" style={rt.label}>
                          {items.length} inferred
                        </span>
                      </div>
                      <ChevronRight size={20} className={`transition-transform ${isExpanded ? 'rotate-90' : ''}`} style={rt.label} />
                    </button>
                    {isExpanded && (
                      <div className="p-4 pt-2" style={{ backgroundColor: 'var(--surface-1)' }}>
                        <div className="flex flex-wrap gap-2">
                          {items.map((item, i) => (
                            <AxiomCard key={i} item={item} index={i} selected={selectedKeys.has(item.key)} onToggle={onAdd ? toggleSelected : undefined} onWhy={toggleWhy} />
                          ))}
                        </div>
                        {explained && items.some(it => it.axiom === explained) && (
                          <div className="mt-2">
                            <ExplainPanel projectId={projectId} draftMode={draftMode} axiom={explained} onClose={() => setExplained(null)} />
                          </div>
                        )}
                      </div>
                    )}
                  </div>
                );
              })}
            </div>
          )}

          {showList && viewMode === 'table' && (
            <div className="max-h-[70vh] overflow-auto">
              <table className="w-full text-sm">
                <thead className="sticky top-0" style={rt.panelHeader}>
                  <tr>
                    {onAdd && <th className="px-4 py-3 text-left font-semibold w-8" style={rt.heading}></th>}
                    <th className="px-4 py-3 text-left font-semibold" style={rt.heading}>#</th>
                    <th className="px-4 py-3 text-left font-semibold" style={rt.heading}>Type</th>
                    <th className="px-4 py-3 text-left font-semibold" style={rt.heading}>Subject</th>
                    <th className="px-4 py-3 text-left font-semibold" style={rt.heading}>Relation</th>
                    <th className="px-4 py-3 text-left font-semibold" style={rt.heading}>Object</th>
                    <th className="px-4 py-3 text-left font-semibold" style={rt.heading}></th>
                  </tr>
                </thead>
                <tbody style={{ color: 'var(--text-primary)' }}>
                  {filteredAxioms.map((ax, i) => {
                    const parsed = formatInferredAxiom(ax);
                    const style = getAxiomTypeStyle(parsed.type);
                    return (
                      <React.Fragment key={i}>
                      <tr style={rt.rowBorder} title={ax.readable}>
                        {onAdd && (
                          <td className="px-4 py-2">
                            {ax.category === 'conclusion' && (
                              <input
                                type="checkbox"
                                checked={selectedKeys.has(ax.description)}
                                onChange={() => toggleSelected(ax.description)}
                                aria-label="Select this conclusion"
                              />
                            )}
                          </td>
                        )}
                        <td className="px-4 py-2 font-mono text-xs" style={rt.label}>{i + 1}</td>
                        <td className="px-4 py-2">
                          <span className="inline-flex items-center gap-1 text-xs font-semibold px-2 py-1 rounded" style={rt.chip}>
                            {style.icon} {parsed.type}
                          </span>
                        </td>
                        <td className="px-4 py-2 font-medium max-w-[200px] truncate" style={rt.value}>{parsed.subject || '-'}</td>
                        <td className="px-4 py-2 font-bold text-center" style={rt.label}>{parsed.predicate || '→'}</td>
                        <td className="px-4 py-2 font-medium max-w-[200px] truncate" style={rt.value}>{parsed.object || '-'}</td>
                        <td className="px-4 py-2">
                          {canExplain(ax) && (
                            <button onClick={() => toggleWhy(ax)} className="text-xs font-medium" style={rt.link}>Why?</button>
                          )}
                        </td>
                      </tr>
                      {explained === ax && (
                        <tr>
                          <td colSpan={onAdd ? 7 : 6} className="px-4 py-2">
                            <ExplainPanel projectId={projectId} draftMode={draftMode} axiom={ax} onClose={() => setExplained(null)} />
                          </td>
                        </tr>
                      )}
                      </React.Fragment>
                    );
                  })}
                </tbody>
              </table>
            </div>
          )}

          {expired && (
            <div className="p-4 flex flex-wrap items-center gap-3 text-sm" style={rt.bannerWarning}>
              <span>{EXPIRED_RUN_MESSAGE}</span>
              {onRerun && (
                <button onClick={onRerun} className="px-3 py-1.5 rounded-lg font-medium" style={rt.buttonPrimary}>Run again</button>
              )}
            </div>
          )}

          {showList && paged && (
            <div className="p-3 flex flex-wrap items-center justify-center gap-3 text-xs" style={rt.divider}>
              <button onClick={() => pagerPrev !== null && setOffset(pagerPrev)} disabled={pagerPrev === null} className="px-3 py-1.5 rounded-lg" style={btn(pagerPrev === null)}>Previous</button>
              <span style={rt.value}>{pageRangeText(offset, pageAxioms.length, pageTotal)}</span>
              <button onClick={() => pagerNext !== null && setOffset(pagerNext)} disabled={pagerNext === null} className="px-3 py-1.5 rounded-lg" style={btn(pagerNext === null)}>Next</button>
              {pageLoading && <span style={rt.label}>Loading...</span>}
              {pageError && <span style={rt.value}>{pageError}</span>}
            </div>
          )}

          {showInferredAxioms && matchedAxioms.length > filteredAxioms.length && (
            <div className="p-3 text-center" style={rt.divider}>
              <button onClick={() => setVisibleLimit(visibleLimit + 100)} className="px-4 py-1.5 text-sm rounded-lg" style={rt.button}>
                Show 100 more ({matchedAxioms.length - filteredAxioms.length} remaining)
              </button>
            </div>
          )}

          {showList && viewMode === 'raw' && (
            <div className="max-h-[70vh] overflow-y-auto">
              {filteredAxioms.map((ax, i) => {
                const style = getAxiomTypeStyle(ax.axiomType);
                return (
                  <div key={i} className="p-4" style={{ ...rt.rowBorder, backgroundColor: i % 2 === 0 ? 'var(--surface-1)' : 'var(--surface-2)', color: 'var(--text-primary)' }}>
                    <div className="flex items-center gap-2 mb-2">
                      {onAdd && ax.category === 'conclusion' && (
                        <input
                          type="checkbox"
                          checked={selectedKeys.has(ax.description)}
                          onChange={() => toggleSelected(ax.description)}
                          aria-label="Select this conclusion"
                        />
                      )}
                      <span className="font-mono text-xs" style={rt.label}>#{i + 1}</span>
                      <span className="inline-flex items-center gap-1 text-xs font-semibold px-2 py-1 rounded" style={rt.chip}>
                        {style.icon} {ax.axiomType}
                      </span>
                      {canExplain(ax) && (
                        <button onClick={() => toggleWhy(ax)} className="text-xs font-medium" style={rt.link}>Why?</button>
                      )}
                    </div>
                    <pre className="text-sm font-mono p-3 rounded-lg overflow-x-auto whitespace-pre-wrap break-all" style={rt.code}>{ax.readable}</pre>
                    {explained === ax && (
                      <div className="mt-2">
                        <ExplainPanel projectId={projectId} draftMode={draftMode} axiom={ax} onClose={() => setExplained(null)} />
                      </div>
                    )}
                  </div>
                );
              })}
            </div>
          )}
        </div>
      )}

      {results.success && results.inferredAxiomsCount === 0 && (
        <div className="rounded-xl p-6 text-center" style={rt.bannerWarning}>
          <div className="text-4xl mb-3">🔍</div>
          <p className="font-semibold text-lg" style={rt.heading}>No New Axioms Inferred</p>
          <p className="text-sm mt-2" style={rt.value}>
            The rules executed successfully, but no new knowledge was derived.
            This may happen if the conditions aren't met or axioms already exist.
          </p>
        </div>
      )}
    </div>
    </div>
  );
};


interface SWRLEditorProps {
  projectId: string;
  context?: PluginContext;
  draftMode?: boolean;
}

export const SWRLEditor: React.FC<SWRLEditorProps> = ({ projectId, context, draftMode = false }) => {

  const rulesVersionRef = useRef(0);
  const [rules, setRules] = useState<SwrlRule[]>([]);
  const [selectedRule, setSelectedRule] = useState<SwrlRule | null>(null);
  const [selectedRuleIds, setSelectedRuleIds] = useState<Set<string>>(new Set());
  const [isLoading, setIsLoading] = useState(true);
  const [isEditing, setIsEditing] = useState(false);
  const [isSaving, setIsSaving] = useState(false);
  const [saveError, setSaveError] = useState<string | null>(null);
  const [isExecuting, setIsExecuting] = useState(false);
  const [executionResult, setExecutionResult] = useState<ExecutionResponse | null>(null);
  const [validationResult, setValidationResult] = useState<SwrlValidationResult | null>(null);
  const [unmigrated, setUnmigrated] = useState<UnmigratedRule[]>([]);
  const [copyingId, setCopyingId] = useState<string | null>(null);
  const [activePanel, setActivePanel] = useState<'editor' | 'results' | 'reference' | 'query'>('editor');
  const [expandedBuiltInCategory, setExpandedBuiltInCategory] = useState<string | null>(null);
  const [confirmDialog, setConfirmDialog] = useState<{ isOpen: boolean; ruleName: string; ruleId: string }>({ isOpen: false, ruleName: '', ruleId: '' });
  const [searchQuery, setSearchQuery] = useState('');
  const [dynamicTemplates, setDynamicTemplates] = useState<typeof RULE_TEMPLATES>([]);
  const [showRuleBuilder, setShowRuleBuilder] = useState(false);
  const [ontologySchema, setOntologySchema] = useState<{ classes: string[], objectProperties: string[], dataProperties: string[] } | null>(null);

  const [editForm, setEditForm] = useState({
    ruleName: '',
    ruleText: '',
    comment: '',
    category: '',
    enabled: true
  });

  const fileInputRef = useRef<HTMLInputElement>(null);
  const importFileRef = useRef<File | null>(null);
  const importSessionRef = useRef<ImportSession | null>(null);
  const [importPreview, setImportPreview] = useState<{ report: ImportReport; fileName: string } | null>(null);
  const [importRunning, setImportRunning] = useState(false);
  const [importNotice, setImportNotice] = useState<string | null>(null);
  const [importError, setImportError] = useState<string | null>(null);
  const textareaRef = useRef<HTMLTextAreaElement>(null);

  const debouncedRuleText = useDebounce(editForm.ruleText, 500);

  const schemaNames = useMemo<OntologySchemaNames | null>(() => {
    if (!ontologySchema) return null;
    return {
      classes: new Set(ontologySchema.classes.map(extractLocalName)),
      objectProperties: new Set(ontologySchema.objectProperties.map(extractLocalName)),
      dataProperties: new Set(ontologySchema.dataProperties.map(extractLocalName)),
    };
  }, [ontologySchema]);

  const validGeneralTemplates = useMemo(
    () => schemaNames
      ? RULE_TEMPLATES.filter(t => templateMatchesOntology(t.template, schemaNames))
      : [],
    [schemaNames]
  );

  const loadOntologySchema = useCallback(async () => {
    try {
      console.log('[SWRL] Loading ontology schema for project:', projectId);
      const res = await apiClient.get<{ classes: string[], objectProperties: string[], dataProperties: string[] }>(
        `/api/ontology/${projectId}/schema`
      );
      console.log('[SWRL] Schema loaded:', res);
      
      const classes = res.classes.map(c => extractLocalName(c)).filter(c => c && !c.startsWith('owl:') && !c.startsWith('rdf:'));
      const objProps = res.objectProperties.map(p => extractLocalName(p)).filter(p => p && !p.startsWith('owl:') && !p.startsWith('rdf:'));
      const dataProps = res.dataProperties.map(p => extractLocalName(p)).filter(p => p && !p.startsWith('owl:') && !p.startsWith('rdf:'));
      const schemaNames: OntologySchemaNames = {
        classes: new Set(classes),
        objectProperties: new Set(objProps),
        dataProperties: new Set(dataProps),
      };
      setOntologySchema({ classes, objectProperties: objProps, dataProperties: dataProps });
      
      const generated: typeof RULE_TEMPLATES = [];
      
      classes.forEach((cls, idx) => {
        if (dataProps.length > 0) {
          const dataProp = dataProps[0];
          const targetCls = classes[idx + 1] || `${cls}Classified`;
          generated.push({
            name: `${cls} Classification`,
            template: `${cls}(?x) ^ ${dataProp}(?x, ?val) ^ swrlb:greaterThan(?val, 100) -> ${targetCls}(?x)`,
            description: `Classify ${cls} into ${targetCls} when ${dataProp} > 100`
          });
        }
        
        if (dataProps.length > 0) {
          const dataProp = dataProps[0];
          generated.push({
            name: `${cls} String Match`,
            template: `${cls}(?x) ^ ${dataProp}(?x, ?str) ^ swrlb:contains(?str, "value") -> ${cls}Matched(?x)`,
            description: `Classify ${cls} based on ${dataProp} containing specific text`
          });
        }
        
        if (dataProps.length > 0) {
          const dataProp = dataProps[0];
          generated.push({
            name: `Query All ${cls}`,
            template: `${cls}(?x) ^ ${dataProp}(?x, ?val) -> sqwrl:select(?x, ?val) ^ sqwrl:orderBy(?val)`,
            description: `List all ${cls} instances ordered by ${dataProp}`
          });
        }
      });
      
      for (let i = 0; i < Math.min(objProps.length, 3); i++) {
        for (let j = 0; j < Math.min(objProps.length, 3); j++) {
          if (i !== j && classes.length > 0) {
            const prop1 = objProps[i];
            const prop2 = objProps[j];
            const inferredProp = `${prop1}${prop2}Chain`;
            generated.push({
              name: `${prop1} → ${prop2} Chain`,
              template: `${prop1}(?x, ?y) ^ ${prop2}(?y, ?z) -> ${inferredProp}(?x, ?z)`,
              description: `Infer ${inferredProp} through ${prop1} and ${prop2} property chain`
            });
          }
        }
      }
      
      if (dataProps.length >= 2 && classes.length > 0) {
        const cls = classes[0];
        const prop1 = dataProps[0];
        const prop2 = dataProps[1];
        
        generated.push({
          name: `${cls} Sum ${prop1}+${prop2}`,
          template: `${cls}(?x) ^ ${prop1}(?x, ?v1) ^ ${prop2}(?x, ?v2) ^ swrlb:add(?sum, ?v1, ?v2) -> has${prop1}${prop2}Sum(?x, ?sum)`,
          description: `Calculate sum of ${prop1} and ${prop2} for ${cls}`
        });
        
        generated.push({
          name: `${cls} ${prop1} × ${prop2}`,
          template: `${cls}(?x) ^ ${prop1}(?x, ?v1) ^ ${prop2}(?x, ?v2) ^ swrlb:multiply(?product, ?v1, ?v2) -> has${prop1}${prop2}Product(?x, ?product)`,
          description: `Calculate product of ${prop1} and ${prop2} for ${cls}`
        });
        
        generated.push({
          name: `${cls} ${prop1} > ${prop2}`,
          template: `${cls}(?x) ^ ${prop1}(?x, ?v1) ^ ${prop2}(?x, ?v2) ^ swrlb:greaterThan(?v1, ?v2) -> has${prop1}Greater(?x, true)`,
          description: `Check if ${prop1} exceeds ${prop2} for ${cls}`
        });
      }
      
      if (classes.length > 0 && objProps.length > 0) {
        const cls = classes[0];
        const objProp = objProps[0];
        generated.push({
          name: `${cls} Shared ${objProp}`,
          template: `${cls}(?x) ^ ${cls}(?y) ^ ${objProp}(?x, ?ref) ^ ${objProp}(?y, ?ref) ^ swrlb:notEqual(?x, ?y) -> related${objProp}(?x, ?y)`,
          description: `Find ${cls} instances sharing the same ${objProp}`
        });
      }
      
      if (classes.length > 0 && dataProps.length > 0) {
        const cls = classes[0];
        const prop = dataProps[0];
        
        generated.push({
          name: `Count ${cls}`,
          template: `${cls}(?x) -> sqwrl:select(?x) ^ sqwrl:count(?x)`,
          description: `Count total number of ${cls} instances`
        });
        
        generated.push({
          name: `${cls} Min ${prop}`,
          template: `${cls}(?x) ^ ${prop}(?x, ?val) -> sqwrl:select(?val) ^ sqwrl:min(?val)`,
          description: `Find minimum ${prop} value in ${cls}`
        });
        
        generated.push({
          name: `${cls} Max ${prop}`,
          template: `${cls}(?x) ^ ${prop}(?x, ?val) -> sqwrl:select(?val) ^ sqwrl:max(?val)`,
          description: `Find maximum ${prop} value in ${cls}`
        });
        
        generated.push({
          name: `${cls} Avg ${prop}`,
          template: `${cls}(?x) ^ ${prop}(?x, ?val) -> sqwrl:select(?x) ^ sqwrl:avg(?val)`,
          description: `Calculate average ${prop} for all ${cls}`
        });
      }
      
      const validGenerated = generated.filter(t => templateMatchesOntology(t.template, schemaNames));
      console.log('[SWRL] Generated', generated.length, 'dynamic templates;', validGenerated.length, 'match ontology schema');
      setDynamicTemplates(validGenerated.slice(0, 20));
      console.log('[SWRL] Set', Math.min(validGenerated.length, 20), 'dynamic templates');
    } catch (e) {
      console.error('[SWRL] Failed to load ontology schema:', e);
      setDynamicTemplates([]);
    }
  }, [projectId, apiClient]);

  useEffect(() => { loadOntologySchema(); }, [loadOntologySchema]);

  const loadRules = useCallback(async () => {
    const myVersion = rulesVersionRef.current;
    setIsLoading(true);
    try {
      const res = await apiClient.get<unknown>(withDraft(`/api/ontology/${projectId}/rules`, draftMode));
      if (rulesVersionRef.current === myVersion) {
        const parsed = normalizeRulesResponse(res);
        setRules(parsed.rules);
        setUnmigrated(parsed.unmigrated);
      }
    } catch (e) {
      console.error('Failed to load rules:', e);
    } finally {
      setIsLoading(false);
    }
  }, [projectId, draftMode]);

  const announceRuleChange = (op: string) => {
    window.dispatchEvent(new CustomEvent('ontology:mutated', { detail: { projectId, ops: [op] } }));
  };

  useEffect(() => { loadRules(); }, [loadRules]);

  useUndoRefresh(projectId, () => { void loadRules(); });

  useEffect(() => {
    if (!debouncedRuleText.trim() || !isEditing) {
      setValidationResult(null);
      return;
    }

    const validate = async () => {
      try {
        const res = await apiClient.post<SwrlValidationResult>(
          withDraft(`/api/swrl/${projectId}/validate`, draftMode),
          { ruleText: debouncedRuleText }
        );
        setValidationResult(res);
      } catch (e) {
        setValidationResult({ valid: false, errorMessage: 'Validation failed' });
      }
    };
    validate();
  }, [debouncedRuleText, projectId, isEditing, draftMode]);

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
    setSaveError(null);
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
    setSaveError(null);
    setActivePanel('editor');
  };

  const handleSave = async () => {
    if (!editForm.ruleName.trim() || !editForm.ruleText.trim()) return;
    setIsSaving(true);
    setSaveError(null);

    try {
      const body = ruleWriteBody(editForm, (selectedRule ? 'Edited rule ' : 'Added rule ') + editForm.ruleName.trim());
      if (selectedRule) {
        const res = await apiClient.put<SwrlRule>(
          withDraft(`/api/ontology/${projectId}/rules/${selectedRule.id}`, draftMode),
          body
        );
        rulesVersionRef.current++;
        const applied = applySavedRule(rules, selectedRule.id, res, editForm);
        setRules(prev => applySavedRule(prev, selectedRule.id, res, editForm).rules);
        setSelectedRule(applied.saved);
        setEditForm(prev => ({ ...prev, ruleName: applied.saved.ruleName }));
      } else {
        const res = await apiClient.post<SwrlRule>(
          withDraft(`/api/ontology/${projectId}/rules`, draftMode),
          body
        );
        rulesVersionRef.current++;
        setRules([...rules, res]);
        setSelectedRule(res);
      }
      setIsEditing(false);
      announceRuleChange(selectedRule ? 'ruleUpdated' : 'ruleAdded');
    } catch (e: any) {
      console.error('Save failed:', e);
      setSaveError(friendlyRuleError(e, 'Failed to save rule - please try again.'));
    } finally {
      setIsSaving(false);
    }
  };

  const handleAddConclusions = async (axioms: InferredAxiom[]): Promise<{ ok: boolean; message: string }> => {
    const ops = axioms.flatMap<Record<string, string | undefined>>(ax => {
      if (!ax.subjectIri || !ax.predicateIri) return [];
      if (ax.predicateIri === 'http://www.w3.org/1999/02/22-rdf-syntax-ns#type' && ax.objectIri) {
        return [{ type: 'addClassAssertion', iri: ax.subjectIri, classIri: ax.objectIri }];
      }
      if (ax.objectIri) {
        return [{ type: 'addObjectPropertyAssertion', iri: ax.subjectIri, property: ax.predicateIri, target: ax.objectIri }];
      }
      if (ax.objectLiteral != null) {
        return [{ type: 'addDataPropertyAssertion', iri: ax.subjectIri, property: ax.predicateIri, value: ax.objectLiteral, datatype: ax.literalDatatypeIri || undefined }];
      }
      return [];
    });
    if (ops.length === 0) return { ok: false, message: 'Nothing to add: none of these can be written as simple facts.' };
    try {
      const res = await apiClient.post<{ changeSetId?: string }>(
        withDraft(`/api/ontology/mutations/${projectId}`, draftMode),
        {
          ops,
          username: 'SWRL rules',
          sessionId: `swrl-add-${Date.now()}`,
          precondition: executionResult?.stamp ?? null,
          origin: { source: 'swrl-rules', label: 'Added from rule results (' + ops.length + ' facts)' }
        }
      );
      window.dispatchEvent(new CustomEvent('ontocode:committed-change', { detail: { projectId, direction: 'APPLY', changeSetId: res?.changeSetId } }));
      return { ok: true, message: `Added ${ops.length} fact${ops.length === 1 ? '' : 's'} to the ontology. You can undo this from the history.` };
    } catch (e: any) {
      if (/stale|out of date|\(409\)|changed since/i.test(String(e?.message || ''))) {
        return { ok: false, message: 'The ontology or the rules changed since you ran this. Run the rules again, then add.' };
      }
      return { ok: false, message: e?.data?.message || e?.message || 'Could not add the facts.' };
    }
  };

  const handleMakeLocalCopy = async (rule: SwrlRule) => {
    setCopyingId(rule.id);
    setSaveError(null);
    try {
      const body = localCopyBody({ ...rule, comment: rule.comment ?? '' }, rules.map(r => r.ruleName));
      const res = await apiClient.post<SwrlRule>(
        withDraft(`/api/ontology/${projectId}/rules`, draftMode),
        { ...body, origin: { source: 'swrl-rules', label: 'Made a local copy of ' + rule.ruleName } }
      );
      rulesVersionRef.current++;
      setRules(prev => [...prev, res]);
      handleSelectRule(res);
      announceRuleChange('ruleAdded');
    } catch (e: any) {
      console.error('Copy failed:', e);
      setSaveError(friendlyRuleError(e, 'Could not make a local copy of this rule.'));
    } finally {
      setCopyingId(null);
    }
  };

  const handleDelete = async () => {
    if (!confirmDialog.ruleId) return;
    try {
      await apiClient.delete(withDraft(`/api/ontology/${projectId}/rules/${confirmDialog.ruleId}`, draftMode));
      rulesVersionRef.current++;
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
      announceRuleChange('ruleDeleted');
    } catch (e: any) {
      console.error('Delete failed:', e);
      setSaveError(friendlyRuleError(e, 'Could not delete the rule.'));
    } finally {
      setConfirmDialog({ isOpen: false, ruleName: '', ruleId: '' });
    }
  };

  const handleToggleEnabled = async (rule: SwrlRule) => {
    try {
      const res = await apiClient.put<SwrlRule>(
        withDraft(`/api/ontology/${projectId}/rules/${rule.id}`, draftMode),
        ruleWriteBody(
          { ruleName: rule.ruleName, ruleText: rule.ruleText, comment: rule.comment || '', category: rule.category || '', enabled: !rule.enabled },
          (rule.enabled ? 'Turned off rule ' : 'Turned on rule ') + rule.ruleName
        )
      );
      rulesVersionRef.current++;
      setRules(rules.map(r => r.id === rule.id ? res : r));
      if (selectedRule?.id === rule.id) {
        setSelectedRule(res);
        setEditForm(prev => ({ ...prev, enabled: res.enabled }));
      }
      announceRuleChange('ruleToggled');
    } catch (e: any) {
      console.error('Toggle failed:', e);
      setSaveError(friendlyRuleError(e, 'Could not change this rule.'));
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
      const res = await apiClient.post<ExecutionResponse>(withDraft(`/api/ontology/${projectId}/rules/execute`, draftMode));
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
        withDraft(`/api/swrl/${projectId}/execute/selected`, draftMode),
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
        withDraft(`/api/swrl/${projectId}/rules/${selectedRule.id}/test`, draftMode)
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

  const triggerDownload = (blob: Blob, fileName: string) => {
    const url = URL.createObjectURL(blob);
    const a = document.createElement('a');
    a.href = url;
    a.download = fileName;
    document.body.appendChild(a);
    a.click();
    a.remove();
    setTimeout(() => URL.revokeObjectURL(url), 1000);
  };

  const handleExportServer = async (format: 'xlsx' | 'json') => {
    setImportError(null);
    try {
      const blob = await apiClient.getBlob(withDraft(`/api/ontology/${projectId}/rules/export?format=${format}`, draftMode));
      triggerDownload(blob, `swrl-rules-${projectId}.${format}`);
    } catch (e) {
      console.error('Export failed:', e);
      setImportError(importErrorMessage(e));
    }
  };

  const handleDownloadTemplate = async () => {
    setImportError(null);
    try {
      const blob = await apiClient.getBlob(`/api/ontology/${projectId}/rules/template.xlsx`);
      triggerDownload(blob, 'swrl-rules-template.xlsx');
    } catch (e) {
      console.error('Template download failed:', e);
      setImportError(importErrorMessage(e));
    }
  };

  const runImport = async (dryRun: boolean) => {
    const file = importFileRef.current;
    const session = importSessionRef.current;
    if (!file || !session) return;
    setImportRunning(true);
    setImportError(null);
    try {
      const form = new FormData();
      form.append('file', file);
      form.append('importId', session.importId);
      if (!dryRun && importPreview?.report.previewStamp) form.append('previewStamp', importPreview.report.previewStamp);
      const res = await apiClient.postForm<ImportReport>(
        withDraft(`/api/ontology/${projectId}/rules/import?dryRun=${dryRun ? 'true' : 'false'}`, draftMode),
        form
      );
      if (dryRun) {
        setImportPreview({ report: { ...res, dryRun: true }, fileName: file.name });
        return;
      }
      importFileRef.current = null;
      importSessionRef.current = null;
      setImportPreview(null);
      setImportNotice(importOutcomeNotice(res));
      rulesVersionRef.current++;
      announceRuleChange('ruleAdded');
      await loadRules();
    } catch (err: any) {
      const stale = staleImportReport(err);
      if (stale) {
        setImportPreview({ report: stale, fileName: file.name });
      } else {
        if (dryRun) setImportPreview(null);
        setImportError(importErrorMessage(err));
      }
    } finally {
      setImportRunning(false);
    }
  };

  const handleImport = async (e: React.ChangeEvent<HTMLInputElement>) => {
    const file = e.target.files?.[0];
    if (fileInputRef.current) fileInputRef.current.value = '';
    if (!file) return;
    setImportNotice(null);
    setImportError(null);
    if (!isImportFile(file.name)) {
      setImportError('Choose an Excel (.xlsx) or JSON (.json) file.');
      return;
    }
    importSessionRef.current = importSessionFor(importSessionRef.current, file);
    importFileRef.current = file;
    setImportPreview(null);
    await runImport(true);
  };

  const closeImportPreview = () => {
    if (importRunning) return;
    setImportPreview(null);
  };

  return (
    <div className="h-full flex flex-col bg-gray-50">
      <header className="bg-white border-b border-gray-200 px-4 py-3">
        <div className="flex items-center justify-between">
          <div>
            <h1 className="text-lg font-bold text-gray-900">SWRL Rule Editor</h1>
            <p className="text-xs text-gray-500">{enabledCount} of {rules.length} rules enabled</p>
          </div>
          <div className="flex items-center gap-2">
            <div className="flex items-center gap-1 border-r border-gray-200 pr-2 mr-2">
              <button onClick={() => handleExportServer('xlsx')} className="px-2 py-1.5 text-xs text-gray-600 hover:bg-gray-100 rounded-lg flex items-center gap-1" title="Export Excel">
                <Download size={14} /> Export Excel
              </button>
              <button onClick={() => handleExportServer('json')} className="px-2 py-1.5 text-xs text-gray-600 hover:bg-gray-100 rounded-lg flex items-center gap-1" title="Export JSON">
                <Download size={14} /> Export JSON
              </button>
              <button onClick={handleDownloadTemplate} className="px-2 py-1.5 text-xs text-gray-600 hover:bg-gray-100 rounded-lg flex items-center gap-1" title="Download Excel template">
                <FileText size={14} /> Download Excel template
              </button>
              <button onClick={() => fileInputRef.current?.click()} disabled={importRunning} className="p-2 text-gray-500 hover:bg-gray-100 rounded-lg disabled:opacity-50" title="Import">
                <Upload size={16} />
              </button>
              <input ref={fileInputRef} type="file" accept=".xlsx,.json" onChange={handleImport} className="hidden" />
              <button onClick={loadRules} className="p-2 text-gray-500 hover:bg-gray-100 rounded-lg" title="Refresh">
                <RefreshCw size={16} />
              </button>
            </div>

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

      {(importNotice || importError) && (
        <div className={`px-4 py-2 text-sm border-b ${importError ? 'bg-red-50 text-red-700 border-red-200' : 'bg-green-50 text-green-700 border-green-200'}`} role="status">
          {importError || importNotice}
        </div>
      )}

      {importPreview && (
        <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/40" role="dialog" aria-label="Import preview">
          <div className="bg-white rounded-xl shadow-xl w-full max-w-3xl max-h-[85vh] flex flex-col">
            <div className="px-5 py-4 border-b border-gray-200">
              <h2 className="text-base font-bold text-gray-900">Preview, nothing saved</h2>
              <p className="text-xs text-gray-500 mt-0.5">{importPreview.fileName}</p>
              <p className="text-sm text-gray-800 mt-2" data-testid="import-summary">{importSummaryLine(importPreview.report, true)}</p>
              {importPreview.report.message && (
                <p className="text-sm text-amber-700 mt-1">{importPreview.report.message}</p>
              )}
            </div>
            <div className="px-5 py-3 overflow-y-auto flex-1">
              {importPreview.report.skipped.length === 0 ? (
                <p className="text-sm text-gray-500">No rows were skipped.</p>
              ) : (
                <table className="w-full text-xs text-left">
                  <thead>
                    <tr className="text-gray-500 border-b border-gray-200">
                      <th className="py-1 pr-2">Sheet</th>
                      <th className="py-1 pr-2">Row</th>
                      <th className="py-1 pr-2">Rule name</th>
                      <th className="py-1 pr-2">Reason</th>
                      <th className="py-1">Suggestions</th>
                    </tr>
                  </thead>
                  <tbody>
                    {skippedRowsView(importPreview.report.skipped).map(r => (
                      <tr key={r.key} className="border-b border-gray-100 align-top">
                        <td className="py-1 pr-2">{r.sheet}</td>
                        <td className="py-1 pr-2">{r.row}</td>
                        <td className="py-1 pr-2">{r.ruleName}</td>
                        <td className="py-1 pr-2">{r.reason}</td>
                        <td className="py-1">{r.suggestions}</td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              )}
            </div>
            <div className="px-5 py-3 border-t border-gray-200 flex justify-end gap-2">
              <button
                onClick={closeImportPreview}
                disabled={importRunning}
                className="px-4 py-2 text-sm font-medium text-gray-600 bg-gray-100 rounded-lg hover:bg-gray-200 disabled:opacity-50"
              >
                Cancel
              </button>
              <button
                onClick={() => runImport(false)}
                disabled={!canRunImport(importPreview.report, importRunning)}
                className="px-4 py-2 text-sm font-medium text-white bg-purple-600 rounded-lg hover:bg-purple-700 disabled:bg-purple-300 flex items-center gap-2"
              >
                {importRunning && <Loader2 size={14} className="animate-spin" />}
                {importButtonLabel(importPreview.report)}
              </button>
            </div>
          </div>
        </div>
      )}

      <div className="flex-1 flex overflow-hidden">
        <aside className="w-72 bg-white border-r border-gray-200 flex flex-col">
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
                className="w-full pl-9 pr-3 py-1.5 text-sm text-black border border-gray-200 rounded-lg focus:outline-none focus:ring-2 focus:ring-purple-200"
              />
            </div>
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
                    onMakeLocalCopy={() => handleMakeLocalCopy(rule)}
                    isCopying={copyingId === rule.id}
                  />
                ))}
              </div>
            )}
            {!isLoading && unmigrated.length > 0 && (
              <div className="m-3 p-3 text-xs bg-amber-50 border border-amber-200 rounded-lg text-amber-900">
                <div className="font-semibold">{unmigratedNotice(unmigrated.length)}</div>
                <ul className="mt-1 space-y-1">
                  {unmigrated.map((u, i) => (
                    <li key={u.id || `${u.ruleName}-${i}`}>
                      <span className="font-medium">{u.ruleName}</span>
                      {u.error ? <span>: {u.error}</span> : null}
                    </li>
                  ))}
                </ul>
              </div>
            )}
            {!isLoading && hasDisabledRule(rules) && (
              <p className="px-3 py-2 text-[11px] text-gray-500">
                Disabled rules are saved in the ontology file. Other tools do not know they are disabled and will run them.
              </p>
            )}
          </div>
        </aside>

        <main className="flex-1 flex flex-col overflow-hidden">
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
                  <div className="bg-white rounded-xl border border-gray-200 p-4">
                    <div className="flex items-center justify-between mb-3">
                      <label className="text-sm font-semibold text-gray-700">Rule Name</label>
                      {!isEditing && selectedRule && !isImportedRule(selectedRule) && (
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
                      className="w-full px-4 py-2.5 text-base text-black font-medium border border-gray-200 rounded-lg focus:outline-none focus:ring-2 focus:ring-purple-200 disabled:bg-gray-50 disabled:text-gray-600"
                      placeholder="Enter rule name"
                    />
                    
                    <div className="grid grid-cols-2 gap-4 mt-4">
                      <div>
                        <label className="text-xs font-medium text-gray-500 mb-1 block">Category</label>
                        <input
                          type="text"
                          value={editForm.category}
                          onChange={e => setEditForm(prev => ({ ...prev, category: e.target.value }))}
                          disabled={!isEditing}
                          className="w-full px-3 py-2 text-sm text-black border border-gray-200 rounded-lg focus:outline-none focus:ring-2 focus:ring-purple-200 disabled:bg-gray-50"
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

                  <div className="bg-white rounded-xl border border-gray-200 overflow-hidden">
                    <div className="flex items-center justify-between px-4 py-3 bg-gray-50 border-b border-gray-200">
                      <label className="text-sm font-semibold text-gray-700">SWRL Expression</label>
                      <div className="flex items-center gap-2">
                      <button
                        onClick={() => setShowRuleBuilder(true)}
                        disabled={!isEditing}
                        className="px-3 py-1 text-xs font-medium text-purple-700 bg-white border border-purple-200 rounded-lg hover:bg-purple-50 disabled:opacity-50"
                      >
                        Build rule
                      </button>
                      <div className="relative group">
                        <button className="px-3 py-1 text-xs font-medium text-gray-600 bg-white border border-gray-200 rounded-lg hover:bg-gray-50 flex items-center gap-1">
                          <BookOpen size={12} /> Templates
                          <ChevronDown size={12} />
                        </button>
                        <div className="absolute right-0 top-full mt-1 w-80 max-h-96 overflow-y-auto bg-white border border-gray-200 rounded-lg shadow-xl z-50 hidden group-hover:block">
                          {dynamicTemplates.length > 0 && (
                            <div>
                              <div className="sticky top-0 px-3 py-2 text-[10px] font-semibold text-green-700 bg-green-50 border-b border-green-200 flex items-center gap-1">
                                <Sparkles size={10} /> Ontology-Based Templates ({dynamicTemplates.length})
                              </div>
                              {dynamicTemplates.map((t, i) => (
                                <button
                                  key={`dyn-${i}`}
                                  onClick={() => setEditForm(prev => ({ ...prev, ruleText: t.template }))}
                                  className="w-full text-left px-3 py-2 text-xs hover:bg-green-50 border-b border-gray-100"
                                >
                                  <div className="font-medium text-green-800">{t.name}</div>
                                  <div className="text-[10px] text-green-600 truncate">{t.description}</div>
                                </button>
                              ))}
                              <div className="px-3 py-2 text-[10px] font-semibold text-purple-700 bg-purple-50 border-b border-purple-200">
                                General Templates ({validGeneralTemplates.length})
                              </div>
                            </div>
                          )}
                          {validGeneralTemplates.length > 0 ? validGeneralTemplates.map((t, i) => (
                            <button
                              key={i}
                              onClick={() => setEditForm(prev => ({ ...prev, ruleText: t.template }))}
                              className="w-full text-left px-3 py-2 text-xs hover:bg-purple-50 border-b border-gray-100 last:border-0"
                            >
                              <div className="font-medium text-gray-800">{t.name}</div>
                              <div className="text-[10px] text-gray-500 truncate">{t.description}</div>
                            </button>
                          )) : (
                            <div className="px-3 py-3 text-xs text-gray-500">
                              {ontologySchema
                                ? 'No templates match this ontology schema. Add the needed classes/properties first.'
                                : 'Loading ontology schema before showing templates...'}
                            </div>
                          )}
                        </div>
                      </div>
                      </div>
                    </div>

                    {showRuleBuilder && isEditing && (
                      <RuleBuilder
                        ruleText={editForm.ruleText}
                        schema={ontologySchema}
                        onUse={text => setEditForm(prev => ({ ...prev, ruleText: text }))}
                        onClose={() => setShowRuleBuilder(false)}
                      />
                    )}

                    <textarea
                      ref={textareaRef}
                      value={editForm.ruleText}
                      onChange={e => setEditForm(prev => ({ ...prev, ruleText: e.target.value }))}
                      disabled={!isEditing}
                      className="w-full h-32 px-4 py-3 font-mono text-sm resize-none focus:outline-none disabled:bg-gray-50 bg-white text-black disabled:text-gray-700"
                      placeholder="Person(?p) ^ hasAge(?p, ?age) ^ swrlb:greaterThan(?age, 18) -> Adult(?p)"
                    />

                    <div className="min-h-[2.5rem]">
                      {isEditing && validationResult && (
                        <div className={`px-4 py-2 flex items-center gap-2 text-sm ${
                          validationResult.valid ? 'bg-green-50 text-green-700' : 'bg-red-50 text-red-700'
                        }`}>
                          {validationResult.valid ? <Check size={14} /> : <AlertCircle size={14} />}
                          {validationResult.valid ? 'Valid syntax' : validationResult.errorMessage}
                        </div>
                      )}
                    </div>

                    {saveError && (
                      <div className="px-4 py-2 flex items-center gap-2 text-sm bg-red-50 text-red-700 border-t border-red-100">
                        <AlertCircle size={14} />
                        {saveError}
                      </div>
                    )}

                    <QuickInsertPanel
                      onInsert={insertAtCursor}
                      disabled={!isEditing}
                      dynamicTemplates={dynamicTemplates}
                      generalTemplates={validGeneralTemplates}
                      schemaLoaded={!!ontologySchema}
                    />
                  </div>

                  <div className="bg-white rounded-xl border border-gray-200 p-4">
                    <label className="text-sm font-semibold text-gray-700 mb-2 block">Description</label>
                    <textarea
                      value={editForm.comment}
                      onChange={e => setEditForm(prev => ({ ...prev, comment: e.target.value }))}
                      disabled={!isEditing}
                      className="w-full h-20 px-3 py-2 text-sm border border-gray-200 rounded-lg resize-none focus:outline-none focus:ring-2 focus:ring-purple-200 disabled:bg-gray-50 bg-white text-black"
                      placeholder="Add notes or documentation..."
                    />
                  </div>

                  {isEditing ? (
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
                        {selectedRule ? 'Update' : 'Save'}
                      </button>
                    </div>
                  ) : selectedRule && isImportedRule(selectedRule) ? (
                    <div className="flex flex-col gap-3 pt-2">
                      <div className="text-sm text-gray-600">{sourceLabel(selectedRule)}. This rule is read-only here.</div>
                      <WhyNotPanel projectId={projectId} ruleId={selectedRule.id} draftMode={draftMode} />
                      <div className="flex items-center gap-3">
                        <div className="flex-1" />
                        <button
                          onClick={() => handleMakeLocalCopy(selectedRule)}
                          disabled={copyingId === selectedRule.id}
                          className="px-4 py-2 text-sm font-medium text-purple-600 bg-purple-50 rounded-lg hover:bg-purple-100 disabled:opacity-50"
                        >
                          Make a local copy
                        </button>
                      </div>
                    </div>
                  ) : selectedRule && (
                    <div className="flex flex-col gap-3 pt-2">
                    <WhyNotPanel projectId={projectId} ruleId={selectedRule.id} draftMode={draftMode} />
                    <div className="flex items-center gap-3">
                      <button
                        onClick={() => setConfirmDialog({ isOpen: true, ruleName: selectedRule.ruleName, ruleId: selectedRule.id })}
                        className="px-4 py-2 text-sm font-medium text-red-600 bg-red-50 rounded-lg hover:bg-red-100 flex items-center gap-2"
                      >
                        <Trash2 size={14} /> Delete
                      </button>
                      <div className="flex-1" />
                      <button
                        onClick={handleTestRule}
                        disabled={!editForm.ruleText.trim()}
                        className="px-4 py-2 text-sm font-medium text-purple-600 bg-purple-50 rounded-lg hover:bg-purple-100 disabled:opacity-50 flex items-center gap-2"
                      >
                        <Play size={14} /> Test
                      </button>
                      <button
                        onClick={() => setIsEditing(true)}
                        className="px-5 py-2 text-sm font-medium text-white bg-purple-600 rounded-lg hover:bg-purple-700 flex items-center gap-2"
                      >
                        <Code size={14} /> Edit
                      </button>
                    </div>
                    </div>
                  )}
                </div>
              )}
            </div>
          ) : activePanel === 'results' ? (
            <ResultsPanel projectId={projectId} draftMode={draftMode} results={executionResult} isExecuting={isExecuting} onAdd={handleAddConclusions} onRerun={executionResult?.executionMode === 'selected' ? handleExecuteSelected : handleExecuteAll} />
          ) : activePanel === 'query' ? (
            <SQWRLQueryPanel projectId={projectId} context={context!} draftMode={draftMode} />
          ) : (
            <div className="flex-1 overflow-y-auto p-4 space-y-3">
              <div className="text-sm text-gray-600 mb-4">
                <p>SWRL built-in predicates. Click a built-in to insert it into your rule.</p>
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

      <ConfirmDialog
        isOpen={confirmDialog.isOpen}
        title="Delete Rule"
        message={`Are you sure you want to delete "${confirmDialog.ruleName}"? You can undo this from the history.`}
        onConfirm={handleDelete}
        onCancel={() => setConfirmDialog({ isOpen: false, ruleName: '', ruleId: '' })}
        confirmText="Delete"
        danger
      />
    </div>
  );
};

export default SWRLEditor;

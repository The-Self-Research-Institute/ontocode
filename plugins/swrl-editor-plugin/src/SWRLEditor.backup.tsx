import React, { useState, useEffect, useCallback, useRef } from 'react';
import { Plus, Trash2, Play, Save, Check, X, AlertCircle, Loader2, Eye, EyeOff, ChevronDown, ChevronRight, Copy, Download, Upload, BarChart2, HelpCircle, BookOpen, GripVertical, Maximize2, Minimize2, LayoutTemplate, Shrink, PanelTopClose, PanelBottomClose, Search } from 'lucide-react';
import apiClient from './apiClient';
import type { SwrlRule, ValidationResult as SwrlValidationResult, ExecutionResponse, PluginContext, PagedResponse, BuiltInCategory, InferredAxiom } from './types';

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

const RULE_TEMPLATES = [
  { name: 'Class Membership', template: 'Person(?p) ^ hasAge(?p, ?age) ^ swrlb:greaterThanOrEqual(?age, 18) -> Adult(?p)', description: 'Classify individuals based on property values' },
  { name: 'Property Transfer', template: 'hasParent(?x, ?y) ^ hasBrother(?y, ?z) -> hasUncle(?x, ?z)', description: 'Infer a relationship based on a chain of properties' },
  { name: 'Math Calculation', template: 'Item(?i) ^ hasPrice(?i, ?p) ^ swrlb:multiply(?tax, ?p, 0.08) -> hasTax(?i, ?tax)', description: 'Calculate a value using math built-ins' },
  { name: 'String Matching', template: 'Person(?p) ^ hasName(?p, ?name) ^ swrlb:startsWith(?name, "Dr.") -> Doctor(?p)', description: 'Classify based on string patterns' },
  { name: 'Temporal Relation', template: 'Event(?e1) ^ Event(?e2) ^ hasTime(?e1, ?t1) ^ hasTime(?e2, ?t2) ^ temporal:before(?t1, ?t2) -> Precedes(?e1, ?e2)', description: 'Infer temporal order of events' },
];

function useDebounce(value: string, delay: number) {
  const [debounced, setDebounced] = useState(value);
  useEffect(() => { 
    const t = setTimeout(() => setDebounced(value), delay); 
    return () => clearTimeout(t); 
  }, [value, delay]);
  return debounced;
}

const ResizableSection: React.FC<{
  title: string;
  defaultOpen?: boolean;
  defaultHeight?: number;
  minHeight?: number;
  maxHeight?: number;
  children: React.ReactNode;
  headerExtra?: React.ReactNode;
  onMaximize?: () => void;
  isMaximized?: boolean;
}> = ({ title, defaultOpen = true, defaultHeight = 150, minHeight = 80, maxHeight = 500, children, headerExtra, onMaximize, isMaximized }) => {
  const [isOpen, setIsOpen] = useState(defaultOpen);
  const [height, setHeight] = useState(defaultHeight);
  const [isResizing, setIsResizing] = useState(false);
  const startY = useRef(0);
  const startHeight = useRef(0);
  const containerRef = useRef<HTMLDivElement>(null);

  const handleMouseDown = useCallback((e: React.MouseEvent) => {
    setIsResizing(true);
    startY.current = e.clientY;
    startHeight.current = height;
    e.preventDefault();
    e.stopPropagation();
  }, [height]);

  useEffect(() => {
    const handleMouseMove = (e: MouseEvent) => {
      if (!isResizing) return;
      const delta = e.clientY - startY.current;
      const newHeight = Math.min(maxHeight, Math.max(minHeight, startHeight.current + delta));
      setHeight(newHeight);
    };

    const handleMouseUp = () => {
      setIsResizing(false);
    };

    if (isResizing) {
      document.addEventListener('mousemove', handleMouseMove);
      document.addEventListener('mouseup', handleMouseUp);
      document.body.style.cursor = 'ns-resize';
      document.body.style.userSelect = 'none';
    }

    return () => {
      document.removeEventListener('mousemove', handleMouseMove);
      document.removeEventListener('mouseup', handleMouseUp);
      document.body.style.cursor = '';
      document.body.style.userSelect = '';
    };
  }, [isResizing, minHeight, maxHeight]);

  return (
    <div ref={containerRef} className="border-2 border-gray-300 rounded-lg overflow-hidden bg-white shadow-sm">
      {}
      <div className="w-full px-4 py-2.5 flex items-center justify-between bg-gradient-to-r from-gray-100 to-gray-50 border-b border-gray-200">
        <button
          onClick={() => setIsOpen(!isOpen)}
          className="flex items-center gap-2 hover:bg-gray-100 rounded px-1 -ml-1 transition-colors"
        >
          {isOpen ? <ChevronDown size={18} className="text-purple-600" /> : <ChevronRight size={18} className="text-purple-600" />}
          <span className="font-semibold text-gray-800">{title}</span>
        </button>
        <div className="flex items-center gap-2">
          {headerExtra && <div onClick={e => e.stopPropagation()}>{headerExtra}</div>}
          {onMaximize && (
            <button
              onClick={(e) => { e.stopPropagation(); onMaximize(); }}
              className="p-1.5 text-gray-500 hover:bg-gray-200 rounded transition-colors"
              title={isMaximized ? "Restore" : "Maximize"}
            >
              {isMaximized ? <Minimize2 size={16} /> : <Maximize2 size={16} />}
            </button>
          )}
        </div>
      </div>

      {}
      {isOpen && (
        <div className="relative">
          <div 
            style={{ height: `${height}px` }} 
            className="overflow-auto transition-none"
          >
            {children}
          </div>

          {}
          <div
            onMouseDown={handleMouseDown}
            className={`absolute bottom-0 left-0 right-0 h-3 cursor-ns-resize flex items-center justify-center group hover:bg-purple-50 transition-colors ${isResizing ? 'bg-purple-100' : 'bg-gray-50'}`}
            title="Drag to resize"
          >
            <div className={`w-16 h-1 rounded-full transition-colors ${isResizing ? 'bg-purple-500' : 'bg-gray-300 group-hover:bg-purple-400'}`} />
          </div>
        </div>
      )}
    </div>
  );
};

const CollapsibleSection: React.FC<{
  title: string;
  defaultOpen?: boolean;
  children: React.ReactNode;
  headerExtra?: React.ReactNode;
  onMaximize?: () => void;
  isMaximized?: boolean;
}> = ({ title, defaultOpen = true, children, headerExtra, onMaximize, isMaximized }) => {
  const [isOpen, setIsOpen] = useState(defaultOpen);

  return (
    <div className="border-2 border-gray-300 rounded-lg overflow-hidden bg-white shadow-sm">
      <div className="w-full px-4 py-2.5 flex items-center justify-between bg-gradient-to-r from-gray-100 to-gray-50 border-b border-gray-200">
        <button
          onClick={() => setIsOpen(!isOpen)}
          className="flex items-center gap-2 hover:bg-gray-100 rounded px-1 -ml-1 transition-colors"
        >
          {isOpen ? <ChevronDown size={18} className="text-purple-600" /> : <ChevronRight size={18} className="text-purple-600" />}
          <span className="font-semibold text-gray-800">{title}</span>
        </button>
        <div className="flex items-center gap-2">
          {headerExtra && <div onClick={e => e.stopPropagation()}>{headerExtra}</div>}
          {onMaximize && (
            <button
              onClick={(e) => { e.stopPropagation(); onMaximize(); }}
              className="p-1.5 text-gray-500 hover:bg-gray-200 rounded transition-colors"
              title={isMaximized ? "Restore" : "Maximize"}
            >
              {isMaximized ? <Minimize2 size={16} /> : <Maximize2 size={16} />}
            </button>
          )}
        </div>
      </div>
      {isOpen && <div className="border-t border-gray-100">{children}</div>}
    </div>
  );
};

function useResizable(initialHeight: number, minHeight: number = 100, maxHeight: number = 600) {
  const [height, setHeight] = useState(initialHeight);
  const [isResizing, setIsResizing] = useState(false);
  const startY = useRef(0);
  const startHeight = useRef(0);

  const handleMouseDown = useCallback((e: React.MouseEvent) => {
    setIsResizing(true);
    startY.current = e.clientY;
    startHeight.current = height;
    e.preventDefault();
  }, [height]);

  useEffect(() => {
    const handleMouseMove = (e: MouseEvent) => {
      if (!isResizing) return;
      const delta = startY.current - e.clientY; // Inverted for bottom panel
      const newHeight = Math.min(maxHeight, Math.max(minHeight, startHeight.current + delta));
      setHeight(newHeight);
    };

    const handleMouseUp = () => {
      setIsResizing(false);
    };

    if (isResizing) {
      document.addEventListener('mousemove', handleMouseMove);
      document.addEventListener('mouseup', handleMouseUp);
      document.body.style.cursor = 'ns-resize';
      document.body.style.userSelect = 'none';
    }

    return () => {
      document.removeEventListener('mousemove', handleMouseMove);
      document.removeEventListener('mouseup', handleMouseUp);
      document.body.style.cursor = '';
      document.body.style.userSelect = '';
    };
  }, [isResizing, minHeight, maxHeight]);

  return { height, handleMouseDown, isResizing };
}

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
        {}
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

        {}
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

const extractLocalName = (uri: string): string => {
  if (!uri) return uri;

  const cleanUri = uri.replace(/^<|>$/g, '');
  const hashIndex = cleanUri.lastIndexOf('#');
  if (hashIndex !== -1) return cleanUri.substring(hashIndex + 1);
  const slashIndex = cleanUri.lastIndexOf('/');
  if (slashIndex !== -1) return cleanUri.substring(slashIndex + 1);
  return uri;
};

const ConfirmDialog: React.FC<{
  isOpen: boolean;
  title: string;
  message: string;
  onConfirm: () => void;
  onCancel: () => void;
}> = ({ isOpen, title, message, onConfirm, onCancel }) => {
  if (!isOpen) return null;

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center bg-black bg-opacity-50">
      <div className="bg-white rounded-lg shadow-xl max-w-md w-full mx-4 overflow-hidden">
        <div className="p-4 border-b border-gray-200">
          <h3 className="text-lg font-semibold text-gray-900">{title}</h3>
        </div>
        <div className="p-4">
          <p className="text-gray-700">{message}</p>
        </div>
        <div className="flex justify-end gap-3 p-4 bg-gray-50 border-t border-gray-200">
          <button
            onClick={onCancel}
            className="px-4 py-2 text-sm font-medium text-gray-700 bg-white border border-gray-300 rounded-lg hover:bg-gray-50 focus:outline-none focus:ring-2 focus:ring-purple-500"
          >
            Cancel
          </button>
          <button
            onClick={onConfirm}
            className="px-4 py-2 text-sm font-medium text-white bg-red-600 rounded-lg hover:bg-red-700 focus:outline-none focus:ring-2 focus:ring-red-500"
          >
            Delete
          </button>
        </div>
      </div>
    </div>
  );
};

const formatInferredAxiom = (axiom: InferredAxiom): { type: string; subject: string; predicate?: string; object?: string; formatted: string } => {
  const readable = axiom.readable || '';

  const clean = (s: string) => extractLocalName(s);

  const classAssertionMatch = readable.match(/ClassAssertion\s*\(\s*<?([^>\s]+)>?\s+<?([^>\s]+)>?\s*\)/i);
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

  const objPropMatch = readable.match(/ObjectPropertyAssertion\s*\(\s*<?([^>\s]+)>?\s+<?([^>\s]+)>?\s+<?([^>\s]+)>?\s*\)/i);
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

  const dataPropMatch = readable.match(/DataPropertyAssertion\s*\(\s*<?([^>\s]+)>?\s+<?([^>\s]+)>?\s+(.+)\s*\)/i);
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

  const subClassMatch = readable.match(/SubClassOf\s*\(\s*<?([^>\s]+)>?\s+<?([^>\s]+)>?\s*\)/i);
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
      {}
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

const ExecutionResultsPanel: React.FC<{ results: ExecutionResponse | null }> = ({ results }) => {
  const [showInferredAxioms, setShowInferredAxioms] = useState(true);
  const [viewMode, setViewMode] = useState<'grouped' | 'table' | 'raw'>('grouped');
  const [expandedGroups, setExpandedGroups] = useState<Set<string>>(new Set());
  const [searchFilter, setSearchFilter] = useState('');

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

  const classAssertions = results.inferredAxioms.filter(ax => {
    const parsed = formatInferredAxiom(ax);
    return parsed.type === 'ClassAssertion';
  });

  const hasSwrlResults = classAssertions.length > 0;

  return (
    <div className="p-4 space-y-4">
      {}
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

        {}
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

      {}
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

      {}
      {results.success && !hasSwrlResults && results.inferredAxioms.length > 0 && (
        <div className="bg-yellow-50 border-2 border-yellow-300 rounded-xl p-4">
          <div className="flex items-start gap-3">
            <AlertCircle size={24} className="text-yellow-600 mt-0.5 flex-shrink-0" />
            <div>
              <div className="font-bold text-yellow-800">No SWRL ClassAssertion Results</div>
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
          </div>
        </div>
      )}

      {}
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

      {}
      {results.success && results.inferredAxioms.length > 0 && (
        <div className="bg-white rounded-xl border-2 border-gray-200 overflow-hidden shadow-sm">
          {}
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
                {}
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
                {}
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
                {}
                <button
                  onClick={() => setShowInferredAxioms(!showInferredAxioms)}
                className="flex items-center gap-1 px-3 py-1.5 text-sm text-purple-600 hover:bg-purple-50 rounded-lg transition-colors"
              >
                {showInferredAxioms ? <EyeOff size={16} /> : <Eye size={16} />}
                {showInferredAxioms ? 'Hide' : 'Show'}
              </button>
            </div>
          </div>
          {}
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

          {}
          {showInferredAxioms && viewMode === 'grouped' && (
            <div className="max-h-[500px] overflow-y-auto">
              {Array.from(groupedAxioms.entries()).map(([type, items]) => {
                const style = getAxiomTypeStyle(type);
                const isExpanded = expandedGroups.has(type);
                return (
                  <div key={type} className="border-b border-gray-100 last:border-b-0">
                    {}
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
                    {}
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

          {}
          {showInferredAxioms && viewMode === 'table' && (
            <div className="max-h-[500px] overflow-auto">
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

          {}
          {showInferredAxioms && viewMode === 'raw' && (
            <div className="max-h-[500px] overflow-y-auto">
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

      {}
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
  );
};

const SWRLEditor: React.FC<{ projectId: string; context: PluginContext }> = ({ projectId, context }) => {
  const [rules, setRules] = useState<SwrlRule[]>([]);
  const [selectedRule, setSelectedRule] = useState<SwrlRule | null>(null);
  const [selectedRuleIds, setSelectedRuleIds] = useState<Set<string>>(new Set()); // Multi-select state
  const [isEditing, setIsEditing] = useState(false);
  const [editForm, setEditForm] = useState({
    ruleName: '',
    ruleText: '',
    comment: '',
    category: '',
    enabled: true
  });

  const [validationResult, setValidationResult] = useState<SwrlValidationResult | null>(null);
  const [executionResult, setExecutionResult] = useState<ExecutionResponse | null>(null);
  const [executedRulesInfo, setExecutedRulesInfo] = useState<{ count: number; names: string[] } | null>(null);
  const [isLoading, setIsLoading] = useState(false);
  const [isExecuting, setIsExecuting] = useState(false);
  const [activeTab, setActiveTab] = useState<'editor' | 'query' | 'results' | 'reference'>('editor');
  const [showStats, setShowStats] = useState(false);
  const [stats, setStats] = useState<any>(null);
  const [expandedBuiltInCategory, setExpandedBuiltInCategory] = useState<string | null>('swrlb');
  const fileInputRef = useRef<HTMLInputElement>(null);

  const [editorCollapsed, setEditorCollapsed] = useState(false);
  const [resultsMaximized, setResultsMaximized] = useState(false);
  const [editorMaximized, setEditorMaximized] = useState(false);
  const [ruleDetailsMaximized, setRuleDetailsMaximized] = useState(false);
  const [ruleExpressionMaximized, setRuleExpressionMaximized] = useState(false);

  const [confirmDialog, setConfirmDialog] = useState<{
    isOpen: boolean;
    ruleName: string;
    ruleId: string;
  }>({ isOpen: false, ruleName: '', ruleId: '' });

  const debouncedRuleText = useDebounce(editForm.ruleText, 500);

  const toggleRuleSelection = (ruleId: string) => {
    const newSelection = new Set(selectedRuleIds);
    if (newSelection.has(ruleId)) {
      newSelection.delete(ruleId);
    } else {
      newSelection.add(ruleId);
    }
    setSelectedRuleIds(newSelection);
  };

  const selectAllRules = () => {
    setSelectedRuleIds(new Set(rules.map(r => r.id)));
  };

  const clearSelection = () => {
    setSelectedRuleIds(new Set());
  };

  const selectEnabledRules = () => {
    setSelectedRuleIds(new Set(rules.filter(r => r.enabled).map(r => r.id)));
  };

  const loadRules = useCallback(async () => {
    if (!projectId) return;
    setIsLoading(true);
    try {
      const response = await apiClient.get<SwrlRule[] | PagedResponse<SwrlRule>>(`/api/swrl/${projectId}/rules`);

      if (Array.isArray(response)) {
        setRules(response);
      } else if (response && typeof response === 'object' && 'content' in response) {
        setRules((response as PagedResponse<SwrlRule>).content || []);
      } else {
        setRules([]);
      }
    } catch (error) {
      console.error('Failed to load SWRL rules:', error);
      setRules([]);
    } finally {
      setIsLoading(false);
    }
  }, [projectId]);

  useEffect(() => {
    loadRules();
  }, [loadRules]);

  useEffect(() => {
    const validate = async () => {
      if (!isEditing || !debouncedRuleText.trim()) {
        setValidationResult(null);
        return;
      }
      try {
        const response = await apiClient.post<SwrlValidationResult>(
          `/api/swrl/${projectId}/validate`, //
          { ruleText: debouncedRuleText }
        );
        setValidationResult(response);
      } catch (error) {
        console.error('Validation failed:', error);
      }
    };
    validate();
  }, [debouncedRuleText, projectId, isEditing]);

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
  };

  const handleSave = async () => {
    try {
      if (selectedRule) {

        const response = await apiClient.put<SwrlRule>(
          `/api/swrl/${projectId}/rules/${selectedRule.id}`, //
          editForm
        );

        setRules(rules.map(r => r.id === selectedRule.id ? response : r));
        setSelectedRule(response);
      } else {

        const response = await apiClient.post<SwrlRule>(
          `/api/swrl/${projectId}/rules`, //
          editForm
        );

        setRules([...rules, response]);
        setSelectedRule(response);
      }

      setIsEditing(false);
      setValidationResult(null);
    } catch (error) {
      console.error('Failed to save rule:', error);
      alert('Failed to save rule. Please check the console for details.');
    }
  };

  const handleDelete = async () => {
    if (!selectedRule) return;

    setConfirmDialog({
      isOpen: true,
      ruleName: selectedRule.ruleName,
      ruleId: selectedRule.id
    });
  };

  const executeDelete = async (ruleId: string) => {
    try {
      await apiClient.delete(`/api/swrl/${projectId}/rules/${ruleId}`);
      setRules(rules.filter(r => r.id !== ruleId));
      if (selectedRule?.id === ruleId) {
        setSelectedRule(null);
        setIsEditing(false);
      }
      setConfirmDialog({ isOpen: false, ruleName: '', ruleId: '' });
    } catch (error) {
      console.error('Failed to delete rule:', error);
      setConfirmDialog({ isOpen: false, ruleName: '', ruleId: '' });
    }
  };

  const handleToggleEnabled = async (rule: SwrlRule) => {
    try {
      const response = await apiClient.put<SwrlRule>(
        `/api/swrl/${projectId}/rules/${rule.id}`, //
        { enabled: !rule.enabled } // Send only the changed field
      );

      setRules(rules.map(r => r.id === rule.id ? response : r));

      if (selectedRule?.id === rule.id) {
        setSelectedRule(response);
        setEditForm(prev => ({ ...prev, enabled: response.enabled }));
      }
    } catch (error) {
      console.error('Failed to toggle rule:', error);
    }
  };

  const handleDuplicate = async () => {
    if (!selectedRule) return;
    try {
      const response = await apiClient.post<SwrlRule>(
        `/api/swrl/${projectId}/rules/${selectedRule.id}/duplicate`
      );
      setRules([...rules, response]);
      setSelectedRule(response);
      setEditForm({
        ruleName: response.ruleName,
        ruleText: response.ruleText,
        comment: response.comment || '',
        category: response.category || '',
        enabled: response.enabled
      });
      setIsEditing(true);
    } catch (error) {
      console.error('Failed to duplicate rule:', error);
      alert('Failed to duplicate rule');
    }
  };

  const handleExport = async () => {
    try {
      const response = await apiClient.get<SwrlRule[]>(`/api/swrl/${projectId}/rules/export`);
      const blob = new Blob([JSON.stringify(response, null, 2)], { type: 'application/json' });
      const url = window.URL.createObjectURL(blob);
      const a = document.createElement('a');
      a.href = url;
      a.download = `swrl-rules-${projectId}.json`;
      a.click();
      window.URL.revokeObjectURL(url);
    } catch (error) {
      console.error('Failed to export rules:', error);
      alert('Failed to export rules');
    }
  };

  const handleImport = async (event: React.ChangeEvent<HTMLInputElement>) => {
    const file = event.target.files?.[0];
    if (!file) return;

    const reader = new FileReader();
    reader.onload = async (e) => {
      try {
        const content = e.target?.result as string;
        const rulesToImport = JSON.parse(content);

        await apiClient.post(
          `/api/swrl/${projectId}/rules/import`,
          rulesToImport
        );

        loadRules();
        alert('Rules imported successfully');
      } catch (error) {
        console.error('Failed to import rules:', error);
        alert('Failed to import rules');
      }
    };
    reader.readAsText(file);
    if (fileInputRef.current) fileInputRef.current.value = '';
  };

  const handleTemplateSelect = (template: string) => {
    setEditForm(prev => ({
      ...prev,
      ruleText: template
    }));
  };

  const insertSymbol = (symbol: string) => {
    const textarea = document.querySelector('textarea[placeholder*="Person(?p)"]') as HTMLTextAreaElement;
    if (textarea) {
      const start = textarea.selectionStart;
      const end = textarea.selectionEnd;
      const text = editForm.ruleText;
      const newText = text.substring(0, start) + symbol + text.substring(end);
      setEditForm(prev => ({ ...prev, ruleText: newText }));

      setTimeout(() => {
        textarea.focus();
        textarea.setSelectionRange(start + symbol.length, start + symbol.length);
      }, 0);
    } else {
      setEditForm(prev => ({ ...prev, ruleText: prev.ruleText + symbol }));
    }
  };

  const loadStats = async () => {
    try {
      const res = await apiClient.get(`/api/swrl/${projectId}/rules/stats`);
      setStats(res);
      setShowStats(true);
    } catch (e) { console.error(e); }
  };

  const handleTestRule = async () => {
    if (!selectedRule) return;
    setIsExecuting(true);
    setExecutionResult(null);
    try {
      const response = await apiClient.post<ExecutionResponse>(
        `/api/swrl/${projectId}/rules/${selectedRule.id}/test`
      );
      setExecutionResult(response);
      setExecutedRulesInfo({ count: 1, names: [selectedRule.ruleName] });
      setActiveTab('results');
    } catch (error) {
      console.error('Failed to test rule:', error);
      alert('Failed to test rule');
    } finally {
      setIsExecuting(false);
    }
  };

  const handleExecuteSelectedRules = async () => {
    if (selectedRuleIds.size === 0) return;
    setIsExecuting(true);
    setExecutionResult(null);

    try {
      const ruleIds = Array.from(selectedRuleIds);
      const selectedNames = rules.filter(r => selectedRuleIds.has(r.id)).map(r => r.ruleName);

      const response = await apiClient.post<ExecutionResponse>(
        `/api/swrl/${projectId}/execute/selected`,
        { ruleIds }
      );
      setExecutionResult(response);
      setExecutedRulesInfo({ count: ruleIds.length, names: selectedNames });
      setActiveTab('results');
      setEditorCollapsed(true); // Collapse editor to show results better
    } catch (error) {
      console.error('Selected rules execution failed:', error);
      alert('Failed to execute selected rules');
    } finally {
      setIsExecuting(false);
    }
  };

  const handleExecuteAllRules = async () => {
    setIsExecuting(true);
    setExecutionResult(null);

    try {
      const enabledNames = rules.filter(r => r.enabled).map(r => r.ruleName);
      const response = await apiClient.post<ExecutionResponse>(
        `/api/swrl/${projectId}/execute`
      );
      setExecutionResult(response);
      setExecutedRulesInfo({ count: enabledCount, names: enabledNames });
      setActiveTab('results');
      setEditorCollapsed(true); // Collapse editor to show results better
    } catch (error) {
      console.error('Rule execution failed:', error);
      alert('Failed to execute rules');
    } finally {
      setIsExecuting(false);
    }
  };

  const categories = Array.from(new Set(rules.map(r => r.category).filter((s): s is string => !!s)));
  const enabledCount = rules.filter(r => r.enabled).length;

  return (
    <div className="h-full flex flex-col bg-gray-100">
      <header className="bg-white p-4 border-b border-gray-200">
        <div className="flex items-center justify-between">
          <div>
            <h1 className="text-xl font-bold text-gray-800">SWRL Rule Editor</h1>
            <p className="text-sm text-gray-500">
              Create and manage Semantic Web Rule Language (SWRL) rules
            </p>
          </div>
          <div className="flex items-center gap-2">
            <div className="text-right mr-4">
              <p className="text-sm text-gray-600">
                {selectedRuleIds.size > 0 
                  ? `${selectedRuleIds.size} selected` 
                  : `${enabledCount} of ${rules.length} enabled`}
              </p>
            </div>

            <div className="flex items-center gap-1 mr-2 border-r pr-2">
              <button onClick={loadStats} className="p-2 text-gray-600 hover:bg-gray-100 rounded-lg" title="Statistics">
                <BarChart2 size={18} />
              </button>
              <button onClick={handleExport} className="p-2 text-gray-600 hover:bg-gray-100 rounded-lg" title="Export Rules">
                <Download size={18} />
              </button>
              <button onClick={() => fileInputRef.current?.click()} className="p-2 text-gray-600 hover:bg-gray-100 rounded-lg" title="Import Rules">
                <Upload size={18} />
              </button>
              <input 
                type="file" 
                ref={fileInputRef} 
                onChange={handleImport} 
                className="hidden" 
                accept=".json" 
              />
            </div>

            {}
            {selectedRuleIds.size > 0 && (
              <button
                onClick={handleExecuteSelectedRules}
                disabled={isExecuting}
                className="flex items-center gap-2 px-4 py-2 bg-green-600 text-white rounded-lg hover:bg-green-700 disabled:bg-green-300 disabled:cursor-not-allowed transition-colors"
                title={`Execute ${selectedRuleIds.size} selected rule(s)`}
              >
                {isExecuting ? (
                  <>
                    <Loader2 size={18} className="animate-spin" />
                    <span>Running...</span>
                  </>
                ) : (
                  <>
                    <Play size={18} />
                    <span>Run Selected ({selectedRuleIds.size})</span>
                  </>
                )}
              </button>
            )}

            {}
            <button
              onClick={handleExecuteAllRules}
              disabled={isExecuting || enabledCount === 0}
              className="flex items-center gap-2 px-4 py-2 bg-purple-600 text-white rounded-lg hover:bg-purple-700 disabled:bg-purple-300 disabled:cursor-not-allowed transition-colors"
              title={`Execute all ${enabledCount} enabled rules`}
            >
              {isExecuting ? (
                <>
                  <Loader2 size={18} className="animate-spin" />
                  <span>Executing...</span>
                </>
              ) : (
                <>
                  <Play size={18} />
                  <span>Execute All ({enabledCount})</span>
                </>
              )}
            </button>
          </div>
        </div>
      </header>

      <div className="flex flex-1 overflow-hidden p-4 gap-4">
        {}
        <aside className="w-80 bg-white border border-gray-200 rounded-lg flex flex-col">
          {}
          <div className="p-2 border-b border-gray-200 space-y-2">
            <button
              onClick={handleNewRule}
              className="w-full flex items-center justify-center gap-2 px-3 py-2 bg-purple-600 text-white rounded-md hover:bg-purple-700 transition-colors text-sm"
            >
              <Plus size={16} />
              New Rule
            </button>

            {}
            {rules.length > 0 && (
              <div className="flex items-center gap-1 text-xs">
                <button 
                  onClick={selectAllRules} 
                  className="px-2 py-1 text-purple-600 hover:bg-purple-50 rounded"
                >
                  All
                </button>
                <button 
                  onClick={selectEnabledRules} 
                  className="px-2 py-1 text-purple-600 hover:bg-purple-50 rounded"
                >
                  Enabled
                </button>
                <button 
                  onClick={clearSelection} 
                  className="px-2 py-1 text-gray-500 hover:bg-gray-50 rounded"
                >
                  Clear
                </button>
                {selectedRuleIds.size > 0 && (
                  <span className="ml-auto text-gray-500">
                    {selectedRuleIds.size} selected
                  </span>
                )}
              </div>
            )}
          </div>

          <div className="flex-1 overflow-y-auto">
            {isLoading ? (
              <div className="flex items-center justify-center p-8">
                <Loader2 className="animate-spin text-purple-600" size={32} />
              </div>
            ) : rules.length === 0 ? (
              <div className="p-4 text-center text-gray-400">
                <p className="mb-2">No rules yet</p>
                <button onClick={handleNewRule} className="text-sm text-purple-600 hover:underline">
                  Create your first rule
                </button>
              </div>
            ) : (
              rules.map(rule => (
                <div
                  key={rule.id}
                  onClick={() => handleSelectRule(rule)}
                  className={`p-3 cursor-pointer border-l-4 group ${
                    selectedRule?.id === rule.id
                      ? 'bg-purple-50 border-purple-500'
                      : selectedRuleIds.has(rule.id)
                      ? 'bg-green-50 border-green-400'
                      : 'border-transparent hover:bg-gray-50'
                  }`}
                >
                  <div className="flex items-start gap-2 mb-1">
                    {}
                    <input
                      type="checkbox"
                      checked={selectedRuleIds.has(rule.id)}
                      onChange={() => toggleRuleSelection(rule.id)}
                      onClick={(e) => e.stopPropagation()}
                      className="mt-1 w-4 h-4 text-green-600 rounded border-gray-300 focus:ring-green-500 cursor-pointer"
                      title="Select for batch execution"
                    />
                    <div className="flex-1 min-w-0">
                      <div className="flex items-start justify-between">
                        <span className={`text-sm font-medium truncate ${
                          selectedRule?.id === rule.id ? 'text-purple-800' : 'text-gray-800'
                        }`}>
                          {rule.ruleName}
                        </span>
                        <div className="flex items-center gap-1 flex-shrink-0">
                          <button
                            onClick={(e) => {
                              e.stopPropagation();
                              setConfirmDialog({
                                isOpen: true,
                                ruleName: rule.ruleName,
                                ruleId: rule.id
                              });
                            }}
                            className="p-1 rounded opacity-0 group-hover:opacity-100 hover:bg-red-100 text-gray-400 hover:text-red-600 transition-all"
                            title="Delete rule"
                          >
                            <Trash2 size={14} />
                          </button>
                          <button
                            onClick={(e) => {
                              e.stopPropagation();
                              handleToggleEnabled(rule);
                            }}
                            className={`p-1 rounded ${
                              rule.enabled ? 'bg-green-100 text-green-700' : 'bg-gray-100 text-gray-400'
                            }`}
                            title={rule.enabled ? 'Enabled' : 'Disabled'}
                          >
                            {rule.enabled ? <Check size={14} /> : <X size={14} />}
                          </button>
                        </div>
                      </div>
                      {rule.category && (
                        <span className="inline-block px-2 py-0.5 text-xs bg-gray-200 text-gray-700 rounded mb-1">
                          {rule.category}
                        </span>
                      )}
                      <p className="text-xs text-gray-500 truncate font-mono">{rule.ruleText}</p>
                    </div>
                  </div>
                </div>
              ))
            )}
          </div>
        </aside>

        {}
        <main className="flex-1 flex flex-col gap-3 overflow-hidden">
          {}
          {!editorCollapsed && !resultsMaximized && (
          <div className={`overflow-y-auto space-y-3 pr-1 ${editorMaximized ? 'flex-1' : ''}`}>
            {}
            <div className="flex items-center justify-between bg-white border border-gray-200 rounded-lg px-3 py-2">
              <span className="text-sm font-semibold text-gray-700">📝 Rule Editor</span>
              <div className="flex items-center gap-1">
                <button 
                  onClick={() => setEditorCollapsed(true)} 
                  className="p-1.5 text-gray-500 hover:bg-gray-100 rounded" 
                  title="Collapse Editor"
                >
                  <PanelTopClose size={16} />
                </button>
                <button 
                  onClick={() => setEditorMaximized(!editorMaximized)} 
                  className="p-1.5 text-gray-500 hover:bg-gray-100 rounded" 
                  title={editorMaximized ? "Restore" : "Maximize Editor"}
                >
                  {editorMaximized ? <Minimize2 size={16} /> : <Maximize2 size={16} />}
                </button>
              </div>
            </div>

            {!selectedRule && !isEditing ? (
              <div className="flex items-center justify-center h-48 text-gray-500 bg-white border-2 border-gray-200 rounded-lg">
                <div className="text-center">
                  <AlertCircle size={36} className="mx-auto mb-2 opacity-30" />
                  <p className="text-base font-semibold text-gray-700">No rule selected</p>
                  <p className="text-xs text-gray-500 mt-1">Click a rule to view/edit, or check rules and click "Run Selected"</p>
                </div>
              </div>
            ) : (
              <>
              {}
              <CollapsibleSection 
                title="Rule Details" 
                defaultOpen={false}
                onMaximize={() => setRuleDetailsMaximized(!ruleDetailsMaximized)}
                isMaximized={ruleDetailsMaximized}
                headerExtra={
                  !isEditing && selectedRule ? (
                    <div className="flex gap-2">
                      <button onClick={handleDuplicate} className="px-3 py-1 text-xs bg-gray-200 text-gray-700 rounded hover:bg-gray-300 flex items-center gap-1">
                        <Copy size={12} /> Duplicate
                      </button>
                      <button onClick={() => setIsEditing(true)} className="px-3 py-1 text-xs bg-purple-100 text-purple-700 rounded hover:bg-purple-200">
                        Edit
                      </button>
                    </div>
                  ) : null
                }
              >
                <div className="p-4 space-y-4">
                  {}
                  <div>
                    <label className="block text-sm font-semibold text-gray-700 mb-1.5">Rule Name *</label>
                    <input
                      type="text"
                      value={editForm.ruleName}
                      onChange={(e) => setEditForm({ ...editForm, ruleName: e.target.value })}
                      disabled={!isEditing}
                      className="w-full px-4 py-2.5 text-base font-medium border-2 border-gray-300 rounded-lg focus:ring-2 focus:ring-purple-500 focus:border-purple-500 focus:outline-none disabled:bg-gray-50 disabled:border-gray-200 disabled:text-gray-600 text-gray-900 placeholder-gray-400 bg-white"
                      placeholder="Enter a descriptive name for this rule"
                    />
                  </div>

                  {}
                  <div className="grid grid-cols-2 gap-4">
                    <div>
                      <label className="block text-sm font-semibold text-gray-700 mb-1.5">Category</label>
                      <input
                        type="text"
                        value={editForm.category}
                        onChange={(e) => setEditForm({ ...editForm, category: e.target.value })}
                        disabled={!isEditing}
                        className="w-full px-4 py-2.5 border-2 border-gray-300 rounded-lg text-sm focus:ring-2 focus:ring-purple-500 focus:border-purple-500 focus:outline-none disabled:bg-gray-50 disabled:border-gray-200 text-gray-900 placeholder-gray-400 bg-white"
                        placeholder="e.g., Classification, Inference"
                        list="categories"
                      />
                      <datalist id="categories">
                        {categories.map(cat => <option key={cat} value={cat} />)}
                      </datalist>
                    </div>
                    <div>
                      <label className="block text-sm font-semibold text-gray-700 mb-1.5">Status</label>
                      <label className={`flex items-center gap-3 px-4 py-2.5 border-2 rounded-lg cursor-pointer transition-all ${
                        editForm.enabled 
                          ? 'bg-green-50 border-green-400 text-green-800' 
                          : 'bg-gray-100 border-gray-300 text-gray-600'
                      } ${!isEditing ? 'opacity-60 cursor-not-allowed' : 'hover:shadow-sm'}`}>
                        <input
                          type="checkbox"
                          checked={editForm.enabled}
                          onChange={(e) => setEditForm({ ...editForm, enabled: e.target.checked })}
                          disabled={!isEditing}
                          className="w-5 h-5 rounded border-2 text-green-600 focus:ring-green-500"
                        />
                        <span className="text-sm font-medium">{editForm.enabled ? '● Enabled' : '○ Disabled'}</span>
                      </label>
                    </div>
                  </div>
                </div>
              </CollapsibleSection>

              {}
              <ResizableSection 
                title="SWRL Rule Expression" 
                defaultOpen={false}
                defaultHeight={220}
                minHeight={150}
                maxHeight={500}
                onMaximize={() => setRuleExpressionMaximized(!ruleExpressionMaximized)}
                isMaximized={ruleExpressionMaximized}
              >
                <div className="p-4 h-full flex flex-col">
                  {}
                  <div className="flex items-center gap-2 mb-2 flex-wrap">
                    <div className="relative group">
                      <button className="px-3 py-1.5 text-xs font-medium text-gray-700 bg-gray-100 hover:bg-gray-200 border border-gray-300 rounded flex items-center gap-1">
                        <LayoutTemplate size={14} /> Templates
                      </button>
                      <div className="absolute left-0 top-full mt-1 w-64 bg-white border border-gray-200 rounded-lg shadow-xl z-50 hidden group-hover:block p-1">
                        <div className="text-xs font-semibold text-gray-500 px-2 py-1 bg-gray-50">Select a Template</div>
                        {RULE_TEMPLATES.map((t, i) => (
                          <button
                            key={i}
                            onClick={() => handleTemplateSelect(t.template)}
                            className="w-full text-left px-3 py-2 text-xs hover:bg-purple-50 hover:text-purple-700 block truncate"
                            title={t.description}
                          >
                            <span className="font-medium block">{t.name}</span>
                            <span className="text-gray-400 text-[10px]">{t.template.substring(0, 30)}...</span>
                          </button>
                        ))}
                      </div>
                    </div>

                    <div className="h-6 w-px bg-gray-300 mx-1"></div>

                    <button onClick={() => insertSymbol(' ^ ')} className="px-2 py-1 text-xs font-mono bg-gray-50 border border-gray-300 rounded hover:bg-gray-100" title="Insert AND">^</button>
                    <button onClick={() => insertSymbol(' -> ')} className="px-2 py-1 text-xs font-mono bg-gray-50 border border-gray-300 rounded hover:bg-gray-100" title="Insert IMPLIES">→</button>
                    <button onClick={() => insertSymbol('(?x)')} className="px-2 py-1 text-xs font-mono bg-gray-50 border border-gray-300 rounded hover:bg-gray-100" title="Insert Variable">(?x)</button>
                    <button onClick={() => insertSymbol('swrlb:')} className="px-2 py-1 text-xs font-mono bg-gray-50 border border-gray-300 rounded hover:bg-gray-100" title="Insert swrlb prefix">swrlb:</button>
                    <button onClick={() => insertSymbol('swrlm:')} className="px-2 py-1 text-xs font-mono bg-gray-50 border border-gray-300 rounded hover:bg-gray-100" title="Insert swrlm prefix">swrlm:</button>
                    <button onClick={() => insertSymbol('temporal:')} className="px-2 py-1 text-xs font-mono bg-gray-50 border border-gray-300 rounded hover:bg-gray-100" title="Insert temporal prefix">temporal:</button>
                  </div>

                  <textarea
                    value={editForm.ruleText}
                    onChange={(e) => setEditForm({ ...editForm, ruleText: e.target.value })}
                    disabled={!isEditing}
                    className="flex-1 w-full px-4 py-3 font-mono text-sm border-2 border-gray-300 rounded-lg bg-slate-50 focus:ring-2 focus:ring-purple-500 focus:border-purple-500 focus:outline-none focus:bg-white disabled:bg-gray-100 disabled:border-gray-200 text-gray-900 placeholder-gray-400 resize-none"
                    placeholder="Person(?p) ^ hasAge(?p, ?age) ^ swrlb:greaterThan(?age, 18) -> Adult(?p)"
                  />
                  <div className="mt-2 flex items-center justify-between text-xs text-gray-500">
                    <span>Use <kbd className="px-1.5 py-0.5 bg-gray-200 rounded font-mono">^</kbd> for AND, <kbd className="px-1.5 py-0.5 bg-gray-200 rounded font-mono">→</kbd> for THEN</span>
                    <span className="text-gray-400">{editForm.ruleText.length} chars</span>
                  </div>

                  {}
                  {validationResult && (
                    <div className={`mt-3 p-3 rounded-lg flex items-start gap-3 ${
                      validationResult.valid
                        ? 'bg-green-50 border-2 border-green-300'
                        : 'bg-red-50 border-2 border-red-300'
                    }`}>
                      {validationResult.valid 
                        ? <Check className="text-green-600 flex-shrink-0" size={18} /> 
                        : <AlertCircle className="text-red-600 flex-shrink-0" size={18} />
                      }
                      <div>
                        <p className={`text-sm font-semibold ${validationResult.valid ? 'text-green-800' : 'text-red-800'}`}>
                          {validationResult.valid ? '✓ Valid SWRL Syntax' : '✗ Invalid SWRL Syntax'}
                        </p>
                        {validationResult.errorMessage && (
                          <p className="mt-1 text-xs text-red-700">{validationResult.errorMessage}</p>
                        )}
                      </div>
                    </div>
                  )}
                </div>
              </ResizableSection>

              {}
              <ResizableSection 
                title="Description / Notes" 
                defaultOpen={false}
                defaultHeight={120}
                minHeight={80}
                maxHeight={300}
              >
                <div className="p-4 h-full">
                  <textarea
                    value={editForm.comment}
                    onChange={(e) => setEditForm({ ...editForm, comment: e.target.value })}
                    disabled={!isEditing}
                    className="w-full h-full px-4 py-3 border-2 border-gray-300 rounded-lg text-sm focus:ring-2 focus:ring-purple-500 focus:border-purple-500 focus:outline-none disabled:bg-gray-50 disabled:border-gray-200 text-gray-900 placeholder-gray-400 bg-white resize-none"
                    placeholder="Add notes, documentation, or comments about this rule..."
                  />
                </div>
              </ResizableSection>

              {}
              {isEditing && (
                <div className="sticky bottom-0 p-4 bg-gradient-to-t from-gray-100 to-transparent pt-6">
                  <div className="flex items-center gap-3 p-4 bg-white border-2 border-gray-200 rounded-lg shadow-lg">
                    <button
                      onClick={handleDelete}
                      disabled={!selectedRule}
                      className="px-4 py-2 text-sm font-medium text-red-700 bg-red-50 border-2 border-red-200 hover:bg-red-100 rounded-lg disabled:opacity-40 disabled:cursor-not-allowed transition-colors flex items-center gap-2"
                    >
                      <Trash2 size={16} />
                      Delete
                    </button>

                    <div className="flex-grow" />

                    <button
                      onClick={handleTestRule}
                      disabled={!selectedRule || !editForm.ruleText.trim()}
                      className="px-4 py-2 text-sm font-medium text-purple-700 bg-purple-50 border-2 border-purple-200 hover:bg-purple-100 rounded-lg disabled:opacity-40 transition-colors flex items-center gap-2"
                    >
                      <Play size={16} />
                      Test Rule
                    </button>

                    <button
                      onClick={() => {
                        setIsEditing(false);
                        if (selectedRule) {
                          handleSelectRule(selectedRule);
                        }
                      }}
                      className="px-4 py-2 text-sm font-medium text-gray-700 bg-gray-100 border-2 border-gray-300 hover:bg-gray-200 rounded-lg transition-colors"
                    >
                      Cancel
                    </button>

                    <button
                      onClick={handleSave}
                      disabled={!editForm.ruleText.trim() || !editForm.ruleName.trim()}
                      className="px-5 py-2 text-sm font-medium text-white bg-purple-600 hover:bg-purple-700 rounded-lg disabled:bg-purple-300 disabled:cursor-not-allowed transition-colors flex items-center gap-2 shadow-md"
                    >
                      <Save size={16} />
                      Save Rule
                    </button>
                  </div>
                </div>
              )}
              </>
            )}
          </div>
          )}

          {}
          {editorCollapsed && !resultsMaximized && (
            <div className="flex items-center justify-between bg-gray-100 border border-gray-200 rounded-lg px-3 py-2">
              <span className="text-sm font-medium text-gray-600">📝 Rule Editor (collapsed)</span>
              <button 
                onClick={() => setEditorCollapsed(false)} 
                className="p-1.5 text-purple-600 hover:bg-purple-50 rounded flex items-center gap-1 text-xs"
              >
                <PanelBottomClose size={14} /> Expand
              </button>
            </div>
          )}

          {}
          <div className={`flex flex-col bg-white border border-gray-200 rounded-lg overflow-hidden ${editorMaximized ? 'hidden' : resultsMaximized ? 'fixed inset-4 z-50 shadow-2xl' : 'flex-1 min-h-[300px]'}`}>
            <div className="flex items-center gap-2 text-xs p-2 border-b bg-gray-50">
              <button onClick={() => setActiveTab('query')} className={`px-3 py-1.5 rounded ${activeTab === 'query' ? 'bg-purple-100 text-purple-700 font-medium' : 'text-gray-600 hover:bg-gray-100'}`}>SQWRL Query</button>
              <button onClick={() => setActiveTab('results')} className={`px-3 py-1.5 rounded flex items-center gap-1 ${activeTab === 'results' ? 'bg-purple-100 text-purple-700 font-medium' : 'text-gray-600 hover:bg-gray-100'}`}>
                Execution Results
                {executionResult && (
                  <span className={`ml-1 px-1.5 py-0.5 text-[10px] rounded-full ${executionResult.success ? 'bg-green-100 text-green-700' : 'bg-red-100 text-red-700'}`}>
                    {executionResult.inferredAxiomsCount}
                  </span>
                )}
              </button>
              <button onClick={() => setActiveTab('reference')} className={`px-3 py-1.5 rounded flex items-center gap-1 ${activeTab === 'reference' ? 'bg-purple-100 text-purple-700 font-medium' : 'text-gray-600 hover:bg-gray-100'}`}>
                <BookOpen size={12} /> Built-in Reference
              </button>

              <div className="flex-1" />

              {}
              <button 
                onClick={() => { setResultsMaximized(!resultsMaximized); setEditorMaximized(false); }} 
                className={`p-1.5 rounded transition-colors ${resultsMaximized ? 'bg-purple-100 text-purple-700' : 'text-gray-500 hover:bg-gray-100'}`}
                title={resultsMaximized ? "Restore panel" : "Maximize panel (fullscreen)"}
              >
                {resultsMaximized ? <Minimize2 size={16} /> : <Maximize2 size={16} />}
              </button>
            </div>

            <div className="flex-1 overflow-auto">
              {activeTab === 'query' && <SQWRLQueryPanel projectId={projectId} context={context} />}
              {activeTab === 'results' && <ExecutionResultsPanel results={executionResult} />}
              {activeTab === 'reference' && (
                <div className="p-4 space-y-3">
                  <div className="text-sm text-gray-600 mb-4">
                    <p>SWRL Built-in predicates based on <a href="https://github.com/protegeproject/swrlapi" target="_blank" rel="noopener noreferrer" className="text-purple-600 hover:underline">SWRLAPI</a>. Click a built-in to insert it into your rule.</p>
                  </div>
                  {SWRL_BUILTINS.map(category => (
                    <div key={category.prefix} className="border rounded-lg overflow-hidden">
                      <button
                        onClick={() => setExpandedBuiltInCategory(expandedBuiltInCategory === category.prefix ? null : category.prefix)}
                        className="w-full flex items-center justify-between p-3 bg-gray-50 hover:bg-gray-100 text-left"
                      >
                        <div>
                          <span className="font-semibold text-gray-800">{category.name}</span>
                          <span className="text-xs text-purple-600 ml-2 font-mono">{category.prefix}:</span>
                        </div>
                        {expandedBuiltInCategory === category.prefix ? <ChevronDown size={16} /> : <ChevronRight size={16} />}
                      </button>
                      {expandedBuiltInCategory === category.prefix && (
                        <div className="divide-y max-h-64 overflow-y-auto">
                          {category.builtIns.map(builtin => (
                            <div
                              key={builtin.name}
                              onClick={() => {
                                if (isEditing) {
                                  setEditForm(prev => ({
                                    ...prev,
                                    ruleText: prev.ruleText + (prev.ruleText ? ' ^ ' : '') + builtin.signature
                                  }));
                                }
                              }}
                              className={`p-3 hover:bg-purple-50 ${isEditing ? 'cursor-pointer' : 'cursor-default'}`}
                            >
                              <div className="flex items-center justify-between">
                                <code className="text-sm font-mono bg-purple-100 text-purple-800 px-2 py-0.5 rounded border border-purple-200">{builtin.fullName}</code>
                                {isEditing && <span className="text-xs text-gray-400">Click to insert</span>}
                              </div>
                              <p className="text-xs text-gray-600 mt-1">{builtin.description}</p>
                              <p className="text-xs font-mono text-gray-500 mt-1 bg-gray-100 px-2 py-1 rounded">{builtin.signature}</p>
                              {builtin.example && (
                                <p className="text-xs text-gray-400 mt-1">Example: <code className="bg-gray-100 px-1 rounded">{builtin.example}</code></p>
                              )}
                            </div>
                          ))}
                        </div>
                      )}
                    </div>
                  ))}
                </div>
              )}
            </div>
          </div>
        </main>
      </div>

      {}
      {resultsMaximized && (
        <div 
          className="fixed inset-0 bg-black bg-opacity-50 z-40"
          onClick={() => setResultsMaximized(false)}
        />
      )}

      {}
      {ruleDetailsMaximized && (
        <>
          <div className="fixed inset-0 bg-black bg-opacity-50 z-40" onClick={() => setRuleDetailsMaximized(false)} />
          <div className="fixed inset-8 bg-white rounded-xl shadow-2xl z-50 flex flex-col overflow-hidden">
            <div className="flex items-center justify-between px-6 py-4 border-b bg-gray-50">
              <h2 className="text-lg font-bold text-gray-800">Rule Details</h2>
              <button onClick={() => setRuleDetailsMaximized(false)} className="p-2 hover:bg-gray-200 rounded-lg">
                <X size={20} />
              </button>
            </div>
            <div className="flex-1 overflow-auto p-6">
              <div className="max-w-3xl mx-auto space-y-4">
                <div>
                  <label className="block text-sm font-semibold text-gray-700 mb-1.5">Rule Name *</label>
                  <input type="text" value={editForm.ruleName} onChange={(e) => setEditForm({ ...editForm, ruleName: e.target.value })} disabled={!isEditing} className="w-full px-4 py-3 text-lg font-medium border-2 border-gray-300 rounded-lg focus:ring-2 focus:ring-purple-500 disabled:bg-gray-50" />
                </div>
                <div className="grid grid-cols-2 gap-4">
                  <div>
                    <label className="block text-sm font-semibold text-gray-700 mb-1.5">Category</label>
                    <input type="text" value={editForm.category} onChange={(e) => setEditForm({ ...editForm, category: e.target.value })} disabled={!isEditing} className="w-full px-4 py-3 border-2 border-gray-300 rounded-lg disabled:bg-gray-50" />
                  </div>
                  <div>
                    <label className="block text-sm font-semibold text-gray-700 mb-1.5">Status</label>
                    <label className={`flex items-center gap-3 px-4 py-3 border-2 rounded-lg cursor-pointer ${editForm.enabled ? 'bg-green-50 border-green-400' : 'bg-gray-100 border-gray-300'}`}>
                      <input type="checkbox" checked={editForm.enabled} onChange={(e) => setEditForm({ ...editForm, enabled: e.target.checked })} disabled={!isEditing} className="w-5 h-5" />
                      <span>{editForm.enabled ? '● Enabled' : '○ Disabled'}</span>
                    </label>
                  </div>
                </div>
              </div>
            </div>
          </div>
        </>
      )}

      {}
      {ruleExpressionMaximized && (
        <>
          <div className="fixed inset-0 bg-black bg-opacity-50 z-40" onClick={() => setRuleExpressionMaximized(false)} />
          <div className="fixed inset-8 bg-white rounded-xl shadow-2xl z-50 flex flex-col overflow-hidden">
            <div className="flex items-center justify-between px-6 py-4 border-b bg-gray-50">
              <h2 className="text-lg font-bold text-gray-800">SWRL Rule Expression</h2>
              <button onClick={() => setRuleExpressionMaximized(false)} className="p-2 hover:bg-gray-200 rounded-lg">
                <X size={20} />
              </button>
            </div>
            <div className="flex-1 flex flex-col p-6">
              <textarea
                value={editForm.ruleText}
                onChange={(e) => setEditForm({ ...editForm, ruleText: e.target.value })}
                disabled={!isEditing}
                className="flex-1 w-full px-6 py-4 font-mono text-base border-2 border-gray-300 rounded-lg bg-slate-50 focus:ring-2 focus:ring-purple-500 disabled:bg-gray-100 text-gray-900 resize-none"
                placeholder="Person(?p) ^ hasAge(?p, ?age) ^ swrlb:greaterThan(?age, 18) -> Adult(?p)"
              />
              <div className="mt-4 flex items-center justify-between text-sm text-gray-500">
                <span>Use <kbd className="px-2 py-1 bg-gray-200 rounded font-mono">^</kbd> for AND, <kbd className="px-2 py-1 bg-gray-200 rounded font-mono">→</kbd> for THEN</span>
                <span>{editForm.ruleText.length} characters</span>
              </div>
            </div>
          </div>
        </>
      )}

      {showStats && stats && (
        <div className="fixed inset-0 bg-black bg-opacity-50 flex items-center justify-center z-50">
          <div className="bg-white rounded-lg p-6 w-96 shadow-xl">
            <div className="flex justify-between items-center mb-4">
              <h3 className="text-lg font-bold text-gray-900">Rule Statistics</h3>
              <button onClick={() => setShowStats(false)} className="text-gray-500 hover:text-gray-700">
                <X size={20} />
              </button>
            </div>
            <div className="space-y-3">
              <div className="flex justify-between">
                <span className="text-gray-600">Total Rules:</span>
                <span className="font-semibold text-gray-900">{stats.totalRules}</span>
              </div>
              <div className="flex justify-between">
                <span className="text-gray-600">Enabled Rules:</span>
                <span className="font-semibold text-green-600">{stats.enabledRules}</span>
              </div>
              <div className="flex justify-between">
                <span className="text-gray-600">Disabled Rules:</span>
                <span className="font-semibold text-red-600">{stats.disabledRules}</span>
              </div>
              {stats.rulesByCategory && Object.keys(stats.rulesByCategory).length > 0 && (
                <div className="border-t pt-2 mt-2">
                  <p className="text-sm font-medium text-gray-700 mb-2">By Category:</p>
                  {Object.entries(stats.rulesByCategory).map(([cat, count]) => (
                    <div key={cat} className="flex justify-between text-sm">
                      <span className="text-gray-600">{cat || 'Uncategorized'}</span>
                      <span className="text-gray-900">{count as number}</span>
                    </div>
                  ))}
                </div>
              )}
            </div>
          </div>
        </div>
      )}

      {}
      <ConfirmDialog
        isOpen={confirmDialog.isOpen}
        title="Delete Rule"
        message={`Are you sure you want to delete the rule "${confirmDialog.ruleName}"? This action cannot be undone.`}
        onConfirm={() => executeDelete(confirmDialog.ruleId)}
        onCancel={() => setConfirmDialog({ isOpen: false, ruleName: '', ruleId: '' })}
      />
    </div>
  );
};

export default SWRLEditor;

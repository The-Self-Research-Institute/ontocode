import React, { useMemo, useState } from 'react';
import { Plus, Trash2, X } from 'lucide-react';
import {
  COMPARISONS,
  OPERATIONS,
  parseRuleText,
  serializeRule,
  isValidVariableName,
  type RuleCondition,
  type RuleCalculation,
  type SimpleRuleModel,
  type ValueKind,
} from './ruleModel';

interface RuleBuilderProps {
  ruleText: string;
  schema: { classes: string[]; dataProperties: string[] } | null;
  onUse: (text: string) => void;
  onClose: () => void;
}

const COMPARISON_LABELS: Record<string, string> = {
  greaterThan: 'is greater than',
  lessThan: 'is less than',
  greaterThanOrEqual: 'is at least',
  lessThanOrEqual: 'is at most',
  equal: 'equals',
  notEqual: 'does not equal',
};

const OPERATION_LABELS: Record<string, string> = {
  add: '+',
  subtract: '-',
  multiply: '×',
  divide: '÷',
  mod: 'remainder of ÷',
};

const localName = (uri: string): string => {
  const clean = uri.replace(/^<|>$/g, '');
  const hash = clean.lastIndexOf('#');
  if (hash !== -1) return clean.substring(hash + 1);
  const slash = clean.lastIndexOf('/');
  return slash !== -1 ? clean.substring(slash + 1) : clean;
};

const EMPTY_RULE: SimpleRuleModel = {
  kind: 'simple',
  when: { cls: '', variable: 'x' },
  conditions: [],
  calculations: [],
  then: { cls: '' },
};

const inputClass = 'px-2 py-1 text-xs border border-gray-300 rounded bg-white text-black focus:outline-none focus:ring-1 focus:ring-purple-400';

function withCurrent(options: string[], current: string): string[] {
  return current && !options.includes(current) ? [current, ...options] : options;
}

const RuleBuilder: React.FC<RuleBuilderProps> = ({ ruleText, schema, onUse, onClose }) => {
  const initial = useMemo(() => (ruleText.trim() ? parseRuleText(ruleText) : EMPTY_RULE), []);
  const [model, setModel] = useState<SimpleRuleModel | null>(initial.kind === 'simple' ? initial : null);

  const classes = useMemo(() => (schema ? schema.classes.map(localName).sort() : []), [schema]);
  const dataProperties = useMemo(() => (schema ? schema.dataProperties.map(localName).sort() : []), [schema]);

  if (initial.kind === 'advanced' || !model) {
    return (
      <div className="bg-white rounded-xl border border-gray-200 p-4 space-y-3">
        <div className="flex items-center justify-between">
          <span className="text-sm font-semibold text-gray-700">Rule builder</span>
          <button onClick={onClose} className="p-1 text-gray-500 hover:text-gray-800" aria-label="Close">
            <X size={14} />
          </button>
        </div>
        <div className="text-sm text-amber-800 bg-amber-50 border border-amber-200 rounded-lg px-3 py-2">
          This rule requires the advanced editor.
          {initial.kind === 'advanced' && <div className="text-xs text-amber-700 mt-1">{initial.reason}</div>}
        </div>
      </div>
    );
  }

  const update = (patch: Partial<SimpleRuleModel>) => setModel(prev => (prev ? { ...prev, ...patch } : prev));

  const setCondition = (index: number, patch: Partial<RuleCondition>) =>
    update({ conditions: model.conditions.map((c, i) => (i === index ? { ...c, ...patch } : c)) });

  const setCalculation = (index: number, patch: Partial<RuleCalculation>) =>
    update({ calculations: model.calculations.map((c, i) => (i === index ? { ...c, ...patch } : c)) });

  const nextName = (prefix: string, taken: string[]) => {
    let n = 1;
    while (taken.includes(`${prefix}${n}`)) n++;
    return `${prefix}${n}`;
  };

  const usedVariables = [
    model.when.variable,
    ...model.conditions.map(c => c.variable),
    ...model.calculations.map(c => c.result),
  ];

  const addCondition = () =>
    update({
      conditions: [
        ...model.conditions.filter(c => c.property !== ''),
        {
          property: dataProperties[0] || '',
          variable: nextName('v', usedVariables),
          comparison: 'greaterThan',
          value: '0',
          valueKind: 'number',
        },
        ...model.conditions.filter(c => c.property === ''),
      ],
    });

  const addCalculation = () =>
    update({
      calculations: [
        ...model.calculations,
        { result: nextName('r', usedVariables), op: 'add', left: '1', right: '1' },
      ],
    });

  const changeKind = (index: number, kind: ValueKind) => {
    const defaults: Record<string, string> = { number: '0', text: '', boolean: 'true', variable: '' };
    setCondition(index, { valueKind: kind, value: defaults[kind] ?? '' });
  };

  const readableVariables = (excludeIndex: number) => [
    ...model.conditions.filter((_, i) => i !== excludeIndex).map(c => c.variable),
    ...model.calculations.map(c => c.result),
  ].filter((v, i, a) => a.indexOf(v) === i);

  const problems: string[] = [];
  if (!model.when.cls) problems.push('Choose the class in WHEN.');
  if (!model.then.cls) problems.push('Choose the class in THEN.');
  if (!isValidVariableName(model.when.variable)) problems.push('The main variable name is not valid.');
  model.conditions.forEach((c, i) => {
    if (!c.property && c.variable && !model.calculations.some(k => k.result === c.variable)) {
      problems.push(`Condition ${i + 1} compares a value that is not calculated.`);
    }
    if (c.property === '' && c.comparison === '') problems.push(`Condition ${i + 1} is incomplete.`);
    if (c.valueKind === 'number' && !/^-?\d+(\.\d+)?$/.test(c.value)) problems.push(`Condition ${i + 1} needs a number.`);
    if (c.valueKind === 'variable' && !isValidVariableName(c.value)) problems.push(`Condition ${i + 1} needs a variable.`);
    if (!isValidVariableName(c.variable)) problems.push(`Condition ${i + 1} has an invalid variable name.`);
  });
  model.calculations.forEach((k, i) => {
    if (!isValidVariableName(k.result)) problems.push(`Calculation ${i + 1} has an invalid result name.`);
    for (const operand of [k.left, k.right]) {
      if (!/^(-?\d+(\.\d+)?|\?[A-Za-z_][A-Za-z0-9_]*)$/.test(operand)) problems.push(`Calculation ${i + 1} has an invalid input.`);
    }
  });
  const preview = problems.length === 0 ? serializeRule(model) : '';
  const roundTrips = problems.length === 0 && JSON.stringify(parseRuleText(preview)) === JSON.stringify(model);
  if (problems.length === 0 && !roundTrips) {
    const reparsed = parseRuleText(preview);
    problems.push(
      reparsed.kind === 'advanced'
        ? reparsed.reason
        : 'Some conditions are not in a form the builder can write. Group conditions on the same property together.'
    );
  }
  const canUse = problems.length === 0;

  const section = 'bg-gray-50 border border-gray-200 rounded-lg p-3 space-y-2';
  const heading = 'text-[10px] font-bold tracking-wide text-purple-700';

  return (
    <div className="bg-white rounded-xl border border-gray-200 p-4 space-y-3">
      <div className="flex items-center justify-between">
        <span className="text-sm font-semibold text-gray-700">Rule builder</span>
        <button onClick={onClose} className="p-1 text-gray-500 hover:text-gray-800" aria-label="Close">
          <X size={14} />
        </button>
      </div>

      <div className={section}>
        <div className={heading}>WHEN</div>
        <div className="flex items-center gap-2 flex-wrap text-xs text-gray-700">
          <span>an individual of class</span>
          <select
            value={model.when.cls}
            onChange={e => update({ when: { ...model.when, cls: e.target.value } })}
            className={inputClass}
          >
            <option value="">Select class...</option>
            {withCurrent(classes, model.when.cls).map(c => (
              <option key={c} value={c}>{c}</option>
            ))}
          </select>
          <span>called</span>
          <input
            value={model.when.variable}
            onChange={e => update({ when: { ...model.when, variable: e.target.value } })}
            className={`${inputClass} w-16`}
          />
        </div>
      </div>

      <div className={section}>
        <div className="flex items-center justify-between">
          <div className={heading}>CONDITIONS</div>
          <button onClick={addCondition} className="text-xs text-purple-700 flex items-center gap-1 hover:underline">
            <Plus size={12} /> Add condition
          </button>
        </div>
        {model.conditions.length === 0 && <div className="text-xs text-gray-500">No conditions.</div>}
        {model.conditions.map((c, i) => (
          <div key={i} className="flex items-center gap-2 flex-wrap text-xs text-gray-700">
            {c.property !== '' || !model.calculations.some(k => k.result === c.variable) ? (
              <select
                value={c.property}
                onChange={e => setCondition(i, { property: e.target.value })}
                className={inputClass}
              >
                <option value="">Select property...</option>
                {withCurrent(dataProperties, c.property).map(p => (
                  <option key={p} value={p}>{p}</option>
                ))}
              </select>
            ) : (
              <span className="px-2 py-1 bg-white border border-gray-200 rounded">calculated</span>
            )}
            <span>as</span>
            <input
              value={c.variable}
              onChange={e => setCondition(i, { variable: e.target.value })}
              className={`${inputClass} w-16`}
            />
            <select
              value={c.comparison}
              onChange={e => {
                const comparison = e.target.value as RuleCondition['comparison'];
                setCondition(i, comparison === '' ? { comparison, valueKind: 'none', value: '' } : {
                  comparison,
                  valueKind: c.valueKind === 'none' ? 'number' : c.valueKind,
                  value: c.valueKind === 'none' ? '0' : c.value,
                });
              }}
              className={inputClass}
            >
              {c.property !== '' && <option value="">(just read the value)</option>}
              {COMPARISONS.map(k => (
                <option key={k} value={k}>{COMPARISON_LABELS[k]}</option>
              ))}
            </select>
            {c.comparison !== '' && (
              <>
                <select value={c.valueKind} onChange={e => changeKind(i, e.target.value as ValueKind)} className={inputClass}>
                  <option value="number">number</option>
                  <option value="text">text</option>
                  <option value="boolean">true/false</option>
                  <option value="variable">variable</option>
                </select>
                {c.valueKind === 'boolean' ? (
                  <select value={c.value} onChange={e => setCondition(i, { value: e.target.value })} className={inputClass}>
                    <option value="true">true</option>
                    <option value="false">false</option>
                  </select>
                ) : c.valueKind === 'variable' ? (
                  <select value={c.value} onChange={e => setCondition(i, { value: e.target.value })} className={inputClass}>
                    <option value="">Select variable...</option>
                    {withCurrent(readableVariables(i), c.value).map(v => (
                      <option key={v} value={v}>{v}</option>
                    ))}
                  </select>
                ) : (
                  <input
                    value={c.value}
                    onChange={e => setCondition(i, { value: e.target.value })}
                    className={`${inputClass} w-28`}
                  />
                )}
              </>
            )}
            <button
              onClick={() => update({ conditions: model.conditions.filter((_, j) => j !== i) })}
              className="p-1 text-gray-400 hover:text-red-600"
              aria-label="Remove condition"
            >
              <Trash2 size={12} />
            </button>
          </div>
        ))}
      </div>

      <div className={section}>
        <div className="flex items-center justify-between">
          <div className={heading}>CALCULATIONS</div>
          <button onClick={addCalculation} className="text-xs text-purple-700 flex items-center gap-1 hover:underline">
            <Plus size={12} /> Add calculation
          </button>
        </div>
        {model.calculations.length === 0 && <div className="text-xs text-gray-500">No calculations.</div>}
        {model.calculations.map((k, i) => {
          const earlier = [
            ...model.conditions.filter(c => c.property !== '').map(c => '?' + c.variable),
            ...model.calculations.slice(0, i).map(c => '?' + c.result),
          ].filter((v, idx, a) => a.indexOf(v) === idx);
          const operandInput = (side: 'left' | 'right') => (
            <input
              list={`rb-vars-${i}-${side}`}
              value={k[side]}
              onChange={e => setCalculation(i, { [side]: e.target.value } as Partial<RuleCalculation>)}
              className={`${inputClass} w-24`}
              placeholder="?var or number"
            />
          );
          return (
            <div key={i} className="flex items-center gap-2 flex-wrap text-xs text-gray-700">
              <input
                value={k.result}
                onChange={e => {
                  const old = k.result;
                  update({
                    calculations: model.calculations.map((c, j) => (j === i ? { ...c, result: e.target.value } : c)),
                    conditions: model.conditions.map(c => (c.property === '' && c.variable === old ? { ...c, variable: e.target.value } : c)),
                  });
                }}
                className={`${inputClass} w-20`}
              />
              <span>=</span>
              {operandInput('left')}
              <select value={k.op} onChange={e => setCalculation(i, { op: e.target.value as RuleCalculation['op'] })} className={inputClass}>
                {OPERATIONS.map(o => (
                  <option key={o} value={o}>{OPERATION_LABELS[o]}</option>
                ))}
              </select>
              {operandInput('right')}
              <datalist id={`rb-vars-${i}-left`}>{earlier.map(v => <option key={v} value={v} />)}</datalist>
              <datalist id={`rb-vars-${i}-right`}>{earlier.map(v => <option key={v} value={v} />)}</datalist>
              <button
                onClick={() => update({ calculations: model.calculations.filter((_, j) => j !== i) })}
                className="p-1 text-gray-400 hover:text-red-600"
                aria-label="Remove calculation"
              >
                <Trash2 size={12} />
              </button>
            </div>
          );
        })}
        <button
          onClick={() => {
            const free = nextName('c', usedVariables);
            update({
              conditions: [
                ...model.conditions,
                { property: '', variable: model.calculations.length > 0 ? model.calculations[model.calculations.length - 1].result : free, comparison: 'greaterThan', value: '0', valueKind: 'number' },
              ],
            });
          }}
          disabled={model.calculations.length === 0}
          className="text-xs text-purple-700 hover:underline disabled:text-gray-400 disabled:no-underline"
        >
          Compare the last calculated value
        </button>
      </div>

      <div className={section}>
        <div className={heading}>THEN</div>
        <div className="flex items-center gap-2 flex-wrap text-xs text-gray-700">
          <span>the individual is also of class</span>
          <select
            value={model.then.cls}
            onChange={e => update({ then: { cls: e.target.value } })}
            className={inputClass}
          >
            <option value="">Select class...</option>
            {withCurrent(classes, model.then.cls).map(c => (
              <option key={c} value={c}>{c}</option>
            ))}
          </select>
        </div>
      </div>

      <div>
        <div className="text-[10px] font-bold tracking-wide text-gray-500 mb-1">PREVIEW</div>
        <pre className="font-mono text-xs bg-gray-900 text-green-300 rounded-lg px-3 py-2 whitespace-pre-wrap break-words min-h-[2rem]">
          {preview || ' '}
        </pre>
        {problems.length > 0 && (
          <ul className="mt-1 text-xs text-red-600 list-disc list-inside">
            {problems.slice(0, 4).map((p, i) => <li key={i}>{p}</li>)}
          </ul>
        )}
      </div>

      <div className="flex justify-end gap-2">
        <button onClick={onClose} className="px-3 py-1.5 text-xs text-gray-600 border border-gray-200 rounded-lg hover:bg-gray-50">
          Cancel
        </button>
        <button
          onClick={() => { onUse(preview); onClose(); }}
          disabled={!canUse}
          className="px-3 py-1.5 text-xs font-medium text-white bg-purple-600 rounded-lg hover:bg-purple-700 disabled:opacity-50"
        >
          Use this rule
        </button>
      </div>
    </div>
  );
};

export default RuleBuilder;

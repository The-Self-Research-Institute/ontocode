export const COMPARISONS = [
  'greaterThan',
  'lessThan',
  'greaterThanOrEqual',
  'lessThanOrEqual',
  'equal',
  'notEqual',
] as const;

export const OPERATIONS = ['add', 'subtract', 'multiply', 'divide', 'mod'] as const;

export type Comparison = typeof COMPARISONS[number];
export type Operation = typeof OPERATIONS[number];
export type ValueKind = 'number' | 'text' | 'boolean' | 'variable' | 'none';

export interface RuleCondition {
  property: string;
  variable: string;
  comparison: Comparison | '';
  value: string;
  valueKind: ValueKind;
}

export interface RuleCalculation {
  result: string;
  op: Operation;
  left: string;
  right: string;
}

export interface SimpleRuleModel {
  kind: 'simple';
  when: { cls: string; variable: string };
  conditions: RuleCondition[];
  calculations: RuleCalculation[];
  then: { cls: string };
}

export interface AdvancedRuleModel {
  kind: 'advanced';
  reason: string;
}

export type RuleModel = SimpleRuleModel | AdvancedRuleModel;

const NAME = /^[^\s(),^?"<>{}\[\]]+$/;
const VAR = /^\?([A-Za-z_][A-Za-z0-9_]*)$/;
const NUMBER = /^-?\d+(\.\d+)?$/;
const PLAIN_VAR = /^[A-Za-z_][A-Za-z0-9_]*$/;

class Unsupported extends Error {}

function fail(reason: string): never {
  throw new Unsupported(reason);
}

function splitTop(text: string, separator: string): string[] {
  const parts: string[] = [];
  let depth = 0;
  let inString = false;
  let current = '';
  for (let i = 0; i < text.length; i++) {
    const ch = text[i];
    if (inString) {
      current += ch;
      if (ch === '\\' && i + 1 < text.length) {
        current += text[++i];
      } else if (ch === '"') {
        inString = false;
      }
      continue;
    }
    if (ch === '"') {
      inString = true;
      current += ch;
    } else if (ch === '(') {
      depth++;
      current += ch;
    } else if (ch === ')') {
      depth--;
      if (depth < 0) fail('The rule has unbalanced brackets.');
      current += ch;
    } else if (depth === 0 && text.startsWith(separator, i)) {
      if (separator === '^' && text[i + 1] === '^') {
        current += '^^';
        i++;
        continue;
      }
      parts.push(current);
      current = '';
      i += separator.length - 1;
    } else {
      current += ch;
    }
  }
  if (inString) fail('The rule has an unclosed quote.');
  if (depth !== 0) fail('The rule has unbalanced brackets.');
  parts.push(current);
  return parts;
}

interface ParsedAtom {
  name: string;
  args: string[];
}

function parseAtom(raw: string): ParsedAtom {
  const text = raw.trim();
  const open = text.indexOf('(');
  if (open <= 0 || !text.endsWith(')')) fail('Part of the rule is not a simple atom such as Name(?x).');
  const name = text.slice(0, open).trim();
  if (!NAME.test(name)) fail('The rule uses a class or property expression the builder cannot show.');
  const inner = text.slice(open + 1, -1);
  if (inner.trim() === '') fail('An atom in the rule has no arguments.');
  const args = splitTop(inner, ',').map(a => a.trim());
  if (args.some(a => a === '')) fail('An atom in the rule has an empty argument.');
  return { name, args };
}

function unquote(token: string): string | null {
  let body = token;
  if (token.startsWith('"') && token.endsWith('"^^xsd:string')) {
    body = token.slice(0, -'^^xsd:string'.length);
  }
  if (body.length < 2 || !body.startsWith('"') || !body.endsWith('"')) return null;
  const inner = body.slice(1, -1);
  let out = '';
  for (let i = 0; i < inner.length; i++) {
    const ch = inner[i];
    if (ch === '\\') {
      const next = inner[i + 1];
      if (next !== '"' && next !== '\\') return null;
      out += next;
      i++;
    } else if (ch === '"') {
      return null;
    } else {
      out += ch;
    }
  }
  return out;
}

function quote(value: string): string {
  return '"' + value.replace(/\\/g, '\\\\').replace(/"/g, '\\"') + '"';
}

function builtinName(name: string): string | null {
  return name.startsWith('swrlb:') ? name.slice('swrlb:'.length) : null;
}

function operandOf(token: string): string {
  const v = VAR.exec(token);
  if (v) return '?' + v[1];
  if (NUMBER.test(token)) return token;
  return fail('An arithmetic step uses something other than a variable or a number.');
}

export function parseRuleText(text: string): RuleModel {
  try {
    return { kind: 'simple', ...parseSimple(text) };
  } catch (e) {
    if (e instanceof Unsupported) return { kind: 'advanced', reason: e.message };
    return { kind: 'advanced', reason: 'The rule text could not be read.' };
  }
}

function parseSimple(text: string): Omit<SimpleRuleModel, 'kind'> {
  if (typeof text !== 'string' || text.trim() === '') fail('The rule is empty.');
  const arrowParts = splitTop(text, '->');
  if (arrowParts.length !== 2) fail('The rule must have one WHEN part, an arrow, and one THEN part.');
  const bodyParts = splitTop(arrowParts[0], '^');
  const headParts = splitTop(arrowParts[1], '^');
  if (headParts.length !== 1) fail('The rule has more than one conclusion.');

  const bodyAtoms = bodyParts.map(parseAtom);
  const headAtom = parseAtom(headParts[0]);

  const classAtoms: ParsedAtom[] = [];
  const propertyAtoms: { atom: ParsedAtom; variable: string }[] = [];
  const builtins: ParsedAtom[] = [];

  for (const atom of bodyAtoms) {
    if (builtinName(atom.name) !== null) {
      builtins.push(atom);
    } else if (atom.args.length === 1) {
      classAtoms.push(atom);
    } else if (atom.args.length === 2) {
      const first = VAR.exec(atom.args[0]);
      const second = VAR.exec(atom.args[1]);
      if (!first) fail('A property in the rule is used on something other than a variable.');
      if (!second) fail('A property in the rule is matched against a fixed value or an individual, which needs the advanced editor.');
      propertyAtoms.push({ atom, variable: second[1] });
    } else {
      fail('An atom in the rule has more than two arguments.');
    }
  }

  if (classAtoms.length !== 1) fail('The rule must have exactly one class condition in the WHEN part.');
  const rootMatch = VAR.exec(classAtoms[0].args[0]);
  if (!rootMatch) fail('The class condition is not on a variable.');
  const root = rootMatch[1];
  if (builtinName(headAtom.name) !== null) fail('The conclusion is a built-in, not a class.');
  if (headAtom.args.length !== 1) fail('The conclusion is not a single class.');
  const headVar = VAR.exec(headAtom.args[0]);
  if (!headVar || headVar[1] !== root) fail('The conclusion is not about the same variable as the WHEN class.');

  const propertyVars = new Map<string, string>();
  for (const { atom, variable } of propertyAtoms) {
    const subject = VAR.exec(atom.args[0]);
    if (!subject || subject[1] !== root) {
      fail('A property in the rule belongs to something other than the main variable (this looks like an object property or a chain).');
    }
    if (variable === root) fail('A property points back at the main variable.');
    if (propertyVars.has(variable)) fail('A variable is read from more than one property.');
    propertyVars.set(variable, atom.name);
  }

  const calculations: RuleCalculation[] = [];
  const comparisonAtoms: ParsedAtom[] = [];
  const calcVars = new Set<string>();
  for (const atom of builtins) {
    const short = builtinName(atom.name) as string;
    if ((OPERATIONS as readonly string[]).includes(short)) {
      if (atom.args.length !== 3) fail('An arithmetic step does not have a result and two inputs.');
      const result = VAR.exec(atom.args[0]);
      if (!result) fail('An arithmetic step does not store its result in a variable.');
      const name = result[1];
      if (name === root || propertyVars.has(name) || calcVars.has(name)) {
        fail('An arithmetic step reuses a variable name that is already taken.');
      }
      const left = operandOf(atom.args[1]);
      const right = operandOf(atom.args[2]);
      for (const operand of [left, right]) {
        if (operand.startsWith('?')) {
          const v = operand.slice(1);
          if (!propertyVars.has(v) && !calcVars.has(v)) {
            fail('An arithmetic step uses a variable that has not been read or calculated before it.');
          }
        }
      }
      calcVars.add(name);
      calculations.push({ result: name, op: short as Operation, left, right });
    } else if ((COMPARISONS as readonly string[]).includes(short)) {
      if (atom.args.length !== 2) fail('A comparison does not have exactly two sides.');
      comparisonAtoms.push(atom);
    } else {
      fail('The rule uses the built-in ' + atom.name + ', which the builder does not support.');
    }
  }

  const used = new Set<string>();
  const byVariable = new Map<string, RuleCondition[]>();
  const calcConditions: RuleCondition[] = [];
  for (const atom of comparisonAtoms) {
    const short = builtinName(atom.name) as Comparison;
    const left = VAR.exec(atom.args[0]);
    if (!left) fail('A comparison has a fixed value on its left side.');
    const lv = left[1];
    const isProp = propertyVars.has(lv);
    if (!isProp && !calcVars.has(lv)) fail('A comparison uses a variable that is not read from a property or calculated.');
    used.add(lv);
    const right = atom.args[1];
    let value: string;
    let valueKind: ValueKind;
    const rv = VAR.exec(right);
    if (rv) {
      if (!propertyVars.has(rv[1]) && !calcVars.has(rv[1])) {
        fail('A comparison uses a variable that is not read from a property or calculated.');
      }
      value = rv[1];
      valueKind = 'variable';
      used.add(value);
    } else if (NUMBER.test(right)) {
      value = right;
      valueKind = 'number';
    } else if (right === 'true' || right === 'false') {
      value = right;
      valueKind = 'boolean';
    } else {
      const s = unquote(right);
      if (s === null) fail('A comparison uses a value type the builder does not support.');
      value = s;
      valueKind = 'text';
    }
    const condition: RuleCondition = {
      property: isProp ? (propertyVars.get(lv) as string) : '',
      variable: lv,
      comparison: short,
      value,
      valueKind,
    };
    if (isProp) {
      const list = byVariable.get(lv) || [];
      list.push(condition);
      byVariable.set(lv, list);
    } else {
      calcConditions.push(condition);
    }
  }
  for (const c of calculations) {
    for (const operand of [c.left, c.right]) {
      if (operand.startsWith('?')) used.add(operand.slice(1));
    }
  }

  const conditions: RuleCondition[] = [];
  for (const { atom, variable } of propertyAtoms) {
    if (!used.has(variable)) {
      fail('A property is read but never compared or used, so it cannot be told apart from an object property.');
    }
    const list = byVariable.get(variable);
    if (list && list.length > 0) {
      conditions.push(...list);
    } else {
      conditions.push({ property: atom.name, variable, comparison: '', value: '', valueKind: 'none' });
    }
  }
  conditions.push(...calcConditions);

  return {
    when: { cls: classAtoms[0].name, variable: root },
    conditions,
    calculations,
    then: { cls: headAtom.name },
  };
}

function valueText(c: RuleCondition): string {
  if (c.valueKind === 'variable') return '?' + c.value;
  if (c.valueKind === 'text') return quote(c.value);
  return c.value;
}

export function serializeRule(model: SimpleRuleModel): string {
  const root = '?' + model.when.variable;
  const parts: string[] = [`${model.when.cls}(${root})`];
  const emitted = new Set<string>();
  const propertyConditions = model.conditions.filter(c => c.property !== '');
  const calcConditions = model.conditions.filter(c => c.property === '');

  for (const c of propertyConditions) {
    const key = c.property + '|' + c.variable;
    if (emitted.has(key)) continue;
    emitted.add(key);
    parts.push(`${c.property}(${root}, ?${c.variable})`);
    for (const d of propertyConditions) {
      if (d.property === c.property && d.variable === c.variable && d.comparison !== '') {
        parts.push(`swrlb:${d.comparison}(?${d.variable}, ${valueText(d)})`);
      }
    }
  }
  for (const k of model.calculations) {
    parts.push(`swrlb:${k.op}(?${k.result}, ${k.left}, ${k.right})`);
  }
  for (const c of calcConditions) {
    if (c.comparison !== '') parts.push(`swrlb:${c.comparison}(?${c.variable}, ${valueText(c)})`);
  }
  return `${parts.join(' ^ ')} -> ${model.then.cls}(${root})`;
}

export function isValidVariableName(name: string): boolean {
  return PLAIN_VAR.test(name);
}

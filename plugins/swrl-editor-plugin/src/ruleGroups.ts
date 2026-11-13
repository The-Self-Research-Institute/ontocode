import { SwrlRule } from './types';

export interface HeadAtom {
  name: string;
  literal: string | null;
}

export interface RuleHeads {
  ruleName: string;
  atoms: HeadAtom[];
}

export const UNIDENTIFIED_RULE = 'Rule not identified';

const atomLocalName = (raw: string): string => raw.replace(/^<|>$/g, '').split(/[#/:]/).pop() || '';

const literalOf = (args: string): string | null => {
  const last = args.split(',').pop()?.trim() ?? '';
  if (!last || last.startsWith('?') || last.includes('(')) {
    return null;
  }
  return last.replace(/\^\^.*$/, '').replace(/^"|"$/g, '').replace(/@[a-zA-Z-]+$/, '').trim();
};

const headText = (ruleText: string): string | null => {
  const ascii = ruleText.lastIndexOf('->');
  const unicode = ruleText.lastIndexOf('→');
  if (ascii < 0 && unicode < 0) {
    return null;
  }
  return ascii > unicode ? ruleText.slice(ascii + 2) : ruleText.slice(unicode + 1);
};

export const parseRuleHeads = (rules: SwrlRule[]): RuleHeads[] => {
  const out: RuleHeads[] = [];
  rules.forEach(rule => {
    const text = rule.ruleText || '';
    const head = headText(text);
    if (!rule.enabled || /sqwrl:/i.test(text) || head === null) {
      return;
    }
    const atoms: HeadAtom[] = [];
    const pattern = /([^\s(),^]+)\s*\(([^)]*)\)/g;
    let m: RegExpExecArray | null;
    while ((m = pattern.exec(head)) !== null) {
      const name = atomLocalName(m[1]);
      if (name) {
        atoms.push({ name, literal: literalOf(m[2]) });
      }
    }
    if (atoms.length > 0) {
      out.push({ ruleName: rule.ruleName, atoms });
    }
  });
  return out;
};

export const headNameSet = (heads: RuleHeads[]): Set<string> =>
  new Set(heads.flatMap(rule => rule.atoms.map(atom => atom.name)));

const sameValue = (a: string, b: string): boolean => {
  const na = Number(a);
  const nb = Number(b);
  if (a !== '' && b !== '' && !Number.isNaN(na) && !Number.isNaN(nb)) {
    return na === nb;
  }
  return a.trim().toLowerCase() === b.trim().toLowerCase();
};

export const rulesProducing = (name: string, literal: string | null | undefined, heads: RuleHeads[]): string[] =>
  heads
    .filter(rule => rule.atoms.some(atom =>
      atom.name === name && (atom.literal === null || literal == null || sameValue(atom.literal, literal))))
    .map(rule => rule.ruleName);

export const ruleGroupLabel = (ruleNames: string[]): string =>
  ruleNames.length === 0 ? UNIDENTIFIED_RULE : ruleNames.join(' or ');

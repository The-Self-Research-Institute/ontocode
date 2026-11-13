import { describe, expect, it } from 'vitest';
import { UNIDENTIFIED_RULE, headNameSet, parseRuleHeads, ruleGroupLabel, rulesProducing } from '../src/ruleGroups';
import type { SwrlRule } from '../src/types';

const rule = (ruleName: string, ruleText: string, enabled = true): SwrlRule => ({ id: ruleName, ruleName, ruleText, enabled });

const PIZZA_RULES: SwrlRule[] = [
  rule('InvalidDiscountRule', 'Customer(?c) ^ hasDiscount(?c, ?d) ^ swrlb:greaterThan(?d, 1.0) -> InvalidDiscountCustomer(?c)'),
  rule('HotDiscountRule', 'Customer(?c) ^ numberOfPizzasPurchased(?c, ?np) ^ swrlb:greaterThan(?np, 1) -> hasDiscount(?c, 0.2)'),
  rule('LessSpicyDiscountRule', 'Customer(?c) ^ numberOfPizzasPurchased(?c, ?np) ^ swrlb:greaterThan(?np, 1) -> hasDiscount(?c, 0.1)'),
  rule('MeatyPizzaRule', 'Pizza(?p) ^ hasTopping(?p, ?t) ^ MeatTopping(?t) -> MeatyPizzaByRule(?p)'),
  rule('TestHotDiscountRule', 'Customer(?c) ^ sqwrl:select(?c) -> hasDiscount(?c, 0.5)'),
  rule('DisabledRule', 'Pizza(?p) -> DisabledPizza(?p)', false),
];

describe('parseRuleHeads', () => {
  const heads = parseRuleHeads(PIZZA_RULES);

  it('reads the class and property names from the head of each enabled rule', () => {
    expect(heads.map(h => h.ruleName)).toEqual(['InvalidDiscountRule', 'HotDiscountRule', 'LessSpicyDiscountRule', 'MeatyPizzaRule']);
    expect(headNameSet(heads)).toEqual(new Set(['InvalidDiscountCustomer', 'hasDiscount', 'MeatyPizzaByRule']));
  });

  it('skips SQWRL queries and disabled rules', () => {
    expect(heads.some(h => h.ruleName === 'TestHotDiscountRule')).toBe(false);
    expect(heads.some(h => h.ruleName === 'DisabledRule')).toBe(false);
  });

  it('keeps the literal of a data property head and ignores variable arguments', () => {
    expect(heads.find(h => h.ruleName === 'HotDiscountRule')?.atoms).toEqual([{ name: 'hasDiscount', literal: '0.2' }]);
    expect(heads.find(h => h.ruleName === 'MeatyPizzaRule')?.atoms).toEqual([{ name: 'MeatyPizzaByRule', literal: null }]);
  });

  it('strips datatype and quotes from a literal head argument', () => {
    const typed = parseRuleHeads([rule('Typed', 'Pizza(?p) -> hasLabel(?p, "big"^^xsd:string)')]);
    expect(typed[0].atoms).toEqual([{ name: 'hasLabel', literal: 'big' }]);
  });

  it('accepts the arrow character as well as the ascii arrow', () => {
    const unicode = parseRuleHeads([rule('Arrow', 'Pizza(?p) → Tasty(?p)')]);
    expect(unicode[0].atoms).toEqual([{ name: 'Tasty', literal: null }]);
  });
});

describe('rulesProducing', () => {
  const heads = parseRuleHeads(PIZZA_RULES);

  it('names the single rule for a class fact', () => {
    expect(rulesProducing('MeatyPizzaByRule', null, heads)).toEqual(['MeatyPizzaRule']);
  });

  it('tells two rules with the same head apart by the literal', () => {
    expect(rulesProducing('hasDiscount', '0.2', heads)).toEqual(['HotDiscountRule']);
    expect(rulesProducing('hasDiscount', '0.1', heads)).toEqual(['LessSpicyDiscountRule']);
  });

  it('compares numbers by value, not by text', () => {
    expect(rulesProducing('hasDiscount', '0.20', heads)).toEqual(['HotDiscountRule']);
    expect(rulesProducing('hasDiscount', '.1', heads)).toEqual(['LessSpicyDiscountRule']);
  });

  it('lists every candidate when the fact carries no literal to separate them', () => {
    expect(rulesProducing('hasDiscount', null, heads)).toEqual(['HotDiscountRule', 'LessSpicyDiscountRule']);
  });

  it('returns nothing for a name no rule produces or a literal no rule produces', () => {
    expect(rulesProducing('Pizza', null, heads)).toEqual([]);
    expect(rulesProducing('hasDiscount', '0.9', heads)).toEqual([]);
  });
});

describe('ruleGroupLabel', () => {
  it('uses the rule name, joins candidates and falls back when none is known', () => {
    expect(ruleGroupLabel(['MeatyPizzaRule'])).toBe('MeatyPizzaRule');
    expect(ruleGroupLabel(['A', 'B'])).toBe('A or B');
    expect(ruleGroupLabel([])).toBe(UNIDENTIFIED_RULE);
  });
});

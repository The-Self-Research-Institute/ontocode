import { describe, expect, it } from 'vitest';
import { buildLabelLookup, describeInheritance } from '../utils/ancestorInheritance';

const NS = 'http://example.org/anc#';
const labelFor = buildLabelLookup(
  [{ id: NS + 'CheesyPizza', definition: 'CheesyPizza' }],
  [{ id: NS + 'Pizza', definition: 'Pizza', navigable: 'true' }, { id: '_:b0', definition: 'hasTopping some Cheese', navigable: 'false' }],
);

describe('describeInheritance', () => {
  it('marks a restriction on the selected class itself as asserted', () => {
    const info = describeInheritance({ id: '_:b0', ancestorIri: NS + 'Margherita', navigable: 'false' }, NS + 'Margherita', 'Margherita', labelFor);
    expect(info).toEqual({ kind: 'asserted', fromDefinition: false });
  });

  it('says where an inherited restriction comes from and that the filler is not a parent', () => {
    const info = describeInheritance(
      { id: '_:b0', ancestorIri: NS + 'CheesyPizza', navigable: 'false', isRestriction: 'true', fillerIri: NS + 'Cheese' },
      NS + 'Margherita', 'Margherita', labelFor);
    expect(info?.kind).toBe('inherited');
    expect(info?.fromLabel).toBe('CheesyPizza');
    expect(info?.fromIri).toBe(NS + 'CheesyPizza');
    expect(info?.fromDefinition).toBe(false);
    expect(info?.note).toBe('Inherited condition. Margherita is not a subclass of Cheese.');
  });

  it('flags entries that come from an equivalent class definition', () => {
    const info = describeInheritance({ id: '_:b1', ancestorIri: NS + 'PepperPizza', navigable: 'false', fromDefinition: 'true' }, NS + 'Hot', 'Hot', labelFor);
    expect(info?.fromDefinition).toBe(true);
    expect(info?.fromLabel).toBe('PepperPizza');
  });

  it('gives a named inherited parent no condition note', () => {
    const info = describeInheritance({ id: NS + 'Pizza', ancestorIri: NS + 'CheesyPizza', navigable: 'true' }, NS + 'Margherita', 'Margherita', labelFor);
    expect(info?.note).toBeUndefined();
    expect(info?.kind).toBe('inherited');
  });

  it('returns nothing when the ancestor is unknown', () => {
    expect(describeInheritance({ id: 'x' }, NS + 'A', 'A', labelFor)).toBeNull();
  });
});

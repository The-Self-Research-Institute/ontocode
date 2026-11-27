import { describe, expect, it } from "vitest";
import { buildEntityIri, computeEntityIriPreview, labelForNewEntity } from "../utils/entityIri";

it("validates an explicitly edited IRI without changing the entity name", () => {
  const iri = "https://example.org/custom#NamedEntity";
  expect(computeEntityIriPreview("https://example.org/project", "My entity", [], undefined, iri))
    .toEqual({ trimmedName: "My entity", computedIri: iri, isDuplicate: false, canCreate: true });
  expect(computeEntityIriPreview("https://example.org/project", "My entity", [iri], undefined, iri).canCreate).toBe(false);
  expect(computeEntityIriPreview("https://example.org/project", "My entity", [], undefined, "relative name").canCreate).toBe(false);
});

describe("buildEntityIri", () => {
  it("adds a hash after an ontology IRI that has none", () => {
    expect(buildEntityIri("http://example.org/onto", "Cat")).toBe("http://example.org/onto#Cat");
  });

  it("does not double the hash when the ontology IRI already ends with one", () => {
    expect(buildEntityIri("http://example.org/onto#", "Cat")).toBe("http://example.org/onto#Cat");
  });

  it("appends straight after a trailing slash", () => {
    expect(buildEntityIri("http://example.org/onto/", "Cat")).toBe("http://example.org/onto/Cat");
  });

  it("uses the saved pattern for the preview and duplicate check", () => {
    const preferences = { autoGenerateIRI: true, iriGenerationPattern: "custom", customPattern: "{namespace}/term/{entity}" } as const;
    const iri = "http://example.org/onto/term/Pizza_topping";
    expect(computeEntityIriPreview("http://example.org/onto#", "Pizza topping", [iri], preferences))
      .toMatchObject({ computedIri: iri, isDuplicate: true, canCreate: false });
    expect(buildEntityIri("http://example.org/onto#", "http://other.org/x#Cat", preferences))
      .toBe("http://other.org/x#Cat");
  });

  it("turns spaces in the name into underscores and keeps a full IRI as it is", () => {
    expect(buildEntityIri("http://example.org/onto", "Pizza topping")).toBe("http://example.org/onto#Pizza_topping");
    expect(buildEntityIri("http://example.org/onto", "http://other.org/x#Cat")).toBe("http://other.org/x#Cat");
  });
});

import { isBulkChangeIri } from '../utils/entityIri';

describe('isBulkChangeIri', () => {
  it('treats spreadsheet imports and merges as bulk changes', () => {
    expect(isBulkChangeIri('urn:ontocode:merge:123')).toBe(true);
    expect(isBulkChangeIri('urn:ontocode:spreadsheet-import:9')).toBe(true);
    expect(isBulkChangeIri('http://example.org/A')).toBe(false);
    expect(isBulkChangeIri(undefined)).toBe(false);
  });
});

describe("labelForNewEntity", () => {
  it("keeps the typed name as the label when the address had to replace spaces", () => {
    expect(labelForNewEntity("thick crust")).toBe("thick crust");
    expect(labelForNewEntity("  Thick   crust ")).toBe("Thick   crust");
  });

  it("adds no label when the name is already a valid address fragment", () => {
    expect(labelForNewEntity("ThickCrust")).toBe("");
    expect(labelForNewEntity("thick_crust")).toBe("");
  });

  it("adds no label for an empty name or a full address", () => {
    expect(labelForNewEntity("   ")).toBe("");
    expect(labelForNewEntity("http://example.org/onto#Crust")).toBe("");
  });

  it("matches the address that buildEntityIri produces", () => {
    expect(buildEntityIri("http://example.org/onto", "thick crust")).toBe("http://example.org/onto#thick_crust");
  });
});

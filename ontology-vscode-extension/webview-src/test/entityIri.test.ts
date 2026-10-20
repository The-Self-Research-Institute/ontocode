import { describe, expect, it } from "vitest";
import { buildEntityIri, labelForNewEntity } from "../utils/entityIri";

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

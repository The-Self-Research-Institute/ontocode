import { describe, expect, it } from "vitest";
import { buildEntityIri } from "../utils/entityIri";

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

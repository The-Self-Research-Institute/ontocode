import { describe, expect, it } from "vitest";
import { isBuiltInPrefix } from "../utils/builtInPrefixes";

describe("isBuiltInPrefix", () => {
  it("recognises the standard prefixes with or without the trailing colon", () => {
    expect(isBuiltInPrefix("xsd", "http://www.w3.org/2001/XMLSchema#")).toBe(true);
    expect(isBuiltInPrefix("skos:", "http://www.w3.org/2004/02/skos/core#")).toBe(true);
  });

  it("does not treat a user prefix as built in", () => {
    expect(isBuiltInPrefix("pizza", "http://example.org/pizza#")).toBe(false);
    expect(isBuiltInPrefix(":", "http://example.org/pizza#")).toBe(false);
  });

  it("lets a standard name that points somewhere else be deleted", () => {
    expect(isBuiltInPrefix("xsd", "http://example.org/my-xsd#")).toBe(false);
  });
});

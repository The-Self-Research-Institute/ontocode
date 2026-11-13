import { describe, expect, it } from "vitest";
import { buildSearchParams } from "../services/searchApi";

describe("buildSearchParams", () => {
  it("sends false, never the text undefined, for options the caller did not set", () => {
    const params = buildSearchParams({ query: "Parmesan Topping", types: ["CLASS"] });
    expect(params.annotations).toBe("false");
    expect(params.regex).toBe("false");
    expect(params.hideDeprecated).toBe("false");
    expect(params.hideBuiltins).toBe("false");
    expect(Object.values(params)).not.toContain("undefined");
  });

  it("sends true only when the option is exactly true", () => {
    const params = buildSearchParams({
      query: "x",
      types: ["CLASS"],
      annotations: true,
      regex: true,
      hideDeprecated: true,
      hideBuiltins: true,
    });
    expect(params.annotations).toBe("true");
    expect(params.regex).toBe("true");
    expect(params.hideDeprecated).toBe("true");
    expect(params.hideBuiltins).toBe("true");
  });

  it("keeps the query, the types, the limit and the scope", () => {
    const params = buildSearchParams({ query: "Parmesan Topping", types: ["CLASS", "INDIVIDUAL"], limit: 20, scope: "all" });
    expect(params.q).toBe("Parmesan Topping");
    expect(params.types).toBe("CLASS,INDIVIDUAL");
    expect(params.limit).toBe("20");
    expect(params.scope).toBe("all");
  });
});

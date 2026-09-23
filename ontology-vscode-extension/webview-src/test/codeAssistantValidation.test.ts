import { describe, it, expect } from "vitest";
import { validateAgainstSchema } from "../services/codeAssistantValidation";
import type { JsonSchema } from "../services/codeAssistantProviders";

describe("validateAgainstSchema", () => {
  it("accepts a matching string", () => {
    const schema: JsonSchema = { type: "string" };
    expect(validateAgainstSchema(schema, "hello").valid).toBe(true);
  });

  it("rejects a type mismatch", () => {
    const schema: JsonSchema = { type: "string" };
    const result = validateAgainstSchema(schema, 42);
    expect(result.valid).toBe(false);
    expect(result.errors[0]).toContain("expected string");
  });

  it("accepts an integer value for type integer", () => {
    const schema: JsonSchema = { type: "integer" };
    expect(validateAgainstSchema(schema, 5).valid).toBe(true);
  });

  it("rejects a fractional value for type integer", () => {
    const schema: JsonSchema = { type: "integer" };
    const result = validateAgainstSchema(schema, 3.5);
    expect(result.valid).toBe(false);
  });

  it("rejects a value not in an enum", () => {
    const schema: JsonSchema = { type: "string", enum: ["a", "b"] };
    const result = validateAgainstSchema(schema, "c");
    expect(result.valid).toBe(false);
    expect(result.errors[0]).toContain("not one of");
  });

  it("flags a missing required object field", () => {
    const schema: JsonSchema = {
      type: "object",
      required: ["name"],
      properties: { name: { type: "string" } },
    };
    const result = validateAgainstSchema(schema, {});
    expect(result.valid).toBe(false);
    expect(result.errors[0]).toContain("name");
  });

  it("validates nested object properties recursively", () => {
    const schema: JsonSchema = {
      type: "object",
      required: ["target"],
      properties: {
        target: {
          type: "object",
          required: ["type"],
          properties: { type: { type: "string", enum: ["identifier", "range"] } },
        },
      },
    };
    const bad = validateAgainstSchema(schema, { target: { type: "bogus" } });
    expect(bad.valid).toBe(false);
    expect(bad.errors[0]).toContain("$.target.type");
  });

  it("validates array items", () => {
    const schema: JsonSchema = {
      type: "array",
      items: { type: "string" },
    };
    const result = validateAgainstSchema(schema, ["a", 2, "c"]);
    expect(result.valid).toBe(false);
    expect(result.errors[0]).toContain("$[1]");
  });

  it("matches the actual READ_CONTEXT_TOOL-shaped payload end to end", () => {
    const schema: JsonSchema = {
      type: "object",
      required: ["targets", "kind"],
      properties: {
        targets: {
          type: "array",
          items: {
            type: "object",
            required: ["type", "value"],
            properties: {
              type: { type: "string", enum: ["identifier", "range"] },
              value: { type: "string" },
            },
          },
        },
        kind: { type: "string", enum: ["definitions", "diagnostics", "references"] },
      },
    };
    const valid = {
      targets: [{ type: "range", value: "turtle:100-50" }],
      kind: "definitions",
    };
    expect(validateAgainstSchema(schema, valid).valid).toBe(true);

    const invalid = { targets: [{ type: "range" }], kind: "definitions" };
    const result = validateAgainstSchema(schema, invalid);
    expect(result.valid).toBe(false);
    expect(result.errors[0]).toContain("value");
  });
});

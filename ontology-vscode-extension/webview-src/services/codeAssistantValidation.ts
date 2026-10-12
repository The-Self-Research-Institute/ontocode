import type { JsonSchema } from "./codeAssistantProviders";

export interface ValidationResult {
  valid: boolean;
  errors: string[];
}

function typeMatches(value: unknown, expected: string): boolean {
  if (expected === "string") return typeof value === "string";
  if (expected === "number") return typeof value === "number" && Number.isFinite(value);
  if (expected === "integer") return typeof value === "number" && Number.isInteger(value);
  if (expected === "boolean") return typeof value === "boolean";
  if (expected === "array") return Array.isArray(value);
  if (expected === "object") return typeof value === "object" && value !== null && !Array.isArray(value);
  return true;
}

export function validateAgainstSchema(schema: JsonSchema, value: unknown, path = "$"): ValidationResult {
  const errors: string[] = [];

  if (!typeMatches(value, schema.type)) {
    return { valid: false, errors: [`${path}: expected ${schema.type}, got ${typeof value}`] };
  }

  if (schema.enum && typeof value === "string" && !schema.enum.includes(value)) {
    errors.push(`${path}: "${value}" is not one of [${schema.enum.join(", ")}]`);
  }

  if (schema.type === "object" && value && typeof value === "object") {
    const obj = value as Record<string, unknown>;
    for (const requiredKey of schema.required ?? []) {
      if (!(requiredKey in obj)) errors.push(`${path}.${requiredKey}: missing required field`);
    }
    if (schema.properties) {
      for (const [key, propSchema] of Object.entries(schema.properties)) {
        if (key in obj) {
          const nested = validateAgainstSchema(propSchema, obj[key], `${path}.${key}`);
          errors.push(...nested.errors);
        }
      }
    }
  }

  if (schema.type === "array" && Array.isArray(value) && schema.items) {
    value.forEach((item, i) => {
      const nested = validateAgainstSchema(schema.items as JsonSchema, item, `${path}[${i}]`);
      errors.push(...nested.errors);
    });
  }

  return { valid: errors.length === 0, errors };
}

function parseJson(text: string): unknown {
  try {
    return JSON.parse(text);
  } catch {
    return undefined;
  }
}

export function coerceJsonStrings(schema: JsonSchema, value: unknown): unknown {
  let current = value;
  if (typeof current === "string" && (schema.type === "array" || schema.type === "object")) {
    const parsed = parseJson(current.trim());
    if (typeMatches(parsed, schema.type)) current = parsed;
  } else if (typeof current === "string" && (schema.type === "integer" || schema.type === "number")) {
    const trimmed = current.trim();
    if (/^-?\d+(\.\d+)?$/.test(trimmed) && typeMatches(Number(trimmed), schema.type)) current = Number(trimmed);
  }
  if (schema.type === "array" && Array.isArray(current) && schema.items) {
    const items = schema.items as JsonSchema;
    return current.map((item) => coerceJsonStrings(items, item));
  }
  if (schema.type === "object" && typeMatches(current, "object") && schema.properties) {
    const obj = { ...(current as Record<string, unknown>) };
    for (const [key, propSchema] of Object.entries(schema.properties)) {
      if (key in obj) obj[key] = coerceJsonStrings(propSchema, obj[key]);
    }
    return obj;
  }
  return current;
}

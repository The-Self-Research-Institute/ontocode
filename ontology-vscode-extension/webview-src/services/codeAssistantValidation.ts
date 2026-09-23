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

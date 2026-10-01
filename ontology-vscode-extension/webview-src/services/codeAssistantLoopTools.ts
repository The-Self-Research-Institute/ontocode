import type { ToolDefinition } from "./codeAssistantProviderTypes";

export const READ_CONTEXT_TOOL: ToolDefinition = {
  name: "read_context",
  description:
    "Read definitions, diagnostics, or references for identifiers, statements, or line ranges in the pinned document snapshot.",
  parameters: {
    type: "object",
    required: ["targets", "kind"],
    properties: {
      targets: {
        type: "array",
        items: {
          type: "object",
          required: ["type", "value"],
          properties: {
            type: {
              type: "string",
              enum: ["identifier", "range", "statement"],
              description:
                "\"identifier\" looks up an entity by IRI. \"range\" reads raw lines. " +
                "\"statement\" returns every statement block in which the entity is the subject, each with its exact line range " +
                "(\"<startLine>-<lineCount>\", both 0-indexed, the same convention propose_edit's range uses), which is the " +
                "easiest way to get the text and range to edit.",
            },
            value: {
              type: "string",
              description:
                "For type \"identifier\": a full IRI. For type \"statement\": a full IRI or a prefixed name such as ex:Pizza. " +
                "For type \"range\": \"<format>:<startLine>-<lineCount>\", both 0-indexed, e.g. \"turtle:100-50\" for 50 lines " +
                "starting at the 101st line. format is one of turtle, rdfxml, manchester, functional.",
            },
          },
        },
      },
      kind: {
        type: "string",
        enum: ["definitions", "diagnostics", "references"],
        description:
          "\"definitions\" returns the declarations and axioms of the targets. " +
          "\"diagnostics\" returns the real parse errors and warnings the document currently has, each item's text starting with " +
          "\"ERROR:\" or \"WARNING:\" and carrying the line range it applies to, 1-indexed (unlike \"statement\" and \"range\", " +
          "which are 0-indexed). \"references\" returns where the targets are used.",
      },
    },
  },
};

export const RUN_SPARQL_TOOL: ToolDefinition = {
  name: "run_sparql",
  description: "Run a single read-only SPARQL SELECT query against the pinned snapshot. Capped in rows, bytes, and time.",
  parameters: {
    type: "object",
    required: ["query"],
    properties: {
      query: { type: "string" },
    },
  },
};

export const PROPOSE_EDIT_TOOL: ToolDefinition = {
  name: "propose_edit",
  description:
    "Propose one or more grouped, dependent edits for human review. Nothing is applied until the user approves a group. " +
    "To rename an identifier, use propose_rename instead of writing the edits by hand: it finds every occurrence for you.",
  parameters: {
    type: "object",
    required: ["groups"],
    properties: {
      groups: {
        type: "array",
        items: {
          type: "object",
          required: ["edits"],
          properties: {
            edits: {
              type: "array",
              items: {
                type: "object",
                required: ["targetPath", "range", "originalText", "newText"],
                properties: {
                  targetPath: {
                    type: "string",
                    description:
                      "The serialization format this edit is written in: one of turtle, rdfxml, owlxml, manchester, functional.",
                  },
                  range: {
                    type: "object",
                    required: ["startLine", "lineCount"],
                    description:
                      "The 0-indexed line range this edit replaces in the document, from a prior read_context range read. " +
                      "For a pure insertion with nothing to replace, set lineCount to 0 and originalText to an empty string.",
                    properties: {
                      startLine: { type: "integer", description: "0-indexed line number where this edit starts." },
                      lineCount: { type: "integer", description: "Number of original lines this edit replaces, starting at startLine." },
                    },
                  },
                  originalText: { type: "string" },
                  newText: { type: "string" },
                },
              },
            },
          },
        },
      },
    },
  },
};

export const PROPOSE_RENAME_TOOL: ToolDefinition = {
  name: "propose_rename",
  description:
    "Propose renaming one identifier everywhere it occurs in the document, for human review. The server finds and rewrites every " +
    "occurrence and validates the result, so do not read or list the occurrences first. Call it alone in its turn. " +
    "Nothing is applied until the user approves it.",
  parameters: {
    type: "object",
    required: ["targetPath", "targetIdentifier", "replacementIdentifier"],
    properties: {
      targetPath: {
        type: "string",
        description: "The serialization format to rename in: one of turtle, rdfxml, owlxml, manchester, functional.",
      },
      targetIdentifier: {
        type: "string",
        description: "The identifier to rename, as a full IRI or a prefixed name exactly as it appears in the document.",
      },
      replacementIdentifier: {
        type: "string",
        description: "The new identifier, in the same form (full IRI or prefixed name) as targetIdentifier.",
      },
    },
  },
};

export const ASSISTANT_TOOLS: ToolDefinition[] = [READ_CONTEXT_TOOL, RUN_SPARQL_TOOL, PROPOSE_EDIT_TOOL, PROPOSE_RENAME_TOOL];

export const PROPOSAL_TOOLS = new Set([PROPOSE_EDIT_TOOL.name, PROPOSE_RENAME_TOOL.name]);

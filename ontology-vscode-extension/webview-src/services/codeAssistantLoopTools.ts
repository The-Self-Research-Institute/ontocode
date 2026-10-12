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
        enum: ["definitions", "diagnostics", "references", "guidance"],
        description:
          "\"definitions\" returns the declarations and axioms of the targets. " +
          "\"diagnostics\" returns the real parse errors and warnings the document currently has, each item's text starting with " +
          "\"ERROR:\" or \"WARNING:\" and carrying the line range it applies to, 1-indexed (unlike \"statement\" and \"range\", " +
          "which are 0-indexed). \"references\" returns where the targets are used. " +
          "\"guidance\" looks up short, hand-written notes on how this app works, by topic key (set targets[].type to " +
          "\"identifier\" and value to the topic, e.g. \"delete\", \"drafts\", \"rename\", \"swrl\", \"consistency\") — use it " +
          "before deleting or removing an identifier, and whenever you're unsure how a feature here is meant to be used. " +
          "Only these four values are valid here — there is no \"statements\" kind. To read an entity's statement " +
          "block, set targets[].type to \"statement\" instead and use kind \"definitions\".",
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

export const CHECK_CONSISTENCY_TOOL: ToolDefinition = {
  name: "check_consistency",
  description:
    "Check whether the ontology is logically consistent using an OWL reasoner. Cheap — usually fast, a few seconds at " +
    "most. Call explain_inconsistency afterward only if this reports consistent: false.",
  parameters: {
    type: "object",
    properties: {},
  },
};

export const EXPLAIN_INCONSISTENCY_TOOL: ToolDefinition = {
  name: "explain_inconsistency",
  description:
    "Explain why the ontology is inconsistent: the conflicting axioms and justifications, already rendered human-readable. " +
    "Slower than check_consistency (it rebuilds the reasoner every call, several seconds up to ~15s) — only call this " +
    "after check_consistency reports consistent: false, not as a first step.",
  parameters: {
    type: "object",
    properties: {},
  },
};

export const ADD_SWRL_RULE_TOOL: ToolDefinition = {
  name: "add_swrl_rule",
  description:
    "Add a SWRL rule to the ontology, for human review. Validates the rule before creating it. Nothing is inferred until " +
    "run_swrl_rule is called afterward — this only adds the rule.",
  parameters: {
    type: "object",
    required: ["ruleName", "ruleText"],
    properties: {
      ruleName: { type: "string", description: "A short, descriptive name for the rule." },
      ruleText: {
        type: "string",
        description:
          "The rule in SWRL syntax, e.g. \"Person(?p) ^ hasParent(?p, ?m) ^ hasParent(?m, ?g) -> hasGrandparent(?p, ?g)\".",
      },
    },
  },
};

export const RUN_SWRL_RULE_TOOL: ToolDefinition = {
  name: "run_swrl_rule",
  description:
    "Execute the ontology's enabled SWRL rules and return the real inferred axioms. Can take up to a minute on a large " +
    "rule set or ontology — if it times out, suggest the user try the SWRL tab directly instead.",
  parameters: {
    type: "object",
    properties: {},
  },
};

export const RUN_FUZZY_QUERY_TOOL: ToolDefinition = {
  name: "run_fuzzy_query",
  description:
    "Run a read-only fuzzy membership query, e.g. \"FIND individuals WHERE memberOf(Diabetic) >= 0.8\". Uses class " +
    "short names, not full IRIs. Only memberOf conditions (with AND/OR/NOT and ORDER BY/LIMIT) are supported — no " +
    "exists()/forall() and no write capability.",
  parameters: {
    type: "object",
    required: ["query"],
    properties: {
      query: { type: "string" },
    },
  },
};

export const ADD_INFERRED_AXIOMS_TOOL: ToolDefinition = {
  name: "add_inferred_axioms",
  description:
    "Add SWRL-inferred axioms to the ontology as new asserted statements, for human review. Pass axiom objects " +
    "exactly as returned by run_swrl_rule's inferredAxioms list (don't re-derive the fields yourself) — axioms " +
    "whose subject/object aren't already in the graph are skipped. Only turtle and ntriples documents are " +
    "supported. Call it alone in its turn. Nothing is applied until the user approves it.",
  parameters: {
    type: "object",
    required: ["targetPath", "axioms"],
    properties: {
      targetPath: {
        type: "string",
        description: "The serialization format to add the axioms to: turtle or ntriples.",
      },
      axioms: {
        type: "array",
        description: "Axiom objects from run_swrl_rule's inferredAxioms list.",
        items: {
          type: "object",
          required: ["axiomType", "subjectIri", "predicateIri"],
          properties: {
            axiomType: { type: "string" },
            subjectIri: { type: "string" },
            predicateIri: { type: "string" },
            objectIri: { type: "string" },
            objectLiteral: { type: "string" },
            literalDatatypeIri: { type: "string" },
            literalLangTag: { type: "string" },
          },
        },
      },
    },
  },
};

export const ADD_FUZZY_MEMBERSHIP_TOOL: ToolDefinition = {
  name: "add_fuzzy_membership",
  description:
    "Add new fuzzy class-membership degrees to the ontology, for human review. Degree must be between 0.0 and 1.0. " +
    "Only adds new memberships — if the entity already has a membership in that class, it's skipped (the user must " +
    "change it by hand in the Fuzzy plugin's tab). Only turtle and ntriples documents are supported. Call it alone " +
    "in its turn. Nothing is applied until the user approves it.",
  parameters: {
    type: "object",
    required: ["targetPath", "memberships"],
    properties: {
      targetPath: {
        type: "string",
        description: "The serialization format to add the memberships to: turtle or ntriples.",
      },
      memberships: {
        type: "array",
        description: "Memberships to add, as full IRIs (not short names).",
        items: {
          type: "object",
          required: ["entityIri", "classIri", "degree"],
          properties: {
            entityIri: { type: "string" },
            classIri: { type: "string" },
            degree: { type: "number" },
          },
        },
      },
    },
  },
};

export const ASSISTANT_TOOLS: ToolDefinition[] = [
  READ_CONTEXT_TOOL, RUN_SPARQL_TOOL, PROPOSE_EDIT_TOOL, PROPOSE_RENAME_TOOL,
  CHECK_CONSISTENCY_TOOL, EXPLAIN_INCONSISTENCY_TOOL,
  ADD_SWRL_RULE_TOOL, RUN_SWRL_RULE_TOOL,
  RUN_FUZZY_QUERY_TOOL,
  ADD_INFERRED_AXIOMS_TOOL,
  ADD_FUZZY_MEMBERSHIP_TOOL,
];

export const PROPOSAL_TOOLS = new Set([
  PROPOSE_EDIT_TOOL.name, PROPOSE_RENAME_TOOL.name, ADD_INFERRED_AXIOMS_TOOL.name, ADD_FUZZY_MEMBERSHIP_TOOL.name,
]);

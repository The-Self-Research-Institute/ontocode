import React from "react";
import { Loader2 } from "lucide-react";

interface Check {
  name: string;
  passed: boolean;
  detail?: string;
  status?: "pending";
}

const CHECK_LABELS: Record<string, string> = {
  has_edits: "The proposal contains edits",
  single_target_path: "All edits target one format",
  range_well_formed: "Line ranges are valid",
  no_intra_group_overlap: "Edits don't overlap",
  size_limits: "Edits are within size limits",
  original_text_matches_live: "The original text still matches the current document",
  syntax_valid: "The edited document parses",
  complete_reference_coverage: "Every other reference is updated too",
  references_resolve: "Referenced names exist in the ontology",
  no_conflicting_declaration: "No conflicting declaration",
  consistency_preserved: "This change keeps the ontology logically consistent",
  rename_occurrences_complete: "The rename covers every occurrence",
  insertion_moved_to_statement_boundary: "Insertion point adjusted",
  insertion_moved_past_sibling_edit: "Insertion point adjusted",
};

export const INSERTION_MOVED_CHECK = "insertion_moved_to_statement_boundary";
export const INSERTION_MOVED_PAST_SIBLING_CHECK = "insertion_moved_past_sibling_edit";
const NOTE_CHECKS = new Set([INSERTION_MOVED_CHECK, INSERTION_MOVED_PAST_SIBLING_CHECK]);

export function checkLabel(name: string): string {
  return CHECK_LABELS[name] ?? name.replace(/_/g, " ");
}

const FAILURE_LABEL_OVERRIDES: Record<string, string> = {
  original_text_matches_live: "The document has changed since this edit was proposed",
};

function failureLabel(name: string): string {
  return FAILURE_LABEL_OVERRIDES[name] ?? checkLabel(name);
}

export const CodeAssistantFailedChecks: React.FC<{ checks: Check[] }> = ({ checks }) => {
  const failed = checks.filter((c) => !c.passed);
  if (failed.length === 0) return null;
  return (
    <ul className="space-y-1 text-xs text-red-800" aria-label="Checks that failed">
      {failed.map((check) => (
        <li key={check.name}>
          <span className="font-semibold">{failureLabel(check.name)}</span>
          {check.detail ? `: ${check.detail}` : ""}
        </li>
      ))}
    </ul>
  );
};

export const CodeAssistantPendingChecks: React.FC<{ checks: Check[] }> = ({ checks }) => {
  const pending = checks.filter((c) => c.status === "pending");
  if (pending.length === 0) return null;
  return (
    <ul className="space-y-1 text-xs text-gray-600" aria-label="Checks still running">
      {pending.map((check) => (
        <li key={check.name} className="flex items-center gap-1.5">
          <Loader2 size={12} className="animate-spin flex-shrink-0" aria-hidden="true" />
          <span>{checkLabel(check.name)}{check.detail ? `: ${check.detail}` : ""}</span>
        </li>
      ))}
    </ul>
  );
};

export const CodeAssistantCheckNotes: React.FC<{ checks: Check[] }> = ({ checks }) => {
  const notes = checks.filter((c) => c.passed && NOTE_CHECKS.has(c.name) && c.detail);
  if (notes.length === 0) return null;
  return (
    <ul className="space-y-1 text-xs text-gray-600" aria-label="Adjustments">
      {notes.map((check) => (
        <li key={check.name}>{check.detail}</li>
      ))}
    </ul>
  );
};

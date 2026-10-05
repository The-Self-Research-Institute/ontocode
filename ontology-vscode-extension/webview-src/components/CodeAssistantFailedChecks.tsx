import React from "react";

interface Check {
  name: string;
  passed: boolean;
  detail?: string;
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
  rename_occurrences_complete: "The rename covers every occurrence",
  insertion_moved_to_statement_boundary: "Insertion point adjusted",
};

export const INSERTION_MOVED_CHECK = "insertion_moved_to_statement_boundary";

export function checkLabel(name: string): string {
  return CHECK_LABELS[name] ?? name.replace(/_/g, " ");
}

export const CodeAssistantFailedChecks: React.FC<{ checks: Check[] }> = ({ checks }) => {
  const failed = checks.filter((c) => !c.passed);
  if (failed.length === 0) return null;
  return (
    <ul className="space-y-1 text-xs text-red-800" aria-label="Checks that failed">
      {failed.map((check) => (
        <li key={check.name}>
          <span className="font-semibold">{checkLabel(check.name)}</span>
          {check.detail ? `: ${check.detail}` : ""}
        </li>
      ))}
    </ul>
  );
};

export const CodeAssistantCheckNotes: React.FC<{ checks: Check[] }> = ({ checks }) => {
  const notes = checks.filter((c) => c.passed && c.name === INSERTION_MOVED_CHECK && c.detail);
  if (notes.length === 0) return null;
  return (
    <ul className="space-y-1 text-xs text-gray-600" aria-label="Adjustments">
      {notes.map((check) => (
        <li key={check.name}>{check.detail}</li>
      ))}
    </ul>
  );
};

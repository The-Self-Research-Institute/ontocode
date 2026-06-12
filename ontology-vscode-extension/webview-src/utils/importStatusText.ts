/** User-facing labels for import pipeline stages (internal stage keys stay unchanged). */
export const IMPORT_STAGE_LABELS: Record<string, string> = {
  parsing: "Parsing ontology file…",
  "graphdb-loading": "Loading ontology data…",
  "graphdb-load-complete": "Data loaded, building index…",
  "hierarchy-warming": "Loading class hierarchy…",
  "computing-metadata": "Computing ontology statistics…",
  bulk_load: "Loading data…",
  conversion: "Converting OWL format…",
  "format-conversion": "Converting format…",
};

/** Remove internal stack names (GraphDB, Fuseki, etc.) from user-visible import text. */
export function sanitizeImportMessage(message: string | undefined | null): string {
  if (!message) return "";

  return message
    .replace(
      /Loading data into GraphDB \(this may take several minutes for large files\)\.\.\./gi,
      "Loading ontology data (large files may take several minutes)…",
    )
    .replace(/Loading into GraphDB\.\.\./gi, "Loading ontology data…")
    .replace(/Loading data into GraphDB/gi, "Loading ontology data")
    .replace(/Loading into GraphDB/gi, "Loading ontology data")
    .replace(/GraphDB load complete, computing metadata\.\.\./gi, "Data loaded, building index…")
    .replace(/GraphDB load complete/gi, "Data loaded")
    .replace(/Triple store ready — loading class tree…?/gi, "Loading class hierarchy…")
    .replace(/Loading triples into Fuseki…?/gi, "Loading ontology data…")
    .replace(/Triples loaded, computing stats…?/gi, "Data loaded, computing statistics…")
    .replace(/Connection to GraphDB lost[^.]*/gi, "Connection lost during import")
    .replace(/Cannot connect to GraphDB[^.]*/gi, "Cannot connect to the ontology service")
    .replace(/GraphDB connection refused[^.]*/gi, "Ontology service connection refused")
    .replace(/GraphDB configuration/gi, "ontology service configuration")
    .replace(/import(?:ed)? into GraphDB/gi, "imported")
    .replace(/from GraphDB/gi, "from storage")
    .replace(/to GraphDB/gi, "to storage")
    .replace(/GraphDB/gi, "ontology database")
    .replace(/Fuseki/gi, "")
    .replace(/triple store/gi, "ontology")
    .replace(/\s{2,}/g, " ")
    .replace(/\s+([,.…])/g, "$1")
    .trim();
}

/** Human-friendly queue wait — caps absurd backend estimates. */
export function formatQueueWait(ms: number | undefined | null): string | null {
  if (ms == null || ms <= 0) return null;
  const minutes = Math.ceil(ms / 60000);
  if (minutes <= 1) return "< 1 min";
  if (minutes <= 15) return `~${minutes} min`;
  return "15+ min";
}

export function importStageLabel(stage?: string, fallbackMessage?: string): string {
  if (fallbackMessage) {
    const sanitized = sanitizeImportMessage(fallbackMessage);
    if (sanitized) return sanitized;
  }
  if (stage && IMPORT_STAGE_LABELS[stage]) return IMPORT_STAGE_LABELS[stage];
  return "Processing…";
}

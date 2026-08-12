/**
 * JSON-LD citation node helpers for the extension-host citation insertion
 * command (Ctrl+Shift+C / "Insert Citation (Sci2Code)").
 *
 * Ported from webview-src/utils/jsonLdCitation.ts (kept as a separate copy —
 * extension host and webview are different build targets). JSON-LD is not
 * line-oriented like Turtle/RDF-XML, so a citation can't be spliced in as
 * raw text at a cursor position without risking invalid JSON (wrong commas,
 * unbalanced brackets). insertCitationNodeIntoJsonLd instead parses the
 * document, mutates it as a JS object, and re-serializes it, so the result
 * is always syntactically valid JSON.
 *
 * Deliberate divergence from the webview original: insertCitationNodeIntoJsonLd
 * here also merges any missing DEFAULT_JSONLD_CONTEXT entries into an
 * *existing* `@context` (the webview version only supplies defaults when
 * `@context` is absent entirely). The extension host has no separate
 * "ensure prefixes are declared" pass for JSON-LD the way ensurePrefixes()
 * has for Turtle/RDF-XML — since any fix has to happen inside the same
 * parse/mutate/reserialize step anyway, this is where it belongs.
 */

/** Default context used when a new JSON-LD document/skeleton is created for citation storage, and to fill in any prefixes missing from an existing @context. */
export const DEFAULT_JSONLD_CONTEXT: Record<string, string> = {
  rdf: "http://www.w3.org/1999/02/22-rdf-syntax-ns#",
  rdfs: "http://www.w3.org/2000/01/rdf-schema#",
  owl: "http://www.w3.org/2002/07/owl#",
  dc: "http://purl.org/dc/elements/1.1/",
  bibo: "http://purl.org/ontology/bibo/",
  foaf: "http://xmlns.com/foaf/0.1/",
  prov: "http://www.w3.org/ns/prov#",
  xsd: "http://www.w3.org/2001/XMLSchema#",
};

export interface ZoteroCitationFields {
  key: string;
  title: string;
  authors: string;
  year?: string;
  doi?: string;
  url?: string;
}

/** Builds the JSON-LD node for a Zotero (or manual) citation, mirroring the fields used by the Turtle/RDF-XML templates. */
export function buildZoteroCitationNode(fields: ZoteroCitationFields): Record<string, any> {
  const citationKey = fields.key.replace(/[^a-zA-Z0-9]/g, "");
  const node: Record<string, any> = {
    "@id": `urn:citation:${citationKey}`,
    "@type": ["owl:NamedIndividual", "prov:Entity"],
    "dc:title": fields.title,
    "dc:creator": fields.authors,
  };
  if (fields.year) node["dc:date"] = { "@value": fields.year, "@type": "xsd:gYear" };
  if (fields.doi) {
    node["dc:identifier"] = `doi:${fields.doi}`;
    node["bibo:doi"] = fields.doi;
  }
  if (fields.url) node["foaf:homepage"] = { "@id": fields.url };
  node["rdfs:comment"] = "Zotero citation";
  return node;
}

/**
 * Inserts a citation node into a JSON-LD document's `@graph` array (creating
 * one if needed), returning the re-serialized document. Always produces
 * syntactically valid JSON. Appends at the end — the extension host has no
 * click-position concept to target a specific insertion point the way the
 * webview's findGraphInsertionIndex does.
 */
export function insertCitationNodeIntoJsonLd(content: string, node: Record<string, any>): string {
  const trimmed = content.trim();
  let parsed: any;

  if (!trimmed) {
    parsed = { "@context": { ...DEFAULT_JSONLD_CONTEXT }, "@graph": [] };
  } else {
    try {
      parsed = JSON.parse(trimmed);
    } catch {
      // Existing content isn't valid JSON — don't try to guess where to splice; start fresh
      // rather than producing something that's also invalid.
      parsed = { "@context": { ...DEFAULT_JSONLD_CONTEXT }, "@graph": [] };
    }
  }

  let graphArray: any[];
  if (Array.isArray(parsed)) {
    graphArray = parsed;
  } else if (parsed && typeof parsed === "object") {
    if (Array.isArray(parsed["@graph"])) {
      graphArray = parsed["@graph"];
    } else {
      const { "@context": context, ...rest } = parsed;
      const hasExistingNode = Object.keys(rest).length > 0;
      graphArray = hasExistingNode ? [rest] : [];
      parsed = { ...(context ? { "@context": context } : {}), "@graph": graphArray };
    }

    // Fill in any prefixes the document's own @context is missing — the node
    // we're about to add uses dc:/foaf:/prov:/xsd: compact IRIs unconditionally.
    const existingContext = parsed["@context"];
    if (existingContext && typeof existingContext === "object" && !Array.isArray(existingContext)) {
      parsed["@context"] = { ...DEFAULT_JSONLD_CONTEXT, ...existingContext };
    } else if (!existingContext) {
      parsed["@context"] = { ...DEFAULT_JSONLD_CONTEXT };
    }
  } else {
    graphArray = [];
    parsed = { "@context": { ...DEFAULT_JSONLD_CONTEXT }, "@graph": graphArray };
  }

  graphArray.push(node);

  return JSON.stringify(parsed, null, 2);
}

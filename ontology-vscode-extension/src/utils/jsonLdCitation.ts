

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

export function buildZoteroCitationNode(fields: ZoteroCitationFields): Record<string, any> {
  const citationKey = fields.key.replace(/[^a-zA-Z0-9]/g, "");
  const node: Record<string, any> = {
    "@id": `urn:citation:${citationKey}`,
    "@type": ["owl:NamedIndividual", "prov:Entity"],
    "dc:title": fields.title,
    "dc:creator": fields.authors,
  };
  if (fields.year) {node["dc:date"] = { "@value": fields.year, "@type": "xsd:gYear" };}
  if (fields.doi) {
    node["dc:identifier"] = `doi:${fields.doi}`;
    node["bibo:doi"] = fields.doi;
  }
  if (fields.url) {node["foaf:homepage"] = { "@id": fields.url };}
  node["rdfs:comment"] = "Zotero citation";
  return node;
}

export function insertCitationNodeIntoJsonLd(content: string, node: Record<string, any>): string {
  const trimmed = content.trim();
  let parsed: any;

  if (!trimmed) {
    parsed = { "@context": { ...DEFAULT_JSONLD_CONTEXT }, "@graph": [] };
  } else {
    try {
      parsed = JSON.parse(trimmed);
    } catch {

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

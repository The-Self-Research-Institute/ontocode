export const BUILT_IN_PREFIXES: Readonly<Record<string, string>> = {
  owl: "http://www.w3.org/2002/07/owl#",
  rdf: "http://www.w3.org/1999/02/22-rdf-syntax-ns#",
  rdfs: "http://www.w3.org/2000/01/rdf-schema#",
  xsd: "http://www.w3.org/2001/XMLSchema#",
  dc: "http://purl.org/dc/elements/1.1/",
  dcterms: "http://purl.org/dc/terms/",
  skos: "http://www.w3.org/2004/02/skos/core#",
};

export function isBuiltInPrefix(prefix: string, namespace: string): boolean {
  return BUILT_IN_PREFIXES[prefix.replace(/:$/, "")] === namespace;
}

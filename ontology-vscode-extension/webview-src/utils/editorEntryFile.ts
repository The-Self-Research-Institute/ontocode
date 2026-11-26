export interface EditorEntryFile {
  id: string;
  filename: string;
}

export function pickFileToOpen<T extends EditorEntryFile>(
  files: T[],
  projectId: string,
  lastOntologyProjectId: string | null,
): T | null {
  if (files.length === 0) {
    return null;
  }
  const prefix = `${projectId}--`;
  if (lastOntologyProjectId && lastOntologyProjectId.startsWith(prefix)) {
    const lastFileId = lastOntologyProjectId.slice(prefix.length);
    const last = files.find((f) => f.id === lastFileId);
    if (last) {
      return last;
    }
  }
  return files[0];
}

export function starterOntology(
  projectName: string | null | undefined,
  existingFilenames: string[],
): { fileName: string; content: string } {
  const existing = new Set(existingFilenames.map((name) => name.toLowerCase()));
  const baseSlug =
    (projectName || "")
      .trim()
      .toLowerCase()
      .replace(/[^a-z0-9]+/g, "-")
      .replace(/^-+|-+$/g, "") || "untitled-ontology";
  let fileName = `${baseSlug}.owl`;
  for (let n = 2; existing.has(fileName.toLowerCase()); n++) {
    fileName = `${baseSlug}-${n}.owl`;
  }
  const ontologyIRI = `http://example.org/ontologies/${fileName.replace(/\.owl$/, "")}`;
  const content = `<?xml version="1.0"?>
<rdf:RDF xmlns="${ontologyIRI}#"
     xml:base="${ontologyIRI}"
     xmlns:owl="http://www.w3.org/2002/07/owl#"
     xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#"
     xmlns:xml="http://www.w3.org/XML/1998/namespace"
     xmlns:xsd="http://www.w3.org/2001/XMLSchema#"
     xmlns:rdfs="http://www.w3.org/2000/01/rdf-schema#">
    <owl:Ontology rdf:about="${ontologyIRI}"/>
    <owl:Class rdf:about="http://www.w3.org/2002/07/owl#Thing"/>
</rdf:RDF>`;
  return { fileName, content };
}

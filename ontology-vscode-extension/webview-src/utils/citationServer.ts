import apiClient from "../services/apiClient";
import { withDraftAndUser } from "./draftScope";

const TURTLE_PREFIXES = [
  "@prefix rdf: <http://www.w3.org/1999/02/22-rdf-syntax-ns#> .",
  "@prefix rdfs: <http://www.w3.org/2000/01/rdf-schema#> .",
  "@prefix owl: <http://www.w3.org/2002/07/owl#> .",
  "@prefix xsd: <http://www.w3.org/2001/XMLSchema#> .",
  "@prefix dc: <http://purl.org/dc/elements/1.1/> .",
  "@prefix dcterms: <http://purl.org/dc/terms/> .",
  "@prefix bibo: <http://purl.org/ontology/bibo/> .",
  "@prefix foaf: <http://xmlns.com/foaf/0.1/> .",
  "@prefix prov: <http://www.w3.org/ns/prov#> .",
  "@prefix skos: <http://www.w3.org/2004/02/skos/core#> .",
];

export function standaloneCitationTurtle(citationLines: string[]): string {
  return [...TURTLE_PREFIXES, "", ...citationLines, ""].join("\n");
}

export function citationInsertUrl(projectId: string): string {
  return withDraftAndUser(`/api/citations/${encodeURIComponent(projectId)}/insert`);
}

export async function recordCitationOnServer(
  projectId: string,
  citationLines: string[],
  metadata: Record<string, unknown>,
  actor: { userId?: string | null; username?: string | null },
): Promise<void> {
  await apiClient.post(citationInsertUrl(projectId), {
    citation: standaloneCitationTurtle(citationLines),
    format: "turtle",
    metadata,
    lineNumber: 0,
    userId: actor.userId || undefined,
    username: actor.username || undefined,
  });
}

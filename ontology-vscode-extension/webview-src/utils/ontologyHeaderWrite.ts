import { announceMutationApplied } from './committedChange';

const HEADER_WRITE = /\/api\/ontology\/metadata\/([^/?#]+)\/(?:annotations|imports|prefixes|gci)(?:[/?#]|$)/;

export function announceOntologyHeaderWrite<T>(url: string, result: T): T {
  const match = HEADER_WRITE.exec(url);
  if (match) {
    announceMutationApplied(decodeURIComponent(match[1]), [{ type: 'ontologyHeader' }], undefined, /[?&]draft=true\b/.test(url));
  }
  return result;
}

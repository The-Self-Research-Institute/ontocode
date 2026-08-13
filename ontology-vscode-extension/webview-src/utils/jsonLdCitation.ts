

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

function positionToLineCol(content: string, position: number): { line: number; column: number } {
  let line = 1;
  let column = 1;
  const end = Math.max(0, Math.min(position, content.length));
  for (let i = 0; i < end; i++) {
    if (content[i] === "\n") {
      line++;
      column = 1;
    } else {
      column++;
    }
  }
  return { line, column };
}

export function validateJsonLdSyntax(content: string): string | null {
  const trimmed = content.trim();
  if (!trimmed) return null; // empty content is allowed, same as other formats

  try {
    JSON.parse(content);
    return null;
  } catch (e: any) {
    const message: string = e?.message || "Invalid JSON";
    const posMatch = message.match(/position (\d+)/i);
    if (posMatch) {
      const { line, column } = positionToLineCol(content, parseInt(posMatch[1], 10));
      return `${message} (line ${line}, column ${column})`;
    }

    if (/\bline\s+\d+/i.test(message)) return message;
    return `${message} (unable to determine exact line — check for a missing comma, quote, or bracket)`;
  }
}

export interface ZoteroCitationFields {
  key: string;
  title: string;
  authors: string;
  year?: string;
  doi?: string;
  url?: string;
  abstractNote?: string;
  publicationTitle?: string;
  volume?: string;
  issue?: string;
  pages?: string;
  publisher?: string;
  itemType?: string;
  tags?: string[];
  isbn?: string;
  issn?: string;
  language?: string;
  rights?: string;
}

export function buildZoteroCitationNode(fields: ZoteroCitationFields): Record<string, any> {
  const citationKey = fields.key.replace(/[^a-zA-Z0-9]/g, "");
  const node: Record<string, any> = {
    "@id": `urn:citation:${citationKey}`,
    "@type": ["owl:NamedIndividual", "prov:Entity"],
    "dc:title": fields.title,
    "dc:creator": fields.authors,
  };
  if (fields.year) node["dc:date"] = { "@value": fields.year, "@type": "xsd:gYear" };
  if (fields.publicationTitle) node["dc:source"] = fields.publicationTitle;
  if (fields.publisher) node["dc:publisher"] = fields.publisher;
  if (fields.doi) {
    node["dc:identifier"] = `doi:${fields.doi}`;
    node["bibo:doi"] = fields.doi;
  }
  if (fields.isbn) node["bibo:isbn"] = fields.isbn;
  if (fields.issn) node["bibo:issn"] = fields.issn;
  if (fields.url) node["foaf:homepage"] = { "@id": fields.url };
  if (fields.volume) node["bibo:volume"] = fields.volume;
  if (fields.issue) node["bibo:issue"] = fields.issue;
  if (fields.pages) node["bibo:pages"] = fields.pages;
  if (fields.language) node["dc:language"] = fields.language;
  if (fields.rights) node["dc:rights"] = fields.rights;
  if (fields.itemType) node["dc:type"] = fields.itemType;
  if (fields.tags && fields.tags.length > 0) node["dc:subject"] = fields.tags;
  if (fields.abstractNote) node["dc:description"] = fields.abstractNote;
  node["rdfs:comment"] = "Zotero citation";
  return node;
}

export function insertCitationNodeIntoJsonLd(
  content: string,
  node: Record<string, any>,
  afterIndex?: number | null,
): string {
  const trimmed = content.trim();
  let parsed: any;

  if (!trimmed) {
    parsed = { "@context": DEFAULT_JSONLD_CONTEXT, "@graph": [] };
  } else {
    try {
      parsed = JSON.parse(trimmed);
    } catch {

      parsed = { "@context": DEFAULT_JSONLD_CONTEXT, "@graph": [] };
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
  } else {
    graphArray = [];
    parsed = { "@context": DEFAULT_JSONLD_CONTEXT, "@graph": graphArray };
  }

  if (typeof afterIndex === "number" && afterIndex >= 0 && afterIndex < graphArray.length) {
    graphArray.splice(afterIndex + 1, 0, node);
  } else {
    graphArray.push(node);
  }

  return JSON.stringify(parsed, null, 2);
}

function skipWhitespace(s: string, i: number): number {
  while (i < s.length && /\s/.test(s[i])) i++;
  return i;
}

function skipJsonValue(s: string, i: number): number {
  i = skipWhitespace(s, i);
  const ch = s[i];

  const skipString = (pos: number): number => {
    pos++; // opening quote
    while (pos < s.length) {
      if (s[pos] === "\\") { pos += 2; continue; }
      if (s[pos] === '"') { pos++; break; }
      pos++;
    }
    return pos;
  };

  if (ch === '"') return skipString(i);

  if (ch === "{" || ch === "[") {
    const open = ch;
    const close = ch === "{" ? "}" : "]";
    let depth = 1;
    i++;
    while (i < s.length && depth > 0) {
      const c = s[i];
      if (c === '"') { i = skipString(i); continue; }
      if (c === open) depth++;
      else if (c === close) depth--;
      i++;
    }
    return i;
  }

  while (i < s.length && !/[,\]\}\s]/.test(s[i])) i++;
  return i;
}

function lineAt(s: string, pos: number): number {
  let line = 0;
  const end = Math.max(0, Math.min(pos, s.length));
  for (let i = 0; i < end; i++) if (s[i] === "\n") line++;
  return line;
}

export function findGraphInsertionIndex(content: string, clickedLine: number): number | null {
  const graphKeyMatch = content.match(/"@graph"\s*:\s*\[/);
  let arrayOpen: number;
  if (graphKeyMatch && graphKeyMatch.index !== undefined) {
    arrayOpen = graphKeyMatch.index + graphKeyMatch[0].length - 1;
  } else {
    const firstNonWs = content.search(/\S/);
    if (firstNonWs === -1 || content[firstNonWs] !== "[") return null;
    arrayOpen = firstNonWs;
  }

  let i = arrayOpen + 1;
  let index = -1;
  let lastElementEndLine = -1;

  while (i < content.length) {
    i = skipWhitespace(content, i);
    if (content[i] === "]" || i >= content.length) break;

    const elemStartLine = lineAt(content, i);
    i = skipJsonValue(content, i);
    const elemEndLine = lineAt(content, i);
    index++;

    if (clickedLine >= elemStartLine && clickedLine <= elemEndLine) {
      return index; // clicked inside this element — insert right after it
    }
    lastElementEndLine = elemEndLine;

    i = skipWhitespace(content, i);
    if (content[i] === ",") { i++; continue; }
    break; // reached the array's closing ']' (or malformed content)
  }

  if (lastElementEndLine >= 0 && clickedLine > lastElementEndLine) return index;
  return null;
}

export function removeCitationNodeFromJsonLd(
  content: string,
  citationUri: string,
): { content: string; removed: boolean } {
  const fullId = `urn:citation:${citationUri}`;
  let parsed: any;
  try {
    parsed = JSON.parse(content);
  } catch {
    return { content, removed: false };
  }

  let removed = false;

  if (Array.isArray(parsed)) {
    const before = parsed.length;
    parsed = parsed.filter((n: any) => !(n && n["@id"] === fullId));
    removed = parsed.length !== before;
  } else if (parsed && Array.isArray(parsed["@graph"])) {
    const before = parsed["@graph"].length;
    parsed["@graph"] = parsed["@graph"].filter((n: any) => !(n && n["@id"] === fullId));
    removed = parsed["@graph"].length !== before;
  }

  if (!removed) return { content, removed: false };
  return { content: JSON.stringify(parsed, null, 2), removed: true };
}

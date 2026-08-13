

export type CodeViewFormat =
  | "turtle"
  | "rdfxml"
  | "ntriples"
  | "owlxml"
  | "manchester"
  | "functional"
  | "jsonld";

export interface LintIssue {
  line: number;
  severity: "warning";
  message: string;
  iri?: string;
}

interface EntityRef {
  iri: string;
  line: number;
}

const MAX_ISSUES = 200; // safety cap — never let a pathological file produce thousands of rows

function namespaceOf(iri: string): string {
  const hash = iri.lastIndexOf("#");
  if (hash !== -1) return iri.slice(0, hash + 1);
  const slash = iri.lastIndexOf("/");
  if (slash !== -1) return iri.slice(0, slash + 1);
  return iri;
}

function mostCommonNamespace(iris: string[]): string | null {
  if (iris.length === 0) return null;
  const counts = new Map<string, number>();
  for (const iri of iris) {
    const ns = namespaceOf(iri);
    counts.set(ns, (counts.get(ns) || 0) + 1);
  }
  let best: string | null = null;
  let bestCount = 0;
  for (const [ns, count] of counts) {
    if (count > bestCount) {
      best = ns;
      bestCount = count;
    }
  }
  return best;
}

function resolveIri(base: string, ref: string): string {
  if (/^[a-zA-Z][a-zA-Z0-9+.-]*:\/\//.test(ref)) return ref; // already absolute
  if (ref.startsWith("#")) return base + ref;
  const schemeMatch = base.match(/^[a-zA-Z][a-zA-Z0-9+.-]*:\/\//);
  if (!schemeMatch) return base + ref;
  const scheme = schemeMatch[0];
  const rest = base.slice(scheme.length);
  const slashIdx = rest.indexOf("/");
  if (slashIdx === -1) return `${scheme}${rest}/${ref}`;
  const authority = rest.slice(0, slashIdx);
  const path = rest.slice(slashIdx);
  const lastSlash = path.lastIndexOf("/");
  const mergedPath = path.slice(0, lastSlash + 1) + ref;
  return `${scheme}${authority}${mergedPath}`;
}

function buildIssues(
  declared: EntityRef[],
  referenced: EntityRef[],
  prefixNamespaces: Set<string>,
): LintIssue[] {
  const issues: LintIssue[] = [];
  const declaredIris = new Set(declared.map((d) => d.iri));
  const home = mostCommonNamespace(declared.map((d) => d.iri));

  if (home) {
    for (const d of declared) {
      const ns = namespaceOf(d.iri);
      if (ns !== home && !prefixNamespaces.has(ns)) {
        issues.push({
          line: d.line,
          severity: "warning",
          message: `This IRI resolves to "${d.iri}", outside the ontology's namespace ("${home}"). If this was meant to be a local entity, check for a missing '#' or '/'.`,
          iri: d.iri,
        });
        if (issues.length >= MAX_ISSUES) return issues;
      }
    }
  }

  const seenUndeclared = new Set<string>();
  for (const r of referenced) {
    if (declaredIris.has(r.iri)) continue;

    if (/#(Thing|Nothing)$/.test(r.iri)) continue;
    if (/^https?:\/\/www\.w3\.org\//.test(r.iri)) continue;
    const key = r.iri;
    if (seenUndeclared.has(key)) continue;
    seenUndeclared.add(key);
    issues.push({
      line: r.line,
      severity: "warning",
      message: `"${r.iri}" is used here but never declared as a class, property, or individual anywhere in this document.`,
      iri: r.iri,
    });
    if (issues.length >= MAX_ISSUES) return issues;
  }

  return issues.sort((a, b) => a.line - b.line);
}

function lintOwlXml(content: string): LintIssue[] {
  const lines = content.split(/\r?\n/);
  const baseMatch = content.match(/xml:base="([^"]+)"/) || content.match(/ontologyIRI="([^"]+)"/);
  const base = baseMatch ? baseMatch[1] : "";

  const prefixNamespaces = new Set<string>();
  const prefixMap = new Map<string, string>();
  const prefixRe = /<Prefix\s+name="([^"]*)"\s+IRI="([^"]+)"\s*\/>/g;
  let pm: RegExpExecArray | null;
  while ((pm = prefixRe.exec(content))) {
    prefixMap.set(pm[1], pm[2]);
    if (pm[1] !== "") prefixNamespaces.add(pm[2]);
  }

  const resolveAttrIri = (raw: string): string | null => {

    const iriM = raw.match(/\bIRI="([^"]+)"/);
    if (iriM) return base ? resolveIri(base, iriM[1]) : iriM[1];
    const abbrM = raw.match(/\babbreviatedIRI="([^:"]*):([^"]+)"/);
    if (abbrM) {
      const ns = prefixMap.get(abbrM[1]);
      return ns ? ns + abbrM[2] : null;
    }
    return null;
  };

  const declared: EntityRef[] = [];
  const referenced: EntityRef[] = [];

  let inDeclaration = false;
  let declarationStartLine = 0;
  let declarationBuffer = "";
  let inAxiom = false;
  let axiomStartLine = 0;
  let axiomBuffer = "";
  const axiomTags = [
    "SubClassOf", "EquivalentClasses", "DisjointClasses", "ClassAssertion",
    "ObjectPropertyAssertion", "DataPropertyAssertion", "SubObjectPropertyOf",
    "SubDataPropertyOf", "EquivalentObjectProperties", "EquivalentDataProperties",
    "ObjectPropertyDomain", "ObjectPropertyRange", "DataPropertyDomain", "DataPropertyRange",
    "DisjointObjectProperties", "InverseObjectProperties", "AnnotationAssertion",
  ];

  for (let i = 0; i < lines.length; i++) {
    const line = lines[i];
    const lineNo = i + 1;

    if (!inDeclaration && /<Declaration>/.test(line)) {
      inDeclaration = true;
      declarationStartLine = lineNo;
      declarationBuffer = line;
      if (/<\/Declaration>/.test(line)) {

        const entM = declarationBuffer.match(/<(Class|ObjectProperty|DataProperty|AnnotationProperty|NamedIndividual|Datatype)\s+IRI="([^"]+)"/);
        if (entM) {
          const resolved = base ? resolveIri(base, entM[2]) : entM[2];
          declared.push({ iri: resolved, line: declarationStartLine });
        }
        inDeclaration = false;
      }
      continue;
    }
    if (inDeclaration) {
      declarationBuffer += "\n" + line;
      if (/<\/Declaration>/.test(line)) {
        const entM = declarationBuffer.match(/<(Class|ObjectProperty|DataProperty|AnnotationProperty|NamedIndividual|Datatype)\s+IRI="([^"]+)"/);
        if (entM) {
          const resolved = base ? resolveIri(base, entM[2]) : entM[2];
          declared.push({ iri: resolved, line: declarationStartLine });
        }
        inDeclaration = false;
      }
      continue;
    }

    if (!inAxiom) {
      const openTag = axiomTags.find((t) => new RegExp(`<${t}(\\s|>)`).test(line));
      if (openTag) {
        inAxiom = true;
        axiomStartLine = lineNo;
        axiomBuffer = line;
        if (new RegExp(`</${openTag}>`).test(line)) {
          collectAxiomRefs(axiomBuffer, axiomStartLine);
          inAxiom = false;
        }
        continue;
      }
    } else {
      axiomBuffer += "\n" + line;
      const closeTag = axiomTags.find((t) => new RegExp(`</${t}>`).test(axiomBuffer));
      if (closeTag) {
        collectAxiomRefs(axiomBuffer, axiomStartLine);
        inAxiom = false;
      }
    }
  }

  function collectAxiomRefs(buffer: string, startLine: number): void {

    if (/<AnnotationAssertion>/.test(buffer)) return;
    const attrRe = /<(?:Class|ObjectProperty|DataProperty|AnnotationProperty|NamedIndividual|Datatype)\s+[^>]*\/>/g;
    let m: RegExpExecArray | null;
    while ((m = attrRe.exec(buffer))) {
      const iri = resolveAttrIri(m[0]);
      if (iri) referenced.push({ iri, line: startLine });
    }
  }

  return buildIssues(declared, referenced, prefixNamespaces);
}

function lintRdfXml(content: string): LintIssue[] {
  const lines = content.split(/\r?\n/);
  const baseMatch = content.match(/xml:base="([^"]+)"/);
  const base = baseMatch ? baseMatch[1] : "";

  const nsDeclaredPrefixes = new Set<string>();
  const xmlnsRe = /xmlns:([\w-]+)="([^"]+)"/g;
  let xm: RegExpExecArray | null;
  while ((xm = xmlnsRe.exec(content))) {
    nsDeclaredPrefixes.add(xm[2]);
  }

  const declared: EntityRef[] = [];
  const referenced: EntityRef[] = [];

  const TYPE_RESOURCE_RE = /#(Class|ObjectProperty|DatatypeProperty|AnnotationProperty|NamedIndividual|Datatype)$/;

  const TYPED_ELEMENT_TAGS = ["owl:Class", "owl:ObjectProperty", "owl:DatatypeProperty", "owl:AnnotationProperty", "owl:NamedIndividual", "rdfs:Datatype"];

  const resolveAbout = (raw: string): string => {
    if (/^[a-zA-Z][a-zA-Z0-9+.-]*:\/\//.test(raw)) return raw; // already absolute
    const ref = raw.startsWith("#") ? raw : "#" + raw;
    return base ? resolveIri(base, ref) : ref;
  };

  let currentSubject: string | null = null;
  let currentSubjectLine = 0;

  for (let i = 0; i < lines.length; i++) {
    const line = lines[i];
    const lineNo = i + 1;

    const aboutM = line.match(/rdf:about="([^"]+)"/) || line.match(/rdf:ID="([^"]+)"/);
    const descOpen = /<rdf:Description\b/.test(line);
    const typedTag = TYPED_ELEMENT_TAGS.find((t) => line.includes(`<${t} `) || line.includes(`<${t}>`));
    if (aboutM && (descOpen || typedTag)) {
      currentSubject = resolveAbout(aboutM[1]);
      currentSubjectLine = lineNo;
      if (typedTag) {

        declared.push({ iri: currentSubject, line: lineNo });
      }
      if (/\/>\s*$/.test(line.trim())) {
        currentSubject = null; // self-closing — no body, nothing more to scan
      }
      continue;
    }

    if (/<\/rdf:Description>/.test(line)) {
      currentSubject = null;
      continue;
    }

    if (!currentSubject) continue;

    const typeM = line.match(/<rdf:type\s+rdf:resource="([^"]+)"/);
    if (typeM && TYPE_RESOURCE_RE.test(typeM[1])) {
      declared.push({ iri: currentSubject, line: currentSubjectLine });
      continue;
    }

    const resourceRe = /rdf:resource="([^"]+)"/g;
    let rm: RegExpExecArray | null;
    while ((rm = resourceRe.exec(line))) {
      const raw = rm[1];
      if (raw.startsWith("http://www.w3.org/2002/07/owl#") && /(Thing|Nothing)$/.test(raw)) continue;
      if (line.includes("rdf:type")) continue; // handled above
      const resolved = raw.startsWith("#") || !/^[a-zA-Z][a-zA-Z0-9+.-]*:\/\//.test(raw) ? resolveAbout(raw) : raw;
      referenced.push({ iri: resolved, line: lineNo });
    }
  }

  return buildIssues(declared, referenced, nsDeclaredPrefixes);
}

const RDF_TYPE_IRI = "http://www.w3.org/1999/02/22-rdf-syntax-ns#type";
const TYPE_OBJECT_IRIS = new Set([
  "http://www.w3.org/2002/07/owl#Class",
  "http://www.w3.org/2002/07/owl#ObjectProperty",
  "http://www.w3.org/2002/07/owl#DatatypeProperty",
  "http://www.w3.org/2002/07/owl#AnnotationProperty",
  "http://www.w3.org/2002/07/owl#NamedIndividual",
  "http://www.w3.org/2000/01/rdf-schema#Datatype",
]);
const RELATIONSHIP_PREDICATE_IRIS = new Set([
  "http://www.w3.org/2000/01/rdf-schema#subClassOf",
  "http://www.w3.org/2000/01/rdf-schema#subPropertyOf",
  "http://www.w3.org/2000/01/rdf-schema#domain",
  "http://www.w3.org/2000/01/rdf-schema#range",
  "http://www.w3.org/2002/07/owl#equivalentClass",
  "http://www.w3.org/2002/07/owl#equivalentProperty",
  "http://www.w3.org/2002/07/owl#disjointWith",
  "http://www.w3.org/2002/07/owl#someValuesFrom",
  "http://www.w3.org/2002/07/owl#allValuesFrom",
  "http://www.w3.org/2002/07/owl#onProperty",
  "http://www.w3.org/2002/07/owl#onClass",
  "http://www.w3.org/2002/07/owl#sameAs",
  "http://www.w3.org/2002/07/owl#differentFrom",
  "http://www.w3.org/2002/07/owl#inverseOf",
]);

function splitTopLevel(text: string, delims: Set<string>): string[] {
  const parts: string[] = [];
  let buf = "";
  let inIri = false;
  let inLiteral = false;
  let depth = 0;
  for (let i = 0; i < text.length; i++) {
    const ch = text[i];
    if (inLiteral) {
      buf += ch;
      if (ch === '"' && text[i - 1] !== "\\") inLiteral = false;
      continue;
    }
    if (inIri) {
      buf += ch;
      if (ch === ">") inIri = false;
      continue;
    }
    if (ch === '"') {
      inLiteral = true;
      buf += ch;
      continue;
    }
    if (ch === "<") {
      inIri = true;
      buf += ch;
      continue;
    }
    if (ch === "[" || ch === "(") {
      depth++;
      buf += ch;
      continue;
    }
    if (ch === "]" || ch === ")") {
      depth--;
      buf += ch;
      continue;
    }
    if (depth === 0 && delims.has(ch)) {
      parts.push(buf);
      buf = "";
      continue;
    }
    buf += ch;
  }
  parts.push(buf);
  return parts;
}

function lintTurtleLike(content: string): LintIssue[] {
  const prefixMap = new Map<string, string>();
  const prefixNamespaces = new Set<string>();
  const prefixRe = /@prefix\s+([\w-]*):\s*<([^>]+)>|PREFIX\s+([\w-]*):\s*<([^>]+)>/gi;
  let pm: RegExpExecArray | null;
  while ((pm = prefixRe.exec(content))) {
    const p = pm[1] ?? pm[3];
    const ns = pm[2] ?? pm[4];
    prefixMap.set(p, ns);
    if (p !== "") prefixNamespaces.add(ns);
  }

  const resolveTerm = (term: string): string | null => {
    term = term.trim();
    if (term.startsWith("<") && term.endsWith(">")) return term.slice(1, -1);
    if (term.startsWith('"') || term.startsWith("_:") || term.startsWith("[") || term.startsWith("(")) return null;
    const colonIdx = term.indexOf(":");
    if (colonIdx !== -1) {
      const prefix = term.slice(0, colonIdx);
      const local = term.slice(colonIdx + 1);
      const ns = prefixMap.get(prefix);
      if (ns) return ns + local;
    }
    return null;
  };

  const declared: EntityRef[] = [];
  const referenced: EntityRef[] = [];

  const processStatement = (stmt: string, startLine: number) => {
    const trimmed = stmt.trim();
    if (!trimmed || trimmed.startsWith("@") || /^PREFIX/i.test(trimmed)) return;

    const subjM = trimmed.match(/^(<[^>]+>|[\w-]*:[\w.-]*)\s+([\s\S]*)$/);
    if (!subjM) return;
    const subjIri = resolveTerm(subjM[1]);
    if (!subjIri) return;
    const rest = subjM[2];

    const groups = splitTopLevel(rest, new Set([";"]));
    for (const group of groups) {
      const g = group.trim();
      if (!g) continue;
      const predM = g.match(/^(a|<[^>]+>|[\w-]*:[\w.-]*)\s+([\s\S]*)$/);
      if (!predM) continue;
      const predToken = predM[1];
      const predIri = predToken === "a" ? RDF_TYPE_IRI : resolveTerm(predToken);
      if (!predIri) continue;
      const objects = splitTopLevel(predM[2], new Set([","])).map((o) => o.trim()).filter(Boolean);

      if (predIri === RDF_TYPE_IRI) {
        for (const obj of objects) {
          const objIri = resolveTerm(obj);
          if (objIri && TYPE_OBJECT_IRIS.has(objIri)) {
            declared.push({ iri: subjIri, line: startLine });
          }
        }
      } else if (RELATIONSHIP_PREDICATE_IRIS.has(predIri)) {
        for (const obj of objects) {
          const objIri = resolveTerm(obj);
          if (objIri) referenced.push({ iri: objIri, line: startLine });
        }
      }
    }
  };

  const lines = content.split(/\r?\n/);
  let buffer = "";
  let bufferStartLine = 1;
  let inIri = false;
  let inLiteral = false;
  let depth = 0;

  for (let i = 0; i < lines.length; i++) {
    const line = lines[i];
    if (buffer.trim() === "") bufferStartLine = i + 1;

    if (buffer.trim() === "" && /^\s*PREFIX\s/i.test(line)) {
      continue;
    }
    for (let j = 0; j < line.length; j++) {
      const ch = line[j];
      if (inLiteral) {
        if (ch === '"' && line[j - 1] !== "\\") inLiteral = false;
        buffer += ch;
        continue;
      }
      if (inIri) {
        if (ch === ">") inIri = false;
        buffer += ch;
        continue;
      }
      if (ch === '"') {
        inLiteral = true;
        buffer += ch;
        continue;
      }
      if (ch === "<") {
        inIri = true;
        buffer += ch;
        continue;
      }
      if (ch === "[" || ch === "(") {
        depth++;
        buffer += ch;
        continue;
      }
      if (ch === "]" || ch === ")") {
        depth--;
        buffer += ch;
        continue;
      }
      if (depth === 0 && ch === "#") break; // rest of line is a comment
      if (depth === 0 && ch === ".") {
        processStatement(buffer, bufferStartLine);
        buffer = "";
        continue;
      }
      buffer += ch;
    }
    buffer += " ";
  }
  if (buffer.trim()) processStatement(buffer, bufferStartLine);

  return buildIssues(declared, referenced, prefixNamespaces);
}

const MANCHESTER_KEYWORDS = new Set([
  "and", "or", "not", "some", "only", "min", "max", "exactly", "value", "that",
  "Functional", "InverseFunctional", "Transitive", "Symmetric", "Asymmetric", "Reflexive", "Irreflexive",
]);

const MANCHESTER_RELATIONSHIP_SECTIONS = new Set([
  "SubClassOf", "EquivalentTo", "DisjointWith", "Types", "SubPropertyOf",
  "Domain", "Range", "InverseOf", "SameAs", "DifferentFrom", "Facts",
]);

const MANCHESTER_SKIP_SECTIONS = new Set(["Annotations", "Characteristics"]);
const MANCHESTER_ALL_SECTIONS = new Set([...MANCHESTER_RELATIONSHIP_SECTIONS, ...MANCHESTER_SKIP_SECTIONS]);

function lintManchester(content: string): LintIssue[] {
  const lines = content.split(/\r?\n/);
  const declared: EntityRef[] = [];
  const referenced: EntityRef[] = [];

  const prefixMap = new Map<string, string>();
  const prefixRe = /Prefix:\s*([\w-]*):\s*<([^>]+)>/g;
  let pm: RegExpExecArray | null;
  while ((pm = prefixRe.exec(content))) prefixMap.set(pm[1], pm[2]);
  const prefixNamespaces = new Set(Array.from(prefixMap.values()));

  const ontM = content.match(/Ontology:\s*<([^>]+)>/);
  const ontologyBase = ontM ? ontM[1] : "";
  const homeNs = ontologyBase ? (ontologyBase + (ontologyBase.endsWith("#") || ontologyBase.endsWith("/") ? "" : "#")) : "";
  if (homeNs) prefixNamespaces.add(homeNs);

  const resolveTerm = (token: string): string => {
    const colonIdx = token.indexOf(":");
    if (colonIdx !== -1) {
      const prefix = token.slice(0, colonIdx);
      const ns = prefixMap.get(prefix);
      if (ns) return ns + token.slice(colonIdx + 1);
    }
    return homeNs ? homeNs + token : token;
  };

  let currentSubject: string | null = null;
  let currentSubjectLine = 0;
  let currentSection: string | null = null;

  const extractRefTokens = (text: string, line: number) => {
    const tokens = text.match(/[\w][\w.-]*(?::[\w.-]+)?/g) || [];
    for (const tok of tokens) {
      if (MANCHESTER_KEYWORDS.has(tok)) continue;
      if (/^\d+$/.test(tok)) continue;
      referenced.push({ iri: resolveTerm(tok), line });
    }
  };

  for (let i = 0; i < lines.length; i++) {
    const line = lines[i];
    const lineNo = i + 1;

    const declM = line.match(/^(Class|ObjectProperty|DataProperty|AnnotationProperty|Individual|Datatype):\s*(\S+)/);
    if (declM) {
      currentSubject = resolveTerm(declM[2]);
      currentSubjectLine = lineNo;
      currentSection = null;
      declared.push({ iri: currentSubject, line: lineNo });
      continue;
    }

    const sectionM = line.match(/^\s*(SubClassOf|EquivalentTo|DisjointWith|Types|SubPropertyOf|Domain|Range|InverseOf|SameAs|DifferentFrom|Facts|Annotations|Characteristics):\s*(.*)$/);
    if (sectionM && MANCHESTER_ALL_SECTIONS.has(sectionM[1])) {
      currentSection = sectionM[1];
      const inline = sectionM[2].replace(/,\s*$/, "").trim();
      if (inline && MANCHESTER_RELATIONSHIP_SECTIONS.has(currentSection)) {
        extractRefTokens(inline, currentSubjectLine);
      }
      continue;
    }

    if (currentSubject && currentSection && MANCHESTER_RELATIONSHIP_SECTIONS.has(currentSection) && /^\s{2,}\S/.test(line)) {
      extractRefTokens(line, currentSubjectLine);
    }
  }

  return buildIssues(declared, referenced, prefixNamespaces);
}

function lintFunctional(content: string): LintIssue[] {
  const lines = content.split(/\r?\n/);
  const declared: EntityRef[] = [];
  const referenced: EntityRef[] = [];
  const axiomFns = [
    "SubClassOf", "EquivalentClasses", "DisjointClasses", "ClassAssertion",
    "ObjectPropertyAssertion", "DataPropertyAssertion", "SubObjectPropertyOf",
    "SubDataPropertyOf", "ObjectPropertyDomain", "ObjectPropertyRange",
    "DataPropertyDomain", "DataPropertyRange", "EquivalentObjectProperties",
    "EquivalentDataProperties", "DisjointObjectProperties", "InverseObjectProperties",
  ];

  const prefixMap = new Map<string, string>();
  const prefixRe = /Prefix\(([\w-]*):=<([^>]+)>\)/g;
  let pm: RegExpExecArray | null;
  while ((pm = prefixRe.exec(content))) prefixMap.set(pm[1], pm[2]);
  const prefixNamespaces = new Set(Array.from(prefixMap.values()));

  const ontM = content.match(/Ontology\(<([^>]+)>/);
  const ontologyBase = ontM ? ontM[1] : "";
  const homeNs = ontologyBase ? (ontologyBase + (ontologyBase.endsWith("#") || ontologyBase.endsWith("/") ? "" : "#")) : "";
  if (homeNs) prefixNamespaces.add(homeNs);

  const resolveTerm = (raw: string): string => {
    if (raw.startsWith("<") && raw.endsWith(">")) return raw.slice(1, -1);
    const colonIdx = raw.indexOf(":");
    if (colonIdx !== -1) {
      const prefix = raw.slice(0, colonIdx);
      const local = raw.slice(colonIdx + 1);
      const ns = prefixMap.get(prefix);
      if (ns) return ns + local;
      if (prefix === "" && homeNs) return homeNs + local;
    }
    return homeNs ? homeNs + raw : raw;
  };

  const declFn = /Declaration\(\s*(Class|ObjectProperty|DataProperty|AnnotationProperty|NamedIndividual|Datatype)\(\s*([^\s)]+)\s*\)\s*\)/g;

  const termRe = /<[^>]+>|[\w-]*:[\w.-]+/g;

  let dm: RegExpExecArray | null;
  const declLineOf = (index: number): number => content.slice(0, index).split(/\r?\n/).length;
  while ((dm = declFn.exec(content))) {
    declared.push({ iri: resolveTerm(dm[2]), line: declLineOf(dm.index) });
  }

  for (let i = 0; i < lines.length; i++) {
    const line = lines[i];
    const lineNo = i + 1;
    for (const fn of axiomFns) {
      if (!line.includes(fn + "(")) continue;
      const startIdx = line.indexOf(fn + "(");
      const rest = line.slice(startIdx);
      let m: RegExpExecArray | null;
      termRe.lastIndex = 0;
      while ((m = termRe.exec(rest))) {
        referenced.push({ iri: resolveTerm(m[0]), line: lineNo });
      }
    }
  }

  return buildIssues(declared, referenced, prefixNamespaces);
}

function lintJsonLd(content: string): LintIssue[] {
  let parsed: any;
  try {
    parsed = JSON.parse(content);
  } catch {
    return []; // malformed JSON is caught by the existing pre-save JSON-LD validator
  }

  const declared: EntityRef[] = [];
  const referenced: EntityRef[] = [];
  const lineOfId = (id: string): number => {
    const idx = content.indexOf(`"${id}"`);
    if (idx === -1) return 1;
    return content.slice(0, idx).split(/\r?\n/).length;
  };

  const classTypes = new Set(["owl:Class", "Class", "http://www.w3.org/2002/07/owl#Class"]);
  const refKeys = new Set([
    "rdfs:subClassOf", "subClassOf", "owl:equivalentClass", "equivalentClass",
    "owl:disjointWith", "disjointWith", "rdf:type", "@type",
  ]);

  const nodes: any[] = Array.isArray(parsed) ? parsed : Array.isArray(parsed?.["@graph"]) ? parsed["@graph"] : [parsed];

  for (const node of nodes) {
    if (!node || typeof node !== "object" || !node["@id"]) continue;
    const id: string = node["@id"];
    const line = lineOfId(id);
    const types = ([] as string[]).concat(node["@type"] || []);
    if (types.some((t) => classTypes.has(t))) {
      declared.push({ iri: id, line });
    }
    for (const key of Object.keys(node)) {
      if (!refKeys.has(key)) continue;
      if (key === "@type" || key === "rdf:type") continue; // already handled above
      const vals = ([] as any[]).concat(node[key]);
      for (const v of vals) {
        const refId = typeof v === "string" ? v : v?.["@id"];
        if (refId) referenced.push({ iri: refId, line });
      }
    }
  }

  return buildIssues(declared, referenced, new Set());
}

export function lintOntologyContent(content: string, format: CodeViewFormat): LintIssue[] {
  if (!content || !content.trim()) return [];
  try {
    switch (format) {
      case "owlxml":
        return lintOwlXml(content);
      case "rdfxml":
        return lintRdfXml(content);
      case "turtle":
      case "ntriples":
        return lintTurtleLike(content);
      case "manchester":
        return lintManchester(content);
      case "functional":
        return lintFunctional(content);
      case "jsonld":
        return lintJsonLd(content);
      default:
        return [];
    }
  } catch (e) {

    console.warn("[ontologyLinter] Lint pass failed, skipping:", e);
    return [];
  }
}

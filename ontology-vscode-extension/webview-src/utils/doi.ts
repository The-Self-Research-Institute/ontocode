

const DOI_EMBED_RE = /10\.\d{4,9}\/[^\s"<>]+/i;

const DOI_STRICT_RE = /^10\.\d{4,9}\/[^\s]+$/i;

export const normalizeDoi = (raw: unknown): string => {
  if (typeof raw !== "string") return "";
  let s = raw.trim();
  if (!s) return "";

  s = s.replace(/^[\s<("'\[\{]+/, "");
  s = s.replace(/[\s>)"'\]\}]+$/, "");

  s = s.replace(/^https?:\/\/(?:dx\.)?doi\.org\//i, "");
  s = s.replace(/^doi:\s*/i, "");
  s = s.replace(/^info:doi\//i, "");

  try {
    s = decodeURIComponent(s);
  } catch {
    // Some legacy strings contain stray '%' that aren't valid escapes — keep raw.
  }

  s = s.replace(/[.,;:\)\]\}>]+$/g, "");

  return s.trim();
};

export const isValidDoiFormat = (raw: unknown): boolean => {
  const s = normalizeDoi(raw);
  return !!s && DOI_STRICT_RE.test(s);
};

export const extractDoiFromText = (text: unknown): string => {
  if (typeof text !== "string" || !text) return "";
  const m = text.match(DOI_EMBED_RE);
  if (!m) return "";
  const norm = normalizeDoi(m[0]);
  return isValidDoiFormat(norm) ? norm : "";
};

export const extractDoiFromZoteroData = (data: unknown): string => {
  if (!data || typeof data !== "object") return "";
  const d = data as Record<string, unknown>;

  const direct = [d.DOI, d.doi, d.Doi];
  for (const cand of direct) {
    const norm = normalizeDoi(cand);
    if (isValidDoiFormat(norm)) return norm;
  }

  const extraVal = d.extra;
  if (typeof extraVal === "string" && extraVal) {

    const labelled = extraVal.match(/(?:^|\n)\s*DOI\s*[:=]\s*([^\n\r]+)/i);
    if (labelled?.[1]) {
      const norm = normalizeDoi(labelled[1]);
      if (isValidDoiFormat(norm)) return norm;
    }
    const embedded = extractDoiFromText(extraVal);
    if (embedded) return embedded;
  }

  const urlVal = d.url;
  if (typeof urlVal === "string" && urlVal) {
    const norm = normalizeDoi(urlVal);
    if (isValidDoiFormat(norm)) return norm;
    const embedded = extractDoiFromText(urlVal);
    if (embedded) return embedded;
  }

  return "";
};

export const toDoiUrl = (doi: string): string => {
  const norm = normalizeDoi(doi);
  if (!norm) return "";
  if (/^https?:\/\//i.test(doi)) return doi;
  return `https://doi.org/${encodeURI(norm)}`;
};

export default {
  normalizeDoi,
  isValidDoiFormat,
  extractDoiFromText,
  extractDoiFromZoteroData,
  toDoiUrl,
};

/**
 * DOI (Digital Object Identifier) helpers for the extension-host citation
 * insertion command (Ctrl+Shift+C).
 *
 * Mirrors webview-src/utils/doi.ts (kept as a separate copy, not a shared
 * import, since the webview and extension host are separate TypeScript
 * projects/build targets) — keep both in sync when changing DOI handling.
 *
 * Pattern checks alone are NOT validation — they only confirm syntactic
 * shape. Authoritative validation requires resolving the DOI through
 * doi.org (see services/doiValidationService.ts).
 */

/** Raw match for a DOI embedded in a longer string. */
const DOI_EMBED_RE = /10\.\d{4,9}\/[^\s"<>]+/i;

/** Strict DOI shape: registrant prefix + slash + non-empty suffix. */
const DOI_STRICT_RE = /^10\.\d{4,9}\/[^\s]+$/i;

/**
 * Trim wrappers and decode common escape forms produced by exporters
 * (Zotero, Mendeley) so equivalent DOIs compare equal.
 */
export const normalizeDoi = (raw: unknown): string => {
  if (typeof raw !== "string") return "";
  let s = raw.trim();
  if (!s) return "";

  // Strip surrounding wrappers, e.g. <10.1/x>, "10.1/x", (10.1/x).
  s = s.replace(/^[\s<("'\[\{]+/, "");
  s = s.replace(/[\s>)"'\]\}]+$/, "");

  // Strip URL/scheme prefixes commonly seen in citation fields.
  s = s.replace(/^https?:\/\/(?:dx\.)?doi\.org\//i, "");
  s = s.replace(/^doi:\s*/i, "");
  s = s.replace(/^info:doi\//i, "");

  try {
    s = decodeURIComponent(s);
  } catch {
    // Some legacy strings contain stray '%' that aren't valid escapes — keep raw.
  }

  // Trailing punctuation that came from prose, not the DOI itself.
  s = s.replace(/[.,;:\)\]\}>]+$/g, "");

  return s.trim();
};

/** Cheap offline shape check. Use doiValidationService for real validation. */
export const isValidDoiFormat = (raw: unknown): boolean => {
  const s = normalizeDoi(raw);
  return !!s && DOI_STRICT_RE.test(s);
};

/**
 * Extract the first DOI-shaped substring from arbitrary text.
 * Useful for Zotero `extra` fields like "DOI: 10.1234/xyz\nPMID: ...".
 */
export const extractDoiFromText = (text: unknown): string => {
  if (typeof text !== "string" || !text) return "";
  const m = text.match(DOI_EMBED_RE);
  if (!m) return "";
  const norm = normalizeDoi(m[0]);
  return isValidDoiFormat(norm) ? norm : "";
};

/**
 * Inspect a Zotero item-like data bag for a DOI, regardless of how the
 * exporter spelled the field. Zotero's own schema uses DOI (uppercase) for
 * journal articles, books, theses, etc.; older exports and translators
 * sometimes emit lowercase doi, and many users stash a DOI in the
 * multi-line extra field as "DOI: 10.xxxx/yyyy".
 *
 * @returns the normalized DOI string, or "" if nothing valid found.
 */
export const extractDoiFromZoteroData = (data: unknown): string => {
  if (!data || typeof data !== "object") return "";
  const d = data as Record<string, unknown>;

  // 1. Direct DOI fields (uppercase preferred — Zotero canonical name).
  const direct = [d.DOI, d.doi, d.Doi];
  for (const cand of direct) {
    const norm = normalizeDoi(cand);
    if (isValidDoiFormat(norm)) return norm;
  }

  // 2. `extra` is a free-text field — scan it for DOI lines.
  const extraVal = d.extra;
  if (typeof extraVal === "string" && extraVal) {
    // Prefer an explicit "DOI: 10.x/y" line over any embedded URL.
    const labelled = extraVal.match(/(?:^|\n)\s*DOI\s*[:=]\s*([^\n\r]+)/i);
    if (labelled?.[1]) {
      const norm = normalizeDoi(labelled[1]);
      if (isValidDoiFormat(norm)) return norm;
    }
    const embedded = extractDoiFromText(extraVal);
    if (embedded) return embedded;
  }

  // 3. `url` may be a https://doi.org/... link.
  const urlVal = d.url;
  if (typeof urlVal === "string" && urlVal) {
    const norm = normalizeDoi(urlVal);
    if (isValidDoiFormat(norm)) return norm;
    const embedded = extractDoiFromText(urlVal);
    if (embedded) return embedded;
  }

  return "";
};

/** Build the canonical resolver URL for a DOI. */
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

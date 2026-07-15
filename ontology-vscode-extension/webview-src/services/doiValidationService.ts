// services/doiValidationService.ts
//
// Thin wrapper over the editor backend's DOI validation endpoint.
//
//   GET /api/citations/validate-doi?doi=...&title=...&publicationTitle=...&year=...
//
// The backend (CitationService.java) performs an authoritative resolution
// against doi.org with content negotiation
// (Accept: application/vnd.citationstyles.csl+json) so we can tell the
// user three things — *not* just whether the string matches a regex:
//
//   1. Does the DOI actually resolve at the DOI Foundation?    -> `valid`
//   2. Does the resolved metadata match the citation we have?  -> `relevant`
//   3. What did the registrar (Crossref / DataCite / mEDRA / …) say it is?
//
// Pure-pattern checks (regex) are NOT validation — they only confirm
// shape. A syntactically-valid DOI may be unregistered, withdrawn, or
// belong to a completely different paper than the one the user thinks.
//
// The endpoint is deliberately GET-only so it can be retried & cached.

import apiClient, { ApiError } from "./apiClient";
import { isValidDoiFormat, normalizeDoi } from "../utils/doi";

export interface DoiValidationResult {
  /** True iff doi.org resolved the DOI to a registered record. */
  valid: boolean;
  /**
   * True iff the resolved record's metadata is consistent with the
   * citation we held (title / publication title / year). When any
   * expected field is missing we treat that field as "no opinion"
   * (see backend `isMetadataMatch`).
   */
  relevant: boolean;
  /** Canonical, case-normalized DOI as returned by the registrar. */
  normalizedDoi?: string;
  resolvedTitle?: string;
  resolvedPublicationTitle?: string;
  resolvedYear?: string;
  /** Set when valid === false or when relevance check failed. */
  error?: string;
  /** Set on `valid && relevant`. */
  message?: string;
}

export interface DoiValidationInput {
  doi: string;
  title?: string;
  publicationTitle?: string;
  year?: string;
  /** Abort signal — currently advisory; apiClient does not honor it in VS Code mode. */
  signal?: AbortSignal;
}

/** Cache validations for the lifetime of the page. Keyed by all expected fields. */
const cache = new Map<string, Promise<DoiValidationResult>>();

const cacheKey = ({ doi, title, publicationTitle, year }: DoiValidationInput) =>
  [normalizeDoi(doi).toLowerCase(), title || "", publicationTitle || "", year || ""].join("\x1f");

const buildOfflineFailure = (doi: string, message: string): DoiValidationResult => ({
  valid: false,
  relevant: false,
  normalizedDoi: normalizeDoi(doi) || undefined,
  error: message,
});

const sanitizeError = (e: unknown): string => {
  if (e instanceof ApiError) {
    const data = e.data as { error?: string } | undefined;
    if (data?.error) return data.error;
    if (e.status === 502 || e.status === 504) {
      return "DOI resolver is temporarily unavailable. Try again in a moment.";
    }
    return e.message || "Unable to validate DOI right now.";
  }
  if (e instanceof Error) return e.message;
  return "Unable to validate DOI right now.";
};

/**
 * Validate a DOI authoritatively via doi.org. Returns the same shape on
 * both happy paths and resolution errors so callers never have to
 * try/catch — `result.valid === false` already conveys failure.
 */
export const validateDoiOnline = async (
  input: DoiValidationInput
): Promise<DoiValidationResult> => {
  const norm = normalizeDoi(input.doi);

  // Short-circuit obviously bad inputs to avoid pointless network round-trips.
  if (!norm) {
    return buildOfflineFailure("", "DOI is empty.");
  }
  if (!isValidDoiFormat(norm)) {
    return buildOfflineFailure(norm, "DOI is not in the expected 10.xxxx/yyy form.");
  }

  const key = cacheKey({ ...input, doi: norm });
  const cached = cache.get(key);
  if (cached) return cached;

  const params: Record<string, string> = { doi: norm };
  if (input.title?.trim()) params.title = input.title.trim();
  if (input.publicationTitle?.trim()) params.publicationTitle = input.publicationTitle.trim();
  if (input.year?.trim()) params.year = input.year.trim();

  const promise = (async (): Promise<DoiValidationResult> => {
    try {
      const data = await apiClient.get<DoiValidationResult>("/api/citations/validate-doi", params);
      // Normalize boolean fields — the backend may return strings in error envelopes.
      return {
        valid: !!data?.valid,
        relevant: !!data?.relevant,
        normalizedDoi: data?.normalizedDoi || norm,
        resolvedTitle: data?.resolvedTitle,
        resolvedPublicationTitle: data?.resolvedPublicationTitle,
        resolvedYear: data?.resolvedYear,
        error: data?.error,
        message: data?.message,
      };
    } catch (e) {
      // Surface the error in the result so the UI can display it without
      // throwing — but evict so the next call can retry.
      cache.delete(key);
      return buildOfflineFailure(norm, sanitizeError(e));
    }
  })();

  cache.set(key, promise);
  return promise;
};

/** Drop the cache (e.g. when the user edits the citation metadata). */
export const clearDoiValidationCache = (): void => {
  cache.clear();
};

export default { validateDoiOnline, clearDoiValidationCache };

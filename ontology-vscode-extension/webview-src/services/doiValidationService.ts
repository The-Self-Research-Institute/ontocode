

import apiClient, { ApiError } from "./apiClient";
import { isValidDoiFormat, normalizeDoi } from "../utils/doi";

export interface DoiValidationResult {

  valid: boolean;

  relevant: boolean;

  normalizedDoi?: string;
  resolvedTitle?: string;
  resolvedPublicationTitle?: string;
  resolvedYear?: string;

  error?: string;

  message?: string;
}

export interface DoiValidationInput {
  doi: string;
  title?: string;
  publicationTitle?: string;
  year?: string;

  signal?: AbortSignal;
}

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

export const validateDoiOnline = async (
  input: DoiValidationInput
): Promise<DoiValidationResult> => {
  const norm = normalizeDoi(input.doi);

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

      cache.delete(key);
      return buildOfflineFailure(norm, sanitizeError(e));
    }
  })();

  cache.set(key, promise);
  return promise;
};

export const clearDoiValidationCache = (): void => {
  cache.clear();
};

export default { validateDoiOnline, clearDoiValidationCache };

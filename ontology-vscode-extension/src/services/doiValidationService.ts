/**
 * Authoritative DOI validation for the extension-host citation insertion
 * command (Ctrl+Shift+C / "Insert Citation (Sci2Code)").
 *
 * Mirrors webview-src/services/doiValidationService.ts (kept as a separate
 * copy — extension host and webview are different build targets) — calls
 * the same backend endpoint (CitationService.java's /api/citations/validate-doi,
 * an authoritative doi.org content-negotiation lookup, not a pattern check)
 * so a citation inserted via the keyboard shortcut gets the same guarantee
 * as one inserted via the main picker.
 */

import axios from 'axios';
import { isValidDoiFormat, normalizeDoi } from '../utils/doi';

export interface DoiValidationResult {
    /** True iff doi.org resolved the DOI to a registered record. */
    valid: boolean;
    /** True iff the resolved record's metadata is consistent with the citation we held. */
    relevant: boolean;
    /** Canonical, case-normalized DOI as returned by the registrar. */
    normalizedDoi?: string;
    resolvedTitle?: string;
    resolvedPublicationTitle?: string;
    resolvedYear?: string;
    /** Set when valid === false or when relevance check failed. */
    error?: string;
}

export interface DoiValidationInput {
    doi: string;
    title?: string;
    publicationTitle?: string;
    year?: string;
}

const buildOfflineFailure = (doi: string, message: string): DoiValidationResult => ({
    valid: false,
    relevant: false,
    normalizedDoi: normalizeDoi(doi) || undefined,
    error: message,
});

/**
 * Validate a DOI authoritatively via doi.org (through the editor backend).
 * Returns the same shape on both happy paths and resolution errors so
 * callers never have to try/catch — `result.valid === false` already
 * conveys failure.
 *
 * Takes the gateway URL and auth token as plain parameters (rather than
 * importing getters from extension.ts) to avoid a circular import —
 * extension.ts imports citationInsertion.ts, which imports this module.
 */
export async function validateDoiOnline(
    gatewayUrl: string,
    authToken: string | undefined,
    input: DoiValidationInput
): Promise<DoiValidationResult> {
    const norm = normalizeDoi(input.doi);

    if (!norm) {
        return buildOfflineFailure('', 'DOI is empty.');
    }
    if (!isValidDoiFormat(norm)) {
        return buildOfflineFailure(norm, 'DOI is not in the expected 10.xxxx/yyy form.');
    }

    try {
        const url = `${gatewayUrl}/api/citations/validate-doi`;
        const params: Record<string, string> = { doi: norm };
        if (input.title?.trim()) params.title = input.title.trim();
        if (input.publicationTitle?.trim()) params.publicationTitle = input.publicationTitle.trim();
        if (input.year?.trim()) params.year = input.year.trim();

        const response = await axios.get(url, {
            params,
            headers: authToken ? { Authorization: `Bearer ${authToken}` } : {},
            timeout: 15000,
        });
        const data = response.data || {};
        return {
            valid: !!data.valid,
            relevant: !!data.relevant,
            normalizedDoi: data.normalizedDoi || norm,
            resolvedTitle: data.resolvedTitle,
            resolvedPublicationTitle: data.resolvedPublicationTitle,
            resolvedYear: data.resolvedYear,
            error: data.error,
        };
    } catch (error) {
        const detail = axios.isAxiosError(error)
            ? error.response?.data?.error || error.message
            : error instanceof Error ? error.message : String(error);
        console.error('[DoiValidation] Failed to validate DOI:', detail);
        return buildOfflineFailure(norm, `Unable to validate DOI right now (${detail}).`);
    }
}

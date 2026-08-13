

import axios from 'axios';
import { isValidDoiFormat, normalizeDoi } from '../utils/doi';

export interface DoiValidationResult {

    valid: boolean;

    relevant: boolean;

    normalizedDoi?: string;
    resolvedTitle?: string;
    resolvedPublicationTitle?: string;
    resolvedYear?: string;

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
        if (input.title?.trim()) {params.title = input.title.trim();}
        if (input.publicationTitle?.trim()) {params.publicationTitle = input.publicationTitle.trim();}
        if (input.year?.trim()) {params.year = input.year.trim();}

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

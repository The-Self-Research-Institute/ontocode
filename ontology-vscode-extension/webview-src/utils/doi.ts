// Lightweight DOI normalization and format validation for the webview
export const normalizeDoi = (raw: string): string => {
    if (!raw) return '';
    let s = raw.trim();
    // Remove surrounding angle brackets, parentheses, braces, quotes
    s = s.replace(/^\s*[<\("'\[]+/, '');
    s = s.replace(/[>\)"'\]\}]+\s*$/, '');
    // If full DOI URL, strip common prefixes
    s = s.replace(/^https?:\/\/(dx\.)?doi\.org\//i, '');
    s = s.replace(/^doi:/i, '');
    try {
        s = decodeURIComponent(s);
    } catch (_) {
        // ignore decode errors
    }
    // Trim trailing punctuation characters commonly appended
    s = s.replace(/[.,;:\)\]\}]+$/g, '');
    return s;
};

// Basic DOI format check (fast, offline).
// Use a permissive regex: registrant element can vary in length and suffix may contain many non-space chars.
const DOI_REGEX = /^10\.\d+\/\S+$/i;

export const isValidDoiFormat = (raw: string): boolean => {
    const s = normalizeDoi(raw);
    if (!s) return false;
    return DOI_REGEX.test(s);
};

export default {
    normalizeDoi,
    isValidDoiFormat,
};

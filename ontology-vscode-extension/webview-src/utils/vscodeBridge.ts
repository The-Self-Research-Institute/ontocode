/**
 * vscodeBridge.ts
 *
 * A drop-in replacement for the VS Code `window.vscode.postMessage` API.
 * When the React app runs outside VS Code Desktop (browser, Electron without
 * a preload bridge, or VS Code Web), this shim ensures every `postMessage`
 * the app fires still works without breaking.
 *
 * The real VS Code extension handles 32 inbound message types.  
 * This bridge covers ALL 27 types the webview actually sends:
 *
 *  Auth:         requestAuthToken, saveAuthToken, logout
 *  Lifecycle:    webviewReady, fileLoaded, setApiBaseUrl
 *  File I/O:     downloadOntology, downloadFile, downloadCurrentOntology,
 *                openLocalFile, importLocalFile
 *  Upload:       uploadOntology, uploadFileToProject, uploadOntologyContent
 *  API proxy:    apiGet, apiPost, apiPut, apiPatch, apiDelete, proxyRequest
 *  Collab:       requestCollaborationStatus, cursorMoved, broadcastCursor
 *  Notifications: showNotification, error, notification
 *  Citations:    requestZoteroLibrary, removeCitationFromGraphDB,
 *                insertCitation, insertManualCitation, insertCitationToGraphDB
 *  Misc:         showSubscriptionPlans, duplicateFilePromptResponse,
 *                getQueueStatus
 */

import apiClient from '../services/apiClient';
import { notificationService } from '../services/notificationService';
import { openOntologyFile, fileContentToBase64 } from './fileAccess';
import { sci2CodeBrowserService } from '../services/sci2CodeBrowserService';

// ── Helper: dispatch a synthetic MessageEvent so listener code sees it ──────
function postToSelf(data: Record<string, any>) {
    window.dispatchEvent(new MessageEvent('message', { data }));
}

// ── Helper: Blob download (shared by downloadOntology & downloadFile) ───────
function triggerBlobDownload(content: string | Blob, filename: string) {
    const blob = content instanceof Blob
        ? content
        : new Blob([content], { type: 'application/octet-stream' });
    const url = URL.createObjectURL(blob);
    const a = document.createElement('a');
    a.href = url;
    a.download = filename;
    document.body.appendChild(a);
    a.click();
    document.body.removeChild(a);
    URL.revokeObjectURL(url);
}

// ── Helper: Inject missing namespace declarations (including custom) ────────
function injectDynamicNamespaces(content: string): string {
    if (!content.includes('<rdf:RDF')) return content;

    // Well-known prefix → namespace URI (synced with OWLFormatConverter.java)
    const knownNs: Record<string, string> = {
        rdf: 'http://www.w3.org/1999/02/22-rdf-syntax-ns#',
        rdfs: 'http://www.w3.org/2000/01/rdf-schema#',
        owl: 'http://www.w3.org/2002/07/owl#',
        xsd: 'http://www.w3.org/2001/XMLSchema#',
        dc: 'http://purl.org/dc/elements/1.1/',
        dcterms: 'http://purl.org/dc/terms/',
        terms: 'http://purl.org/dc/terms/',
        bibo: 'http://purl.org/ontology/bibo/',
        foaf: 'http://xmlns.com/foaf/0.1/',
        skos: 'http://www.w3.org/2004/02/skos/core#',
        prov: 'http://www.w3.org/ns/prov#',
        schema: 'http://schema.org/',
        vann: 'http://purl.org/vocab/vann/',
        cc: 'http://creativecommons.org/ns#',
        doap: 'http://usefulinc.com/ns/doap#',
        obo: 'http://purl.obolibrary.org/obo/',
        oboInOwl: 'http://www.geneontology.org/formats/oboInOwl#',
        swrl: 'http://www.w3.org/2003/11/swrl#',
        swrlb: 'http://www.w3.org/2003/11/swrlb#',
        sio: 'http://semanticscience.org/resource/',
        sh: 'http://www.w3.org/ns/shacl#',
        dcat: 'http://www.w3.org/ns/dcat#',
        void: 'http://rdfs.org/ns/void#',
        org: 'http://www.w3.org/ns/org#',
        time: 'http://www.w3.org/2006/time#',
        geo: 'http://www.opengis.net/ont/geosparql#',
        ssn: 'http://www.w3.org/ns/ssn/',
        sosa: 'http://www.w3.org/ns/sosa/',
        faldo: 'http://biohackathon.org/resource/faldo#',
    };

    // Scan for all used prefixes
    const usedPrefixes = new Set<string>();
    const usedRegex = /(?:<|\s)([a-zA-Z][a-zA-Z0-9_-]*):[a-zA-Z]/g;
    let m;
    while ((m = usedRegex.exec(content)) !== null) {
        const p = m[1];
        if (p !== 'xmlns' && p !== 'xml') usedPrefixes.add(p);
    }

    // Find undeclared prefixes
    const toInject: Array<{ prefix: string; uri: string }> = [];
    const unresolved: string[] = [];

    // Extract document context for dynamic resolution
    const xmlBaseMatch = content.match(/xml:base\s*=\s*"([^"]+)"/);
    const ontologyMatch = content.match(/<owl:Ontology\s+rdf:about\s*=\s*"([^"]+)"/);
    const defaultNsMatch = content.match(/<rdf:RDF[^>]*\sxmlns\s*=\s*"([^"]+)"/);
    const xmlBase = xmlBaseMatch?.[1];
    const ontologyIri = ontologyMatch?.[1];
    const defaultNs = defaultNsMatch?.[1];

    for (const prefix of usedPrefixes) {
        if (content.includes(`xmlns:${prefix}=`)) continue; // already declared

        if (knownNs[prefix]) {
            toInject.push({ prefix, uri: knownNs[prefix] });
            continue;
        }

        // Dynamic resolution for custom prefixes
        let resolvedUri: string | null = null;

        // Strategy A: Match prefix:LocalName against full URIs
        const lnRegex = new RegExp(`(?:<|\\s)${prefix.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')}:([a-zA-Z][a-zA-Z0-9_.-]*)`, 'g');
        const localNames: string[] = [];
        let lnMatch;
        while ((lnMatch = lnRegex.exec(content)) !== null) {
            if (!localNames.includes(lnMatch[1])) localNames.push(lnMatch[1]);
        }
        for (const localName of localNames) {
            const escaped = localName.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
            const uriRegex = new RegExp(`(?:rdf:about|rdf:resource|rdf:datatype)\\s*=\\s*"([^"]+[#/])${escaped}"`, 'i');
            const uriMatch = content.match(uriRegex);
            if (uriMatch) {
                resolvedUri = uriMatch[1];
                break;
            }
        }

        // Strategy B: Derive from xml:base / ontology IRI / default namespace
        if (!resolvedUri) {
            const base = xmlBase || ontologyIri || defaultNs;
            if (base) {
                resolvedUri = base.endsWith('#') || base.endsWith('/') ? base : base + '#';
            }
        }

        if (resolvedUri) {
            toInject.push({ prefix, uri: resolvedUri });
            console.log(`[BrowserBridge] Dynamically resolved prefix '${prefix}' → '${resolvedUri}'`);
        } else {
            unresolved.push(prefix);
        }
    }

    if (unresolved.length > 0) {
        console.warn(`[BrowserBridge] Unresolved namespace prefixes (may cause parse errors): ${unresolved.join(', ')}`);
    }

    if (toInject.length === 0) return content;

    console.log(`[BrowserBridge] Injecting ${toInject.length} namespace declarations: ${toInject.map(t => t.prefix).join(', ')}`);

    const rdfTagStart = content.indexOf('<rdf:RDF');
    const rdfTagEnd = content.indexOf('>', rdfTagStart);
    if (rdfTagStart < 0 || rdfTagEnd < 0) return content;

    const selfClosing = content[rdfTagEnd - 1] === '/';
    const insertPos = selfClosing ? rdfTagEnd - 1 : rdfTagEnd;

    const injection = toInject
        .map(({ prefix, uri }) => `\n         xmlns:${prefix}="${uri}"`)
        .join('');

    return content.substring(0, insertPos) + injection + content.substring(insertPos);
}

// ── The bridge itself ───────────────────────────────────────────────────────
function handleBrowserMessage(message: any) {
    switch (message.type) {

        // ──────── Auth ──────────────────────────────────────────────────────

        case 'requestAuthToken': {
            const token = localStorage.getItem('authToken');
            postToSelf({ type: 'storedAuthToken', token: token || null });
            break;
        }

        case 'saveAuthToken': {
            if (message.token) {
                localStorage.setItem('authToken', message.token);
            }
            break;
        }

        case 'logout': {
            localStorage.removeItem('authToken');
            postToSelf({ type: 'loggedOut' });
            break;
        }

        // ──────── Lifecycle ─────────────────────────────────────────────────

        case 'webviewReady': {
            // In browser the app is already loaded; just send stored token
            const token = localStorage.getItem('authToken');
            postToSelf({ type: 'storedAuthToken', token: token || null });
            break;
        }

        case 'fileLoaded': {
            // Extension uses this to initialise collaboration WebSocket.
            // In browser mode, collaboration goes through direct WS — no-op.
            console.log('[BrowserBridge] fileLoaded:', message.projectId);
            break;
        }

        case 'setApiBaseUrl': {
            if (message.deploymentType) {
                localStorage.setItem('deploymentType', message.deploymentType);
            }
            break;
        }

        // ──────── Download ──────────────────────────────────────────────────

        case 'downloadOntology': {
            // Fetch the ontology export from the API and trigger download
            (async () => {
                try {
                    const response = await apiClient.get(message.url, undefined, { responseType: 'blob' as any });
                    triggerBlobDownload(response, message.filename);
                } catch (err) {
                    console.error('[BrowserBridge] downloadOntology failed:', err);
                    notificationService.error('Download Failed', 'Could not download ontology file');
                }
            })();
            break;
        }

        case 'downloadFile': {
            triggerBlobDownload(message.content, message.filename);
            break;
        }

        case 'downloadCurrentOntology': {
            console.warn('[BrowserBridge] downloadCurrentOntology — not implemented in browser mode');
            break;
        }

        // ──────── Local file open ───────────────────────────────────────────

        case 'openLocalFile': {
            (async () => {
                const fileData = await openOntologyFile();
                if (!fileData) return;

                if (message.projectId) {
                    // Project context is known — hand off directly to the uploadOntology
                    // handler so the full upload + GraphDB polling flow runs immediately.
                    // (Without this, projectId is dropped and the upload never starts when
                    // the user is already inside a project dashboard.)
                    handleBrowserMessage({
                        type: 'uploadOntology',
                        projectId: message.projectId,
                        fileName: fileData.fileName,
                        fileContent: fileContentToBase64(fileData.fileContent),
                        importMode: message.importMode,
                        partition: message.partition,
                    });
                } else {
                    // No project context yet — store as pending so the user can pick
                    // a project and the upload will trigger via handleProjectSelected.
                    postToSelf({
                        type: 'pendingFileUpload',
                        fileName: fileData.fileName,
                        fileContent: fileContentToBase64(fileData.fileContent),
                        fileSize: fileData.fileSize,
                        importMode: message.importMode,
                        partition: message.partition,
                    });
                }
            })();
            break;
        }

        case 'importLocalFile': {
            // In the extension this reads from the local FS.
            // In browser we open a file picker instead.
            (async () => {
                const fileData = await openOntologyFile();
                if (fileData) {
                    postToSelf({
                        type: 'pendingFileUpload',
                        fileName: fileData.fileName,
                        fileContent: fileContentToBase64(fileData.fileContent),
                        fileSize: fileData.fileSize,
                    });
                }
            })();
            break;
        }

        // ──────── Upload ────────────────────────────────────────────────────

        case 'uploadOntology': {
            (async () => {
                // Hoist so the catch block can reference it for error reporting
                const uploadProjectId = message.projectId
                    || (message.fileName || '').replace(/\.(owl|rdf|ttl|n3|nt|jsonld)$/i, '');

                // ── Notify Dashboard to open progress dialog immediately ──
                // (mirrors what the VS Code extension sends right after file selection)
                postToSelf({ type: 'showLoading', projectId: uploadProjectId });

                try {
                    const token = localStorage.getItem('authToken');
                    const deploymentType = localStorage.getItem('deploymentType') || 'cloud';
                    const config = (window as any).__ONTOCODE_CONFIG__;
                    const baseUrl = deploymentType === 'self-hosted'
                        ? (config?.SELF_HOSTED_GATEWAY_URL || 'http://localhost:80')
                        : (config?.CLOUD_GATEWAY_URL || 'https://ontocodeapi.selfresearch.org');

                    // Decode base64 → text for namespace injection
                    const byteString = atob(message.fileContent);
                    const bytes = new Uint8Array(byteString.length);
                    for (let i = 0; i < byteString.length; i++) bytes[i] = byteString.charCodeAt(i);
                    let fileText = new TextDecoder().decode(bytes);

                    // ── Dynamic namespace injection for RDF/XML files ──
                    if (fileText.includes('<rdf:RDF')) {
                        fileText = injectDynamicNamespaces(fileText);
                    }

                    const blob = new Blob([fileText], { type: 'application/rdf+xml' });
                    const formData = new FormData();
                    formData.append('file', blob, message.fileName);

                    const query = new URLSearchParams();
                    if (message.ownerEmail) query.set('ownerEmail', message.ownerEmail);
                    if (message.importMode) query.set('importMode', message.importMode);
                    if (message.partition) query.set('partition', message.partition);
                    if (message.skipDuplicateCheck) query.set('action', 'replace');

                    const resp = await fetch(
                        `${baseUrl}/api/ontology/upload/${encodeURIComponent(uploadProjectId)}?${query.toString()}`,
                        {
                            method: 'POST',
                            headers: token ? { Authorization: `Bearer ${token}` } : {},
                            body: formData,
                        }
                    );

                    const responseText = await resp.text();
                    let responseData: any = {};
                    try { responseData = JSON.parse(responseText); } catch { responseData = { error: responseText }; }

                    if (resp.ok && responseData.success !== false) {
                        // Backend may return a different projectId on replace
                        const actualProjectId = responseData.projectId || uploadProjectId;
                        const actualFilename = responseData.filename || message.fileName;
                        console.log('[BrowserBridge] uploadOntology accepted, actualProjectId:', actualProjectId, 'polling for completion...');

                        // If the server assigned a different projectId, update the Dashboard
                        if (actualProjectId !== uploadProjectId) {
                            postToSelf({ type: 'showLoading', projectId: actualProjectId });
                        }

                        // Poll /api/ontology/status until COMPLETED (GraphDB processes async)
                        const maxAttempts = 60;
                        const getDelay = (att: number) => {
                            if (att <= 3) return 2000;
                            if (att <= 6) return 3000;
                            if (att <= 10) return 5000;
                            return 10000;
                        };

                        const pollStatus = async () => {
                            for (let attempt = 1; attempt <= maxAttempts; attempt++) {
                                await new Promise(r => setTimeout(r, getDelay(attempt)));
                                try {
                                    const statusResp = await fetch(
                                        `${baseUrl}/api/ontology/status/${encodeURIComponent(actualProjectId)}`,
                                        { headers: token ? { Authorization: `Bearer ${token}` } : {} }
                                    );
                                    const statusData = await statusResp.json().catch(() => ({}));
                                    const payload = statusData?.data || statusData;
                                    const status = payload?.status;
                                    console.log(`[BrowserBridge] Status poll #${attempt}: ${status}`);

                                    if (status === 'COMPLETED') {
                                        // Mirror extension: send fileReady first (triggers fetchData in Dashboard)
                                        postToSelf({ type: 'fileReady', projectId: actualProjectId });
                                        // Also send importStatusUpdate for status tracking UI
                                        postToSelf({
                                            type: 'importStatusUpdate',
                                            status: {
                                                type: 'IMPORT_COMPLETED',
                                                projectId: actualProjectId,
                                                status: 'COMPLETED',
                                                progress: 100,
                                                filename: actualFilename,
                                            },
                                        });
                                        return;
                                    }
                                    if (status === 'FAILED' || status === 'ERROR') {
                                        postToSelf({ type: 'importFailed', projectId: actualProjectId, error: payload?.statusMessage || 'Import failed in GraphDB' });
                                        return;
                                    }

                                    // Send progress update
                                    if (payload?.statusMessage) {
                                        postToSelf({
                                            type: 'importStatusUpdate',
                                            status: {
                                                type: 'IMPORT_PROGRESS',
                                                projectId: actualProjectId,
                                                status: 'PROCESSING',
                                                progress: Math.min(95, Math.floor((attempt / maxAttempts) * 100)),
                                                metadata: { message: payload.statusMessage },
                                            },
                                        });
                                    }
                                } catch (pollErr) {
                                    console.warn(`[BrowserBridge] Status poll #${attempt} error:`, pollErr);
                                }
                            }
                            // Timeout
                            postToSelf({ type: 'importFailed', projectId: actualProjectId, error: 'Import timed out waiting for GraphDB processing' });
                        };
                        pollStatus();
                    } else if (responseData.isDuplicate) {
                        console.log('[BrowserBridge] uploadOntology duplicate detected');
                        postToSelf({
                            type: 'importFailed',
                            projectId: responseData.projectId || uploadProjectId,
                            error: responseData.error,
                            isDuplicate: true,
                            filename: responseData.filename || message.fileName,
                        });
                    } else {
                        console.error('[BrowserBridge] uploadOntology failed:', responseData.error || responseText);
                        postToSelf({
                            type: 'importFailed',
                            projectId: uploadProjectId,
                            error: responseData.error || responseText,
                        });
                    }
                } catch (err: any) {
                    console.error('[BrowserBridge] uploadOntology error:', err);
                    postToSelf({ type: 'importFailed', projectId: uploadProjectId, error: err?.message || 'Upload failed' });
                }
            })();
            break;
        }

        case 'uploadFileToProject': {
            (async () => {
                try {
                    await apiClient.post(`/api/projects/${message.projectId}/files`, {
                        fileName: message.fileName,
                        fileData: `data:application/rdf+xml;base64,${
                            /^[A-Za-z0-9+/=]+$/.test(message.fileContent)
                                ? message.fileContent
                                : fileContentToBase64(message.fileContent)
                        }`,
                        fileSize: message.fileSize,
                        fileType: 'owl',
                    });
                    console.log('[BrowserBridge] uploadFileToProject success');
                } catch (err: any) {
                    console.error('[BrowserBridge] uploadFileToProject error:', err);
                    notificationService.error('Upload Failed', err?.message || 'File upload failed');
                }
            })();
            break;
        }

        case 'uploadOntologyContent': {
            (async () => {
                try {
                    await apiClient.post(`/api/ontology/${message.projectId}/content`, {
                        content: message.content,
                        format: message.format,
                    });
                    console.log('[BrowserBridge] uploadOntologyContent success');
                    postToSelf({ type: 'uploadOntologyContentDone', success: true, projectId: message.projectId });
                } catch (err: any) {
                    console.error('[BrowserBridge] uploadOntologyContent error:', err);
                    postToSelf({ type: 'uploadOntologyContentDone', success: false, projectId: message.projectId });
                }
            })();
            break;
        }

        // ──────── Collaboration ─────────────────────────────────────────────

        case 'requestCollaborationStatus': {
            // In browser mode we talk to the backend over HTTP.
            // Report connected so the UI doesn't show "Offline".
            postToSelf({ type: 'collaborationStatus', connected: true });
            break;
        }

        case 'cursorMoved':
        case 'broadcastCursor': {
            // Requires WebSocket room — no-op in standalone browser mode.
            break;
        }

        // ──────── Notifications ─────────────────────────────────────────────

        case 'showNotification': {
            const n = message.notification || {};
            notificationService.notify({
                title: n.title || 'Notification',
                message: n.message || '',
                type: n.type || 'info',
            });
            break;
        }

        case 'error': {
            notificationService.error('Error', message.value || 'Unknown error');
            break;
        }

        case 'notification': {
            notificationService.notify({
                title: message.level || 'info',
                message: message.message || '',
                type: (message.level as any) || 'info',
            });
            break;
        }

        // ──────── Citations / Zotero (Sci2Code browser integration) ────────

        case 'requestZoteroLibrary': {
            (async () => {
                try {
                    if (!sci2CodeBrowserService.isConfigured()) {
                        postToSelf({
                            type: 'zoteroLibraryError',
                            error: 'ZOTERO_NOT_CONFIGURED'
                        });
                        return;
                    }
                    const items = await sci2CodeBrowserService.fetchLibrary();
                    postToSelf({ type: 'zoteroLibraryData', items: items || [] });
                } catch (err: any) {
                    postToSelf({ type: 'zoteroLibraryError', error: err?.message || 'Zotero unavailable' });
                }
            })();
            break;
        }

        case 'insertCitation': {
            // Zotero citation: fetch metadata, format locally, then insert to backend
            (async () => {
                try {
                    const citationKey = message.citationKey;
                    const format = message.format || 'rdfxml';
                    const metadata = await sci2CodeBrowserService.getCitationMetadata(citationKey);
                    if (!metadata) throw new Error('Citation metadata not found');

                    const formattedCitation = sci2CodeBrowserService.formatCitationForOntology(metadata, format);

                    await apiClient.post(`/api/citations/${message.projectId}/insert`, {
                        citation: formattedCitation,
                        format,
                        metadata,
                        lineNumber: message.lineNumber || 0,
                    });
                    postToSelf({
                        type: 'citationFormatted',
                        citation: formattedCitation,
                        metadata,
                        projectId: message.projectId,
                    });
                    notificationService.success('Citation Added', `Inserted: ${metadata.title}`);
                } catch (err: any) {
                    console.error('[BrowserBridge] insertCitation error:', err);
                    notificationService.error('Citation Failed', err?.message || 'Failed to insert citation');
                }
            })();
            break;
        }

        case 'insertManualCitation': {
            // Manual citation: user provides metadata, format locally, insert to backend
            (async () => {
                try {
                    const item = {
                        key: `manual_${Date.now()}`,
                        title: message.citation?.title || message.metadata?.title || '',
                        creators: message.citation?.authors
                            ? message.citation.authors.split(',').map((a: string) => {
                                const parts = a.trim().split(' ');
                                return { firstName: parts.slice(0, -1).join(' '), lastName: parts[parts.length - 1] || '', creatorType: 'author' };
                              })
                            : message.metadata?.creators || [],
                        date: message.citation?.year || message.metadata?.date || '',
                        doi: message.citation?.doi || message.metadata?.doi,
                        url: message.citation?.url || message.metadata?.url,
                        itemType: message.citation?.itemType || message.metadata?.itemType || 'journalArticle',
                        publicationTitle: message.citation?.publicationTitle || message.metadata?.publicationTitle,
                    };
                    const format = message.format || 'rdfxml';
                    const formattedCitation = sci2CodeBrowserService.formatCitationForOntology(item, format);

                    await apiClient.post(`/api/citations/${message.projectId}/insert`, {
                        citation: formattedCitation,
                        format,
                        metadata: item,
                        lineNumber: message.lineNumber || 0,
                    });
                    postToSelf({
                        type: 'citationFormatted',
                        citation: formattedCitation,
                        metadata: item,
                        projectId: message.projectId,
                    });
                    notificationService.success('Citation Added', `Inserted: ${item.title}`);
                } catch (err: any) {
                    console.error('[BrowserBridge] insertManualCitation error:', err);
                    notificationService.error('Citation Failed', err?.message || 'Failed to insert citation');
                }
            })();
            break;
        }

        case 'insertCitationToGraphDB': {
            // Direct citation insertion (already formatted content)
            (async () => {
                try {
                    await apiClient.post(`/api/citations/${message.projectId}/insert`, {
                        citation: message.citation,
                        format: message.format || 'rdfxml',
                        metadata: message.metadata,
                        lineNumber: message.lineNumber || 0,
                    });
                    postToSelf({
                        type: 'citationFormatted',
                        citation: message.citation,
                        metadata: message.metadata,
                        projectId: message.projectId,
                    });
                    notificationService.success('Citation Added', 'Citation inserted successfully');
                } catch (err: any) {
                    console.error('[BrowserBridge] insertCitationToGraphDB error:', err);
                    notificationService.error('Citation Failed', err?.message || 'Failed to insert citation');
                }
            })();
            break;
        }

        case 'removeCitationFromGraphDB': {
            (async () => {
                try {
                    const encodedUri = encodeURIComponent(message.citationUri);
                    await apiClient.delete(`/api/citations/${message.projectId}/${encodedUri}`);
                    console.log('[BrowserBridge] removeCitationFromGraphDB success');
                } catch (err: any) {
                    console.error('[BrowserBridge] removeCitationFromGraphDB error:', err);
                }
            })();
            break;
        }

        // ──────── Misc ──────────────────────────────────────────────────────

        case 'showSubscriptionPlans': {
            postToSelf({ type: 'showSubscriptionPlans' });
            break;
        }

        case 'duplicateFilePromptResponse': {
            // This is a response from the webview to the extension's prompt.
            // In browser mode the duplicate-check flow is handled inline;
            // this should not trigger, but log it just in case.
            console.log('[BrowserBridge] duplicateFilePromptResponse (no-op in browser):', message);
            break;
        }

        case 'getQueueStatus': {
            // No background queue in browser mode; signal completed
            postToSelf({
                type: 'queueStatusUpdate',
                status: { projectId: message.projectId, status: 'COMPLETED', position: 0 },
            });
            break;
        }

        // ──────── API Proxy (apiGet/apiPost/apiPut/apiPatch/apiDelete) ──────
        // The ApiClient already handles browser mode via direct axios.
        // These cases only fire if something calls window.vscode.postMessage
        // with an API type directly (shouldn't happen in browser mode).

        case 'apiGet':
        case 'apiPost':
        case 'apiPut':
        case 'apiPatch':
        case 'apiDelete':
        case 'proxyRequest': {
            console.warn('[BrowserBridge] API proxy message received in browser — should use apiClient instead:', message.type);
            break;
        }

        default:
            console.log('[BrowserBridge] Unhandled message type:', message.type);
    }
}

/**
 * Install the browser bridge.
 *
 * Call this ONCE at startup (e.g. in index.tsx) BEFORE any component mounts.
 * It sets `window.vscode` so that ALL existing code paths
 * (`if (window.vscode) { window.vscode.postMessage(...) }`) work unchanged.
 *
 * In VS Code Desktop `window.vscode` is already provided by the webview;
 * this function does nothing in that case.
 */
export function installBrowserBridge() {
    // Already running inside VS Code Desktop / a preload that set window.vscode
    if (window.vscode && !(window as any).__ONTOCODE_BROWSER_BRIDGE__) {
        console.log('[BrowserBridge] VS Code API detected — bridge NOT installed');
        return;
    }

    console.log('[BrowserBridge] Installing browser-mode bridge');
    (window as any).__ONTOCODE_BROWSER_BRIDGE__ = true;

    (window as any).vscode = {
        postMessage: (msg: any) => {
            // async so callers don't block
            Promise.resolve().then(() => handleBrowserMessage(msg));
        },
    };
}

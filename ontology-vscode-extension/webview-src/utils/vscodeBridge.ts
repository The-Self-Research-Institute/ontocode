/**
 * vscodeBridge.ts
 *
 * A drop-in replacement for the VS Code `window.vscode.postMessage` API.
 * When the React app runs outside VS Code Desktop (browser, Electron without
 * a preload bridge, or VS Code Web), this shim ensures every `postMessage`
 * the app fires still works without breaking.
 *
 * The real VS Code extension handles 33 inbound message types.  
 * This bridge covers ALL 28 types the webview actually sends:
 *
 *  Auth:         requestAuthToken, saveAuthToken, logout
 *  Lifecycle:    webviewReady, fileLoaded, setApiBaseUrl
 *  File I/O:     downloadOntology, downloadFile, downloadCurrentOntology,
 *                openLocalFile, createNewFile, importLocalFile
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
import { getGatewayUrl } from '../config/deploymentConfig';
import { exportOntologyAsBlob } from '../services/exportService';
import { uploadFormDataWithProgress, uploadBlobInChunks } from './uploadWithProgress';

let browserZoteroLibrarySessionCounter = 0;

// ── Helper: dispatch a synthetic MessageEvent so listener code sees it ──────
function postToSelf(data: Record<string, any>) {
    window.dispatchEvent(new MessageEvent('message', { data }));
}

// ── Helper: extract email from JWT auth token ───────────────────────────────
function getOwnerEmailFromToken(): string | undefined {
    try {
        const token = localStorage.getItem('authToken');
        if (!token) return undefined;
        const parts = token.split('.');
        if (parts.length !== 3) return undefined;
        const payload = JSON.parse(atob(parts[1].replace(/-/g, '+').replace(/_/g, '/')));
        return payload.email || undefined;
    } catch {
        return undefined;
    }
}

/** JWT workspaceId — must be duplicated on upload URL query for FREE-plan owner checks (see extension upload URL). */
function getWorkspaceIdFromToken(): string | undefined {
    try {
        const token = localStorage.getItem('authToken');
        if (!token) return undefined;
        const parts = token.split('.');
        if (parts.length !== 3) return undefined;
        const payload = JSON.parse(atob(parts[1].replace(/-/g, '+').replace(/_/g, '/')));
        const ws = payload.workspaceId;
        return typeof ws === 'string' && ws.trim() ? ws.trim() : undefined;
    } catch {
        return undefined;
    }
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

    // Scan for prefixes used in XML element/attribute names only (not in text content).
    // In RDF/XML, namespace prefixes appear as:
    //   1. Element names: <prefix:LocalName ...>  or  </prefix:LocalName>
    //   2. Attribute names: prefix:attr="..."
    // We must NOT match "prefix:text" inside string values like rdfs:label.
    const usedPrefixes = new Set<string>();

    // Match opening/closing element names: <prefix:Name or </prefix:Name
    const elementPrefixRegex = /<\/?([a-zA-Z][a-zA-Z0-9_-]*):[a-zA-Z]/g;
    let m;
    while ((m = elementPrefixRegex.exec(content)) !== null) {
        const p = m[1];
        if (p !== 'xmlns' && p !== 'xml') usedPrefixes.add(p);
    }
    // Match attribute names: whitespace followed by prefix:attr= (with = to ensure it's an attribute, not text)
    const attrPrefixRegex = /\s([a-zA-Z][a-zA-Z0-9_-]*):[a-zA-Z][a-zA-Z0-9_-]*\s*=/g;
    while ((m = attrPrefixRegex.exec(content)) !== null) {
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
            // Submit the export as a background job and poll until ready, rather than one
            // long-blocking request — a large ontology's export can take longer than axios's
            // client-side timeout even though the backend supports far longer. Large exports
            // stream to disk when the File System Access API is available (avoids ~200MB+
            // Blob OOM in the tab). See exportService.ts / OntologyExportJobService.java.
            //
            // The submit-poll-download cycle can take minutes for large ontologies, so the
            // caller (Dashboard's export button) keeps its spinner alive until it sees one of
            // these two messages come back, instead of clearing it right after this fire-and-
            // forget postMessage resolves.
            (async () => {
                try {
                    await exportOntologyAsBlob(
                        getGatewayUrl(),
                        message.projectId,
                        message.format,
                        message.filename,
                    );
                    window.dispatchEvent(new MessageEvent('message', {
                        data: { type: 'downloadOntologyComplete', requestId: message.requestId },
                    }));
                } catch (err) {
                    console.error('[BrowserBridge] downloadOntology failed:', err);
                    const msg = err instanceof Error ? err.message : 'Could not download ontology file';
                    window.dispatchEvent(new MessageEvent('message', {
                        data: { type: 'downloadOntologyFailed', requestId: message.requestId, error: msg, cancelled: msg.includes('cancelled') },
                    }));
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
                    // Workspace flow: save to project library (GridFS) first, then let
                    // handleLoadProjectFile handle the GraphDB import via fileReady.
                    try {
                        const fileContent = fileData.isBase64 ? fileData.fileContent : fileContentToBase64(fileData.fileContent);
                        let contentStr = fileContent;
                        if (/^[A-Za-z0-9+/=]+$/.test(contentStr)) {
                            const binaryStr = atob(contentStr);
                            const bytes = new Uint8Array(binaryStr.length);
                            for (let i = 0; i < binaryStr.length; i++) bytes[i] = binaryStr.charCodeAt(i);
                            contentStr = new TextDecoder().decode(bytes);
                        }
                        const fileBlob = new Blob([contentStr], { type: 'application/rdf+xml' });
                        const formData = new FormData();
                        formData.append('file', fileBlob, fileData.fileName);
                        formData.append('fileName', fileData.fileName);
                        formData.append('fileType', 'owl');
                        const respData: any = await apiClient.post(`/api/projects/${message.projectId}/files`, formData);
                        const uploadedFileId = respData?.fileId || respData?.id;
                        const uploadedFileName = respData?.filename || fileData.fileName;
                        postToSelf({
                            type: 'fileReady',
                            projectId: message.projectId,
                            uploadedFileId,
                            uploadedFileName,
                        });
                    } catch (err: any) {
                        const errData = err?.data || err?.response?.data;
                        if (err?.status === 413 || err?.response?.status === 413) {
                            const detail = errData?.message || errData?.error || 'Storage limit exceeded. Please upgrade your plan or delete existing files.';
                            notificationService.error('Storage Limit Exceeded', detail);
                        } else {
                            notificationService.error('Upload Failed', errData?.error || err?.message || 'File upload to project failed');
                        }
                    }
                } else {
                    // No project context yet — store as pending so the user can pick
                    // a project and the upload will trigger via handleProjectSelected.
                    postToSelf({
                        type: 'pendingFileUpload',
                        fileName: fileData.fileName,
                        fileContent: fileData.isBase64 ? fileData.fileContent : fileContentToBase64(fileData.fileContent),
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
                        fileContent: fileData.isBase64 ? fileData.fileContent : fileContentToBase64(fileData.fileContent),
                        fileSize: fileData.fileSize,
                    });
                }
            })();
            break;
        }

        case 'createNewFileWithName': {
            (async () => {
                let fileName = message.fileName;

                // Validate file extension
                const validExtensions = ['.owl', '.rdf', '.ttl', '.n3', '.nt', '.jsonld'];
                const hasValidExtension = validExtensions.some(ext => fileName.toLowerCase().endsWith(ext));

                if (!hasValidExtension) {
                    console.error('[BrowserBridge] Invalid file extension:', fileName);
                    alert('File must have a valid ontology extension (.owl, .rdf, .ttl, .n3, .nt, .jsonld)');
                    return;
                }

                // Create minimal empty ontology content with owl:Thing
                // Note: duplicate check is handled server-side during upload
                const ontologyIRI = `http://example.org/ontologies/${fileName.replace(/\.[^/.]+$/, '')}`;
                const emptyOntologyContent = `<?xml version="1.0"?>
<rdf:RDF xmlns="${ontologyIRI}#"
     xml:base="${ontologyIRI}"
     xmlns:owl="http://www.w3.org/2002/07/owl#"
     xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#"
     xmlns:xml="http://www.w3.org/XML/1998/namespace"
     xmlns:xsd="http://www.w3.org/2001/XMLSchema#"
     xmlns:rdfs="http://www.w3.org/2000/01/rdf-schema#">
    <owl:Ontology rdf:about="${ontologyIRI}"/>
    
    <!-- Classes -->
    <owl:Class rdf:about="http://www.w3.org/2002/07/owl#Thing"/>
</rdf:RDF>`;

                const fileContentBase64 = fileContentToBase64(emptyOntologyContent);
                const fileSize = new Blob([emptyOntologyContent]).size;

                console.log(`[BrowserBridge] Creating new file: ${fileName} (${fileSize} bytes)`);

                if (message.projectId) {
                    // Upload directly to project files endpoint using multipart/form-data
                    try {
                        postToSelf({ type: 'showLoading', projectId: message.projectId });
                        const fileBlob = new Blob([emptyOntologyContent], { type: 'application/rdf+xml' });
                        const formData = new FormData();
                        formData.append('file', fileBlob, fileName);
                        formData.append('fileName', fileName);
                        formData.append('fileType', 'owl');
                        const respData: any = await apiClient.post(`/api/projects/${message.projectId}/files`, formData);
                        const uploadedFileId = respData?.fileId || respData?.id;
                        const uploadedFileName = respData?.filename || fileName;
                        console.log(`[BrowserBridge] createNewFileWithName success - fileId: ${uploadedFileId}, fileName: ${uploadedFileName}`);
                        postToSelf({
                            type: 'fileReady',
                            projectId: message.projectId,
                            uploadedFileId,
                            uploadedFileName,
                        });
                    } catch (err: any) {
                        console.error('[BrowserBridge] createNewFileWithName upload error:', err);
                        notificationService.error('Upload Failed', err?.message || 'File creation failed');
                        postToSelf({ type: 'hideLoading', projectId: message.projectId });
                    }
                } else {
                    // No project context — upload directly to GraphDB as standalone ontology
                    const standaloneProjectId = fileName.replace(/\.(owl|rdf|ttl|n3|nt|jsonld)$/i, '');
                    console.log('[BrowserBridge] No project context, uploading as standalone:', standaloneProjectId);

                    // Upload to GraphDB first
                    postToSelf({ type: 'showLoading', projectId: standaloneProjectId });

                    try {
                        const token = localStorage.getItem('authToken');
                        const baseUrl = getGatewayUrl();

                        // Upload file to GraphDB
                        const formData = new FormData();
                        const fileBlob = new Blob([emptyOntologyContent], { type: 'application/rdf+xml' });
                        formData.append('file', fileBlob, fileName);

                        const uploadUrl = `${baseUrl}/api/ontology/upload/${standaloneProjectId}`;
                        const uploadResp = await fetch(uploadUrl, {
                            method: 'POST',
                            headers: token ? { Authorization: `Bearer ${token}` } : {},
                            body: formData,
                        });

                        if (!uploadResp.ok) {
                            throw new Error(`Upload failed: ${uploadResp.statusText}`);
                        }

                        const uploadData = await uploadResp.json();
                        console.log('[BrowserBridge] Standalone file uploaded to GraphDB:', uploadData);

                        // Immediately save to MongoDB to persist the file
                        console.log('[BrowserBridge] Saving to MongoDB for persistence...');
                        const saveResp = await fetch(`${baseUrl}/api/ontology/save/${standaloneProjectId}`, {
                            method: 'POST',
                            headers: token ? { Authorization: `Bearer ${token}` } : {},
                        });

                        if (saveResp.ok) {
                            console.log('[BrowserBridge] ✅ New file saved to MongoDB - will persist after refresh');
                            notificationService.success('File Created', `${fileName} created and saved to database`);
                        } else {
                            console.warn('[BrowserBridge] ⚠️ File uploaded but save failed - may be lost on refresh');
                        }

                        // Notify that file is ready
                        postToSelf({
                            type: 'fileReady',
                            projectId: standaloneProjectId,
                            uploadedFileName: fileName,
                        });
                    } catch (err: any) {
                        console.error('[BrowserBridge] Standalone file creation failed:', err);
                        notificationService.error('Upload Failed', err?.message || 'File creation failed');
                    } finally {
                        postToSelf({ type: 'hideLoading', projectId: standaloneProjectId });
                    }
                }
            })();
            break;
        }

        case 'createNewFile': {
            (async () => {
                let fileName = '';
                let validFileName = false;

                // Validate file extension
                const validExtensions = ['.owl', '.rdf', '.ttl', '.n3', '.nt', '.jsonld'];

                // Loop until user provides valid unique filename or cancels
                while (!validFileName) {
                    // Prompt user for file name
                    fileName = prompt('Enter a name for the new ontology file:', fileName || 'my-ontology.owl');
                    if (!fileName) {
                        console.log('[BrowserBridge] User cancelled new file creation');
                        return;
                    }

                    const trimmedFileName = fileName.trim();
                    if (!trimmedFileName) {
                        alert('File name is required.');
                        continue;
                    }

                    // Validate file extension
                    const hasValidExtension = validExtensions.some(ext => trimmedFileName.toLowerCase().endsWith(ext));

                    if (!hasValidExtension) {
                        alert('File must have a valid ontology extension (.owl, .rdf, .ttl, .n3, .nt, .jsonld)');
                        continue;
                    }

                    fileName = trimmedFileName;

                    // Check for duplicates if in project context
                    if (message.projectId) {
                        const token = localStorage.getItem('authToken');
                        const baseUrl = getGatewayUrl();

                        try {
                            const checkUrl = `${baseUrl}/api/projects/${message.projectId}/files/check?fileName=${encodeURIComponent(fileName)}`;
                            const checkResp = await fetch(checkUrl, {
                                headers: token ? { Authorization: `Bearer ${token}` } : {},
                            });
                            const checkData = await checkResp.json().catch(() => ({}));

                            if (checkData.exists) {
                                const retry = confirm(`A file named "${fileName}" already exists in this project.\n\nClick OK to choose a different name, or Cancel to abort.`);
                                if (!retry) {
                                    console.log('[BrowserBridge] User cancelled after duplicate detected');
                                    return;
                                }
                                continue; // Ask for new name
                            }
                        } catch (checkError) {
                            console.warn('[BrowserBridge] Failed to check for duplicate:', checkError);
                            // Continue with upload if check fails
                        }
                    }

                    validFileName = true;
                }

                // Create minimal empty ontology content with owl:Thing
                const ontologyIRI = `http://example.org/ontologies/${fileName.replace(/\.[^/.]+$/, '')}`;
                const emptyOntologyContent = `<?xml version="1.0"?>
<rdf:RDF xmlns="${ontologyIRI}#"
     xml:base="${ontologyIRI}"
     xmlns:owl="http://www.w3.org/2002/07/owl#"
     xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#"
     xmlns:xml="http://www.w3.org/XML/1998/namespace"
     xmlns:xsd="http://www.w3.org/2001/XMLSchema#"
     xmlns:rdfs="http://www.w3.org/2000/01/rdf-schema#">
    <owl:Ontology rdf:about="${ontologyIRI}"/>
    
    <!-- Classes -->
    <owl:Class rdf:about="http://www.w3.org/2002/07/owl#Thing"/>
</rdf:RDF>`;

                const fileContentBase64 = fileContentToBase64(emptyOntologyContent);
                const fileSize = new Blob([emptyOntologyContent]).size;

                console.log(`[BrowserBridge] Creating new file: ${fileName} (${fileSize} bytes)`);

                if (message.projectId) {
                    // Upload directly to project files endpoint using multipart/form-data
                    try {
                        postToSelf({ type: 'showLoading', projectId: message.projectId });
                        const fileBlob = new Blob([emptyOntologyContent], { type: 'application/rdf+xml' });
                        const formData = new FormData();
                        formData.append('file', fileBlob, fileName);
                        formData.append('fileName', fileName);
                        formData.append('fileType', 'owl');
                        const respData: any = await apiClient.post(`/api/projects/${message.projectId}/files`, formData);
                        const uploadedFileId = respData?.fileId || respData?.id;
                        const uploadedFileName = respData?.filename || fileName;
                        console.log(`[BrowserBridge] createNewFile success - fileId: ${uploadedFileId}, fileName: ${uploadedFileName}`);
                        postToSelf({
                            type: 'fileReady',
                            projectId: message.projectId,
                            uploadedFileId,
                            uploadedFileName,
                        });
                    } catch (err: any) {
                        console.error('[BrowserBridge] createNewFile upload error:', err);
                        notificationService.error('Upload Failed', err?.message || 'File creation failed');
                        postToSelf({ type: 'hideLoading', projectId: message.projectId });
                    }
                } else {
                    // No project context — upload directly to GraphDB as standalone ontology
                    const standaloneProjectId = fileName.replace(/\.(owl|rdf|ttl|n3|nt|jsonld)$/i, '');
                    console.log('[BrowserBridge] No project context, uploading as standalone:', standaloneProjectId);

                    // Upload to GraphDB first
                    postToSelf({ type: 'showLoading', projectId: standaloneProjectId });

                    try {
                        const token = localStorage.getItem('authToken');
                        const baseUrl = getGatewayUrl();

                        // Upload file to GraphDB
                        const formData = new FormData();
                        const fileBlob = new Blob([emptyOntologyContent], { type: 'application/rdf+xml' });
                        formData.append('file', fileBlob, fileName);

                        const uploadUrl = `${baseUrl}/api/ontology/upload/${standaloneProjectId}`;
                        const uploadResp = await fetch(uploadUrl, {
                            method: 'POST',
                            headers: token ? { Authorization: `Bearer ${token}` } : {},
                            body: formData,
                        });

                        if (!uploadResp.ok) {
                            throw new Error(`Upload failed: ${uploadResp.statusText}`);
                        }

                        const uploadData = await uploadResp.json();
                        console.log('[BrowserBridge] Standalone file uploaded to GraphDB:', uploadData);

                        // Immediately save to MongoDB to persist the file
                        console.log('[BrowserBridge] Saving to MongoDB for persistence...');
                        const saveResp = await fetch(`${baseUrl}/api/ontology/save/${standaloneProjectId}`, {
                            method: 'POST',
                            headers: token ? { Authorization: `Bearer ${token}` } : {},
                        });

                        if (saveResp.ok) {
                            console.log('[BrowserBridge] ✅ New file saved to MongoDB - will persist after refresh');
                            notificationService.success('File Created', `${fileName} created and saved to database`);
                        } else {
                            console.warn('[BrowserBridge] ⚠️ File uploaded but save failed - may be lost on refresh');
                        }

                        // Notify that file is ready
                        postToSelf({
                            type: 'fileReady',
                            projectId: standaloneProjectId,
                            uploadedFileName: fileName,
                        });
                    } catch (err: any) {
                        console.error('[BrowserBridge] Standalone file creation failed:', err);
                        notificationService.error('Upload Failed', err?.message || 'File creation failed');
                    } finally {
                        postToSelf({ type: 'hideLoading', projectId: standaloneProjectId });
                    }
                }
            })();
            break;
        }

        // ──────── Upload ────────────────────────────────────────────────────

        case 'uploadOntology': {
            (async () => {
                const uploadPipelineStart = Date.now();
                console.log(`[BrowserBridge] [PERF] ⏱️ Upload pipeline started at ${new Date().toISOString()}`);

                // Hoist so the catch block can reference it for error reporting
                    const uploadProjectId = message.projectId
                    || (message.fileName || '').replace(/\.(owl|rdf|ttl|n3|nt|jsonld|zip)$/i, '');

                // ── Notify Dashboard to open progress dialog immediately (but allow cancellation if duplicate) ──
                // (mirrors what the VS Code extension sends right after file selection)
                postToSelf({ type: 'showLoading', projectId: uploadProjectId });

                try {
                    const token = localStorage.getItem('authToken');
                    const baseUrl = getGatewayUrl();
                    const resolvedOwnerEmail = message.ownerEmail || getOwnerEmailFromToken();
                    const resolvedWorkspaceId = message.workspaceId || getWorkspaceIdFromToken();

                    // ── FAST PATH: Check if file already exists in GraphDB (skip upload entirely) ──
                    // Skip this when forceUpload is set — caller already confirmed GraphDB is empty
                    if (!message.forceUpload) {
                    try {
                        const statusUrl = `${baseUrl}/api/ontology/status/${encodeURIComponent(uploadProjectId)}`;
                        const statusResp = await fetch(statusUrl, {
                            headers: token ? { Authorization: `Bearer ${token}` } : {},
                        });
                        const statusData = await statusResp.json().catch(() => ({}));
                        const status = statusData?.data?.status || statusData?.status;

                        if (status === 'COMPLETED') {
                            console.log('[BrowserBridge] File already exists in GraphDB, sending fileReady immediately:', uploadProjectId);
                            postToSelf({ type: 'loadingComplete' });
                            postToSelf({
                                type: 'fileReady',
                                projectId: uploadProjectId,
                                uploadedFileName: message.fileName,
                            });
                            return; // No upload, no queries — just open the existing file
                        }
                    } catch (statusErr) {
                        console.log('[BrowserBridge] Status check failed, proceeding with upload:', statusErr);
                    }
                    }

                    // ── Check for duplicate BEFORE uploading (if not explicitly skipping) ──
                    if (!message.skipDuplicateCheck) {
                        console.log('[BrowserBridge] Checking for duplicate file before upload:', message.fileName);
                        try {
                            const checkUrl = `${baseUrl}/api/ontology/check-duplicate?filename=${encodeURIComponent(message.fileName)}${resolvedOwnerEmail ? `&ownerEmail=${encodeURIComponent(resolvedOwnerEmail)}` : ''}`;
                            const checkResp = await fetch(checkUrl, {
                                headers: token ? { Authorization: `Bearer ${token}` } : {},
                            });
                            const checkData = await checkResp.json().catch(() => ({}));

                            console.log('[BrowserBridge] Duplicate check result:', checkData);

                            if (checkData.isDuplicate) {
                                console.log('[BrowserBridge] Duplicate detected! File already exists:', checkData);
                                const existingProjectId = checkData.projectId || uploadProjectId;
                                const existingFileId = checkData.existingFile?.fileId || checkData.existingFile?.id;
                                const existingFileName = checkData.existingFile?.fileName || message.fileName;

                                // Cancel the loading state
                                postToSelf({ type: 'loadingComplete' });

                                // Notify that duplicate exists - open the existing file instead
                                postToSelf({
                                    type: 'fileReady',
                                    projectId: existingProjectId,
                                    uploadedFileId: existingFileId,
                                    uploadedFileName: existingFileName,
                                });

                                // Show success message indicating file already exists
                                postToSelf({
                                    type: 'importStatusUpdate',
                                    status: {
                                        type: 'IMPORT_COMPLETED',
                                        projectId: existingProjectId,
                                        status: 'COMPLETED',
                                        progress: 100,
                                        filename: message.fileName,
                                        message: `File "${message.fileName}" already exists. Opening existing file...`,
                                    },
                                });

                                return; // Stop the upload process
                            }

                            console.log('[BrowserBridge] No duplicate found, proceeding with upload');
                        } catch (checkErr) {
                            console.warn('[BrowserBridge] Duplicate check failed, continuing with upload:', checkErr);
                            // Continue with upload if check fails
                        }
                    } else {
                        console.log('[BrowserBridge] Skipping duplicate check (skipDuplicateCheck=true)');
                    }

                    // ── Convert base64 to upload blob ──
                    // For large files (>50 MB base64 ≈ >37 MB raw), skip the expensive
                    // text decode + namespace injection path. The byte-by-byte atob loop
                    // and regex-based namespace scan each take minutes on 200 MB+ files.
                    // The backend already handles missing namespaces during RDF parsing.
                    const base64Length = message.fileContent ? message.fileContent.length : 0;
                    const LARGE_FILE_THRESHOLD = 50 * 1024 * 1024; // 50 MB base64 (~37 MB raw)

                    let blob: Blob;
                    const decodeStart = Date.now();
                    const isOntologyPackage = (message.fileName || '').toLowerCase().endsWith('.zip');
                    if (isOntologyPackage || base64Length > LARGE_FILE_THRESHOLD) {
                        // FAST PATH: chunked base64→binary without text decode or namespace injection
                        console.log(`[BrowserBridge] ${isOntologyPackage ? 'Ontology package' : 'Large file'} (${(base64Length / (1024 * 1024)).toFixed(0)} MB base64), using fast binary upload`);
                        const CHUNK = 1024 * 1024; // decode 1 MB at a time
                        const chunks: Uint8Array[] = [];
                        for (let offset = 0; offset < base64Length; offset += CHUNK) {
                            const slice = message.fileContent.slice(offset, offset + CHUNK);
                            const raw = atob(slice);
                            const buf = new Uint8Array(raw.length);
                            for (let i = 0; i < raw.length; i++) buf[i] = raw.charCodeAt(i);
                            chunks.push(buf);
                        }
                        blob = new Blob(chunks as BlobPart[], { type: isOntologyPackage ? 'application/zip' : 'application/octet-stream' });
                        console.log(`[BrowserBridge] [PERF] Large file base64→binary decode: ${Date.now() - decodeStart}ms`);
                    } else {
                        // SMALL FILE PATH: full text decode + namespace injection
                        const byteString = atob(message.fileContent);
                        const bytes = new Uint8Array(byteString.length);
                        for (let i = 0; i < byteString.length; i++) bytes[i] = byteString.charCodeAt(i);
                        let fileText = new TextDecoder().decode(bytes);

                        // ── Dynamic namespace injection for RDF/XML files ──
                        if (fileText.includes('<rdf:RDF')) {
                            fileText = injectDynamicNamespaces(fileText);
                        }

                        blob = new Blob([fileText], { type: 'application/rdf+xml' });
                        console.log(`[BrowserBridge] [PERF] Small file decode + namespace injection: ${Date.now() - decodeStart}ms`);
                    }
                    // CHUNK_UPLOAD_THRESHOLD is kept well under Cloudflare's 100MB proxy cap (and any
                    // similar CDN/reverse-proxy limit) so large ontologies never hit that wall — this
                    // path currently doesn't compress before upload (unlike the VS Code extension host
                    // path), so chunking is this surface's only defense against large-file size caps.
                    const CHUNK_UPLOAD_THRESHOLD = 40 * 1024 * 1024; // 40MB
                    const httpPostStart = Date.now();
                    let resp: { ok: boolean; status: number };
                    let responseText: string;

                    if (blob.size > CHUNK_UPLOAD_THRESHOLD) {
                        console.log(`[BrowserBridge] File is ${(blob.size / (1024 * 1024)).toFixed(1)}MB, using chunked upload`);
                        const bytes = new Uint8Array(await blob.arrayBuffer());
                        const chunkedResult = await uploadBlobInChunks(uploadProjectId, bytes, message.fileName, baseUrl, {
                            headers: token ? { Authorization: `Bearer ${token}` } : {},
                            ownerEmail: resolvedOwnerEmail,
                            workspaceId: resolvedWorkspaceId,
                            importMode: message.importMode,
                            partition: message.partition,
                            action: message.skipDuplicateCheck ? 'replace' : undefined,
                        });
                        resp = { ok: chunkedResult.ok, status: chunkedResult.status };
                        responseText = chunkedResult.text;
                    } else {
                        const formData = new FormData();
                        formData.append('file', blob, message.fileName);

                        const query = new URLSearchParams();
                        if (resolvedOwnerEmail) query.set('ownerEmail', resolvedOwnerEmail);
                        if (resolvedWorkspaceId) query.set('workspaceId', resolvedWorkspaceId);
                        if (message.importMode) query.set('importMode', message.importMode);
                        if (message.partition) query.set('partition', message.partition);
                        if (message.skipDuplicateCheck) query.set('action', 'replace');

                        const uploadUrl = `${baseUrl}/api/ontology/upload/${encodeURIComponent(uploadProjectId)}?${query.toString()}`;
                        const uploadResult = await uploadFormDataWithProgress(uploadUrl, formData, {
                            headers: token ? { Authorization: `Bearer ${token}` } : {},
                            timeoutMs: 7_200_000,
                            projectId: uploadProjectId,
                        });
                        resp = { ok: uploadResult.ok, status: uploadResult.status };
                        responseText = uploadResult.text;
                    }
                    console.log(`[BrowserBridge] [PERF] HTTP POST upload: ${Date.now() - httpPostStart}ms`);

                    let responseData: any = {};
                    try { responseData = JSON.parse(responseText); } catch { responseData = { error: responseText }; }

                    if (resp.ok && responseData.success !== false) {
                        // Backend may return a different projectId on replace
                        const actualProjectId = responseData.projectId || uploadProjectId;
                        const actualFilename = responseData.filename || message.fileName;
                        const actualFileId = responseData.fileId || responseData.id;
                        console.log('[BrowserBridge] uploadOntology accepted, actualProjectId:', actualProjectId, 'fileId:', actualFileId, 'polling for completion...');

                        // If the server assigned a different projectId, update the Dashboard
                        if (actualProjectId !== uploadProjectId) {
                            postToSelf({ type: 'showLoading', projectId: actualProjectId });
                        }

                        // Poll /api/ontology/status until COMPLETED (GraphDB processes async)
                        // Time-based timeout: 15 min baseline + 1 min per 50MB
                        const fileSizeMB = base64Length > 0 ? (base64Length * 3 / 4) / (1024 * 1024) : 50; // actual file size (base64 is 4/3x)
                        const timeoutMs = Math.min(
                            7_200_000,
                            Math.max(60 * 60 * 1000, Math.ceil(fileSizeMB / 50) * 60 * 1000 + 30 * 60 * 1000),
                        );
                        console.log(`[BrowserBridge] File ~${fileSizeMB.toFixed(0)}MB, poll timeout: ${(timeoutMs / 60000).toFixed(1)} min`);
                        const getDelay = (att: number) => {
                            if (att <= 3) return 2000;
                            if (att <= 6) return 3000;
                            if (att <= 10) return 5000;
                            return 10000;
                        };

                        const pollStatus = async () => {
                            const pollStartTime = Date.now();
                            let attempt = 0;
                            while (Date.now() - pollStartTime < timeoutMs) {
                                attempt++;
                                await new Promise(r => setTimeout(r, getDelay(attempt)));
                                try {
                                    const statusResp = await fetch(
                                        `${baseUrl}/api/ontology/status/${encodeURIComponent(actualProjectId)}`,
                                        { headers: token ? { Authorization: `Bearer ${token}` } : {} }
                                    );
                                    const statusData = await statusResp.json().catch(() => ({}));
                                    const payload = statusData?.data || statusData;
                                    const status = payload?.status;
                                    console.log(`[BrowserBridge] Status poll #${attempt}: ${status}`, payload);

                                    if (status === 'COMPLETED') {
                                        console.log(`[BrowserBridge] [PERF] Status polling completed: ${Date.now() - pollStartTime}ms (${attempt} polls)`);
                                        console.log(`[BrowserBridge] [PERF] ⏱️ Total upload pipeline: ${Date.now() - uploadPipelineStart}ms`);

                                        // Try to get fileId from status response or upload response
                                        let fileId = actualFileId || payload?.fileId || payload?.id;

                                        // If still no fileId, fetch the file list and find by name
                                        if (!fileId) {
                                            console.log('[BrowserBridge] No fileId in response, waiting 1 second before fetching file list...');
                                            await new Promise(r => setTimeout(r, 1000)); // Wait for database sync

                                            console.log('[BrowserBridge] Fetching file list to find newly created file...');
                                            try {
                                                const filesResp = await fetch(
                                                    `${baseUrl}/api/projects/${encodeURIComponent(actualProjectId)}/files`,
                                                    { headers: token ? { Authorization: `Bearer ${token}` } : {} }
                                                );
                                                const filesData = await filesResp.json().catch(() => ({}));
                                                const filesList = filesData?.files || filesData?.data || [];
                                                console.log('[BrowserBridge] Fetched file list count:', filesList.length);
                                                console.log('[BrowserBridge] Looking for filename:', actualFilename);
                                                console.log('[BrowserBridge] File list details:', filesList.map((f: any) => ({
                                                    id: f.id || f.fileId,
                                                    name: f.name || f.filename || f.fileName,
                                                    uploadedAt: f.uploadedAt
                                                })));

                                                // Find the file by name (case-insensitive, check multiple properties)
                                                const normalizedTarget = actualFilename.toLowerCase();
                                                const matchedFile = filesList.find((f: any) => {
                                                    const fileName = f.name || f.filename || f.fileName || '';
                                                    return fileName.toLowerCase() === normalizedTarget;
                                                });

                                                if (matchedFile) {
                                                    fileId = matchedFile.id || matchedFile.fileId;
                                                    console.log('[BrowserBridge] ✅ Found file by name:', actualFilename, 'ID:', fileId);
                                                } else {
                                                    console.warn('[BrowserBridge] ⚠️ Could not find file in list by name:', actualFilename);
                                                    console.warn('[BrowserBridge] ⚠️ Available filenames:', filesList.map((f: any) => f.name || f.filename || f.fileName));
                                                }
                                            } catch (fetchErr) {
                                                console.warn('[BrowserBridge] Failed to fetch file list:', fetchErr);
                                            }
                                        }

                                        console.log('[BrowserBridge] Sending fileReady with fileId:', fileId);

                                        // Mirror extension: send fileReady first (triggers fetchData in Dashboard)
                                        postToSelf({
                                            type: 'fileReady',
                                            projectId: actualProjectId,
                                            uploadedFileId: fileId,
                                            uploadedFileName: actualFilename
                                        });
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
                                        postToSelf({ type: 'importFailed', projectId: actualProjectId, error: payload?.statusMessage || 'Import failed' });
                                        return;
                                    }

                                    // Send progress update
                                    if (payload?.statusMessage) {
                                        // Extract real progress from backend statusMessage (e.g., "Importing... (90%) | ETA...")
                                        const progressMatch = payload.statusMessage.match(/\((\d+)%\)/);
                                        const elapsedPct = Math.min(95, Math.floor(((Date.now() - pollStartTime) / timeoutMs) * 100));
                                        const realProgress = progressMatch ? parseInt(progressMatch[1], 10) : elapsedPct;
                                        postToSelf({
                                            type: 'importStatusUpdate',
                                            status: {
                                                type: 'IMPORT_PROGRESS',
                                                projectId: actualProjectId,
                                                status: 'PROCESSING',
                                                progress: realProgress,
                                                metadata: { message: payload.statusMessage },
                                            },
                                        });
                                    }
                                } catch (pollErr) {
                                    console.warn(`[BrowserBridge] Status poll #${attempt} error:`, pollErr);
                                }
                            }
                            // Timeout
                            postToSelf({ type: 'importFailed', projectId: actualProjectId, error: 'Import timed out waiting for processing to complete' });
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
                    // Convert content to binary Blob for multipart upload
                    let contentStr = message.fileContent;
                    if (/^[A-Za-z0-9+/=]+$/.test(contentStr)) {
                        // base64 → binary string → Uint8Array
                        const binaryStr = atob(contentStr);
                        const bytes = new Uint8Array(binaryStr.length);
                        for (let i = 0; i < binaryStr.length; i++) bytes[i] = binaryStr.charCodeAt(i);
                        contentStr = new TextDecoder().decode(bytes);
                    }
                    const fileBlob = new Blob([contentStr], { type: 'application/rdf+xml' });
                    const formData = new FormData();
                    formData.append('file', fileBlob, message.fileName);
                    formData.append('fileName', message.fileName);
                    formData.append('fileType', 'owl');
                    await apiClient.post(`/api/projects/${message.projectId}/files`, formData, {
                        onUploadProgress: (progressEvent) => {
                            if (progressEvent.total) {
                                const percent = Math.round((progressEvent.loaded / progressEvent.total) * 100);
                                postToSelf({
                                    type: 'uploadProgress',
                                    projectId: message.projectId,
                                    percent,
                                    loaded: progressEvent.loaded,
                                    total: progressEvent.total,
                                    message: percent >= 100
                                        ? 'Upload complete. Processing on server...'
                                        : `Uploading: ${percent}%`,
                                });
                            }
                        },
                    });
                    console.log('[BrowserBridge] uploadFileToProject success');
                } catch (err: any) {
                    console.error('[BrowserBridge] uploadFileToProject error:', err);
                    const errData = err?.data || err?.response?.data;
                    if (err?.status === 413 || err?.response?.status === 413) {
                        const detail = errData?.message || errData?.error || 'Storage limit exceeded. Please upgrade your plan or delete existing files.';
                        notificationService.error('Storage Limit Exceeded', detail);
                    } else {
                        notificationService.error('Upload Failed', errData?.error || err?.message || 'File upload failed');
                    }
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
                const librarySessionId = ++browserZoteroLibrarySessionCounter;
                try {
                    if (!sci2CodeBrowserService.isConfigured()) {
                        postToSelf({
                            type: 'zoteroLibraryError',
                            error: 'ZOTERO_NOT_CONFIGURED',
                            librarySessionId,
                        });
                        return;
                    }

                    const PAGE_SIZE = 100;
                    let start = 0;
                    let totalResults = Infinity; // will be set after first page

                    const qRaw =
                        typeof (message as Record<string, unknown>).searchQuery === 'string'
                            ? String((message as Record<string, unknown>).searchQuery).trim()
                            : '';
                    const pageOpts = qRaw ? { q: qRaw } : undefined;

                    while (start < totalResults) {
                        const { items, totalResults: total } = await sci2CodeBrowserService.fetchLibraryPage(
                            start,
                            PAGE_SIZE,
                            pageOpts
                        );

                        // Lock in the real total from the first response
                        if (start === 0) {
                            totalResults = total;
                        }

                        if (!items || items.length === 0) break;

                        if (start === 0) {
                            // First batch — send as initial payload so the UI can show results fast
                            postToSelf({
                                type: 'zoteroLibraryData',
                                items,
                                hasMore: start + items.length < totalResults,
                                librarySessionId,
                            });
                        } else {
                            postToSelf({
                                type: 'zoteroLibraryDataAppend',
                                items,
                                hasMore: start + items.length < totalResults,
                                librarySessionId,
                            });
                        }

                        start += items.length;

                        // Stop if we got the last page
                        if (items.length < PAGE_SIZE || start >= totalResults) break;
                    }

                    postToSelf({ type: 'zoteroLibraryDataComplete', librarySessionId });
                } catch (err: any) {
                    postToSelf({
                        type: 'zoteroLibraryError',
                        error: err?.message || 'Zotero unavailable',
                        librarySessionId,
                    });
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
            (async () => {
                try {
                    const positionData: any = await apiClient.get(`/api/import-queue/position/${message.projectId}`);
                    if (!positionData?.inQueue) {
                        postToSelf({
                            type: 'queueStatusUpdate',
                            status: {
                                projectId: message.projectId,
                                status: 'COMPLETED',
                                queuePosition: 0,
                                totalInQueue: 0,
                                estimatedWaitTimeMs: 0,
                                message: positionData?.message || 'Not in queue',
                            },
                        });
                        return;
                    }
                    postToSelf({
                        type: 'queueStatusUpdate',
                        status: {
                            projectId: message.projectId,
                            status: positionData.status || 'QUEUED',
                            queuePosition: positionData.position ?? 0,
                            totalInQueue: positionData.totalInQueue ?? 0,
                            estimatedWaitTimeMs: positionData.estimatedWaitMs ?? 0,
                            message: positionData.message,
                        },
                    });
                } catch (err) {
                    console.warn('[BrowserBridge] getQueueStatus failed:', err);
                }
            })();
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

        // In a real VS Code webview this goes through the extension host
        // (vscode.env.openExternal) because sandboxed iframes block direct
        // navigation. A plain browser tab has no such sandbox — just navigate.
        case 'openExternalUrl': {
            if (message.url) {
                window.location.href = message.url;
            }
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
    // The Electron preload already installed its own, richer window.vscode (native
    // dialogs, encrypted token storage, its own Zotero fetch, etc) — never overwrite it.
    if ((window as any).__ONTOCODE_NATIVE_VSCODE_BRIDGE__) {
        console.log('[BrowserBridge] Native (Electron) bridge detected — bridge NOT installed');
        return;
    }
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

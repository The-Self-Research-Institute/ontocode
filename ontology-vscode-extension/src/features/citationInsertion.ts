import * as vscode from 'vscode';
import { sci2CodeService, CitationItem, CitationFormat } from '../services/sci2CodeService';
import { validateDoiOnline } from '../services/doiValidationService';
import { extractDoiFromZoteroData, isValidDoiFormat, normalizeDoi } from '../utils/doi';
import { insertCitationNodeIntoJsonLd } from '../utils/jsonLdCitation';

interface QuickPickCitation extends vscode.QuickPickItem {
  key: string;
  citation: any;
}

const AUTH_TOKEN_KEY = 'ontocode.authToken';

/**
 * Authoritatively validate a DOI (doi.org, via the editor backend) and, if it
 * doesn't resolve or doesn't match this citation, let the user correct it,
 * use it anyway, drop it, or cancel the whole insertion — the keyboard-shortcut
 * path used to embed whatever was in Zotero's DOI field completely unchecked.
 *
 * Returns `{ cancelled: true }` if the user backs out entirely; otherwise
 * `{ doi }` with `doi` possibly undefined (user chose to insert without one).
 */
async function resolveDoiForInsertion(
  context: vscode.ExtensionContext,
  gatewayUrl: string,
  candidateDoi: string,
  meta: { title?: string; publicationTitle?: string; year?: string }
): Promise<{ doi?: string; cancelled: boolean }> {
  const token = await (context as any).secrets.get(AUTH_TOKEN_KEY);
  let doi = normalizeDoi(candidateDoi);

  while (true) {
    if (!doi || !isValidDoiFormat(doi)) {
      const choice = await vscode.window.showQuickPick(
        [
          { label: '$(edit) Enter a DOI', action: 'enter' as const },
          { label: '$(check) Insert without a DOI', action: 'skip' as const },
          { label: '$(x) Cancel', action: 'cancel' as const },
        ],
        {
          placeHolder: doi
            ? `"${doi}" isn't a valid DOI format`
            : 'No DOI found for this citation',
          ignoreFocusOut: true,
        }
      );
      if (!choice || choice.action === 'cancel') return { cancelled: true };
      if (choice.action === 'skip') return { doi: undefined, cancelled: false };
      const entered = await vscode.window.showInputBox({
        prompt: 'Enter DOI',
        value: doi,
        placeHolder: 'e.g., 10.1016/j.websem.2011.01.001',
        ignoreFocusOut: true,
      });
      if (entered === undefined) continue; // Esc — loop back to the same prompt
      doi = normalizeDoi(entered);
      continue;
    }

    const result = await vscode.window.withProgress(
      { location: vscode.ProgressLocation.Notification, title: 'Validating DOI...' },
      () => validateDoiOnline(gatewayUrl, token, { doi, ...meta })
    );

    if (result.valid && result.relevant) {
      return { doi: result.normalizedDoi || doi, cancelled: false };
    }

    const reason = result.error
      || (result.valid
        ? 'This DOI resolves, but its registrar metadata does not match this citation (title/year).'
        : 'This DOI does not resolve at doi.org.');

    const choice = await vscode.window.showQuickPick(
      [
        { label: `$(warning) Use "${doi}" anyway`, action: 'force' as const },
        { label: '$(edit) Enter a different DOI', action: 'enter' as const },
        { label: '$(check) Insert without a DOI', action: 'skip' as const },
        { label: '$(x) Cancel', action: 'cancel' as const },
      ],
      { placeHolder: reason, ignoreFocusOut: true }
    );
    if (!choice || choice.action === 'cancel') return { cancelled: true };
    if (choice.action === 'force') return { doi, cancelled: false };
    if (choice.action === 'skip') return { doi: undefined, cancelled: false };
    const entered = await vscode.window.showInputBox({
      prompt: 'Enter a corrected DOI',
      value: doi,
      placeHolder: 'e.g., 10.1016/j.websem.2011.01.001',
      ignoreFocusOut: true,
    });
    if (entered === undefined) continue; // Esc — re-show the same choice for the same doi
    doi = normalizeDoi(entered);
  }
}

export async function insertCitationCommand(context: vscode.ExtensionContext, gatewayUrl: string) {
  // Debug: Log all open editors
  console.log('=== DEBUG: Looking for ontology editor ===');
  console.log('Active editor:', vscode.window.activeTextEditor?.document.fileName);
  console.log('Visible editors:', vscode.window.visibleTextEditors.map(e => e.document.fileName));
  console.log('Open documents:', vscode.workspace.textDocuments.map(d => d.fileName));
  
  // Find the editor with an ontology file
  const editor = findOntologyEditor();
  
  if (!editor) {
    // Show more helpful error with what we found
    const openFiles = vscode.workspace.textDocuments
      .filter(d => !d.isUntitled && d.uri.scheme === 'file')
      .map(d => {
        const fileName = d.fileName;
        return fileName.substring(fileName.lastIndexOf('/') + 1).substring(fileName.lastIndexOf('\\') + 1);
      })
      .join(', ');
    
    vscode.window.showWarningMessage(
      `No ontology file found. Open files: ${openFiles || 'none'}. Please open a .owl, .ttl, .rdf, or .n3 file.`
    );
    return;
  }

  console.log('Found ontology editor:', editor.document.fileName);

  // Focus the editor to ensure it's active
  await vscode.window.showTextDocument(editor.document, editor.viewColumn);

  const document = editor.document;
  const fileName = document.fileName;
  const lastDot = fileName.lastIndexOf('.');
  const fileExtension = lastDot !== -1 ? fileName.substring(lastDot).toLowerCase() : '';
  
  console.log('File name:', fileName);
  console.log('File extension:', fileExtension);
  
  // Determine format based on file extension
  const format: CitationFormat = (fileExtension === '.ttl' || fileExtension === '.n3')
    ? 'turtle'
    : fileExtension === '.jsonld'
    ? 'jsonld'
    : 'rdfxml';

  const manualEntryItem: QuickPickCitation = {
    label: '$(plus) Add Citation Manually...',
    description: 'Enter citation details directly without Zotero',
    key: 'manual',
    citation: null
  };

  // Show the picker immediately with a visible busy spinner while the library
  // loads in the background — a background notification toast (the previous
  // approach) is easy to miss entirely since it appears away from where the
  // user is looking right after pressing the shortcut.
  const quickPick = vscode.window.createQuickPick<QuickPickCitation>();
  quickPick.placeholder = 'Loading your Zotero library...';
  quickPick.matchOnDescription = true;
  quickPick.matchOnDetail = true;
  quickPick.ignoreFocusOut = true;
  quickPick.busy = true;
  quickPick.items = [manualEntryItem];
  quickPick.show();

  // Get Zotero library. sci2CodeService/zoteroApiService already show specific,
  // actionable dialogs for "not configured" and real fetch errors (invalid key,
  // network failure, etc.) — this catch is just a last-resort net, not the
  // primary error path, so items staying [] here still lets the picker fall
  // back gracefully to manual entry instead of the whole command failing.
  let items: any[] = [];
  try {
    items = await sci2CodeService.getZoteroLibrary();
  } catch (e) {
    console.error('Failed to load Zotero library:', e);
    vscode.window.showErrorMessage(
      `Could not load your Zotero library: ${e instanceof Error ? e.message : String(e)}. You can still add a citation manually.`
    );
  }

  // Create quick pick items
  const quickPickItems: QuickPickCitation[] = items.map(item => {
    const title = item.data?.title || 'Untitled';
    const creators = item.data?.creators?.map((c: any) =>
      `${c.firstName} ${c.lastName}`.trim()
    ).join(', ') || 'Unknown author';
    const year = item.data?.date ? extractYear(item.data.date) : '';

    return {
      label: title,
      description: `${item.data?.itemType || 'Item'} ${year ? `(${year})` : ''}`,
      detail: creators,
      key: item.key,
      citation: item
    };
  });

  quickPick.items = [manualEntryItem, ...quickPickItems];
  quickPick.placeholder = 'Search Zotero or add citation manually';
  quickPick.busy = false;

  const selected = await new Promise<QuickPickCitation | undefined>(resolve => {
    quickPick.onDidAccept(() => resolve(quickPick.selectedItems[0]));
    quickPick.onDidHide(() => resolve(undefined));
  });
  quickPick.dispose();

  if (!selected) {
    return;
  }

  if (selected.key === 'manual') {
    const manualItem = await showManualCitationDialog(context, gatewayUrl);
    if (manualItem && manualItem !== 'cancelled') {
      await insertManualCitation(editor, manualItem, format);
    }
    return;
  }

  console.log('Using format:', format);

  // Validate the citation's DOI (or offer to add/correct one) before inserting —
  // this used to embed selected.citation.data?.DOI completely unchecked.
  const data = selected.citation?.data;
  const candidateDoi = extractDoiFromZoteroData(data);
  const { doi, cancelled } = await resolveDoiForInsertion(context, gatewayUrl, candidateDoi, {
    title: data?.title,
    publicationTitle: data?.publicationTitle,
    year: data?.date ? extractYear(data.date) : undefined,
  });
  if (cancelled) {
    return;
  }

  // Make sure editor is still valid and focused
  const currentEditor = vscode.window.activeTextEditor;
  if (currentEditor && currentEditor.document.uri.toString() === editor.document.uri.toString()) {
    await insertCitation(currentEditor, selected.key, format, doi);
  } else {
    // Re-focus the original editor
    const reopenedEditor = await vscode.window.showTextDocument(editor.document, editor.viewColumn);
    await insertCitation(reopenedEditor, selected.key, format, doi);
  }
}

// Helper function to find an editor with an ontology file
function findOntologyEditor(): vscode.TextEditor | undefined {
  const validExtensions = ['.owl', '.ttl', '.rdf', '.n3', '.nt', '.jsonld'];
  
  console.log('=== Searching for ontology editor ===');
  
  // First try the active editor
  const activeEditor = vscode.window.activeTextEditor;
  if (activeEditor) {
    const fileName = activeEditor.document.fileName;
    const ext = fileName.substring(fileName.lastIndexOf('.')).toLowerCase();
    console.log('Active editor:', fileName, 'Extension:', ext);
    
    if (validExtensions.includes(ext)) {
      console.log('✓ Active editor is an ontology file');
      return activeEditor;
    }
  } else {
    console.log('No active editor');
  }
  
  // Search all visible editors
  console.log('Checking visible editors...');
  for (const editor of vscode.window.visibleTextEditors) {
    const fileName = editor.document.fileName;
    const ext = fileName.substring(fileName.lastIndexOf('.')).toLowerCase();
    console.log('Visible editor:', fileName, 'Extension:', ext);
    
    if (validExtensions.includes(ext)) {
      console.log('✓ Found ontology file in visible editors');
      return editor;
    }
  }
  
  // Search all open documents
  console.log('Checking all open documents...');
  for (const doc of vscode.workspace.textDocuments) {
    if (doc.isUntitled) continue;
    if (doc.uri.scheme !== 'file') continue;
    
    const fileName = doc.fileName;
    const ext = fileName.substring(fileName.lastIndexOf('.')).toLowerCase();
    console.log('Open document:', fileName, 'Extension:', ext);
    
    if (validExtensions.includes(ext)) {
      console.log('✓ Found ontology file in open documents');
      // Document found, but need to check if there's an editor for it
      const editor = vscode.window.visibleTextEditors.find(e => 
        e.document.uri.toString() === doc.uri.toString()
      );
      if (editor) {
        return editor;
      }
      // If no visible editor, we'll need to open one - but return undefined
      // so the caller can handle it
    }
  }
  
  console.log('✗ No ontology file found');
  return undefined;
}

function extractYear(dateString: string): string {
  if (!dateString) return '';
  
  const parsed = new Date(dateString);
  if (!isNaN(parsed.getTime())) {
    return parsed.getFullYear().toString();
  }
  
  const yearMatch = dateString.match(/\b(19|20)\d{2}\b/);
  return yearMatch ? yearMatch[0] : '';
}

function getIndentation(document: vscode.TextDocument, position: vscode.Position): string {
  const line = document.lineAt(position.line);
  const match = line.text.match(/^(\s*)/);
  return match ? match[1] : '';
}

function indentText(text: string, indent: string): string {
  const lines = text.split('\n');
  return lines.map(line => indent + line).join('\n');
}

/**
 * Inserts a formatted citation into the document. JSON-LD isn't line-oriented
 * like Turtle/RDF-XML, so it can't be spliced in as raw text at the cursor
 * without risking invalid JSON — instead the whole document is parsed,
 * the citation node is added to @graph, and the result is re-serialized.
 */
async function insertFormattedCitation(
  editor: vscode.TextEditor,
  format: CitationFormat,
  formattedCitation: string
): Promise<boolean> {
  const document = editor.document;
  await ensurePrefixes(document, format);

  if (format === 'jsonld') {
    const node = JSON.parse(formattedCitation);
    const newText = insertCitationNodeIntoJsonLd(document.getText(), node);
    const fullRange = new vscode.Range(document.positionAt(0), document.positionAt(document.getText().length));
    return await editor.edit(editBuilder => {
      editBuilder.replace(fullRange, newText);
    });
  }

  const position = editor.selection.active;
  const indent = getIndentation(document, position);
  const indentedCitation = indentText(formattedCitation, indent);

  return await editor.edit(editBuilder => {
    editBuilder.insert(position, '\n' + indentedCitation + '\n');
  });
}

async function insertCitation(
  editor: vscode.TextEditor,
  citationKey: string,
  format: CitationFormat,
  validatedDoi?: string
): Promise<void> {
  const formattedCitation = await vscode.window.withProgress(
    {
      location: vscode.ProgressLocation.Notification,
      title: 'Formatting citation...',
      cancellable: false
    },
    async () => {
      return await sci2CodeService.formatCitationForOntology(citationKey, format, validatedDoi);
    }
  );

  if (!formattedCitation) {
    vscode.window.showErrorMessage('Failed to format citation.');
    return;
  }

  const success = await insertFormattedCitation(editor, format, formattedCitation);

  if (!success) {
    vscode.window.showErrorMessage('Failed to insert citation into document.');
    return;
  }

  // Get citation metadata for success message
  const metadata = await sci2CodeService.getCitationMetadata(citationKey);
  const title = metadata?.title || 'Citation';
  
  // Update repository citation files
  if (metadata) {
    await updateRepositoryCitations(metadata);
  }
  
  vscode.window.showInformationMessage(`✓ Inserted citation: ${title}`);
}

async function updateRepositoryCitations(item: CitationItem): Promise<void> {
  const workspaceFolders = vscode.workspace.workspaceFolders;
  if (!workspaceFolders) return;

  const rootPath = workspaceFolders[0].uri.fsPath;
  const bibPath = vscode.Uri.file(rootPath + '/references.bib');
  const cffPath = vscode.Uri.file(rootPath + '/CITATION.cff');
  const mdPath = vscode.Uri.file(rootPath + '/CITATIONS.md');

  try {
    // 1. Update references.bib
    const bibSnippet = sci2CodeService.convertToBibTeX(item);
    let bibContent = '';
    try {
      const bibData = await vscode.workspace.fs.readFile(bibPath);
      bibContent = Buffer.from(bibData).toString('utf8');
    } catch (e) {
      // File doesn't exist, start fresh
    }

    if (!bibContent.includes(item.title)) {
      bibContent += '\n' + bibSnippet;
      await vscode.workspace.fs.writeFile(bibPath, Buffer.from(bibContent, 'utf8'));
      console.log('Updated references.bib');
    }

    // 2. Update CITATION.cff
    const cffRef = sci2CodeService.convertToCFFReference(item);
    let cffContent = '';
    try {
      const cffData = await vscode.workspace.fs.readFile(cffPath);
      cffContent = Buffer.from(cffData).toString('utf8');
    } catch (e) {
      // File doesn't exist
    }

    if (cffContent && !cffContent.includes(item.title)) {
      // Simple append to references section
      if (!cffContent.includes('references:')) {
        cffContent += '\nreferences:\n';
      }
      
      const refString = `  - type: ${cffRef.type}\n` +
                        `    title: "${cffRef.title}"\n` +
                        `    authors:\n` +
                        (cffRef.authors.map((a: any) => `      - family-names: "${a['family-names']}"\n        given-names: "${a['given-names']}"`).join('\n')) + '\n' +
                        (cffRef.year ? `    year: ${cffRef.year}\n` : '') +
                        (cffRef.doi ? `    doi: ${cffRef.doi}\n` : '') +
                        (cffRef.url ? `    url: "${cffRef.url}"\n` : '');
      
      cffContent += refString;
      await vscode.workspace.fs.writeFile(cffPath, Buffer.from(cffContent, 'utf8'));
      console.log('Updated CITATION.cff');
    }

    // 3. Update CITATIONS.md
    let mdContent = '';
    try {
      const mdData = await vscode.workspace.fs.readFile(mdPath);
      mdContent = Buffer.from(mdData).toString('utf8');
    } catch (e) {
      // File doesn't exist
    }

    if (mdContent && !mdContent.includes(item.title)) {
      const authors = item.creators?.map(c => `${c.lastName}, ${c.firstName.charAt(0)}.`).join(', ') || 'Unknown';
      const year = item.date ? (item.date.match(/\d{4}/)?.[0] || '') : '';
      const mdEntry = `\n- **${item.title}**\n  - ${authors}${year ? ` (${year})` : ''}.\n` +
                      (item.url ? `  - URL: [${item.url}](${item.url})\n` : '') +
                      (item.doi ? `  - DOI: ${item.doi}\n` : '');
      
      if (mdContent.includes('## References')) {
        mdContent = mdContent.replace('*No additional references yet. Use the Zotero integration to add citations.*', '');
        mdContent += mdEntry;
      } else {
        mdContent += '\n## References\n' + mdEntry;
      }
      
      await vscode.workspace.fs.writeFile(mdPath, Buffer.from(mdContent, 'utf8'));
      console.log('Updated CITATIONS.md');
    }
  } catch (error) {
    console.error('Failed to update repository citations:', error);
  }
}

export async function ensurePrefixes(document: vscode.TextDocument, format: CitationFormat): Promise<void> {
  // JSON-LD has no separate "ensure prefixes" pass: insertCitationNodeIntoJsonLd
  // merges any missing @context entries as part of its own parse/mutate/
  // reserialize step, since that has to happen atomically with the insert anyway.
  if (format === 'jsonld') return;

  const text = document.getText();

  if (format === 'turtle') {
    const requiredPrefixes = [
      { prefix: '@prefix dc:', declaration: '@prefix dc: <http://purl.org/dc/terms/> .' },
      { prefix: '@prefix prov:', declaration: '@prefix prov: <http://www.w3.org/ns/prov#> .' },
      { prefix: '@prefix foaf:', declaration: '@prefix foaf: <http://xmlns.com/foaf/0.1/> .' },
      { prefix: '@prefix xsd:', declaration: '@prefix xsd: <http://www.w3.org/2001/XMLSchema#> .' }
    ];

    const missingPrefixes = requiredPrefixes.filter(p => !text.includes(p.prefix));
    
    if (missingPrefixes.length > 0) {
      const editors = vscode.window.visibleTextEditors.filter(e => 
        e.document.uri.toString() === document.uri.toString()
      );
      
      if (editors.length > 0) {
        const editor = editors[0];
        await editor.edit(editBuilder => {
          let insertPosition = new vscode.Position(0, 0);
          
          const prefixMatch = text.match(/@prefix/);
          if (prefixMatch && prefixMatch.index !== undefined) {
            insertPosition = document.positionAt(prefixMatch.index);
          }
          
          const prefixBlock = missingPrefixes.map(p => p.declaration).join('\n') + '\n\n';
          editBuilder.insert(insertPosition, prefixBlock);
        });
      }
    }
  } else if (format === 'rdfxml') {
    // URIs matched to what formatCitationForOntology's rdfxml fragment actually
    // emits (dc:title/creator/date/identifier, foaf:homepage, prov#Entity type) —
    // see extension.ts's formatCitationForOntology.
    const requiredNamespaces: Record<string, string> = {
      'xmlns:dc': 'http://purl.org/dc/elements/1.1/',
      'xmlns:foaf': 'http://xmlns.com/foaf/0.1/',
      'xmlns:prov': 'http://www.w3.org/ns/prov#',
      'xmlns:xsd': 'http://www.w3.org/2001/XMLSchema#',
    };

    const rootMatch = text.match(/<rdf:RDF([^>]*)>/i);
    if (!rootMatch || rootMatch.index === undefined) {
      // No <rdf:RDF> root to attach namespaces to — nothing safe to do here;
      // the citation insert below will fail visibly instead of silently.
      return;
    }

    const existingAttrs = rootMatch[1];
    const missing = Object.entries(requiredNamespaces).filter(
      ([prefix]) => !new RegExp(prefix.replace(':', '\\:'), 'i').test(existingAttrs)
    );
    if (missing.length === 0) return;

    const editors = vscode.window.visibleTextEditors.filter(e =>
      e.document.uri.toString() === document.uri.toString()
    );
    if (editors.length === 0) return;

    const editor = editors[0];
    const startPos = document.positionAt(rootMatch.index);
    const endPos = document.positionAt(rootMatch.index + rootMatch[0].length);
    const newAttrs = missing.map(([prefix, uri]) => `\n         ${prefix}="${uri}"`).join('');
    const enhancedRoot = `<rdf:RDF${existingAttrs}${newAttrs}>`;

    await editor.edit(editBuilder => {
      editBuilder.replace(new vscode.Range(startPos, endPos), enhancedRoot);
    });
  }
}

export async function insertCitationAtClass(classIRI: string, context: vscode.ExtensionContext, gatewayUrl: string): Promise<void> {
  const editor = findOntologyEditor();
  if (!editor) {
    vscode.window.showWarningMessage('No ontology file is open.');
    return;
  }

  const document = editor.document;
  const text = document.getText();
  
  const patterns = [
    new RegExp(`<${classIRI}>`, 'g'),
    new RegExp(`:${classIRI.split(/[#/]/).pop()}\\s+a\\s+owl:Class`, 'g')
  ];
  
  let position: vscode.Position | null = null;
  
  for (const pattern of patterns) {
    const match = pattern.exec(text);
    if (match && match.index !== undefined) {
      position = document.positionAt(match.index + match[0].length);
      break;
    }
  }
  
  if (position) {
    await vscode.window.showTextDocument(editor.document, editor.viewColumn);
    editor.selection = new vscode.Selection(position, position);
    await insertCitationCommand(context, gatewayUrl);
  } else {
    vscode.window.showWarningMessage(`Could not find class ${classIRI} in document.`);
  }
}

async function showManualCitationDialog(
  context: vscode.ExtensionContext,
  gatewayUrl: string
): Promise<CitationItem | 'cancelled' | null> {
  const title = await vscode.window.showInputBox({
    prompt: 'Enter the title of the work',
    placeHolder: 'e.g., The OWL API: A Java API for the semantic web',
    ignoreFocusOut: true
  });
  if (!title) return null;

  const author = await vscode.window.showInputBox({
    prompt: 'Enter the author(s)',
    placeHolder: 'e.g., Matthew Horridge, Sean Bechhofer',
    ignoreFocusOut: true
  });
  if (!author) return null;

  const year = await vscode.window.showInputBox({
    prompt: 'Enter the year',
    placeHolder: 'e.g., 2011',
    ignoreFocusOut: true,
    validateInput: (value) => {
      return /^\d{4}$/.test(value) ? null : 'Please enter a valid 4-digit year';
    }
  });
  if (!year) return null;

  const doiInput = await vscode.window.showInputBox({
    prompt: 'Enter DOI (optional)',
    placeHolder: 'e.g., 10.1016/j.websem.2011.01.001',
    ignoreFocusOut: true
  });

  const url = await vscode.window.showInputBox({
    prompt: 'Enter URL (optional)',
    placeHolder: 'e.g., http://owlcs.github.io/owlapi/',
    ignoreFocusOut: true
  });

  // Validate the DOI the same way a Zotero-sourced one is — a manually typed
  // DOI is just as likely to be mistyped or made up as a bad Zotero field.
  // An empty field skips straight through (no DOI was ever offered).
  let doi: string | undefined;
  if (doiInput?.trim()) {
    const resolved = await resolveDoiForInsertion(context, gatewayUrl, doiInput, { title, year });
    if (resolved.cancelled) return 'cancelled';
    doi = resolved.doi;
  }

  // Parse authors
  const creators = author.split(',').map(a => {
    const parts = a.trim().split(' ');
    const lastName = parts.pop() || '';
    const firstName = parts.join(' ');
    return { firstName, lastName, creatorType: 'author' };
  });

  return {
    key: title.toLowerCase().replace(/\s+/g, '_').substring(0, 20) + '_' + year,
    title,
    creators,
    date: year,
    doi,
    url: url || undefined,
    itemType: 'manual'
  };
}

async function insertManualCitation(
  editor: vscode.TextEditor,
  item: CitationItem,
  format: CitationFormat
): Promise<void> {
  const formattedCitation = sci2CodeService.formatManualCitation(item, format);
  const success = await insertFormattedCitation(editor, format, formattedCitation);

  if (!success) {
    vscode.window.showErrorMessage('Failed to insert citation into document.');
    return;
  }

  // Update repository citation files
  await updateRepositoryCitations(item);
  
  vscode.window.showInformationMessage(`✓ Inserted manual citation: ${item.title}`);
}

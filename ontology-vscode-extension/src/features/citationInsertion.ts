import * as vscode from 'vscode';
import * as path from 'path';
import { sci2CodeService, CitationItem } from '../services/sci2CodeService';

interface QuickPickCitation extends vscode.QuickPickItem {
  key: string;
  citation: any;
}

export async function insertCitationCommand() {
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
      .map(d => path.basename(d.fileName))
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
  const fileExtension = path.extname(fileName).toLowerCase();
  
  console.log('File name:', fileName);
  console.log('File extension:', fileExtension);
  
  // Get Zotero library
  const items = await vscode.window.withProgress(
    {
      location: vscode.ProgressLocation.Notification,
      title: 'Loading Zotero library...',
      cancellable: false
    },
    async () => {
      return await sci2CodeService.getZoteroLibrary();
    }
  );

  if (items.length === 0) {
    vscode.window.showWarningMessage(
      'No Zotero items found. Please configure Sci2Code and ensure you have items in your library.'
    );
    return;
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

  // Show quick pick
  const selected = await vscode.window.showQuickPick(quickPickItems, {
    placeHolder: 'Search and select a citation to insert',
    matchOnDescription: true,
    matchOnDetail: true,
    ignoreFocusOut: true
  });

  if (!selected) {
    return;
  }

  // Determine format based on file extension
  const format = (fileExtension === '.ttl' || fileExtension === '.n3') 
    ? 'turtle' 
    : 'rdfxml';

  console.log('Using format:', format);

  // Make sure editor is still valid and focused
  const currentEditor = vscode.window.activeTextEditor;
  if (currentEditor && currentEditor.document.uri.toString() === editor.document.uri.toString()) {
    await insertCitation(currentEditor, selected.key, format);
  } else {
    // Re-focus the original editor
    const reopenedEditor = await vscode.window.showTextDocument(editor.document, editor.viewColumn);
    await insertCitation(reopenedEditor, selected.key, format);
  }
}

// Helper function to find an editor with an ontology file
function findOntologyEditor(): vscode.TextEditor | undefined {
  const validExtensions = ['.owl', '.ttl', '.rdf', '.n3', '.nt'];
  
  console.log('=== Searching for ontology editor ===');
  
  // First try the active editor
  const activeEditor = vscode.window.activeTextEditor;
  if (activeEditor) {
    const fileName = activeEditor.document.fileName;
    const ext = path.extname(fileName).toLowerCase();
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
    const ext = path.extname(fileName).toLowerCase();
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
    const ext = path.extname(fileName).toLowerCase();
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

async function insertCitation(
  editor: vscode.TextEditor,
  citationKey: string,
  format: 'turtle' | 'rdfxml'
): Promise<void> {
  const formattedCitation = await vscode.window.withProgress(
    {
      location: vscode.ProgressLocation.Notification,
      title: 'Formatting citation...',
      cancellable: false
    },
    async () => {
      return await sci2CodeService.formatCitationForOntology(citationKey, format);
    }
  );

  if (!formattedCitation) {
    vscode.window.showErrorMessage('Failed to format citation.');
    return;
  }

  // Ensure prefixes are present
  const document = editor.document;
  await ensurePrefixes(document, format);

  // Get the current position before making edits
  const position = editor.selection.active;
  const indent = getIndentation(document, position);
  const indentedCitation = indentText(formattedCitation, indent);

  // Insert citation
  const success = await editor.edit(editBuilder => {
    editBuilder.insert(position, '\n' + indentedCitation + '\n');
  });

  if (!success) {
    vscode.window.showErrorMessage('Failed to insert citation into document.');
    return;
  }

  // Get citation metadata for success message
  const metadata = await sci2CodeService.getCitationMetadata(citationKey);
  const title = metadata?.title || 'Citation';
  
  vscode.window.showInformationMessage(`✓ Inserted citation: ${title}`);
}

async function ensurePrefixes(document: vscode.TextDocument, format: 'turtle' | 'rdfxml'): Promise<void> {
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
    if (!text.includes('xmlns:dc=') || !text.includes('xmlns:prov=') || 
        !text.includes('xmlns:foaf=') || !text.includes('xmlns:xsd=')) {
      
      const addNamespaces = await vscode.window.showInformationMessage(
        'Your RDF/XML file may be missing required namespace declarations. Would you like to see the required namespaces?',
        'Show', 'Cancel'
      );
      
      if (addNamespaces === 'Show') {
        const channel = vscode.window.createOutputChannel('OntoCode Namespaces');
        channel.appendLine('Add these to your root RDF element:');
        channel.appendLine('xmlns:dc="http://purl.org/dc/terms/"');
        channel.appendLine('xmlns:prov="http://www.w3.org/ns/prov#"');
        channel.appendLine('xmlns:foaf="http://xmlns.com/foaf/0.1/"');
        channel.appendLine('xmlns:xsd="http://www.w3.org/2001/XMLSchema#"');
        channel.show();
      }
    }
  }
}

export async function insertCitationAtClass(classIRI: string): Promise<void> {
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
    await insertCitationCommand();
  } else {
    vscode.window.showWarningMessage(`Could not find class ${classIRI} in document.`);
  }
}
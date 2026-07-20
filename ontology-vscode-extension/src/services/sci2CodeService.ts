import * as vscode from 'vscode';
import { buildZoteroCitationNode } from '../utils/jsonLdCitation';

export type CitationFormat = 'turtle' | 'rdfxml' | 'jsonld';

export interface CitationItem {
  key: string;
  title: string;
  creators: Array<{ firstName: string; lastName: string; creatorType: string }>;
  date: string;
  doi?: string;
  url?: string;
  itemType: string;
  abstractNote?: string;
  publicationTitle?: string;
  volume?: string;
  issue?: string;
  pages?: string;
  publisher?: string;
  tags?: Array<{ tag: string }>;
}

interface Sci2CodeAPI {
  getZoteroLibrary(): Promise<any[]>;
  getZoteroItem(key: string): Promise<any | null>;
  formatCitationForOntology(key: string, format?: CitationFormat, overrideDoi?: string): Promise<string>;
  getCitationMetadata(key: string): Promise<CitationItem | null>;
  isAuthenticated?(): Promise<boolean>; // Make it optional
}

class Sci2CodeService {
  private api: Sci2CodeAPI | null = null;
  private extensionId = 'self.ontocode-extension'; // Use OntoCode's own extension ID
  private initializationAttempted = false;

  /**
   * Finds/activates OntoCode's own extension exports and caches them — no
   * prompts, no side effects, safe to call from passive UI (e.g. the sidebar
   * status row) that shouldn't pop up a "configure Zotero?" dialog just
   * because it rendered.
   */
  private async ensureApi(): Promise<boolean> {
    if (!this.api) {
      this.initializationAttempted = true;

      try {
        console.log('Looking for Sci2Code extension with ID:', this.extensionId);

        const extension = vscode.extensions.getExtension(this.extensionId);

        if (!extension) {
          console.error('Sci2Code extension not found');

          // List all installed extensions for debugging
          const allExtensions = vscode.extensions.all
            .filter(ext => !ext.id.startsWith('vscode.'))
            .map(ext => ext.id);
          console.log('Installed extensions:', allExtensions);

          const install = await vscode.window.showWarningMessage(
            `Sci2Code extension (${this.extensionId}) is required for citation features. Please ensure it's installed.`,
            'Show Extensions', 'Cancel'
          );

          if (install === 'Show Extensions') {
            await vscode.commands.executeCommand('workbench.extensions.search', 'sci2code');
          }
          return false;
        }

        console.log('Sci2Code extension found, checking activation...');

        if (!extension.isActive) {
          console.log('Activating Sci2Code extension...');
          await extension.activate();
        }

        this.api = extension.exports as Sci2CodeAPI;

        if (!this.api) {
          console.error('Sci2Code extension exports are undefined');
          vscode.window.showErrorMessage(
            'Sci2Code extension did not export an API. Please update the extension.'
          );
          return false;
        }

        console.log('Sci2Code API initialized successfully');
        console.log('Available API methods:', Object.keys(this.api));
      } catch (error) {
        console.error('Failed to initialize Sci2Code:', error);

        vscode.window.showErrorMessage(
          `Failed to initialize Sci2Code: ${error instanceof Error ? error.message : String(error)}`
        );

        return false;
      }
    }

    return true;
  }

  /**
   * Full init used by the actual insert-citation commands: finds/activates
   * the API (silent) and, if Zotero isn't configured yet, offers to configure
   * it right now. Has user-facing side effects — only call this from a flow
   * the user just explicitly triggered (Insert Citation, Open Citation
   * Picker), never from passive/background UI.
   */
  async initialize(): Promise<boolean> {
    const ok = await this.ensureApi();
    if (!ok || !this.api) return false;

    // Check whether Zotero is configured (optional, don't fail if method doesn't exist)
    if (typeof this.api.isAuthenticated === 'function') {
      const isAuth = await this.api.isAuthenticated();
      console.log('Zotero configured:', isAuth);

      if (!isAuth) {
        // Previously prompted "Log in to Zotero" and ran a 'sci2code.login'
        // command that doesn't exist anywhere in this extension — a guaranteed
        // dead end. Zotero has no "login" concept here, just an API key; route
        // to the real, working configure command instead.
        const configure = await vscode.window.showInformationMessage(
          'Zotero isn\'t configured yet. Configure it now to insert citations from your library.',
          'Configure', 'Cancel'
        );

        if (configure === 'Configure') {
          await vscode.commands.executeCommand('ontocode.configureZotero');
          // Re-check — the configure command may have succeeded synchronously
          // (it awaits the credential prompt before returning).
          const nowAuth = await this.api.isAuthenticated();
          if (!nowAuth) return false;
        } else {
          return false;
        }
      }
    } else {
      console.log('isAuthenticated method not available, skipping auth check');
    }

    return true;
  }

  /**
   * Silent status read for passive UI (sidebar status row) — never prompts.
   */
  async getConnectionStatus(): Promise<'connected' | 'not-configured' | 'unavailable'> {
    const ok = await this.ensureApi();
    if (!ok || !this.api) return 'unavailable';

    if (typeof this.api.isAuthenticated !== 'function') return 'unavailable';

    try {
      const isAuth = await this.api.isAuthenticated();
      return isAuth ? 'connected' : 'not-configured';
    } catch (error) {
      console.error('Failed to check Zotero connection status:', error);
      return 'unavailable';
    }
  }

  async getZoteroLibrary(): Promise<any[]> {
    // Always go through initialize() (not gated on `!this.api`) — it re-checks
    // Zotero configuration every call even when the extension lookup itself is
    // cached, so declining the configure prompt once doesn't silently suppress
    // it for the rest of the session (see initialize()'s comment).
    const initialized = await this.initialize();
    if (!initialized || !this.api) {
      return [];
    }

    try {
      console.log('Fetching Zotero library...');
      const items = await this.api.getZoteroLibrary();
      console.log(`Fetched ${items?.length || 0} items from Zotero`);
      return items || [];
    } catch (error) {
      console.error('Failed to get Zotero library:', error);
      vscode.window.showErrorMessage(
        `Failed to load Zotero library: ${error instanceof Error ? error.message : String(error)}`
      );
      return [];
    }
  }

  async getCitationMetadata(key: string): Promise<CitationItem | null> {
    const initialized = await this.initialize();
    if (!initialized || !this.api) {
      return null;
    }

    try {
      return await this.api.getCitationMetadata(key);
    } catch (error) {
      console.error('Failed to get citation metadata:', error);
      return null;
    }
  }

  convertToBibTeX(item: CitationItem): string {
    const key = item.key.replace(/[^a-zA-Z0-9]/g, '');
    const year = item.date ? (item.date.match(/\d{4}/)?.[0] || '2025') : '2025';
    const authors = item.creators?.map(c => `${c.lastName}, ${c.firstName}`).join(' and ') || 'Unknown';
    
    let bib = `@${item.itemType === 'journalArticle' ? 'article' : 'misc'}{${key}${year},\n`;
    bib += `  title = {${item.title}},\n`;
    bib += `  author = {${authors}},\n`;
    if (year) bib += `  year = {${year}},\n`;
    if (item.publicationTitle) bib += `  journal = {${item.publicationTitle}},\n`;
    if (item.doi) bib += `  doi = {${item.doi}},\n`;
    if (item.url) bib += `  url = {${item.url}},\n`;
    bib += `}\n`;
    return bib;
  }

  convertToCFFReference(item: CitationItem): any {
    const year = item.date ? parseInt(item.date.match(/\d{4}/)?.[0] || '2025') : 2025;
    
    return {
      type: item.itemType === 'journalArticle' ? 'article' : 'generic',
      title: item.title,
      authors: item.creators?.map(c => ({
        'family-names': c.lastName,
        'given-names': c.firstName
      })) || [],
      year: year,
      doi: item.doi,
      url: item.url
    };
  }

  async formatCitationForOntology(key: string, format: CitationFormat = 'turtle', overrideDoi?: string): Promise<string | null> {
    const initialized = await this.initialize();
    if (!initialized || !this.api) {
      return null;
    }

    try {
      return await this.api.formatCitationForOntology(key, format, overrideDoi);
    } catch (error) {
      console.error('Failed to format citation:', error);
      vscode.window.showErrorMessage(`Failed to format citation: ${error}`);
      return null;
    }
  }

  formatManualCitation(item: CitationItem, format: CitationFormat = 'turtle'): string {
    const key = item.key.replace(/[^a-zA-Z0-9]/g, '');
    const authors = item.creators?.map(c => `${c.firstName} ${c.lastName}`).join(', ') || 'Unknown';
    const year = item.date ? (item.date.match(/\d{4}/)?.[0] || '') : '';

    if (format === 'jsonld') {
      const node = buildZoteroCitationNode({ key, title: item.title, authors, year, doi: item.doi, url: item.url });
      return JSON.stringify(node, null, 2);
    }

    if (format === 'turtle') {
      let ttl = `@prefix rdf: <http://www.w3.org/1999/02/22-rdf-syntax-ns#> .\n`;
      ttl += `@prefix rdfs: <http://www.w3.org/2000/01/rdf-schema#> .\n`;
      ttl += `@prefix owl: <http://www.w3.org/2002/07/owl#> .\n`;
      ttl += `@prefix dc: <http://purl.org/dc/elements/1.1/> .\n`;
      ttl += `@prefix foaf: <http://xmlns.com/foaf/0.1/> .\n`;
      ttl += `@prefix prov: <http://www.w3.org/ns/prov#> .\n`;
      ttl += `@prefix xsd: <http://www.w3.org/2001/XMLSchema#> .\n\n`;
      ttl += `###  Manual Citation: ${item.title}\n`;
      ttl += `<urn:citation:${key}> rdf:type owl:NamedIndividual ,\n`;
      ttl += `         prov:Entity ;\n`;
      ttl += `    dc:title "${this.escapeTurtle(item.title)}" ;\n`;
      ttl += `    dc:creator "${this.escapeTurtle(authors)}" ;\n`;
      if (year) ttl += `    dc:date "${year}"^^xsd:gYear ;\n`;
      if (item.doi) ttl += `    dc:identifier "doi:${this.escapeTurtle(item.doi)}" ;\n`;
      if (item.url) ttl += `    foaf:homepage <${item.url}> ;\n`;
      ttl += `    rdfs:comment "Manually added citation" .\n`;
      return ttl;
    } else {
      // Bare fragment, not a standalone document — see formatCitationForOntology's
      // rdfxml comment in extension.ts for why: this gets inserted directly into
      // an already-open file's existing <rdf:RDF> root, so it must not carry its
      // own <?xml?>/<rdf:RDF> wrapper.
      let xml = `<!-- Manual Citation: ${this.escapeXml(item.title)} -->\n`;
      xml += `<owl:NamedIndividual rdf:about="urn:citation:${key}">\n`;
      xml += `    <rdf:type rdf:resource="http://www.w3.org/ns/prov#Entity"/>\n`;
      xml += `    <dc:title>${this.escapeXml(item.title)}</dc:title>\n`;
      xml += `    <dc:creator>${this.escapeXml(authors)}</dc:creator>\n`;
      if (year) xml += `    <dc:date rdf:datatype="http://www.w3.org/2001/XMLSchema#gYear">${year}</dc:date>\n`;
      if (item.doi) xml += `    <dc:identifier>doi:${this.escapeXml(item.doi)}</dc:identifier>\n`;
      if (item.url) xml += `    <foaf:homepage rdf:resource="${this.escapeXml(item.url)}"/>\n`;
      xml += `    <rdfs:comment>Manually added citation</rdfs:comment>\n`;
      xml += `</owl:NamedIndividual>`;
      return xml;
    }
  }

  private escapeTurtle(str: string): string {
    return str
      .replace(/\\/g, '\\\\')
      .replace(/"/g, '\\"')
      .replace(/\n/g, '\\n')
      .replace(/\r/g, '\\r')
      .replace(/\t/g, '\\t');
  }

  private escapeXml(str: string): string {
    return str
      .replace(/&/g, '&amp;')
      .replace(/</g, '&lt;')
      .replace(/>/g, '&gt;')
      .replace(/"/g, '&quot;')
      .replace(/'/g, '&apos;');
  }

  isAvailable(): boolean {
    return this.api !== null;
  }

  // Helper to find the correct extension ID
  static async findSci2CodeExtension(): Promise<string | null> {
    const allExtensions = vscode.extensions.all;
    
    // Search for extensions with "sci2code" in their ID
    for (const ext of allExtensions) {
      if (ext.id.toLowerCase().includes('sci2code')) {
        console.log('Found potential Sci2Code extension:', ext.id);
        return ext.id;
      }
    }
    
    return null;
  }
}

export const sci2CodeService = new Sci2CodeService();

// Export helper to find extension
export async function detectSci2CodeExtension(): Promise<void> {
  const extensionId = await Sci2CodeService.findSci2CodeExtension();
  
  if (extensionId) {
    vscode.window.showInformationMessage(
      `Found Sci2Code extension: ${extensionId}. Please update your configuration with this ID.`
    );
    console.log('Sci2Code Extension ID:', extensionId);
  } else {
    vscode.window.showWarningMessage(
      'Sci2Code extension not found. Please install it from the marketplace.'
    );
  }
}
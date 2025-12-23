import * as vscode from 'vscode';

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
  formatCitationForOntology(key: string, format?: 'turtle' | 'rdfxml'): Promise<string>;
  getCitationMetadata(key: string): Promise<CitationItem | null>;
  isAuthenticated?(): Promise<boolean>; // Make it optional
}

class Sci2CodeService {
  private api: Sci2CodeAPI | null = null;
  private extensionId = 'SelfResearchInstitute.sci2code'; // IMPORTANT: Update with actual extension ID
  private initializationAttempted = false;

  async initialize(): Promise<boolean> {
    if (this.initializationAttempted && this.api !== null) {
      return true;
    }

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
      
      // Check authentication (optional, don't fail if method doesn't exist)
      if (typeof this.api.isAuthenticated === 'function') {
        const isAuth = await this.api.isAuthenticated();
        console.log('Sci2Code authentication status:', isAuth);
        
        if (!isAuth) {
          const login = await vscode.window.showInformationMessage(
            'Please log in to Zotero to use citation features.',
            'Login', 'Cancel'
          );
          
          if (login === 'Login') {
            await vscode.commands.executeCommand('sci2code.login');
          }
          return false;
        }
      } else {
        console.log('isAuthenticated method not available, skipping auth check');
      }

      return true;
      
    } catch (error) {
      console.error('Failed to initialize Sci2Code:', error);
      
      vscode.window.showErrorMessage(
        `Failed to initialize Sci2Code: ${error instanceof Error ? error.message : String(error)}`
      );
      
      return false;
    }
  }

  async getZoteroLibrary(): Promise<any[]> {
    if (!this.api) {
      const initialized = await this.initialize();
      if (!initialized || !this.api) {
        return [];
      }
    }
    
    try {
      console.log('Fetching Zotero library...');
      const items = await this.api.getZoteroLibrary();
      console.log(`Fetched ${items?.length || 0} items from Zotero`);
      return items || [];
    } catch (error) {
      console.error('Failed to get Zotero library:', error);
      vscode.window.showErrorMessage(
        'Failed to load Zotero library. Please check your Sci2Code configuration.'
      );
      return [];
    }
  }

  async getCitationMetadata(key: string): Promise<CitationItem | null> {
    if (!this.api) {
      await this.initialize();
    }

    if (!this.api) {
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

  async formatCitationForOntology(key: string, format: 'turtle' | 'rdfxml' = 'turtle'): Promise<string | null> {
    if (!this.api) {
      await this.initialize();
    }

    if (!this.api) {
      return null;
    }

    try {
      return await this.api.formatCitationForOntology(key, format);
    } catch (error) {
      console.error('Failed to format citation:', error);
      vscode.window.showErrorMessage(`Failed to format citation: ${error}`);
      return null;
    }
  }

  formatManualCitation(item: CitationItem, format: 'turtle' | 'rdfxml' = 'turtle'): string {
    const key = item.key.replace(/[^a-zA-Z0-9]/g, '');
    const authors = item.creators?.map(c => `${c.firstName} ${c.lastName}`).join(', ') || 'Unknown';
    const year = item.date ? (item.date.match(/\d{4}/)?.[0] || '') : '';
    
    if (format === 'turtle') {
      let ttl = `###  Manual Citation: ${item.title}\n`;
      ttl += `:${key} rdf:type owl:NamedIndividual ,\n`;
      ttl += `         prov:Entity ;\n`;
      ttl += `    dc:title "${item.title}" ;\n`;
      ttl += `    dc:creator "${authors}" ;\n`;
      if (year) ttl += `    dc:date "${year}"^^xsd:gYear ;\n`;
      if (item.doi) ttl += `    dc:identifier "doi:${item.doi}" ;\n`;
      if (item.url) ttl += `    foaf:homepage <${item.url}> ;\n`;
      ttl += `    rdfs:comment "Manually added citation" .\n`;
      return ttl;
    } else {
      let xml = `    <!-- Manual Citation: ${item.title} -->\n`;
      xml += `    <owl:NamedIndividual rdf:about="&ont;${key}">\n`;
      xml += `        <rdf:type rdf:resource="http://www.w3.org/ns/prov#Entity"/>\n`;
      xml += `        <dc:title>${item.title}</dc:title>\n`;
      xml += `        <dc:creator>${authors}</dc:creator>\n`;
      if (year) xml += `        <dc:date rdf:datatype="&xsd;gYear">${year}</dc:date>\n`;
      if (item.doi) xml += `        <dc:identifier>doi:${item.doi}</dc:identifier>\n`;
      if (item.url) xml += `        <foaf:homepage rdf:resource="${item.url}"/>\n`;
      xml += `        <rdfs:comment>Manually added citation</rdfs:comment>\n`;
      xml += `    </owl:NamedIndividual>\n`;
      return xml;
    }
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
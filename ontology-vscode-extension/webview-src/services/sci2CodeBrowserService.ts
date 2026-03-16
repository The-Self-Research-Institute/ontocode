/**
 * Browser-compatible Sci2Code service.
 * Mirrors the VS Code sci2CodeService + zoteroApiService for standalone web usage.
 * Calls the Zotero public API directly and formats citations locally.
 */

// ─── Types ──────────────────────────────────────────────────────────────────

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

export interface ZoteroItem {
  key: string;
  version: number;
  data: {
    key: string;
    version: number;
    itemType: string;
    title: string;
    creators: Array<{ creatorType: string; firstName: string; lastName: string }>;
    abstractNote?: string;
    date?: string;
    DOI?: string;
    url?: string;
    publicationTitle?: string;
    volume?: string;
    issue?: string;
    pages?: string;
    publisher?: string;
    tags?: Array<{ tag: string }>;
  };
}

interface ZoteroConfig {
  apiKey: string;
  userId: string;
  libraryType: 'user' | 'group';
  groupId?: string;
}

// ─── Storage keys ───────────────────────────────────────────────────────────

const STORAGE_KEY_API = 'zoteroApiKey';
const STORAGE_KEY_USER = 'zoteroUserId';
const STORAGE_KEY_LIB_TYPE = 'zoteroLibraryType';
const STORAGE_KEY_GROUP = 'zoteroGroupId';

// ─── Service ────────────────────────────────────────────────────────────────

class Sci2CodeBrowserService {
  private baseUrl = 'https://api.zotero.org';
  private cachedItems: ZoteroItem[] | null = null;

  // ── Config ──────────────────────────────────────────────────────────────

  getConfig(): ZoteroConfig | null {
    const apiKey = localStorage.getItem(STORAGE_KEY_API);
    const userId = localStorage.getItem(STORAGE_KEY_USER);
    if (!apiKey || !userId) return null;
    return {
      apiKey,
      userId,
      libraryType: (localStorage.getItem(STORAGE_KEY_LIB_TYPE) as 'user' | 'group') || 'user',
      groupId: localStorage.getItem(STORAGE_KEY_GROUP) || undefined,
    };
  }

  isConfigured(): boolean {
    return this.getConfig() !== null;
  }

  saveConfig(cfg: { apiKey: string; userId?: string; libraryType?: string; groupId?: string }) {
    localStorage.setItem(STORAGE_KEY_API, cfg.apiKey);
    if (cfg.userId) localStorage.setItem(STORAGE_KEY_USER, cfg.userId);
    if (cfg.libraryType) localStorage.setItem(STORAGE_KEY_LIB_TYPE, cfg.libraryType);
    if (cfg.groupId) localStorage.setItem(STORAGE_KEY_GROUP, cfg.groupId);
    this.cachedItems = null; // bust cache
  }

  /**
   * Save config, auto-resolving userId from API key if not provided.
   */
  async saveConfigAutoResolve(cfg: { apiKey: string; libraryType?: string; groupId?: string }): Promise<string | null> {
    const userId = await this.fetchUserIdFromApiKey(cfg.apiKey);
    if (!userId) return null;
    this.saveConfig({ ...cfg, userId });
    return userId;
  }

  clearConfig() {
    localStorage.removeItem(STORAGE_KEY_API);
    localStorage.removeItem(STORAGE_KEY_USER);
    localStorage.removeItem(STORAGE_KEY_LIB_TYPE);
    localStorage.removeItem(STORAGE_KEY_GROUP);
    this.cachedItems = null;
  }

  // ── Zotero API ─────────────────────────────────────────────────────────

  /**
   * Fetch the user ID associated with an API key via GET /keys/{key}.
   * Returns the numeric userID string, or null on failure.
   */
  async fetchUserIdFromApiKey(apiKey: string): Promise<string | null> {
    try {
      const resp = await fetch(`${this.baseUrl}/keys/${encodeURIComponent(apiKey)}`, {
        headers: { 'Zotero-API-Version': '3' },
      });
      if (!resp.ok) return null;
      const data = await resp.json();
      return data.userID ? String(data.userID) : null;
    } catch {
      return null;
    }
  }

  async fetchLibrary(limit = 100): Promise<ZoteroItem[]> {
    const cfg = this.getConfig();
    if (!cfg) throw new Error('Zotero not configured');

    const libraryPath = cfg.libraryType === 'group' && cfg.groupId
      ? `groups/${cfg.groupId}`
      : `users/${cfg.userId}`;

    const resp = await fetch(
      `${this.baseUrl}/${libraryPath}/items?limit=${limit}&format=json&include=data&itemType=-attachment`,
      {
        headers: {
          'Zotero-API-Key': cfg.apiKey,
          'Zotero-API-Version': '3',
        },
      }
    );

    if (!resp.ok) {
      if (resp.status === 403) throw new Error('Invalid Zotero API key');
      if (resp.status === 404) throw new Error('Zotero user/group not found');
      throw new Error(`Zotero API error: ${resp.status}`);
    }

    const items: ZoteroItem[] = await resp.json();
    this.cachedItems = items;
    return items;
  }

  async fetchItem(itemKey: string): Promise<ZoteroItem | null> {
    const cfg = this.getConfig();
    if (!cfg) return null;

    const libraryPath = cfg.libraryType === 'group' && cfg.groupId
      ? `groups/${cfg.groupId}`
      : `users/${cfg.userId}`;

    const resp = await fetch(
      `${this.baseUrl}/${libraryPath}/items/${itemKey}?format=json&include=data`,
      {
        headers: {
          'Zotero-API-Key': cfg.apiKey,
          'Zotero-API-Version': '3',
        },
      }
    );

    if (!resp.ok) return null;
    return resp.json();
  }

  async testConnection(): Promise<boolean> {
    const cfg = this.getConfig();
    if (!cfg) return false;

    const libraryPath = cfg.libraryType === 'group' && cfg.groupId
      ? `groups/${cfg.groupId}`
      : `users/${cfg.userId}`;

    const resp = await fetch(
      `${this.baseUrl}/${libraryPath}/items?limit=1&format=json`,
      {
        headers: {
          'Zotero-API-Key': cfg.apiKey,
          'Zotero-API-Version': '3',
        },
      }
    );
    return resp.ok;
  }

  // ── Citation metadata ──────────────────────────────────────────────────

  zoteroItemToCitationItem(zi: ZoteroItem): CitationItem {
    return {
      key: zi.data.key || zi.key,
      title: zi.data.title || '',
      creators: (zi.data.creators || []).map(c => ({
        firstName: c.firstName || '',
        lastName: c.lastName || '',
        creatorType: c.creatorType || 'author',
      })),
      date: zi.data.date || '',
      doi: zi.data.DOI,
      url: zi.data.url,
      itemType: zi.data.itemType || 'journalArticle',
      abstractNote: zi.data.abstractNote,
      publicationTitle: zi.data.publicationTitle,
      volume: zi.data.volume,
      issue: zi.data.issue,
      pages: zi.data.pages,
      publisher: zi.data.publisher,
      tags: zi.data.tags,
    };
  }

  async getCitationMetadata(key: string): Promise<CitationItem | null> {
    // Try cache first
    if (this.cachedItems) {
      const found = this.cachedItems.find(i => (i.data?.key || i.key) === key);
      if (found) return this.zoteroItemToCitationItem(found);
    }
    const item = await this.fetchItem(key);
    return item ? this.zoteroItemToCitationItem(item) : null;
  }

  // ── Citation formatting (mirrors sci2CodeService.formatManualCitation) ──

  formatCitationForOntology(item: CitationItem, format: 'turtle' | 'rdfxml' = 'turtle'): string {
    const key = item.key.replace(/[^a-zA-Z0-9]/g, '');
    const authors = item.creators?.map(c => `${c.firstName} ${c.lastName}`).join(', ') || 'Unknown';
    const year = item.date ? (item.date.match(/\d{4}/)?.[0] || '') : '';

    if (format === 'turtle') {
      let ttl = `@prefix rdf: <http://www.w3.org/1999/02/22-rdf-syntax-ns#> .\n`;
      ttl += `@prefix rdfs: <http://www.w3.org/2000/01/rdf-schema#> .\n`;
      ttl += `@prefix owl: <http://www.w3.org/2002/07/owl#> .\n`;
      ttl += `@prefix dc: <http://purl.org/dc/elements/1.1/> .\n`;
      ttl += `@prefix foaf: <http://xmlns.com/foaf/0.1/> .\n`;
      ttl += `@prefix prov: <http://www.w3.org/ns/prov#> .\n`;
      ttl += `@prefix xsd: <http://www.w3.org/2001/XMLSchema#> .\n\n`;
      ttl += `###  Citation: ${item.title}\n`;
      ttl += `<urn:citation:${key}> rdf:type owl:NamedIndividual ,\n`;
      ttl += `         prov:Entity ;\n`;
      ttl += `    dc:title "${this.escapeTurtle(item.title)}" ;\n`;
      ttl += `    dc:creator "${this.escapeTurtle(authors)}" ;\n`;
      if (year) ttl += `    dc:date "${year}"^^xsd:gYear ;\n`;
      if (item.doi) ttl += `    dc:identifier "doi:${this.escapeTurtle(item.doi)}" ;\n`;
      if (item.url) ttl += `    foaf:homepage <${item.url}> ;\n`;
      ttl += `    rdfs:comment "Zotero citation" .\n`;
      return ttl;
    } else {
      let xml = `<?xml version="1.0"?>\n`;
      xml += `<rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#"\n`;
      xml += `         xmlns:rdfs="http://www.w3.org/2000/01/rdf-schema#"\n`;
      xml += `         xmlns:owl="http://www.w3.org/2002/07/owl#"\n`;
      xml += `         xmlns:dc="http://purl.org/dc/elements/1.1/"\n`;
      xml += `         xmlns:foaf="http://xmlns.com/foaf/0.1/"\n`;
      xml += `         xmlns:prov="http://www.w3.org/ns/prov#"\n`;
      xml += `         xmlns:xsd="http://www.w3.org/2001/XMLSchema#">\n\n`;
      xml += `    <!-- Citation: ${this.escapeXml(item.title)} -->\n`;
      xml += `    <owl:NamedIndividual rdf:about="urn:citation:${key}">\n`;
      xml += `        <rdf:type rdf:resource="http://www.w3.org/ns/prov#Entity"/>\n`;
      xml += `        <dc:title>${this.escapeXml(item.title)}</dc:title>\n`;
      xml += `        <dc:creator>${this.escapeXml(authors)}</dc:creator>\n`;
      if (year) xml += `        <dc:date rdf:datatype="http://www.w3.org/2001/XMLSchema#gYear">${year}</dc:date>\n`;
      if (item.doi) xml += `        <dc:identifier>doi:${this.escapeXml(item.doi)}</dc:identifier>\n`;
      if (item.url) xml += `        <foaf:homepage rdf:resource="${this.escapeXml(item.url)}"/>\n`;
      xml += `        <rdfs:comment>Zotero citation</rdfs:comment>\n`;
      xml += `    </owl:NamedIndividual>\n`;
      xml += `</rdf:RDF>`;
      return xml;
    }
  }

  // ── BibTeX / CFF helpers ───────────────────────────────────────────────

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

  convertToCFFReference(item: CitationItem): Record<string, unknown> {
    const year = item.date ? parseInt(item.date.match(/\d{4}/)?.[0] || '2025') : 2025;
    return {
      type: item.itemType === 'journalArticle' ? 'article' : 'generic',
      title: item.title,
      authors: item.creators?.map(c => ({
        'family-names': c.lastName,
        'given-names': c.firstName,
      })) || [],
      year,
      doi: item.doi,
      url: item.url,
    };
  }

  // ── Escaping helpers ───────────────────────────────────────────────────

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
}

export const sci2CodeBrowserService = new Sci2CodeBrowserService();

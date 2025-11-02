import type { ComponentType } from 'react';
import { LucideIcon } from 'lucide-react';

// VS Code Webview API types
interface VSCodeApi {
  postMessage(message: { type: string; value?: any; [key: string]: any }): void;
  getState(): any;
  setState(newState: any): void;
}

declare global {
  interface Window {
    vscode?: VSCodeApi;
  }
}

// New interfaces for plugin context
export interface NotificationService {
  success: (message: string) => void;
  error: (message: string) => void;
  info: (message: string) => void;
}

export interface PluginContext {
  projectId: string;
  apiClient: {
    get: <T>(url: string, config?: { params?: Record<string, unknown> }) => Promise<{ data: T }>;
    post: <T>(url: string, body?: unknown) => Promise<{ data: T }>;
    delete: <T>(url: string, config?: { params?: Record<string, unknown> }) => Promise<{ data: T }>;
  };
  notificationService: NotificationService;
}

// Ontology Entity Types
export interface Axiom {
  id: string;
  type: 'SubClassOf' | 'EquivalentTo' | 'DisjointWith';
  definition: string;
}

export interface PropertyAssertion {
    id: string;
    propertyIri: string;
    propertyLabel: string;
    targetIri?: string;
    targetLabel?: string;
    targetLiteral?: string;
    isObjectProperty: boolean;
}

export interface TreeNode {
  id: string;
  label: string;
  annotations?: Record<string, string>;
  children?: TreeNode[] | null;
  // Class-specific axioms
  equivalentClassesAxioms?: Axiom[];
  subClassOfAxioms?: Axiom[];
  disjointClassesAxioms?: Axiom[];
}

export interface Property {
  id: string;
  iri: string;
  label: string;
  type: string;
  annotations?: Record<string, string>;
  domains?: string[];
  ranges?: string[];
  characteristics?: string[];
  superProperties?: string[];
  subProperties?: string[];
  children?: Property[];
}

export interface Individual {
  id: string;
  iri: string;
  label: string;
  annotations?: Record<string, string>;
  types?: string[];
  sameAs?: string[];
  differentFrom?: string[];
  propertyAssertions?: PropertyAssertion[];
}

export interface AnnotationProperty {
  id: string;
  iri: string;
  label: string;
  annotations?: Record<string, string>;
}

export interface Datatype {
  id: string;
  iri: string;
  label: string;
  annotations?: Record<string, string>;
}

export type SelectableItem =
  | TreeNode
  | Property
  | Individual
  | AnnotationProperty
  | Datatype;
  
export interface OntologyPrefix {
    prefix: string;
    namespace: string;
}

export interface OntologyMetadata {
  filename: string;
  ontologyIRI: string | null;
  versionIRI: string | null;
  classCount: number;
  objectPropertyCount: number;
  dataPropertyCount: number;
  individualCount: number;
  axiomCount: number;
  annotations?: Record<string, string>;
  prefixes?: OntologyPrefix[];
  logicalAxiomCount?: number;
  declarationAxiomCount?: number;
  subClassOfAxiomCount?: number;
  equivalentClassesAxiomCount?: number;
  disjointClassesAxiomCount?: number;
  subObjectPropertyOfAxiomCount?: number;
  inverseObjectPropertiesAxiomCount?: number;
}

export interface ClassUsage {
  classIri: string;
  totalUsages: number;
  usages: AxiomUsage[];
}

export interface AxiomUsage {
  category: string;
  description: string;
  relatedEntity: string;
  axiomType: string;
}


// SWRL Types
export interface SWRLRule {
  id: string;
  name: string;
  ruleText: string;
  comment?: string;
  enabled: boolean;
}

export interface ValidationResult {
    valid: boolean;
    message: string;
}

export interface SQWRLResult {
    columns: string[];
    rows: Record<string, any>[];
}

// SPARQL Types
export interface SparqlQuery {
  id: string;
  name: string;
  queryText: string;
}

interface SparqlBinding {
  [key: string]: {
    type: 'uri' | 'literal' | 'bnode';
    value: string;
    datatype?: string;
    'xml:lang'?: string;
  };
}

export interface SparqlQueryResult {
  head: {
    vars: string[];
  };
  results: {
    bindings: SparqlBinding[];
  };
}


// Plugin System Types
export interface OntologyPlugin {
  id: string;
  name: string;
  version: string;
  description: string;
  author: string;
  icon?: LucideIcon;
  component: ComponentType<{ context: PluginContext; [key: string]: any }>;
  activate: () => Promise<void>;
  deactivate: () => Promise<void>;
}
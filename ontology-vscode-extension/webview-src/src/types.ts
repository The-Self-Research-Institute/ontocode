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

// Ontology Entity Types
export interface TreeNode {
  id: string;
  label: string;
  annotations?: Record<string, string>;
  children?: TreeNode[] | null;
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

// Plugin System Types
export interface OntologyPlugin {
  id: string;
  name: string;
  version: string;
  description: string;
  author: string;
  icon?: LucideIcon;
  // Fix: Cannot find namespace 'React'.
  component: ComponentType<any>;
  activate: () => Promise<void>;
  deactivate: () => Promise<void>;
}
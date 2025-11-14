import { LucideIcon } from 'lucide-react';
import React from 'react';

// ============ Base Entity Types ============

export interface TreeNode {
  id: string;
  label: string;
  parent?: string;
  children?: TreeNode[] | null;
  hasChildren?: boolean;
  annotations?: Record<string, string>;
  equivalentClassesAxioms?: Axiom[];
  subClassOfAxioms?: Axiom[];
  disjointClassesAxioms?: Axiom[];
  usage?: ClassUsage;
}

export interface Property {
  id: string;
  label: string;
  type: 'ObjectProperty' | 'DatatypeProperty' | 'AnnotationProperty';
  domains?: string[];
  ranges?: string[];
  superProperties?: string[];
  characteristics?: string[];
  annotations?: Record<string, string>;
  usage?: PropertyUsage;
}

export interface Individual {
  id: string;
  label: string;
  types?: string[];
  annotations?: Record<string, string>;
  propertyAssertions?: PropertyAssertion[];
}

export interface PropertyAssertion {
  id: string;
  propertyIri: string;
  propertyLabel: string;
  targetIri?: string;
  targetLabel?: string;
  targetLiteral?: string;
  isObjectProperty: boolean;
  datatypeIri?: string;
}

export interface Axiom {
  id: string;
  type: 'EquivalentTo' | 'SubClassOf' | 'DisjointWith';
  definition: string;
}

// ============ Metadata Types ============

export interface OntologyMetadata {
  ontologyIRI?: string;
  versionIRI?: string;
  imports?: string[];
  annotations?: Record<string, string>;
  classCount?: number;
  objectPropertyCount?: number;
  dataPropertyCount?: number;
  annotationPropertyCount?: number;
  individualCount?: number;
  axiomCount?: number;
}

export interface OntologyPrefix {
  prefix: string;
  namespace: string;
}

export interface OntologyStatistics {
  classCount: number;
  objectPropertyCount: number;
  dataPropertyCount: number;
  annotationPropertyCount: number;
  individualCount: number;
  axiomCount: number;
  logicalAxiomCount: number;
  declarationAxiomCount: number;
  subClassOfAxiomCount: number;
  equivalentClassesAxiomCount: number;
  disjointClassesAxiomCount: number;
  gciCount: number;
  hiddenGciCount: number;
}

export interface ProjectStatus {
  status: 'UPLOADED' | 'PROCESSING' | 'COMPLETED' | 'ERROR';
  statusMessage: string;
  updatedAt: string;
}

// ============ Usage Statistics ============

export interface ClassUsage {
  instanceCount: number;
  subClassCount: number;
  propertyDomainCount: number;
  propertyRangeCount: number;
}

export interface PropertyUsage {
  assertionCount: number;
  domainUsageCount: number;
  rangeUsageCount: number;
}

export interface AxiomUsage {
  entityIri: string;
  axiomType: string;
  count: number;
}

// ============ SPARQL Types ============

export interface SparqlQuery {
  id: string;
  name: string;
  queryText: string;
  projectId?: string;
  createdAt?: string;
  updatedAt?: string;
}

export interface SparqlQueryResult {
  head: {
    vars: string[];
  };
  results: {
    bindings: Array<Record<string, {
      type: 'uri' | 'literal' | 'bnode';
      value: string;
      'xml:lang'?: string;
      datatype?: string;
    }>>;
  };
}

// ============ SWRL Types ============

export interface SwrlRule {
  id: string;
  projectId: string;
  ruleName: string;
  ruleText: string;
  comment?: string;
  category?: string;
  enabled: boolean;
  createdAt: string;
  updatedAt: string;
}

export interface ValidationResult {
  valid: boolean;
  errorMessage: string | null;
  suggestions?: string[];
  
  isValid: boolean;
  orphanClasses?: string[];
  unusedProperties?: string[];
  missingLabels?: string[];
  circularDependencies?: string[];
}

export interface InferredAxiom {
  axiomType: string;
  description: string;
  readable: string;
}

export interface ExecutionResponse {
  success: boolean;
  executionTimeMs: number;
  inferredAxiomsCount: number;
  totalRulesExecuted: number;
  inferredAxioms: InferredAxiom[];
  errorMessage: string | null;
}

// ============ Neo4j Graph Types ============

export interface OntologyClassNode {
  iri: string;
  label: string;
  projectId: string;
  comment?: string;
  deprecated: boolean;
  superClasses?: OntologyClassNode[];
  parents?: OntologyClassNode[]; 
  hasChildren?: boolean;
}

export interface PropertyNode {
  iri: string;
  label: string;
  type: 'ObjectProperty' | 'DatatypeProperty';
  projectId: string;
  functional: boolean;
  inverseFunctional?: boolean;
  transitive?: boolean;
  symmetric?: boolean;
}

export interface ClassStatistics {
  classIri: string;
  label: string;
  instanceCount: number;
  subClassCount: number;
}

// ============ Plugin System Types ============

export interface PluginContext {
  projectId: string;
  ontology?: OntologyMetadata | null;
}

export interface OntologyPlugin {
  id: string;
  name: string;
  version: string;
  description: string;
  author: string;
  icon: LucideIcon;
  component: React.ComponentType<any>;
  activate: (context?: PluginContext) => Promise<boolean>;
  deactivate: (context?: PluginContext) => Promise<boolean>;
  settings?: PluginSettings;
}

export interface PluginSettings {
  [key: string]: {
    type: 'string' | 'number' | 'boolean' | 'select';
    label: string;
    default: any;
    options?: any[];
  };
}

// ============ UI Types ============

export type SelectableItem = TreeNode | Property | Individual | { id: string; label: string, annotations?: Record<string, string> };

export interface TabConfig {
  id: string;
  label: string;
  icon: LucideIcon;
}

// ============ Search Types ============

export interface SearchResult {
  id: string;
  label: string;
  type: 'Class' | 'ObjectProperty' | 'DatatypeProperty' | 'Individual' | 'AnnotationProperty';
  score: number;
}

// ============ API Response Types ============

export interface ApiResponse<T> {
  success: boolean;
  data?: T;
  error?: string;
  message?: string;
}

export interface PagedResponse<T> {
  data: T[];
  total: number;
  page: number;
  pageSize: number;
  hasMore: boolean;
}

// ============ Form Types ============

export interface CreateClassRequest {
  iri: string;
  label: string;
  parentIri?: string;
}

export interface UpdateClassRequest {
  iri: string;
  label?: string;
  parentIri?: string;
}

export interface CreatePropertyRequest {
  type: 'ObjectProperty' | 'DatatypeProperty' | 'AnnotationProperty';
  iri: string;
  label: string;
  domains?: string[];
  ranges?: string[];
}

export interface CreateIndividualRequest {
  iri: string;
  label: string;
  types: string[];
}

export interface AddAnnotationRequest {
  subjectIri: string;
  propertyIri: string;
  value: string;
  lang?: string;
  datatypeIri?: string;
}

export interface PropertyAssertionRequest {
  subjectIri: string;
  propertyIri: string;
  objectIri?: string;
  literalValue?: string;
  datatypeIri?: string;
}

// ============ Import/Export Types ============

export interface ImportOptions {
  format: 'RDF/XML' | 'Turtle' | 'N-Triples' | 'JSON-LD';
  clearExisting: boolean;
  validateOnImport: boolean;
}

export interface ExportOptions {
  format: 'RDF/XML' | 'Turtle' | 'N-Triples' | 'JSON-LD' | 'Manchester';
  includeImports: boolean;
  includeInferredAxioms: boolean;
}

// ============ Reasoning Types ============

export interface ReasonerConfig {
  name: 'HermiT' | 'Pellet' | 'ELK' | 'Openllet';
  enabled: boolean;
  inferSubClasses: boolean;
  inferTypes: boolean;
  inferProperties: boolean;
}

export interface ReasoningResult {
  success: boolean;
  inferredAxiomsCount: number;
  inferredSubClasses?: Array<{ subClass: string; superClass: string }>;
  inferredTypes?: Array<{ individual: string; type: string }>;
  inconsistencies?: string[];
  executionTimeMs: number;
}

// ============ Diff/Version Types ============

export interface OntologyChange {
  id: string;
  type: 'ADD' | 'REMOVE' | 'MODIFY';
  entityType: 'Class' | 'Property' | 'Individual' | 'Axiom';
  entityIri: string;
  oldValue?: any;
  newValue?: any;
  timestamp: string;
  author?: string;
}

export interface OntologyVersion {
  id: string;
  versionNumber: string;
  createdAt: string;
  author: string;
  message: string;
  changes: OntologyChange[];
}

// ============ Collaboration Types ============

export interface User {
  id: string;
  username: string;
  email: string;
  role: 'admin' | 'editor' | 'viewer';
}

export interface ProjectPermission {
  projectId: string;
  userId: string;
  role: 'owner' | 'editor' | 'viewer';
  grantedAt: string;
  grantedBy: string;
}

// ============ Notification Types ============

export interface Notification {
  id: string;
  type: 'success' | 'error' | 'warning' | 'info';
  message: string;
  details?: string;
  timestamp: string;
  read: boolean;
}

// ============ Theme Types ============

export interface Theme {
  id: string;
  name: string;
  colors: {
    primary: string;
    secondary: string;
    accent: string;
    background: string;
    surface: string;
    text: string;
    error: string;
    warning: string;
    success: string;
    info: string;
  };
}

// ============ Editor State Types ============

export interface EditorState {
  selectedItem: SelectableItem | null;
  expandedNodes: string[];
  searchQuery: string;
  activeTab: string;
  entitiesTab: string;
  isDirty: boolean;
  undoStack: OntologyChange[];
  redoStack: OntologyChange[];
}

// ============ Configuration Types ============

export interface AppConfig {
  backendUrl: string;
  theme: string;
  autoSave: boolean;
  autoSaveInterval: number;
  showLineNumbers: boolean;
  fontSize: number;
  enableValidation: boolean;
  enableReasoning: boolean;
  reasonerConfig: ReasonerConfig;
}

export default {};
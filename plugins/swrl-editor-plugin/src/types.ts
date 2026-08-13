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

export interface PagedResponse<T> {
  content: T[];
  pageable: {
    pageNumber: number;
    pageSize: number;
  };
  totalElements: number;
  totalPages: number;
  first: boolean;
  last: boolean;
}

export interface ValidationResult {
  valid: boolean;
  errorMessage: string | null;
  suggestions?: string[];

  parsedAtoms?: ParsedAtom[];
  usedBuiltIns?: string[];
  usedClasses?: string[];
  usedProperties?: string[];
}

export type AtomType = 
  | 'ClassAtom' 
  | 'DataPropertyAtom' 
  | 'ObjectPropertyAtom' 
  | 'SameIndividualAtom' 
  | 'DifferentIndividualsAtom' 
  | 'BuiltInAtom' 
  | 'DataRangeAtom';

export interface ParsedAtom {
  type: AtomType;
  predicate: string;
  arguments: string[];
}

export interface BuiltInCategory {
  prefix: string;
  name: string;
  description: string;
  builtIns: BuiltInInfo[];
}

export interface BuiltInInfo {
  name: string;
  fullName: string;
  description: string;
  signature: string;
  example?: string;
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
  executedRuleNames?: string[]; // Names of rules that were actually executed
  executionMode?: 'all' | 'selected'; // Indicates if all rules or selected rules were executed
}

export interface PluginContext {
  projectId: string;
  ontology?: any | null;
}

export interface SQWRLResult {
  columns: string[];
  rows: Record<string, any>[];
  executionTimeMs?: number;
}

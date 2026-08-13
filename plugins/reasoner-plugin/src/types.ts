

export type ReasonerType = 'hermit' | 'pellet' | 'fact++' | 'elk';

export type ReasoningTask = 
  | 'consistency'
  | 'classification'
  | 'realization'
  | 'satisfiability'
  | 'entailment'
  | 'explanation';

export interface ReasonerConfig {
  reasonerType: ReasonerType;
  timeout: number; // milliseconds
  useIncrementalReasoning: boolean;
  cacheResults: boolean;
  maxConcurrentTasks: number;
}

export interface ConsistencyResult {
  isConsistent: boolean;
  timestamp: string;
  duration: number;
  errors?: string[];
  warnings?: string[];
  unsatisfiableClasses?: UnsatisfiableClass[];
}

export interface UnsatisfiableClass {
  iri: string;
  label: string;
}

export interface InconsistencyExplanation {
  isConsistent: boolean;
  unsatisfiableClasses: UnsatisfiableClass[];
  explanations: ClassExplanation[];
  reasonerType: string;
  timestamp?: string;
}

export interface ClassExplanation {
  classIri: string;
  classLabel: string;
  reason: string;
  axioms: string[];
}

export interface ClassificationResult {
  timestamp: string;
  duration: number;
  classHierarchy: ClassNode[];
  equivalentClasses: string[][];
  unsatisfiableClasses: string[];
}

export interface ClassNode {
  iri: string;
  label: string;
  superClasses: string[];
  subClasses: string[];
  equivalentClasses: string[];
  satisfiable: boolean;
}

export interface RealizationResult {
  timestamp: string;
  duration: number;
  instances: InstanceMapping[];
}

export interface InstanceMapping {
  individualIri: string;
  individualLabel: string;
  directTypes: string[];
  allTypes: string[];
}

export interface SatisfiabilityResult {
  classIri: string;
  isSatisfiable: boolean;
  explanation?: string;
  duration: number;
}

export interface EntailmentResult {
  axiom: string;
  isEntailed: boolean;
  explanation?: string;
  duration: number;
}

export interface ExplanationResult {
  axiom: string;
  explanations: Explanation[];
  timestamp: string;
}

export interface Explanation {
  axioms: string[];
  description: string;
  size: number;
}

export interface ReasonerStatus {
  isRunning: boolean;
  currentTask?: ReasoningTask;
  progress?: number;
  message?: string;
}

export interface InferredAxiom {
  type: 'subClassOf' | 'equivalentClass' | 'disjointWith' | 'instanceOf' | 'sameAs' | 'differentFrom';
  subject: string;
  predicate?: string;
  object: string;
  confidence: number;
  explanation?: string;
}

export interface ReasonerStats {
  totalClasses: number;
  totalIndividuals: number;
  totalProperties: number;
  satisfiableClasses: number;
  unsatisfiableClasses: number;
  inferredAxioms: number;
  lastReasoningTime: number;
}

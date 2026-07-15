// Shared types for Dashboard hooks inter-communication
import type {
  TreeNode,
  Property,
  Individual,
  OntologyMetadata,
  SelectableItem,
  AnnotationProperty,
  Datatype,
} from "../../../types";
import type { CollaborationPanelRef } from "../../CollaborationPanel";
import type { FileInfo } from "../dashboardUtils";

export interface DashboardProps {
  onBackToProjects?: () => void;
  onFileSelected?: (fileId: string, fileName: string) => void;
  selectedFileId?: string;
  selectedFileName?: string;
  projectId?: string;
}

export interface ConfirmDialogState {
  isOpen: boolean;
  title: string;
  message: string;
  onConfirm: () => void;
  onCancel?: () => void;
  confirmLabel?: string;
  cancelLabel?: string;
}

export interface DuplicatePromptState {
  isOpen: boolean;
  requestId: string | null;
  fileName: string;
  context: "project" | "ontology";
  projectId?: string;
  ownerEmail?: string;
  defaultCopyName?: string;
  detail?: string;
  allowOpenExisting?: boolean;
  error?: string;
}

export interface ExplanationState {
  open: boolean;
  loading: boolean;
  data: any;
  error: string | null;
}

export interface PrefixDialogData {
  prefix: string;
  namespace: string;
  isEdit: boolean;
  originalPrefix: string;
}

export interface ImportDialogData {
  iri: string;
  isEdit: boolean;
  originalIri: string;
}

export interface EditAnnotationData {
  propertyIri: string;
  currentValue: string;
  entityId: string;
  language?: string;
  datatype?: string;
  originalPropertyIri?: string;
}

export interface EditGCIData {
  subClass: string;
  superClass: string;
  value: string;
  index: number;
}

export interface OntologyAnnotation {
  propertyIri: string;
  value: string;
  datatype?: string;
  lang?: string;
}

export interface GeneralClassAxiom {
  subExpression: string;
  superClassIri?: string;
  superClassLabel?: string;
  definition?: string;
}

export interface ProjectImportStatus {
  type: string;
  status: string;
  progress?: number;
}

export interface DeleteFileDialogState {
  isOpen: boolean;
  projectId: string;
  fileName: string;
}

export interface UnsavedChangesDialogState {
  isOpen: boolean;
  onLeave: () => void;
}

// Re-export commonly used types
export type {
  TreeNode,
  Property,
  Individual,
  OntologyMetadata,
  SelectableItem,
  AnnotationProperty,
  Datatype,
  FileInfo,
  CollaborationPanelRef,
};

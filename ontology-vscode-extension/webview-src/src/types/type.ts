export interface PropertyDto extends TreeNode {
  type: 'ObjectProperty' | 'DataProperty';
  domains?: string[];
  iri: string;
  ranges?: string[];
  characteristics?: string[];
  superProperties?: string[];
  subProperties?: string[];
  children?: PropertyDto[];
}

export interface TreeNode {
  id: string;
  label: string;
  annotations?: Record<string, string>;
  children?: TreeNode[];
}
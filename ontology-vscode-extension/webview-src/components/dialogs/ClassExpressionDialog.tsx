import React, { useState, useEffect, useRef } from 'react';
import { X, CheckCircle2, AlertCircle, Loader2 } from 'lucide-react';
import EntityHierarchy from '../EntityHierarchy';
import ontologyMutationService from '../../services/ontologyMutationService';
import expressionService from '../../services/expressionService';
import { notificationService } from '../../services/notificationService';
import type { TreeNode, Property } from '../../types';

// Structured data for object/data restrictions
export interface RestrictionData {
  type: 'objectRestriction' | 'dataRestriction';
  axiomType: 'EquivalentTo' | 'SubClassOf';
  propertyIri: string;
  restrictionType: 'some' | 'only' | 'min' | 'max' | 'exactly' | 'value';
  fillerIri: string; // Class IRI for object restrictions, datatype IRI for data restrictions
  cardinality?: number;
}

interface ClassExpressionDialogProps {
  isOpen: boolean;
  onClose: () => void;
  onConfirm: (expression: string, restrictionData?: RestrictionData) => void | Promise<void>;
  classHierarchy: TreeNode[];
  objectProperties: Property[];
  dataProperties: Property[];
  title?: string;
  initialValue?: string;
  initialClassIri?: string;
  initialTab?: 'hierarchy' | 'objectRestriction' | 'classExpression' | 'dataRestriction';
  /** Restrict which tabs are shown. If not specified, all tabs are shown. */
  allowedTabs?: TabType[];
  initialRestrictionData?: {
    propertyIri?: string;
    restrictionType?: 'some' | 'only' | 'min' | 'max' | 'exactly' | 'value';
    fillerIri?: string;
    cardinality?: number;
    isDataProperty?: boolean;
  };
  projectId?: string;
  expandedNodes?: string[];
  onToggleNode?: (nodeId: string) => void;
  onAddClass?: (type: 'subclass' | 'sibling') => void;
  onDeleteClass?: () => void;
  onAddObjectProperty?: (type: 'subclass' | 'sibling', parentId?: string, name?: string) => Promise<void>;
  onAddDataProperty?: (type: 'subclass' | 'sibling', parentId?: string, name?: string) => Promise<void>;
  onDeleteProperty?: () => void;
  // NEW: Optional property hierarchies as TreeNode[] if they have structure
  objectPropertiesTree?: TreeNode[];
  dataPropertiesTree?: TreeNode[];
  // NEW: Property toggle handlers for loading children
  onToggleObjectProperty?: (nodeId: string) => void;
  onToggleDataProperty?: (nodeId: string) => void;
  // NEW: Callbacks for refreshing data after mutations
  onRefreshClasses?: () => void;
  onRefreshProperties?: () => void;
  // NEW: Metadata for generating IRIs
  metadata?: { ontologyIRI?: string };
}

export type TabType = 'hierarchy' | 'objectRestriction' | 'classExpression' | 'dataRestriction';

/**
 * ClassExpressionDialog - Protégé desktop-style class expression builder
 *
 * Matches Protégé desktop UI with:
 * - EntityHierarchy for all tree views (classes, properties)
 * - Asserted/Inferred toggles
 * - Compact two-panel layouts for restrictions
 * - Professional toolbar integration
 */
const ClassExpressionDialog: React.FC<ClassExpressionDialogProps> = ({
  isOpen,
  onClose,
  onConfirm,
  classHierarchy,
  objectProperties,
  dataProperties,
  title = "Class Expression Editor",
  initialValue = "",
  initialClassIri,
  initialTab,
  initialRestrictionData,
  allowedTabs,
  projectId,
  expandedNodes = [],
  onToggleNode,
  onAddClass,
  onDeleteClass,
  onAddObjectProperty,
  onAddDataProperty,
  onDeleteProperty,
  objectPropertiesTree: externalObjectPropertiesTree,
  dataPropertiesTree: externalDataPropertiesTree,
  onToggleObjectProperty,
  onToggleDataProperty,
  onRefreshClasses,
  onRefreshProperties,
  metadata
}) => {
  // If allowedTabs is specified, use it; otherwise show all tabs
  const visibleTabs = allowedTabs || ['hierarchy', 'objectRestriction', 'classExpression', 'dataRestriction'];
  
  const [activeTab, setActiveTab] = useState<TabType>('hierarchy');

  // Class hierarchy state
  const [selectedClass, setSelectedClass] = useState<TreeNode | null>(null);
  const [classSearchQuery, setClassSearchQuery] = useState('');
  const [localExpandedNodes, setLocalExpandedNodes] = useState<string[]>([]);
  
  // Selected items for restriction panels
  const [selectedFillerClass, setSelectedFillerClass] = useState<TreeNode | null>(null);

  // Object Restriction state
  const [selectedProperty, setSelectedProperty] = useState<Property | null>(null);
  const [restrictionType, setRestrictionType] = useState<'some' | 'only' | 'min' | 'max' | 'exactly' | 'value'>('some');
  const [cardinality, setCardinality] = useState(1);
  const [restrictionFiller, setRestrictionFiller] = useState<TreeNode | null>(null);
  const [fillerSearchQuery, setFillerSearchQuery] = useState('');
  const [objectPropSearchQuery, setObjectPropSearchQuery] = useState('');
  const [propertyExpandedNodes, setPropertyExpandedNodes] = useState<string[]>([]);
  const [fillerExpandedNodes, setFillerExpandedNodes] = useState<string[]>([]);

  // Data Restriction state
  const [selectedDataProperty, setSelectedDataProperty] = useState<Property | null>(null);
  const [dataRestrictionType, setDataRestrictionType] = useState<'some' | 'only' | 'min' | 'max' | 'exactly' | 'value'>('some');
  const [dataCardinality, setDataCardinality] = useState(1);
  const [datatype, setDatatype] = useState('xsd:string');
  const [dataPropertyExpandedNodes, setDataPropertyExpandedNodes] = useState<string[]>([]);
  const [dataPropSearchQuery, setDataPropSearchQuery] = useState('');

  // Class Expression (Manchester) state
  const [manchesterExpression, setManchesterExpression] = useState(initialValue);
  const [manchesterParseError, setManchesterParseError] = useState<string | null>(null);
  const [manchesterParseOk, setManchesterParseOk] = useState(false);
  const parseTimerRef = useRef<ReturnType<typeof setTimeout> | null>(null);

  // Inline class creation state
  const [showInlineCreate, setShowInlineCreate] = useState(false);
  const [inlineCreateType, setInlineCreateType] = useState<'subclass' | 'sibling'>('subclass');
  const [inlineClassName, setInlineClassName] = useState('');
  const [isCreatingClass, setIsCreatingClass] = useState(false);
  const [isSavingConfirm, setIsSavingConfirm] = useState(false);

  // Inline class deletion state
  const [showInlineDelete, setShowInlineDelete] = useState(false);
  const [isDeletingClass, setIsDeletingClass] = useState(false);

  // Inline property creation state
  const [showInlinePropertyCreate, setShowInlinePropertyCreate] = useState(false);
  const [inlinePropertyCreateType, setInlinePropertyCreateType] = useState<'subclass' | 'sibling'>('subclass');
  const [inlinePropertyName, setInlinePropertyName] = useState('');
  const [isCreatingProperty, setIsCreatingProperty] = useState(false);
  const [propertyCreationTab, setPropertyCreationTab] = useState<'object' | 'data'>('object');

  // Track if we've already initialized the dialog to prevent re-initialization
  const [hasInitialized, setHasInitialized] = useState(false);

  // Reset state when dialog opens with initialValue
  useEffect(() => {
    if (isOpen && !hasInitialized) {
      setHasInitialized(true);
      setManchesterExpression(initialValue);
      // Set the active tab based on initialTab prop or default behavior
      if (initialTab) {
        setActiveTab(initialTab);
        
        const classIriToSelect = initialClassIri || initialValue;

        // If opening hierarchy tab and we have an IRI, try to find and select that class.
        // Keep initialValue reserved for the expression text so the expression editor
        // still shows the existing axiom when users switch tabs while editing.
        if (initialTab === 'hierarchy' && classIriToSelect && (classIriToSelect.startsWith('http://') || classIriToSelect.startsWith('https://') || classIriToSelect.startsWith('urn:'))) {
          const findClassWithPath = (nodes: TreeNode[], targetId: string, path: string[] = []): { node: TreeNode | null, path: string[] } => {
            for (const node of nodes) {
              if (node.id === targetId) {
                return { node, path };
              }
              if (node.children && node.children.length > 0) {
                const result = findClassWithPath(node.children, targetId, [...path, node.id]);
                if (result.node) return result;
              }
            }
            return { node: null, path: [] };
          };
          
          const { node: foundClass, path: pathToClass } = findClassWithPath(classHierarchy, classIriToSelect);
          if (foundClass) {
            setSelectedClass(foundClass);
            // Expand parent nodes so the selected class is visible
            if (pathToClass.length > 0) {
              setLocalExpandedNodes(pathToClass);
            }
          }
        }
      } else if (initialValue) {
        setActiveTab('classExpression');
      } else {
        setActiveTab('hierarchy');
      }

      // Pre-populate restriction data if provided
      if (initialRestrictionData) {
        if (initialRestrictionData.isDataProperty) {
          // Data property restriction
          if (initialRestrictionData.propertyIri) {
            // Check if it's owl:topDataProperty (not in dataProperties array)
            if (initialRestrictionData.propertyIri === 'http://www.w3.org/2002/07/owl#topDataProperty') {
              setSelectedDataProperty({
                id: 'http://www.w3.org/2002/07/owl#topDataProperty',
                label: 'owl:topDataProperty',
                type: 'DatatypeProperty'
              });
            } else {
              const dataProp = dataProperties.find(p => p.id === initialRestrictionData.propertyIri);
              if (dataProp) setSelectedDataProperty(dataProp);
            }
          }
          if (initialRestrictionData.restrictionType) {
            setDataRestrictionType(initialRestrictionData.restrictionType);
          }
          if (initialRestrictionData.cardinality !== undefined) {
            setDataCardinality(initialRestrictionData.cardinality);
          }
          if (initialRestrictionData.fillerIri) {
            setDatatype(initialRestrictionData.fillerIri);
          }
        } else {
          // Object property restriction
          if (initialRestrictionData.propertyIri) {
            // Check if it's owl:topObjectProperty (not in objectProperties array)
            if (initialRestrictionData.propertyIri === 'http://www.w3.org/2002/07/owl#topObjectProperty') {
              setSelectedProperty({
                id: 'http://www.w3.org/2002/07/owl#topObjectProperty',
                label: 'owl:topObjectProperty',
                type: 'ObjectProperty'
              });
            } else {
              const objProp = objectProperties.find(p => p.id === initialRestrictionData.propertyIri);
              if (objProp) setSelectedProperty(objProp);
            }
          }
          if (initialRestrictionData.restrictionType) {
            setRestrictionType(initialRestrictionData.restrictionType);
          }
          if (initialRestrictionData.cardinality !== undefined) {
            setCardinality(initialRestrictionData.cardinality);
          }
          if (initialRestrictionData.fillerIri) {
            // Find the filler class in the hierarchy and build path to it
            const findClassWithPath = (nodes: TreeNode[], targetId: string, path: string[] = []): { node: TreeNode | null, path: string[] } => {
              for (const node of nodes) {
                if (node.id === targetId) {
                  return { node, path };
                }
                if (node.children && node.children.length > 0) {
                  const result = findClassWithPath(node.children, targetId, [...path, node.id]);
                  if (result.node) return result;
                }
              }
              return { node: null, path: [] };
            };
            
            const { node: fillerClass, path: pathToFiller } = findClassWithPath(classHierarchy, initialRestrictionData.fillerIri);
            if (fillerClass) {
              setRestrictionFiller(fillerClass);
              // Expand all parent nodes so the selected node is visible
              if (pathToFiller.length > 0) {
                setFillerExpandedNodes(pathToFiller);
              }
            }
          }
        }
      }
    }
  }, [isOpen, hasInitialized, initialValue, initialClassIri, initialTab, initialRestrictionData, objectProperties, dataProperties, classHierarchy]);

  // Reset hasInitialized when dialog closes
  useEffect(() => {
    if (!isOpen) {
      setHasInitialized(false);
    }
  }, [isOpen]);

  // Convert flat property list to tree structure with top property
  const propertiesToTree = (properties: Property[], isDataProperty: boolean = false): TreeNode[] => {
    const topPropertyIri = isDataProperty
      ? 'http://www.w3.org/2002/07/owl#topDataProperty'
      : 'http://www.w3.org/2002/07/owl#topObjectProperty';

    const topPropertyLabel = isDataProperty
      ? 'owl:topDataProperty'
      : 'owl:topObjectProperty';

    // If no properties provided, create just the top property
    if (properties.length === 0) {
      return [{
        id: topPropertyIri,
        label: topPropertyLabel,
        hasChildren: false,
        children: []
      }];
    }

    // Build a map of properties by ID for quick lookup
    const propMap = new Map<string, Property>();
    properties.forEach(prop => propMap.set(prop.id, prop));

    // Build children map: parentId -> child properties
    const childrenMap = new Map<string, Property[]>();

    properties.forEach(prop => {
      if (prop.superProperties && prop.superProperties.length > 0) {
        // This property has parents, add it as a child to each parent
        prop.superProperties.forEach(parentId => {
          if (!childrenMap.has(parentId)) {
            childrenMap.set(parentId, []);
          }
          childrenMap.get(parentId)!.push(prop);
        });
      } else {
        // No superProperties means it's a direct child of top property
        if (!childrenMap.has(topPropertyIri)) {
          childrenMap.set(topPropertyIri, []);
        }
        childrenMap.get(topPropertyIri)!.push(prop);
      }
    });

    // Recursive function to build tree nodes
    const buildNode = (prop: Property): TreeNode => {
      const children = childrenMap.get(prop.id) || [];
      return {
        id: prop.id,
        label: prop.label,
        hasChildren: children.length > 0,
        children: children.map(buildNode)
      };
    };

    // Build top property node
    const topPropertyChildren = childrenMap.get(topPropertyIri) || [];

    const result = [{
      id: topPropertyIri,
      label: topPropertyLabel,
      hasChildren: topPropertyChildren.length > 0,
      children: topPropertyChildren.map(buildNode)
    }];

    return result;
  };

  const buildObjectRestriction = (): string => {
    if (!selectedProperty || !restrictionFiller) return '';

    const propName = selectedProperty.label;
    const fillerName = restrictionFiller.label;

    switch (restrictionType) {
      case 'some':
        return `${propName} some ${fillerName}`;
      case 'only':
        return `${propName} only ${fillerName}`;
      case 'min':
        return `${propName} min ${cardinality} ${fillerName}`;
      case 'max':
        return `${propName} max ${cardinality} ${fillerName}`;
      case 'exactly':
        return `${propName} exactly ${cardinality} ${fillerName}`;
      case 'value':
        return `${propName} value ${fillerName}`;
      default:
        return '';
    }
  };

  const buildDataRestriction = (): string => {
    console.log('[ClassExpressionDialog] buildDataRestriction called', {
      selectedDataProperty,
      datatype,
      dataRestrictionType,
      dataCardinality
    });
    if (!selectedDataProperty) {
      console.warn('[ClassExpressionDialog] buildDataRestriction: No data property selected');
      return '';
    }

    const propName = selectedDataProperty.label;
    console.log('[ClassExpressionDialog] buildDataRestriction: propName=', propName, 'datatype=', datatype);

    switch (dataRestrictionType) {
      case 'some':
        return `${propName} some ${datatype}`;
      case 'only':
        return `${propName} only ${datatype}`;
      case 'min':
        return `${propName} min ${dataCardinality} ${datatype}`;
      case 'max':
        return `${propName} max ${dataCardinality} ${datatype}`;
      case 'exactly':
        return `${propName} exactly ${dataCardinality} ${datatype}`;
      case 'value':
        return `${propName} value ${datatype}`;
      default:
        return '';
    }
  };

  useEffect(() => {
    if (!projectId || activeTab !== 'classExpression') {
      setManchesterParseError(null);
      setManchesterParseOk(false);
      return;
    }
    const expr = manchesterExpression.trim();
    if (!expr) {
      setManchesterParseError(null);
      setManchesterParseOk(false);
      return;
    }
    if (parseTimerRef.current) clearTimeout(parseTimerRef.current);
    parseTimerRef.current = setTimeout(async () => {
      try {
        const result = await expressionService.parseExpression(projectId, expr);
        if (result.success) {
          setManchesterParseError(null);
          setManchesterParseOk(true);
        } else {
          setManchesterParseError(result.error || 'Invalid Manchester expression');
          setManchesterParseOk(false);
        }
      } catch (err: unknown) {
        setManchesterParseError(err instanceof Error ? err.message : 'Validation failed');
        setManchesterParseOk(false);
      }
    }, 400);
    return () => {
      if (parseTimerRef.current) clearTimeout(parseTimerRef.current);
    };
  }, [projectId, activeTab, manchesterExpression]);

  const handleConfirm = async () => {
    console.log('[ClassExpressionDialog] handleConfirm called', {
      activeTab,
      selectedClass: selectedClass?.id,
      selectedClassLabel: selectedClass?.label,
      selectedProperty: selectedProperty?.id,
      restrictionFiller: restrictionFiller?.id,
      selectedDataProperty: selectedDataProperty?.id,
      manchesterExpression: manchesterExpression
    });
    
    let expression = '';
    let restrictionData: RestrictionData | undefined = undefined;

    switch (activeTab) {
      case 'hierarchy':
        if (selectedClass) {
          expression = selectedClass.id;
          console.log('[ClassExpressionDialog] Hierarchy tab - selected class IRI:', expression, 'label:', selectedClass.label);
        } else {
          console.warn('[ClassExpressionDialog] Hierarchy tab - no class selected!');
          notificationService.warning('Selection Required', 'Please select a class from the hierarchy');
          return;
        }
        break;
      case 'objectRestriction':
        expression = buildObjectRestriction();
        // Also build structured restriction data for backend
        if (selectedProperty && restrictionFiller) {
          restrictionData = {
            type: 'objectRestriction',
            axiomType: 'SubClassOf', // Default - caller can change this if needed
            propertyIri: selectedProperty.id,
            restrictionType: restrictionType,
            fillerIri: restrictionFiller.id,
            cardinality: ['min', 'max', 'exactly'].includes(restrictionType) ? cardinality : undefined
          };
        }
        break;
      case 'classExpression':
        expression = manchesterExpression.trim();
        if (!expression) {
          notificationService.warning('Expression Required', 'Enter a Manchester class expression');
          return;
        }
        if (projectId) {
          const result = await expressionService.parseExpression(projectId, expression);
          if (!result.success) {
            notificationService.error('Invalid Expression', result.error || 'Could not parse Manchester expression');
            return;
          }
        }
        break;
      case 'dataRestriction':
        console.log('[ClassExpressionDialog] dataRestriction case - calling buildDataRestriction');
        expression = buildDataRestriction();
        console.log('[ClassExpressionDialog] dataRestriction expression result:', expression);
        // Also build structured restriction data for backend
        if (selectedDataProperty) {
          const fillerIri = datatype.startsWith('http://') || datatype.startsWith('rdf:') || datatype.startsWith('rdfs:') || datatype.startsWith('owl:')
            ? (datatype.includes(':') && !datatype.startsWith('http') 
              ? (datatype.startsWith('rdf:') ? `http://www.w3.org/1999/02/22-rdf-syntax-ns#${datatype.replace('rdf:', '')}` 
                : datatype.startsWith('rdfs:') ? `http://www.w3.org/2000/01/rdf-schema#${datatype.replace('rdfs:', '')}`
                : datatype.startsWith('owl:') ? `http://www.w3.org/2002/07/owl#${datatype.replace('owl:', '')}`
                : datatype)
              : datatype) 
            : `http://www.w3.org/2001/XMLSchema#${datatype.replace('xsd:', '')}`;
          console.log('[ClassExpressionDialog] dataRestriction fillerIri:', fillerIri);
          restrictionData = {
            type: 'dataRestriction',
            axiomType: 'SubClassOf', // Default - caller can change this if needed
            propertyIri: selectedDataProperty.id,
            restrictionType: dataRestrictionType,
            fillerIri: fillerIri,
            cardinality: ['min', 'max', 'exactly'].includes(dataRestrictionType) ? dataCardinality : undefined
          };
          console.log('[ClassExpressionDialog] dataRestriction restrictionData:', restrictionData);
        }
        break;
    }

    if (expression) {
      console.log('[ClassExpressionDialog] Calling onConfirm with expression:', expression);
      onConfirm(expression, restrictionData);
      handleClose();
    } else {
      console.warn('[ClassExpressionDialog] No expression to confirm! activeTab:', activeTab, 'selectedClass:', selectedClass);
    }
  };

  const handleClose = () => {
    // Reset all state
    setSelectedClass(null);
    setSelectedProperty(null);
    setRestrictionFiller(null);
    setSelectedDataProperty(null);
    setManchesterExpression('');
    setClassSearchQuery('');
    setFillerSearchQuery('');
    setActiveTab('hierarchy');
    // Reset inline create state
    setShowInlineCreate(false);
    setInlineClassName('');
    // Reset inline delete state
    setShowInlineDelete(false);
    onClose();
  };

  // Handle toggle for hierarchy tab
  const handleHierarchyToggle = async (nodeId: string) => {
    // Always update local expanded state first for immediate UI feedback
    const isExpanded = localExpandedNodes.includes(nodeId);
    setLocalExpandedNodes(
      isExpanded
        ? localExpandedNodes.filter(id => id !== nodeId)
        : [...localExpandedNodes, nodeId]
    );
    
    // Also call parent's toggle if provided (to load children)
    if (onToggleNode) {
      await onToggleNode(nodeId);
    }
  };

  // Handle toggle for object properties
  const handleObjectPropertyToggle = async (nodeId: string) => {
    const isExpanded = propertyExpandedNodes.includes(nodeId);
    setPropertyExpandedNodes(
      isExpanded
        ? propertyExpandedNodes.filter(id => id !== nodeId)
        : [...propertyExpandedNodes, nodeId]
    );
    if (onToggleObjectProperty) {
      await onToggleObjectProperty(nodeId);
    }
  };

  // Handle toggle for data properties
  const handleDataPropertyToggle = async (nodeId: string) => {
    const isExpanded = dataPropertyExpandedNodes.includes(nodeId);
    setDataPropertyExpandedNodes(
      isExpanded
        ? dataPropertyExpandedNodes.filter(id => id !== nodeId)
        : [...dataPropertyExpandedNodes, nodeId]
    );
    if (onToggleDataProperty) {
      await onToggleDataProperty(nodeId);
    }
  };

  // Handle toggle for restriction filler
  const handleFillerToggle = async (nodeId: string) => {
    if (onToggleNode) {
      // Use parent's toggle if available
      await onToggleNode(nodeId);
      // Also update local state for this panel
      const isExpanded = fillerExpandedNodes.includes(nodeId);
      setFillerExpandedNodes(
        isExpanded
          ? fillerExpandedNodes.filter(id => id !== nodeId)
          : [...fillerExpandedNodes, nodeId]
      );
    } else {
      // Fallback to local state only
      const isExpanded = fillerExpandedNodes.includes(nodeId);
      setFillerExpandedNodes(
        isExpanded
          ? fillerExpandedNodes.filter(id => id !== nodeId)
          : [...fillerExpandedNodes, nodeId]
      );
    }
  };

  // Helper to find parent of a node in the hierarchy
  const findParentNode = (nodes: TreeNode[], targetId: string, parent: TreeNode | null = null): TreeNode | null => {
    for (const node of nodes) {
      if (node.id === targetId) return parent;
      if (node.children && node.children.length > 0) {
        const found = findParentNode(node.children, targetId, node);
        if (found !== null) return found;
      }
    }
    return null;
  };

  // Handle inline class creation
  const handleInlineAddClass = (type: 'subclass' | 'sibling') => {
    setInlineCreateType(type);
    setInlineClassName('');
    setShowInlineCreate(true);
  };

  // Submit inline class creation
  const handleInlineCreateSubmit = async () => {
    if (!inlineClassName.trim() || !projectId) return;
    
    setIsCreatingClass(true);
    try {
      let parentIri = 'http://www.w3.org/2002/07/owl#Thing';
      
      if (inlineCreateType === 'subclass' && selectedClass) {
        parentIri = selectedClass.id;
      } else if (inlineCreateType === 'sibling' && selectedClass) {
        const parent = findParentNode(classHierarchy, selectedClass.id);
        parentIri = parent?.id || 'http://www.w3.org/2002/07/owl#Thing';
      }
      
      // Generate IRI from class name
      const baseIri = metadata?.ontologyIRI || 'http://example.org/ontology#';
      const cleanName = inlineClassName.trim().replace(/\s+/g, '_');
      const newClassIri = baseIri.endsWith('#') || baseIri.endsWith('/') 
        ? `${baseIri}${cleanName}` 
        : `${baseIri}#${cleanName}`;
      
      // Ensure parent node is expanded so new class will be visible
      // Add parent to local expanded nodes if not already expanded
      if (!localExpandedNodes.includes(parentIri)) {
        setLocalExpandedNodes(prev => [...prev, parentIri]);
      }
      // Also trigger parent's toggle to ensure it's expanded in external state
      if (onToggleNode && !expandedNodes.includes(parentIri)) {
        await onToggleNode(parentIri);
      }
      
      // Create the class via the mutation service
      await ontologyMutationService.createClass(
        projectId,
        newClassIri,
        inlineClassName.trim(),
        parentIri,
        'anonymous',
        'Anonymous'
      );
      
      // Refresh the class hierarchy
      if (onRefreshClasses) {
        onRefreshClasses();
      }
      
      // Reset inline create state
      setShowInlineCreate(false);
      setInlineClassName('');
    } catch (error) {
      console.error('Failed to create class:', error);
    } finally {
      setIsCreatingClass(false);
    }
  };

  // Cancel inline creation
  const handleInlineCreateCancel = () => {
    setShowInlineCreate(false);
    setInlineClassName('');
  };

  // Show inline delete confirmation
  const handleInlineDeleteStart = () => {
    if (!selectedClass || selectedClass.id.includes('Thing')) return;
    setShowInlineDelete(true);
  };

  // Confirm and execute inline delete
  const handleInlineDeleteConfirm = async () => {
    if (!selectedClass || !projectId) return;
    
    setIsDeletingClass(true);
    try {
      await ontologyMutationService.deleteClass(
        projectId,
        selectedClass.id,
        'anonymous',
        'Anonymous'
      );
      
      // Clear selection and hide confirmation
      setSelectedClass(null);
      setShowInlineDelete(false);
      
      // Refresh the class hierarchy
      if (onRefreshClasses) {
        onRefreshClasses();
      }
    } catch (error) {
      console.error('Failed to delete class:', error);
    } finally {
      setIsDeletingClass(false);
    }
  };

  // Cancel inline delete
  const handleInlineDeleteCancel = () => {
    setShowInlineDelete(false);
  };

  // Handle inline property creation
  const handleInlineAddProperty = (type: 'subclass' | 'sibling') => {
    // Determine which tab we're on (object or data properties)
    const isDataTab = activeTab === 'dataRestriction';
    setPropertyCreationTab(isDataTab ? 'data' : 'object');
    setInlinePropertyCreateType(type);
    setInlinePropertyName('');
    setShowInlinePropertyCreate(true);
  };

  // Submit inline property creation
  const handleInlinePropertyCreateSubmit = async () => {
    if (!inlinePropertyName.trim() || !projectId) return;
    
    setIsCreatingProperty(true);
    try {
      const isDataProperty = propertyCreationTab === 'data';
      const selectedProp = isDataProperty ? selectedDataProperty : selectedProperty;
      
      let parentIri = isDataProperty 
        ? 'http://www.w3.org/2002/07/owl#topDataProperty'
        : 'http://www.w3.org/2002/07/owl#topObjectProperty';
      
      if (inlinePropertyCreateType === 'subclass' && selectedProp) {
        parentIri = selectedProp.id;
      } else if (inlinePropertyCreateType === 'sibling' && selectedProp) {
        // Find parent of selected property
        const hierarchy = isDataProperty ? dataPropertiesTree : objectPropertiesTree;
        const parent = findParentNode(hierarchy, selectedProp.id);
        if (parent) {
          parentIri = parent.id;
        }
      }
      
      // Generate IRI from property name
      const baseIri = metadata?.ontologyIRI || 'http://example.org/ontology#';
      const cleanName = inlinePropertyName.trim().replace(/\s+/g, '_');
      const newPropertyIri = baseIri.endsWith('#') || baseIri.endsWith('/') 
        ? `${baseIri}${cleanName}` 
        : `${baseIri}#${cleanName}`;
      
      // Create the property via the mutation service
      if (isDataProperty) {
        await ontologyMutationService.createDataProperty(
          projectId,
          newPropertyIri,
          inlinePropertyName.trim(),
          parentIri,
          'anonymous',
          'Anonymous'
        );
      } else {
        await ontologyMutationService.createObjectProperty(
          projectId,
          newPropertyIri,
          inlinePropertyName.trim(),
          parentIri,
          'anonymous',
          'Anonymous'
        );
      }
      
      // Refresh if handler provided
      if (onRefreshProperties) {
        onRefreshProperties();
      }
      
      // Reset inline create state
      setShowInlinePropertyCreate(false);
      setInlinePropertyName('');
    } catch (error) {
      console.error('Failed to create property:', error);
      notificationService.error('Create Failed', `Failed to create property: ${error instanceof Error ? error.message : 'Unknown error'}`);
    } finally {
      setIsCreatingProperty(false);
    }
  };

  // Cancel inline property creation
  const handleInlinePropertyCreateCancel = () => {
    setShowInlinePropertyCreate(false);
    setInlinePropertyName('');
  };

  const datatypes = [
    'owl:rational',
    'owl:real',
    'rdf:langString',
    'rdf:PlainLiteral',
    'rdf:XMLLiteral',
    'rdfs:Literal',
    'xsd:anyURI',
    'xsd:base64Binary',
    'xsd:boolean',
    'xsd:byte',
    'xsd:date',
    'xsd:dateTime',
    'xsd:dateTimeStamp',
    'xsd:decimal',
    'xsd:double',
    'xsd:float',
    'xsd:int',
    'xsd:integer',
    'xsd:long',
    'xsd:string'
  ];

  const restrictionTypes = [
    { value: 'some', label: 'Some (existential)' },
    { value: 'only', label: 'Only (universal)' },
    { value: 'min', label: 'Min (minimum cardinality)' },
    { value: 'max', label: 'Max (maximum cardinality)' },
    { value: 'exactly', label: 'Exactly (exact cardinality)' },
    { value: 'value', label: 'Value (has value)' }
  ];

  const manchesterKeywords = ['and', 'or', 'not', 'some', 'only', 'min', 'max', 'exactly', 'value'];

  const isOkEnabled =
    (activeTab === 'hierarchy' && selectedClass !== null) ||
    (activeTab === 'objectRestriction' && selectedProperty && restrictionFiller) ||
    (activeTab === 'classExpression' && manchesterExpression.trim()) ||
    (activeTab === 'dataRestriction' && selectedDataProperty);
  
  // Debug logging for OK button state
  useEffect(() => {
    console.log('[ClassExpressionDialog] OK button state:', {
      isOkEnabled,
      activeTab,
      selectedClass: selectedClass?.id,
      selectedClassExists: selectedClass !== null,
      selectedProperty: selectedProperty?.id,
      restrictionFiller: restrictionFiller?.id,
      selectedDataProperty: selectedDataProperty?.id,
      datatype,
      dataRestrictionType
    });
  }, [isOkEnabled, activeTab, selectedClass, selectedProperty, restrictionFiller, selectedDataProperty, datatype, dataRestrictionType]);

  if (!isOpen) return null;

  // Use external property trees if provided, otherwise convert from flat list
  const objectPropertiesTree = externalObjectPropertiesTree || propertiesToTree(objectProperties, false);
  const dataPropertiesTree = externalDataPropertiesTree || propertiesToTree(dataProperties, true);

  // Combine external and local expanded nodes for immediate UI feedback
  // External nodes come from parent (for lazy loading), local nodes track immediate user interactions
  const effectiveExpandedNodes = [...new Set([...expandedNodes, ...localExpandedNodes])];
  const effectiveFillerExpandedNodes = [...new Set([...expandedNodes, ...fillerExpandedNodes])];

  return (
    <div className="fixed inset-0 bg-black bg-opacity-50 flex items-center justify-center z-50">
      <div className="bg-white rounded-lg shadow-xl w-full max-w-7xl mx-4 flex flex-col h-[90vh]">
        {/* Header */}
        <div className="px-6 py-3 border-b border-gray-300 flex justify-between items-center bg-gray-50">
          <h3 className="text-lg font-semibold text-gray-900">{title}</h3>
          <button
            onClick={handleClose}
            className="text-gray-400 hover:text-gray-600 transition-colors"
            title="Close"
          >
            <X size={20} />
          </button>
        </div>

        {/* Tabs - only show tabs that are in visibleTabs */}
        <div className="flex border-b border-gray-300 bg-gray-100">
          {visibleTabs.includes('hierarchy') && (
            <button
              onClick={() => setActiveTab('hierarchy')}
              className={`px-4 py-2 text-sm font-medium transition-colors ${
                activeTab === 'hierarchy'
                  ? 'bg-white text-gray-900 border-t-2 border-t-blue-500'
                  : 'text-gray-600 hover:text-gray-900 hover:bg-gray-200'
              }`}
            >
              Class hierarchy
            </button>
          )}
          {visibleTabs.includes('dataRestriction') && (
            <button
              onClick={() => setActiveTab('dataRestriction')}
              className={`px-4 py-2 text-sm font-medium transition-colors ${
                activeTab === 'dataRestriction'
                  ? 'bg-white text-gray-900 border-t-2 border-t-blue-500'
                  : 'text-gray-600 hover:text-gray-900 hover:bg-gray-200'
              }`}
            >
              Data restriction creator
            </button>
          )}
          {visibleTabs.includes('classExpression') && (
            <button
              onClick={() => setActiveTab('classExpression')}
              className={`px-4 py-2 text-sm font-medium transition-colors ${
                activeTab === 'classExpression'
                  ? 'bg-white text-gray-900 border-t-2 border-t-blue-500'
                  : 'text-gray-600 hover:text-gray-900 hover:bg-gray-200'
              }`}
            >
              Class expression editor
            </button>
          )}
          {visibleTabs.includes('objectRestriction') && (
            <button
              onClick={() => setActiveTab('objectRestriction')}
              className={`px-4 py-2 text-sm font-medium transition-colors ${
                activeTab === 'objectRestriction'
                  ? 'bg-white text-gray-900 border-t-2 border-t-blue-500'
                  : 'text-gray-600 hover:text-gray-900 hover:bg-gray-200'
              }`}
            >
              Object restriction creator
            </button>
          )}
        </div>

        {/* Content Area */}
        <div className="flex-1 overflow-hidden min-h-0 bg-white">
          {/* Class Hierarchy Tab */}
          <div className={`h-full flex flex-col${activeTab !== 'hierarchy' ? ' hidden' : ''}`}>
              {/* Inline Create Form */}
              {showInlineCreate && (
                <div className="px-3 py-2 bg-amber-50 border-b border-amber-200">
                  <div className="flex items-center gap-2">
                    <span className="text-xs text-amber-800 font-medium">
                      New {inlineCreateType === 'subclass' ? 'subclass of' : 'sibling of'} {selectedClass?.label || 'owl:Thing'}:
                    </span>
                    <input
                      type="text"
                      value={inlineClassName}
                      onChange={(e) => setInlineClassName(e.target.value)}
                      onKeyDown={(e) => {
                        if (e.key === 'Enter' && inlineClassName.trim()) {
                          handleInlineCreateSubmit();
                        } else if (e.key === 'Escape') {
                          handleInlineCreateCancel();
                        }
                      }}
                      placeholder="Enter class name..."
                      className="flex-1 px-2 py-1 text-sm border border-amber-300 rounded focus:outline-none focus:ring-1 focus:ring-amber-500 bg-white"
                      autoFocus
                      disabled={isCreatingClass}
                    />
                    <button
                      onClick={handleInlineCreateSubmit}
                      disabled={!inlineClassName.trim() || isCreatingClass}
                      className="px-3 py-1 text-xs bg-amber-600 text-white rounded hover:bg-amber-700 disabled:opacity-50 disabled:cursor-not-allowed"
                    >
                      {isCreatingClass ? 'Creating...' : 'Create'}
                    </button>
                    <button
                      onClick={handleInlineCreateCancel}
                      disabled={isCreatingClass}
                      className="px-2 py-1 text-xs text-gray-600 hover:text-gray-800"
                    >
                      Cancel
                    </button>
                  </div>
                </div>
              )}
              
              {/* Inline Delete Confirmation */}
              {showInlineDelete && selectedClass && (
                <div className="px-3 py-2 bg-red-50 border-b border-red-200">
                  <div className="flex items-center gap-2">
                    <span className="text-xs text-red-800 font-medium">
                      Delete "{selectedClass.label}"?
                    </span>
                    <span className="flex-1" />
                    <button
                      onClick={handleInlineDeleteConfirm}
                      disabled={isDeletingClass}
                      className="px-3 py-1 text-xs bg-red-600 text-white rounded hover:bg-red-700 disabled:opacity-50 disabled:cursor-not-allowed"
                    >
                      {isDeletingClass ? 'Deleting...' : 'Delete'}
                    </button>
                    <button
                      onClick={handleInlineDeleteCancel}
                      disabled={isDeletingClass}
                      className="px-2 py-1 text-xs text-gray-600 hover:text-gray-800"
                    >
                      Cancel
                    </button>
                  </div>
                </div>
              )}
              
              <div className="flex-1 overflow-hidden">
                <EntityHierarchy
                  entitiesTab="Classes"
                  filteredData={classHierarchy}
                  selectedItem={selectedClass}
                  expandedNodes={effectiveExpandedNodes}
                  searchQuery={classSearchQuery}
                  onSearchQueryChange={setClassSearchQuery}
                  onSelectItem={(item) => {
                    console.log('[ClassExpressionDialog] Class selected from hierarchy:', item);
                    setSelectedClass(item as TreeNode);
                  }}
                  onToggleNode={handleHierarchyToggle}
                  onAddItem={projectId ? (type) => handleInlineAddClass(type as 'subclass' | 'sibling') : () => {}}
                  onDeleteItem={projectId ? handleInlineDeleteStart : () => {}}
                  hideToolbarActions={!projectId}
                />
              </div>
          </div>

          {/* Object Restriction Creator Tab */}
          <div className={`h-full flex${activeTab !== 'objectRestriction' ? ' hidden' : ''}`}>
              {/* LEFT: Restricted property - Uses EntityHierarchy */}
              <div className="w-1/2 border-r border-gray-300 flex flex-col">
                <div className="bg-gray-100 px-3 py-2 border-b border-gray-300">
                  <h4 className="text-sm font-semibold text-gray-700">Restricted property</h4>
                </div>
                
                {/* Inline Property Create Form */}
                {showInlinePropertyCreate && propertyCreationTab === 'object' && (
                  <div className="px-3 py-2 bg-blue-50 border-b border-blue-200">
                    <div className="flex items-center gap-2">
                      <span className="text-xs text-blue-800 font-medium">
                        New {inlinePropertyCreateType === 'subclass' ? 'subproperty of' : 'sibling of'} {selectedProperty?.label || 'owl:topObjectProperty'}:
                      </span>
                      <input
                        type="text"
                        value={inlinePropertyName}
                        onChange={(e) => setInlinePropertyName(e.target.value)}
                        onKeyDown={(e) => {
                          if (e.key === 'Enter' && inlinePropertyName.trim()) {
                            handleInlinePropertyCreateSubmit();
                          } else if (e.key === 'Escape') {
                            handleInlinePropertyCreateCancel();
                          }
                        }}
                        placeholder="Enter property name..."
                        className="flex-1 px-2 py-1 text-sm border border-blue-300 rounded focus:outline-none focus:ring-1 focus:ring-blue-500 bg-white"
                        autoFocus
                        disabled={isCreatingProperty}
                      />
                      <button
                        onClick={handleInlinePropertyCreateSubmit}
                        disabled={!inlinePropertyName.trim() || isCreatingProperty}
                        className="px-3 py-1 text-xs bg-blue-600 text-white rounded hover:bg-blue-700 disabled:opacity-50 disabled:cursor-not-allowed"
                      >
                        {isCreatingProperty ? 'Creating...' : 'Create'}
                      </button>
                      <button
                        onClick={handleInlinePropertyCreateCancel}
                        disabled={isCreatingProperty}
                        className="px-2 py-1 text-xs text-gray-600 hover:text-gray-800"
                      >
                        Cancel
                      </button>
                    </div>
                  </div>
                )}
                
                <div className="flex-1 overflow-hidden">
                  <EntityHierarchy
                    entitiesTab="ObjectProperties"
                    filteredData={objectPropertiesTree}
                    selectedItem={selectedProperty as any}
                    expandedNodes={propertyExpandedNodes}
                    searchQuery={objectPropSearchQuery}
                    onSearchQueryChange={setObjectPropSearchQuery}
                    onSelectItem={(item) => setSelectedProperty(item as any as Property)}
                    onToggleNode={handleObjectPropertyToggle}
                    onAddItem={projectId ? (type) => handleInlineAddProperty(type as 'subclass' | 'sibling') : () => {}}
                    onDeleteItem={onDeleteProperty || (() => {})}
                    hideToolbarActions={!projectId}
                  />
                </div>
              </div>

              {/* RIGHT: Restriction filler - Uses EntityHierarchy */}
              <div className="w-1/2 flex flex-col">
                <div className="bg-gray-100 px-3 py-2 border-b border-gray-300">
                  <h4 className="text-sm font-semibold text-gray-700">Restriction filler</h4>
                </div>
                <div className="flex-1 overflow-hidden">
                  <EntityHierarchy
                    entitiesTab="Classes"
                    filteredData={classHierarchy}
                    selectedItem={restrictionFiller}
                    expandedNodes={effectiveFillerExpandedNodes}
                    searchQuery={fillerSearchQuery}
                    onSearchQueryChange={setFillerSearchQuery}
                    onSelectItem={(item) => setRestrictionFiller(item as TreeNode)}
                    onToggleNode={handleFillerToggle}
                    onAddItem={projectId ? (type) => handleInlineAddClass(type as 'subclass' | 'sibling') : () => {}}
                    onDeleteItem={projectId ? handleInlineDeleteStart : () => {}}
                    hideToolbarActions={!projectId}
                  />
                </div>
              </div>
          </div>

          {/* Class Expression Editor Tab */}
          <div className={`h-full p-6 flex flex-col${activeTab !== 'classExpression' ? ' hidden' : ''}`}>
              <div className="flex-1 flex flex-col min-h-0">
                <label className="text-sm font-semibold text-gray-700 mb-2">Class Expression</label>
                <textarea
                  value={manchesterExpression}
                  onChange={(e) => {
                    setManchesterExpression(e.target.value);
                    setManchesterParseOk(false);
                  }}
                  placeholder={"e.g., Pizza and (hasTopping some Cheese)\n      not VegetarianPizza\n      {IndividualA, IndividualB}"}
                  className="flex-1 p-4 border border-gray-300 rounded font-mono text-sm focus:outline-none focus:ring-2 focus:ring-blue-500 resize-none bg-white text-black"
                />
                {projectId && manchesterExpression.trim() && (
                  <div className={`mt-2 flex items-center gap-2 text-xs px-2 py-1 rounded ${
                    manchesterParseError ? 'bg-red-50 text-red-700' : manchesterParseOk ? 'bg-green-50 text-green-700' : 'text-gray-500'
                  }`}>
                    {manchesterParseError ? (
                      <><AlertCircle size={14} /> {manchesterParseError}</>
                    ) : manchesterParseOk ? (
                      <><CheckCircle2 size={14} /> Valid Manchester expression</>
                    ) : (
                      <span>Validating…</span>
                    )}
                  </div>
                )}
              </div>
              <div className="mt-4 p-4 bg-amber-50 rounded border border-amber-200">
                <p className="text-xs font-semibold text-amber-900 mb-2">MANCHESTER OWL SYNTAX</p>
                <p className="text-xs text-amber-800 mb-2">
                  Full Manchester syntax is validated against your ontology signature (OWLAPI parser).
                  Supports <span className="font-mono">and</span>, <span className="font-mono">or</span>, <span className="font-mono">not</span>, <span className="font-mono">some</span>, <span className="font-mono">only</span>, cardinality, and <span className="font-mono">{'{a, b}'}</span> enumerations.
                </p>
                <p className="text-xs text-amber-700">Use the <strong>Restriction</strong> tab for guided restriction building with pickers.</p>
              </div>
          </div>

          {/* Data Restriction Creator Tab */}
          <div className={`h-full flex${activeTab !== 'dataRestriction' ? ' hidden' : ''}`}>
              {/* LEFT: Restricted property - Uses EntityHierarchy */}
              <div className="w-1/2 border-r border-gray-300 flex flex-col">
                <div className="bg-gray-100 px-3 py-2 border-b border-gray-300">
                  <h4 className="text-sm font-semibold text-gray-700">Restricted property</h4>
                </div>
                
                {/* Inline Data Property Creation Form */}
                {showInlinePropertyCreate && propertyCreationTab === 'data' && (
                  <div className="p-3 bg-green-50 border-b border-green-200">
                    <div className="flex items-center gap-2">
                      <input
                        type="text"
                        value={inlinePropertyName}
                        onChange={(e) => setInlinePropertyName(e.target.value)}
                        onKeyDown={(e) => {
                          if (e.key === 'Enter' && inlinePropertyName.trim()) {
                            handleInlinePropertyCreateSubmit();
                          } else if (e.key === 'Escape') {
                            handleInlinePropertyCreateCancel();
                          }
                        }}
                        placeholder="Enter data property name..."
                        className="flex-1 px-2 py-1 text-sm border border-green-300 rounded focus:outline-none focus:ring-2 focus:ring-green-500"
                        autoFocus
                      />
                      <button
                        onClick={handleInlinePropertyCreateSubmit}
                        disabled={!inlinePropertyName.trim() || isCreatingProperty}
                        className="px-3 py-1 text-xs font-semibold text-white bg-green-600 rounded hover:bg-green-700 disabled:bg-gray-300 disabled:cursor-not-allowed"
                      >
                        {isCreatingProperty ? 'Creating...' : 'Create'}
                      </button>
                      <button
                        onClick={handleInlinePropertyCreateCancel}
                        className="px-3 py-1 text-xs font-semibold text-green-800 bg-white border border-green-300 rounded hover:bg-green-100"
                      >
                        Cancel
                      </button>
                    </div>
                    <p className="mt-1 text-xs text-green-700">Press Enter to create, Escape to cancel</p>
                  </div>
                )}
                
                <div className="flex-1 overflow-hidden">
                  <EntityHierarchy
                    entitiesTab="DataProperties"
                    filteredData={dataPropertiesTree}
                    selectedItem={selectedDataProperty as any}
                    expandedNodes={dataPropertyExpandedNodes}
                    searchQuery={dataPropSearchQuery}
                    onSearchQueryChange={setDataPropSearchQuery}
                    onSelectItem={(item) => setSelectedDataProperty(item as any as Property)}
                    onToggleNode={handleDataPropertyToggle}
                    onAddItem={projectId ? (type) => handleInlineAddProperty(type as 'subclass' | 'sibling') : () => {}}
                    onDeleteItem={onDeleteProperty || (() => {})}
                    hideToolbarActions={!projectId}
                  />
                </div>
              </div>

              {/* RIGHT: Restriction filler (Datatypes) */}
              <div className="w-1/2 flex flex-col">
                <div className="bg-gray-100 px-3 py-2 border-b border-gray-300">
                  <h4 className="text-sm font-semibold text-gray-700">Restriction filler</h4>
                </div>
                <div className="flex-1 overflow-y-auto">
                  {datatypes.map(dt => (
                    <div
                      key={dt}
                      onClick={() => setDatatype(dt)}
                      className={`flex items-center gap-2 px-3 py-1.5 cursor-pointer transition-colors text-sm border-b border-gray-100 ${
                        datatype === dt ? 'bg-red-50 font-semibold' : 'hover:bg-gray-50'
                      }`}
                    >
                      <span className={`w-3.5 h-3.5 rounded-sm border flex-shrink-0 ${
                        datatype === dt ? 'bg-red-600 border-red-700' : 'bg-red-400 border-red-600'
                      }`} />
                      <span className="font-mono text-xs">{dt}</span>
                    </div>
                  ))}
                </div>
              </div>
          </div>
        </div>

        {/* Restriction Type Controls - Bottom panel for restriction tabs */}
        {(activeTab === 'objectRestriction' || activeTab === 'dataRestriction') && (
          <div className="px-4 py-3 bg-gray-50 border-t border-gray-300">
            <div className="flex items-center gap-4">
              <label className="text-sm font-semibold text-gray-700">Restriction type</label>
              <select
                value={activeTab === 'objectRestriction' ? restrictionType : dataRestrictionType}
                onChange={(e) => {
                  const val = e.target.value as any;
                  if (activeTab === 'objectRestriction') {
                    setRestrictionType(val);
                  } else {
                    setDataRestrictionType(val);
                  }
                }}
                className="flex-1 max-w-xs px-3 py-2 text-sm border border-gray-300 rounded focus:outline-none focus:ring-2 focus:ring-blue-500"
              >
                {restrictionTypes
                  .filter(t => activeTab === 'dataRestriction' ? t.value !== 'value' : true)
                  .map(type => (
                    <option key={type.value} value={type.value}>{type.label}</option>
                  ))}
              </select>

              {((activeTab === 'objectRestriction' && (restrictionType === 'min' || restrictionType === 'max' || restrictionType === 'exactly')) ||
                (activeTab === 'dataRestriction' && (dataRestrictionType === 'min' || dataRestrictionType === 'max' || dataRestrictionType === 'exactly'))) && (
                <>
                  <label className="text-sm font-semibold text-gray-700">Cardinality</label>
                  <input
                    type="number"
                    min="0"
                    value={activeTab === 'objectRestriction' ? cardinality : dataCardinality}
                    onChange={(e) => {
                      const val = parseInt(e.target.value) || 0;
                      if (activeTab === 'objectRestriction') {
                        setCardinality(val);
                      } else {
                        setDataCardinality(val);
                      }
                    }}
                    className="w-24 px-3 py-2 text-sm border border-gray-300 rounded focus:outline-none focus:ring-2 focus:ring-blue-500"
                  />
                </>
              )}
            </div>
          </div>
        )}

        {/* Footer */}
        <div className="px-6 py-3 border-t border-gray-300 flex justify-end gap-3 bg-gray-50">
          <button
            onClick={handleClose}
            className="px-5 py-2 text-sm font-medium text-gray-700 bg-white border border-gray-300 rounded hover:bg-gray-100 transition-colors"
          >
            Cancel
          </button>
          <button
            onClick={handleConfirm}
            disabled={!isOkEnabled}
            className="px-5 py-2 text-sm font-medium text-white bg-blue-600 rounded hover:bg-blue-700 disabled:opacity-50 disabled:cursor-not-allowed transition-colors"
          >
            OK
          </button>
        </div>
      </div>
    </div>
  );
};

export default ClassExpressionDialog;

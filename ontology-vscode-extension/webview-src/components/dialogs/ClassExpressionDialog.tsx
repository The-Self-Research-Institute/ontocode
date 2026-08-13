import React, { useState, useEffect, useRef } from 'react';
import { X, CheckCircle2, AlertCircle, Loader2 } from 'lucide-react';
import EntityHierarchy from '../EntityHierarchy';
import ontologyMutationService from '../../services/ontologyMutationService';
import expressionService from '../../services/expressionService';
import { notificationService } from '../../services/notificationService';
import type { TreeNode, Property } from '../../types';

export interface RestrictionData {
  type: 'objectRestriction' | 'dataRestriction';
  axiomType: 'EquivalentTo' | 'SubClassOf' | 'DisjointWith';
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

  allowedTabs?: TabType[];

  axiomType?: 'EquivalentTo' | 'SubClassOf' | 'DisjointWith';
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

  objectPropertiesTree?: TreeNode[];
  dataPropertiesTree?: TreeNode[];

  onToggleObjectProperty?: (nodeId: string) => void;
  onToggleDataProperty?: (nodeId: string) => void;

  onRefreshClasses?: () => void;
  onRefreshProperties?: () => void;

  metadata?: { ontologyIRI?: string };
}

export type TabType = 'hierarchy' | 'objectRestriction' | 'classExpression' | 'dataRestriction';

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
  axiomType = 'SubClassOf',
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

  const visibleTabs = allowedTabs || ['hierarchy', 'objectRestriction', 'classExpression', 'dataRestriction'];

  const [activeTab, setActiveTab] = useState<TabType>('hierarchy');

  const [selectedClass, setSelectedClass] = useState<TreeNode | null>(null);
  const [classSearchQuery, setClassSearchQuery] = useState('');
  const [localExpandedNodes, setLocalExpandedNodes] = useState<string[]>([]);

  const [selectedFillerClass, setSelectedFillerClass] = useState<TreeNode | null>(null);

  const [selectedProperty, setSelectedProperty] = useState<Property | null>(null);
  const [restrictionType, setRestrictionType] = useState<'some' | 'only' | 'min' | 'max' | 'exactly' | 'value'>('some');
  const [cardinality, setCardinality] = useState(1);
  const [restrictionFiller, setRestrictionFiller] = useState<TreeNode | null>(null);
  const [fillerSearchQuery, setFillerSearchQuery] = useState('');
  const [objectPropSearchQuery, setObjectPropSearchQuery] = useState('');
  const [propertyExpandedNodes, setPropertyExpandedNodes] = useState<string[]>([]);
  const [fillerExpandedNodes, setFillerExpandedNodes] = useState<string[]>([]);

  const [selectedDataProperty, setSelectedDataProperty] = useState<Property | null>(null);
  const [dataRestrictionType, setDataRestrictionType] = useState<'some' | 'only' | 'min' | 'max' | 'exactly' | 'value'>('some');
  const [dataCardinality, setDataCardinality] = useState(1);
  const [datatype, setDatatype] = useState('xsd:string');
  const [dataPropertyExpandedNodes, setDataPropertyExpandedNodes] = useState<string[]>([]);
  const [dataPropSearchQuery, setDataPropSearchQuery] = useState('');

  const [manchesterExpression, setManchesterExpression] = useState(initialValue);
  const [manchesterParseError, setManchesterParseError] = useState<string | null>(null);
  const [manchesterParseOk, setManchesterParseOk] = useState(false);
  const parseTimerRef = useRef<ReturnType<typeof setTimeout> | null>(null);

  const [showInlineCreate, setShowInlineCreate] = useState(false);
  const [inlineCreateType, setInlineCreateType] = useState<'subclass' | 'sibling'>('subclass');
  const [inlineClassName, setInlineClassName] = useState('');
  const [isCreatingClass, setIsCreatingClass] = useState(false);
  const [isSavingConfirm, setIsSavingConfirm] = useState(false);

  const [showInlineDelete, setShowInlineDelete] = useState(false);
  const [isDeletingClass, setIsDeletingClass] = useState(false);

  const [showInlinePropertyCreate, setShowInlinePropertyCreate] = useState(false);
  const [inlinePropertyCreateType, setInlinePropertyCreateType] = useState<'subclass' | 'sibling'>('subclass');
  const [inlinePropertyName, setInlinePropertyName] = useState('');
  const [isCreatingProperty, setIsCreatingProperty] = useState(false);
  const [propertyCreationTab, setPropertyCreationTab] = useState<'object' | 'data'>('object');

  const [hasInitialized, setHasInitialized] = useState(false);

  useEffect(() => {
    if (isOpen && !hasInitialized) {
      setHasInitialized(true);
      setManchesterExpression(initialValue);

      if (initialTab) {
        setActiveTab(initialTab);

        const classIriToSelect = initialClassIri || initialValue;

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

            if (pathToClass.length > 0) {
              setLocalExpandedNodes(pathToClass);
            }
          } else {

            const localName = classIriToSelect.split(/[#/]/).pop() || classIriToSelect;
            setClassSearchQuery(localName);
          }
        }
      } else if (initialValue) {
        setActiveTab('classExpression');
      } else {
        setActiveTab('hierarchy');
      }

      if (initialRestrictionData) {
        if (initialRestrictionData.isDataProperty) {

          if (initialRestrictionData.propertyIri) {

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

          if (initialRestrictionData.propertyIri) {

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

              if (pathToFiller.length > 0) {
                setFillerExpandedNodes(pathToFiller);
              }
            }
          }
        }
      }
    }
  }, [isOpen, hasInitialized, initialValue, initialClassIri, initialTab, initialRestrictionData, objectProperties, dataProperties, classHierarchy]);

  useEffect(() => {
    if (!isOpen) {
      setHasInitialized(false);
    }
  }, [isOpen]);

  const propertiesToTree = (properties: Property[], isDataProperty: boolean = false): TreeNode[] => {
    const topPropertyIri = isDataProperty
      ? 'http://www.w3.org/2002/07/owl#topDataProperty'
      : 'http://www.w3.org/2002/07/owl#topObjectProperty';

    const topPropertyLabel = isDataProperty
      ? 'owl:topDataProperty'
      : 'owl:topObjectProperty';

    if (properties.length === 0) {
      return [{
        id: topPropertyIri,
        label: topPropertyLabel,
        hasChildren: false,
        children: []
      }];
    }

    const propMap = new Map<string, Property>();
    properties.forEach(prop => propMap.set(prop.id, prop));

    const childrenMap = new Map<string, Property[]>();

    properties.forEach(prop => {
      if (prop.superProperties && prop.superProperties.length > 0) {

        prop.superProperties.forEach(parentId => {
          if (!childrenMap.has(parentId)) {
            childrenMap.set(parentId, []);
          }
          childrenMap.get(parentId)!.push(prop);
        });
      } else {

        if (!childrenMap.has(topPropertyIri)) {
          childrenMap.set(topPropertyIri, []);
        }
        childrenMap.get(topPropertyIri)!.push(prop);
      }
    });

    const buildNode = (prop: Property): TreeNode => {
      const children = childrenMap.get(prop.id) || [];
      return {
        id: prop.id,
        label: prop.label,
        hasChildren: children.length > 0,
        children: children.map(buildNode)
      };
    };

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
    if (!selectedDataProperty) {
      console.warn('[ClassExpressionDialog] buildDataRestriction: No data property selected');
      return '';
    }

    const propName = selectedDataProperty.label;

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
    if (isSavingConfirm) return;

    let expression = '';
    let restrictionData: RestrictionData | undefined = undefined;

    switch (activeTab) {
      case 'hierarchy':
        if (selectedClass) {
          expression = selectedClass.id;
        } else {
          console.warn('[ClassExpressionDialog] Hierarchy tab - no class selected!');
          notificationService.warning('Selection Required', 'Please select a class from the hierarchy');
          return;
        }
        break;
      case 'objectRestriction':
        expression = buildObjectRestriction();

        if (selectedProperty && restrictionFiller) {
          restrictionData = {
            type: 'objectRestriction',
            axiomType,
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
        expression = buildDataRestriction();

        if (selectedDataProperty) {
          const fillerIri = datatype.startsWith('http://') || datatype.startsWith('rdf:') || datatype.startsWith('rdfs:') || datatype.startsWith('owl:')
            ? (datatype.includes(':') && !datatype.startsWith('http') 
              ? (datatype.startsWith('rdf:') ? `http://www.w3.org/1999/02/22-rdf-syntax-ns#${datatype.replace('rdf:', '')}` 
                : datatype.startsWith('rdfs:') ? `http://www.w3.org/2000/01/rdf-schema#${datatype.replace('rdfs:', '')}`
                : datatype.startsWith('owl:') ? `http://www.w3.org/2002/07/owl#${datatype.replace('owl:', '')}`
                : datatype)
              : datatype) 
            : `http://www.w3.org/2001/XMLSchema#${datatype.replace('xsd:', '')}`;
          restrictionData = {
            type: 'dataRestriction',
            axiomType,
            propertyIri: selectedDataProperty.id,
            restrictionType: dataRestrictionType,
            fillerIri: fillerIri,
            cardinality: ['min', 'max', 'exactly'].includes(dataRestrictionType) ? dataCardinality : undefined
          };
        }
        break;
    }

    if (expression) {
      setIsSavingConfirm(true);
      try {
        await onConfirm(expression, restrictionData);
        handleClose();
      } catch (error) {
        console.error('[ClassExpressionDialog] onConfirm failed:', error);
      } finally {
        setIsSavingConfirm(false);
      }
    } else {
      console.warn('[ClassExpressionDialog] No expression to confirm! activeTab:', activeTab, 'selectedClass:', selectedClass);
    }
  };

  const handleClose = () => {

    setSelectedClass(null);
    setSelectedProperty(null);
    setRestrictionFiller(null);
    setSelectedDataProperty(null);
    setManchesterExpression('');
    setClassSearchQuery('');
    setFillerSearchQuery('');
    setActiveTab('hierarchy');

    setShowInlineCreate(false);
    setInlineClassName('');

    setShowInlineDelete(false);
    onClose();
  };

  const handleHierarchyToggle = async (nodeId: string) => {

    const isExpanded = localExpandedNodes.includes(nodeId);
    setLocalExpandedNodes(
      isExpanded
        ? localExpandedNodes.filter(id => id !== nodeId)
        : [...localExpandedNodes, nodeId]
    );

    if (onToggleNode) {
      await onToggleNode(nodeId);
    }
  };

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

  const handleFillerToggle = async (nodeId: string) => {
    if (onToggleNode) {

      await onToggleNode(nodeId);

      const isExpanded = fillerExpandedNodes.includes(nodeId);
      setFillerExpandedNodes(
        isExpanded
          ? fillerExpandedNodes.filter(id => id !== nodeId)
          : [...fillerExpandedNodes, nodeId]
      );
    } else {

      const isExpanded = fillerExpandedNodes.includes(nodeId);
      setFillerExpandedNodes(
        isExpanded
          ? fillerExpandedNodes.filter(id => id !== nodeId)
          : [...fillerExpandedNodes, nodeId]
      );
    }
  };

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

  const handleInlineAddClass = (type: 'subclass' | 'sibling') => {
    setInlineCreateType(type);
    setInlineClassName('');
    setShowInlineCreate(true);
  };

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

      const baseIri = metadata?.ontologyIRI || 'http://example.org/ontology#';
      const cleanName = inlineClassName.trim().replace(/\s+/g, '_');
      const newClassIri = baseIri.endsWith('#') || baseIri.endsWith('/') 
        ? `${baseIri}${cleanName}` 
        : `${baseIri}#${cleanName}`;

      if (!localExpandedNodes.includes(parentIri)) {
        setLocalExpandedNodes(prev => [...prev, parentIri]);
      }

      if (onToggleNode && !expandedNodes.includes(parentIri)) {
        await onToggleNode(parentIri);
      }

      await ontologyMutationService.createClass(
        projectId,
        newClassIri,
        inlineClassName.trim(),
        parentIri,
        'anonymous',
        'Anonymous'
      );

      if (onRefreshClasses) {
        onRefreshClasses();
      }

      setShowInlineCreate(false);
      setInlineClassName('');
    } catch (error) {
      console.error('Failed to create class:', error);
    } finally {
      setIsCreatingClass(false);
    }
  };

  const handleInlineCreateCancel = () => {
    setShowInlineCreate(false);
    setInlineClassName('');
  };

  const handleInlineDeleteStart = () => {
    if (!selectedClass || selectedClass.id.includes('Thing')) return;
    setShowInlineDelete(true);
  };

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

      setSelectedClass(null);
      setShowInlineDelete(false);

      if (onRefreshClasses) {
        onRefreshClasses();
      }
    } catch (error) {
      console.error('Failed to delete class:', error);
    } finally {
      setIsDeletingClass(false);
    }
  };

  const handleInlineDeleteCancel = () => {
    setShowInlineDelete(false);
  };

  const handleInlineAddProperty = (type: 'subclass' | 'sibling') => {

    const isDataTab = activeTab === 'dataRestriction';
    setPropertyCreationTab(isDataTab ? 'data' : 'object');
    setInlinePropertyCreateType(type);
    setInlinePropertyName('');
    setShowInlinePropertyCreate(true);
  };

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

        const hierarchy = isDataProperty ? dataPropertiesTree : objectPropertiesTree;
        const parent = findParentNode(hierarchy, selectedProp.id);
        if (parent) {
          parentIri = parent.id;
        }
      }

      const baseIri = metadata?.ontologyIRI || 'http://example.org/ontology#';
      const cleanName = inlinePropertyName.trim().replace(/\s+/g, '_');
      const newPropertyIri = baseIri.endsWith('#') || baseIri.endsWith('/') 
        ? `${baseIri}${cleanName}` 
        : `${baseIri}#${cleanName}`;

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

      if (onRefreshProperties) {
        onRefreshProperties();
      }

      setShowInlinePropertyCreate(false);
      setInlinePropertyName('');
    } catch (error) {
      console.error('Failed to create property:', error);
      notificationService.error('Create Failed', `Failed to create property: ${error instanceof Error ? error.message : 'Unknown error'}`);
    } finally {
      setIsCreatingProperty(false);
    }
  };

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

  const topObjectPropertyIri = 'http://www.w3.org/2002/07/owl#topObjectProperty';
  const topDataPropertyIri = 'http://www.w3.org/2002/07/owl#topDataProperty';

  const isOkEnabled = !isSavingConfirm && (
    (activeTab === 'hierarchy' && selectedClass !== null) ||
    (activeTab === 'objectRestriction' && selectedProperty && restrictionFiller
      && selectedProperty.id !== topObjectPropertyIri) ||
    (activeTab === 'classExpression' && manchesterExpression.trim()) ||
    (activeTab === 'dataRestriction' && selectedDataProperty
      && selectedDataProperty.id !== topDataPropertyIri)
  );

  useEffect(() => {
  }, [isOkEnabled, activeTab, selectedClass, selectedProperty, restrictionFiller, selectedDataProperty, datatype, dataRestrictionType]);

  if (!isOpen) return null;

  const objectPropertiesTree = externalObjectPropertiesTree || propertiesToTree(objectProperties, false);
  const dataPropertiesTree = externalDataPropertiesTree || propertiesToTree(dataProperties, true);

  const effectiveExpandedNodes = [...new Set([...expandedNodes, ...localExpandedNodes])];
  const effectiveFillerExpandedNodes = [...new Set([...expandedNodes, ...fillerExpandedNodes])];

  return (
    <div className="fixed inset-0 bg-black bg-opacity-50 flex items-center justify-center z-50">
      <div className="bg-white rounded-lg shadow-xl w-full max-w-7xl mx-4 flex flex-col h-[90vh]">
        {}
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

        {}
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

        {}
        <div className="flex-1 overflow-hidden min-h-0 bg-white">
          {}
          <div className={`h-full flex flex-col${activeTab !== 'hierarchy' ? ' hidden' : ''}`}>
              {}
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

              {}
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
                    setSelectedClass(item as TreeNode);
                  }}
                  onToggleNode={handleHierarchyToggle}
                  onAddItem={projectId ? (type) => handleInlineAddClass(type as 'subclass' | 'sibling') : () => {}}
                  onDeleteItem={projectId ? handleInlineDeleteStart : () => {}}
                  hideToolbarActions={!projectId}
                />
              </div>
          </div>

          {}
          <div className={`h-full flex${activeTab !== 'objectRestriction' ? ' hidden' : ''}`}>
              {}
              <div className="w-1/2 border-r border-gray-300 flex flex-col">
                <div className="bg-gray-100 px-3 py-2 border-b border-gray-300">
                  <h4 className="text-sm font-semibold text-gray-700">Restricted property</h4>
                </div>

                {}
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

              {}
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

          {}
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

          {}
          <div className={`h-full flex${activeTab !== 'dataRestriction' ? ' hidden' : ''}`}>
              {}
              <div className="w-1/2 border-r border-gray-300 flex flex-col">
                <div className="bg-gray-100 px-3 py-2 border-b border-gray-300">
                  <h4 className="text-sm font-semibold text-gray-700">Restricted property</h4>
                </div>

                {}
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

              {}
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

        {}
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

        {}
        <div className="px-6 py-3 border-t border-gray-300 flex justify-end gap-3 bg-gray-50">
          <button
            onClick={handleClose}
            className="px-5 py-2 text-sm font-medium text-gray-700 bg-white border border-gray-300 rounded hover:bg-gray-100 transition-colors"
          >
            Cancel
          </button>
          <button
            onClick={() => void handleConfirm()}
            disabled={!isOkEnabled}
            className="px-5 py-2 text-sm font-medium text-white bg-blue-600 rounded hover:bg-blue-700 disabled:opacity-50 disabled:cursor-not-allowed transition-colors inline-flex items-center gap-2"
          >
            {isSavingConfirm && <Loader2 size={14} className="animate-spin" />}
            {isSavingConfirm ? 'Saving…' : 'OK'}
          </button>
        </div>
      </div>
    </div>
  );
};

export default ClassExpressionDialog;

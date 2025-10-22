import { useState, useEffect, useCallback, useRef } from "react";
import {
  ChevronRight,
  ChevronDown,
  Plus,
  Settings,
  Search,
  FileText,
  BarChart3,
  Eye,
  Filter,
  Upload,
  RefreshCw,
  Database,
  Tag,
  Link2,
  Share2,
  List,
  Code,
  Loader2,
} from "lucide-react";
import apiClient from "../services/apiClient";

interface TreeNode {
  id: string;
  label: string;
  annotations?: Record<string, string>;
  children?: TreeNode[] | null;
}

interface Property {
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

interface Individual {
  id: string;
  iri: string;
  label: string;
  annotations?: Record<string, string>;
  types?: string[];
  sameAs?: string[];
  differentFrom?: string[];
}

interface AnnotationProperty {
  id: string;
  iri: string;
  label: string;
  annotations?: Record<string, string>;
}

interface Datatype {
  id: string;
  iri: string;
  label: string;
  annotations?: Record<string, string>;
}

interface OntologyMetadata {
  filename: string;
  ontologyIRI: string | null;
  versionIRI: string | null;
  classCount: number;
  objectPropertyCount: number;
  dataPropertyCount: number;
  individualCount: number;
  axiomCount: number;
  annotations?: Record<string, string>;
}

interface ClassUsage {
  classIri: string;
  totalUsages: number;
  usages: AxiomUsage[];
}

interface AxiomUsage {
  category: string;
  description: string;
  relatedEntity: string;
  axiomType: string;
}

type SelectableItem =
  | TreeNode
  | Property
  | Individual
  | AnnotationProperty
  | Datatype;

const AnnotationValue = ({ value }: { value: string }) => {
  let cleanedValue = value.toString();
  if (cleanedValue.startsWith('"')) cleanedValue = cleanedValue.substring(1);
  if (cleanedValue.endsWith('"^^xsd:string') || cleanedValue.endsWith('"')) {
    cleanedValue = cleanedValue
      .replace(/"\^\^xsd:string$/, "")
      .replace(/"$/, "");
  }
  const isMathML = /<mathml:math/i.test(cleanedValue);
  if (isMathML) {
    return (
      <div
        dangerouslySetInnerHTML={{ __html: cleanedValue }}
        className="text-sm"
      />
    );
  }
  return (
    <p className="text-sm text-gray-700 leading-relaxed whitespace-pre-wrap">
      {cleanedValue}
    </p>
  );
};

const AnnotationsDisplay = ({
  annotations,
}: {
  annotations?: Record<string, string>;
}) => {
  if (!annotations || Object.keys(annotations).length === 0) {
    return <p className="text-sm text-gray-400 italic">No annotations</p>;
  }
  const annotationOrder = [
    "label",
    "definition",
    "comment",
    "IAO_0000115",
    "IAO_0000111",
  ];
  const sortedAnnotations = Object.entries(annotations).sort(
    ([keyA], [keyB]) => {
      const indexA = annotationOrder.indexOf(keyA);
      const indexB = annotationOrder.indexOf(keyB);
      if (indexA === -1 && indexB === -1) return keyA.localeCompare(keyB);
      if (indexA === -1) return 1;
      if (indexB === -1) return -1;
      return indexA - indexB;
    }
  );
  const getLabelColor = (key: string) => {
    if (key === "label" || key === "IAO_0000111")
      return "bg-purple-100 text-purple-800";
    if (key === "definition" || key === "IAO_0000115")
      return "bg-blue-100 text-blue-800";
    if (key === "comment") return "bg-green-100 text-green-800";
    return "bg-gray-100 text-gray-800";
  };
  return (
    <div className="space-y-4">
      {sortedAnnotations.map(([key, value]) => (
        <div
          key={key}
          className="border-l-4 border-purple-500 pl-4 py-2 bg-gray-50 rounded-r"
        >
          <div
            className={`inline-block text-xs font-semibold px-2 py-1 rounded mb-2 ${getLabelColor(
              key
            )}`}
          >
            {key
              .replace(/([A-Z])/g, " $1")
              .replace(/^./, (str) => str.toUpperCase())
              .replace("rdfs", "rdfs:")}
          </div>
          <AnnotationValue value={value} />
        </div>
      ))}
    </div>
  );
};

const LoadingDialog = ({
  isOpen,
  message,
}: {
  isOpen: boolean;
  message?: string;
}) => {
  if (!isOpen) return null;

  return (
    <div className="fixed inset-0 bg-black bg-opacity-50 flex items-center justify-center z-50">
      <div className="bg-white rounded-lg shadow-xl p-8 max-w-sm w-full mx-4">
        <div className="flex flex-col items-center">
          <Loader2 size={48} className="text-purple-600 animate-spin mb-4" />
          <h3 className="text-lg font-semibold text-gray-900 mb-2">
            {message || "Loading Ontology"}
          </h3>
          <p className="text-sm text-gray-500 text-center">
            Please wait while we fetch your ontology data...
          </p>
        </div>
      </div>
    </div>
  );
};

const AxiomDisplay = ({
  description,
  onClassClick,
  projectId,
}: {
  description: string;
  onClassClick: (iri: string) => void;
  projectId: string;
}) => {
  const parseAndStyleAxiom = (text: string) => {
    const keywords = {
      SubClassOf: "text-blue-600 font-semibold",
      EquivalentTo: "text-blue-600 font-semibold",
      DisjointWith: "text-blue-600 font-semibold",
      some: "text-pink-600 font-semibold",
      only: "text-pink-600 font-semibold",
      and: "text-pink-600 font-semibold",
      or: "text-pink-600 font-semibold",
      not: "text-pink-600 font-semibold",
      min: "text-pink-600 font-semibold",
      max: "text-pink-600 font-semibold",
      exactly: "text-pink-600 font-semibold",
    };

    const propertyPattern =
      /(has part|part of|overlaps|develops from|located in|has role|bearer of|inheres in|realized in|participates in|contains|contained in)/gi;

    const words = text.split(/(\s+)/);

    return (
      <span>
        {words.map((word, idx) => {
          const trimmedWord = word.trim();

          if (keywords[trimmedWord as keyof typeof keywords]) {
            return (
              <span
                key={idx}
                className={keywords[trimmedWord as keyof typeof keywords]}
              >
                {word}
              </span>
            );
          }

          const propertyMatch = trimmedWord.match(propertyPattern);
          if (propertyMatch) {
            return (
              <span key={idx} className="text-purple-600 font-medium">
                {word}
              </span>
            );
          }

          const isClassName =
            trimmedWord.length > 0 &&
            !keywords[trimmedWord as keyof typeof keywords] &&
            !trimmedWord.match(/^[()]/);

          if (isClassName && trimmedWord !== "") {
            return (
              <span
                key={idx}
                className="text-gray-900 hover:text-blue-600 hover:underline cursor-pointer"
                onClick={() => handleClassNameClick(trimmedWord)}
              >
                {word}
              </span>
            );
          }

          return (
            <span key={idx} className="text-gray-700">
              {word}
            </span>
          );
        })}
      </span>
    );
  };

  const handleClassNameClick = async (className: string) => {
    if (!projectId) return;

    try {
      const response = await apiClient.get(
        `/api/ontology/classes/search/${projectId}`,
        {
          params: { query: className },
        }
      );

      if (response.data && response.data.length > 0) {
        const foundClass = response.data[0];
        onClassClick(foundClass.id);
      }
    } catch (error) {
      console.error("Failed to find class:", error);
    }
  };

  return parseAndStyleAxiom(description);
};

const EnhancedDashboard = () => {
  const [projectId, setProjectId] = useState<string | null>(null);
  const [metadata, setMetadata] = useState<OntologyMetadata | null>(null);
  const [mainTab, setMainTab] = useState("Entities");
  const [entitiesTab, setEntitiesTab] = useState("Classes");
  const [selectedItem, setSelectedItem] = useState<SelectableItem | null>(null);
  const [expandedNodes, setExpandedNodes] = useState<string[]>([]);
  const [searchQuery, setSearchQuery] = useState("");
  const [isInitialLoading, setIsInitialLoading] = useState(false);
  const [classUsage, setClassUsage] = useState<ClassUsage | null>(null);
  const [detailTab, setDetailTab] = useState<"annotations" | "usage">(
    "annotations"
  );
  const [loadingUsage, setLoadingUsage] = useState(false);

  const [classHierarchy, setClassHierarchy] = useState<TreeNode[]>([]);
  const [objectProperties, setObjectProperties] = useState<Property[]>([]);
  const [dataProperties, setDataProperties] = useState<Property[]>([]);
  const [annotationProperties, setAnnotationProperties] = useState<
    AnnotationProperty[]
  >([]);
  const [individuals, setIndividuals] = useState<Individual[]>([]);
  const [datatypes, setDatatypes] = useState<Datatype[]>([]);
  const [filteredData, setFilteredData] = useState<
    (TreeNode | Property | AnnotationProperty | Datatype | Individual)[]
  >([]);

  const [objectPropertyHierarchy, setObjectPropertyHierarchy] = useState<
    Property[]
  >([]);
  const [dataPropertyHierarchy, setDataPropertyHierarchy] = useState<
    Property[]
  >([]);
  const [showHierarchicalView, setShowHierarchicalView] = useState(true);

  // Infinite scroll state
  const [topLevelPage, setTopLevelPage] = useState(0);
  const [topLevelHasMore, setTopLevelHasMore] = useState(true);
  const [isLoadingMore, setIsLoadingMore] = useState(false);
  const [allTopLevelClasses, setAllTopLevelClasses] = useState<TreeNode[]>([]);

  const classListRef = useRef<HTMLDivElement>(null);

  
  const fetchData = useCallback(async () => {
    if (!projectId) return;

    setIsInitialLoading(true);
    setSelectedItem(null);
    setSearchQuery("");
    setTopLevelPage(0);
    setTopLevelHasMore(true);
    setAllTopLevelClasses([]);

    try {
      const [
        metadataRes,
        topLevelRes,
        propertiesRes,
        individualsRes,
        annotationPropsRes,
        datatypesRes,
        objPropHierarchyRes,
        dataPropHierarchyRes,
      ] = await Promise.allSettled([
        apiClient.get(`/api/ontology/metadata/${projectId}`),
        apiClient.get(`/api/ontology/classes/top-level/${projectId}`, {
          params: { page: 0, size: 100 },
        }),
        apiClient.get(`/api/ontology/properties/${projectId}`),
        apiClient.get(`/api/ontology/individuals/${projectId}`),
        apiClient.get(`/api/ontology/annotation-properties/${projectId}`),
        apiClient.get(`/api/ontology/datatypes/${projectId}`),
        apiClient.get(`/api/ontology/object-properties/tree/${projectId}`),
        apiClient.get(`/api/ontology/data-properties/tree/${projectId}`),
      ]);

      if (metadataRes.status === "fulfilled" && metadataRes.value.data?.data) {
        setMetadata(
          metadataRes.value.data.data.metadata || metadataRes.value.data.data
        );
      }

      if (topLevelRes.status === "fulfilled" && topLevelRes.value.data) {
        const { classes, hasMore } = topLevelRes.value.data;

        const topLevelNodes: TreeNode[] = classes.map(
          (c: {
            id: string;
            label: string;
            annotations?: Record<string, string>;
            hasChildren: boolean;
          }) => ({
            id: c.id,
            label: c.label,
            annotations: c.annotations,
            children: c.hasChildren ? [] : null,
          })
        );

        setAllTopLevelClasses(topLevelNodes);
        setTopLevelHasMore(hasMore);

        const owlThingNode: TreeNode = {
          id: "http://www.w3.org/2002/07/owl#Thing",
          label: "owl:Thing",
          children: topLevelNodes,
          annotations: {},
        };

        setClassHierarchy([owlThingNode]);
        setFilteredData([owlThingNode]);
        setExpandedNodes([owlThingNode.id]);
      }

      if (
        propertiesRes.status === "fulfilled" &&
        propertiesRes.value.data?.data
      ) {
        const allProps = propertiesRes.value.data.data || [];
        setObjectProperties(
          allProps.filter((p: Property) => p.type === "ObjectProperty")
        );
        setDataProperties(
          allProps.filter((p: Property) => p.type === "DataProperty")
        );
      }

      if (
        individualsRes.status === "fulfilled" &&
        individualsRes.value.data?.data
      ) {
        setIndividuals(individualsRes.value.data.data || []);
      }

      if (
        annotationPropsRes.status === "fulfilled" &&
        annotationPropsRes.value.data?.data
      ) {
        setAnnotationProperties(annotationPropsRes.value.data.data || []);
      }

      if (
        datatypesRes.status === "fulfilled" &&
        datatypesRes.value.data?.data
      ) {
        setDatatypes(datatypesRes.value.data.data || []);
      }

      if (
        objPropHierarchyRes.status === "fulfilled" &&
        objPropHierarchyRes.value.data
      ) {
        setObjectPropertyHierarchy(objPropHierarchyRes.value.data);
      }

      if (
        dataPropHierarchyRes.status === "fulfilled" &&
        dataPropHierarchyRes.value.data
      ) {
        setDataPropertyHierarchy(dataPropHierarchyRes.value.data);
      }
    } catch (error) {
      console.error("Failed to fetch data:", error);
    } finally {
      setIsInitialLoading(false);
    }
  }, [projectId]);

  const pollProcessingStatus = useCallback(async (projectId: string) => {
    const maxAttempts = 60;
    let attempts = 0;

    const poll = async (): Promise<void> => {
      try {
        const response = await apiClient.get(
          `/api/ontology/status/${projectId}`
        );
        const status = response.data.data?.status;

        console.log(`[Poll ${attempts + 1}/${maxAttempts}] Status:`, status);

        if (status === "COMPLETED") {
          console.log("Processing completed, fetching data...");
          setIsInitialLoading(false);
          await fetchData();
          return;
        } else if (status === "ERROR") {
          setIsInitialLoading(false);
          const errorMsg =
            response.data.data?.statusMessage || "Processing failed";
          console.error("Processing failed:", errorMsg);
          return;
        } else if (attempts < maxAttempts) {
          attempts++;
          setTimeout(poll, 5000);
        } else {
          setIsInitialLoading(false);
          console.error("Processing timeout");
        }
      } catch (error: unknown) {
        console.error("Error polling status:", error);
        if (
          typeof error === "object" &&
          error !== null &&
          "response" in error
        ) {
          const err = error as { response?: { status?: number } };
          if (err.response?.status === 404 && attempts < 10) {
            attempts++;
            setTimeout(poll, 2000);
            return;
          }
        }
        setIsInitialLoading(false);
      }
    };

    poll();
  }, [fetchData]);

  useEffect(() => {
    const handleMessage = (event: MessageEvent) => {
      const message = event.data;
      switch (message.type) {
        case "showLoading":
          setIsInitialLoading(true);
          break;
        case "fileReady":
          setProjectId(message.projectId);
          pollProcessingStatus(message.projectId);
          break;
        case "loadingFailed":
          setIsInitialLoading(false);
          break;
      }
    };
    window.addEventListener("message", handleMessage);
    return () => window.removeEventListener("message", handleMessage);
  }, [pollProcessingStatus]);

  const fetchClassUsage = useCallback(
    async (classIri: string) => {
      if (!projectId) return;

      setLoadingUsage(true);
      try {
        const response = await apiClient.get(
          `/api/ontology/classes/usage/${projectId}`,
          {
            params: { classIri },
          }
        );
        setClassUsage(response.data);
      } catch (error) {
        console.error("Failed to load class usage:", error);
        setClassUsage(null);
      } finally {
        setLoadingUsage(false);
      }
    },
    [projectId]
  );

  useEffect(() => {
    if (selectedItem && "children" in selectedItem) {
      fetchClassUsage(selectedItem.id);
    } else {
      setClassUsage(null);
    }
  }, [selectedItem, fetchClassUsage]);



const expandPathToClass = useCallback(async (classIri: string) => {
  if (!projectId) return;
  
  try {
    // Get the target class details
    const response = await apiClient.get(`/api/ontology/classes/search/${projectId}`, {
      params: { query: classIri }
    });
    
    if (response.data && response.data.length > 0) {
      // ✅ Removed unused variable
      
      // Expand owl:Thing if not already expanded
      if (!expandedNodes.includes('http://www.w3.org/2002/07/owl#Thing')) {
        setExpandedNodes(prev => [...prev, 'http://www.w3.org/2002/07/owl#Thing']);
      }
      
      // If the class has annotations with parent information, expand those
      // This is a simplified approach - in a real implementation,
      // you'd need to trace the full path from owl:Thing to the target class
    }
  } catch (error) {
    console.error('Failed to expand path:', error);
  }
}, [projectId, expandedNodes]); // ✅ Added dependencies

// ✅ Fixed: Added expandPathToClass to dependency array
const handleNavigateToClass = useCallback(async (classIri: string) => {
  if (!projectId) return;
  
  try {
    // First, expand the path to the class
    await expandPathToClass(classIri);
    
    // Then select the class
    const response = await apiClient.get(`/api/ontology/classes/search/${projectId}`, {
      params: { query: classIri }
    });
    
    if (response.data && response.data.length > 0) {
      // ✅ Removed unused variable
      setSelectedItem(response.data[0]);
      
      // Scroll to the class in the tree
      setTimeout(() => {
        const element = document.querySelector(`[data-class-id="${classIri}"]`);
        if (element) {
          element.scrollIntoView({ behavior: 'smooth', block: 'center' });
        }
      }, 100);
    }
  } catch (error) {
    console.error('Failed to navigate to class:', error);
  }
}, [projectId, expandPathToClass]); 

  const loadTopLevelClasses = useCallback(
    async (page: number, append: boolean = false) => {
      if (!projectId || isLoadingMore) return;

      setIsLoadingMore(true);

      try {
        const response = await apiClient.get(
          `/api/ontology/classes/top-level/${projectId}`,
          {
            params: { page, size: 100 },
          }
        );

        const { classes, hasMore } = response.data;

        const treeNodes: TreeNode[] = classes.map(
          (c: {
            id: string;
            label: string;
            annotations?: Record<string, string>;
            hasChildren: boolean;
          }) => ({
            id: c.id,
            label: c.label,
            annotations: c.annotations,
            children: c.hasChildren ? [] : null,
          })
        );

        if (append) {
          setAllTopLevelClasses((prev) => [...prev, ...treeNodes]);
        } else {
          setAllTopLevelClasses(treeNodes);
        }

        setTopLevelHasMore(hasMore);
        setTopLevelPage(page);
      } catch (error) {
        console.error("Failed to load top-level classes:", error);
      } finally {
        setIsLoadingMore(false);
      }
    },
    [projectId, isLoadingMore]
  );

  const loadChildren = async (nodeId: string): Promise<TreeNode[]> => {
    if (!projectId) return [];

    try {
      const response = await apiClient.get(
        `/api/ontology/classes/children/${projectId}`,
        {
          params: { parentIri: nodeId },
        }
      );

      return response.data || [];
    } catch (error) {
      console.error("Failed to load children:", error);
      return [];
    }
  };

  useEffect(() => {
    fetchData();
  }, [fetchData]);

  useEffect(() => {
    if (allTopLevelClasses.length > 0 && entitiesTab === "Classes") {
      const owlThingNode: TreeNode = {
        id: "http://www.w3.org/2002/07/owl#Thing",
        label: "owl:Thing",
        children: allTopLevelClasses,
        annotations: {},
      };

      setClassHierarchy([owlThingNode]);
      if (!searchQuery) {
        setFilteredData([owlThingNode]);
      }
    }
  }, [allTopLevelClasses, searchQuery, entitiesTab]);

  useEffect(() => {
    if (!searchQuery) {
      switch (entitiesTab) {
        case "Classes":
          setFilteredData(classHierarchy);
          break;
        case "ObjectProperties":
          setFilteredData(
            showHierarchicalView && objectPropertyHierarchy.length > 0
              ? objectPropertyHierarchy
              : objectProperties
          );
          break;
        case "DataProperties":
          setFilteredData(
            showHierarchicalView && dataPropertyHierarchy.length > 0
              ? dataPropertyHierarchy
              : dataProperties
          );
          break;
        case "AnnotationProperties":
          setFilteredData(annotationProperties);
          break;
        case "Datatypes":
          setFilteredData(datatypes);
          break;
        case "Individuals":
          setFilteredData(individuals);
          break;
      }
      return;
    }

    const debounce = setTimeout(() => {
      const lowercasedQuery = searchQuery.toLowerCase();

      if (entitiesTab === "Classes") {
        const searchTreeRecursive = (nodes: TreeNode[]): TreeNode[] => {
          const results: TreeNode[] = [];

          for (const node of nodes) {
            const nodeMatches = node.label
              ?.toLowerCase()
              .includes(lowercasedQuery);

            const matchingChildren = node.children
              ? searchTreeRecursive(node.children)
              : [];

            if (nodeMatches || matchingChildren.length > 0) {
              results.push({
                ...node,
                children:
                  matchingChildren.length > 0
                    ? matchingChildren
                    : node.children,
              });
            }
          }

          return results;
        };

        const searchResults = searchTreeRecursive(classHierarchy);
        setFilteredData(searchResults);

        const expandedIds: string[] = [];
        const collectExpandedIds = (nodes: TreeNode[]) => {
          nodes.forEach((node) => {
            if (node.children && node.children.length > 0) {
              expandedIds.push(node.id);
              collectExpandedIds(node.children);
            }
          });
        };
        collectExpandedIds(searchResults);
        setExpandedNodes(expandedIds);

        return;
      }

      if (
        (entitiesTab === "ObjectProperties" ||
          entitiesTab === "DataProperties") &&
        showHierarchicalView
      ) {
        const searchPropertyTree = (properties: Property[]): Property[] => {
          const results: Property[] = [];

          for (const prop of properties) {
            const propMatches = prop.label
              ?.toLowerCase()
              .includes(lowercasedQuery);
            const matchingChildren = prop.children
              ? searchPropertyTree(prop.children)
              : [];

            if (propMatches || matchingChildren.length > 0) {
              results.push({
                ...prop,
                children:
                  matchingChildren.length > 0
                    ? matchingChildren
                    : prop.children,
              });
            }
          }

          return results;
        };

        const sourceHierarchy =
          entitiesTab === "ObjectProperties"
            ? objectPropertyHierarchy
            : dataPropertyHierarchy;

        const searchResults = searchPropertyTree(sourceHierarchy);
        setFilteredData(searchResults);

        const expandedIds: string[] = [];
        const collectIds = (props: Property[]) => {
          props.forEach((prop) => {
            if (prop.children && prop.children.length > 0) {
              expandedIds.push(prop.id);
              collectIds(prop.children);
            }
          });
        };
        collectIds(searchResults);
        setExpandedNodes(expandedIds);

        return;
      }

      let sourceData: SelectableItem[] = [];

      switch (entitiesTab) {
        case "ObjectProperties":
          sourceData = objectProperties;
          break;
        case "DataProperties":
          sourceData = dataProperties;
          break;
        case "AnnotationProperties":
          sourceData = annotationProperties;
          break;
        case "Datatypes":
          sourceData = datatypes;
          break;
        case "Individuals":
          sourceData = individuals;
          break;
      }

      const results = sourceData.filter(
        (item) =>
          item.label?.toLowerCase().includes(lowercasedQuery) ||
          item.id?.toLowerCase().includes(lowercasedQuery)
      );

      setFilteredData(results);
    }, 300);

    return () => clearTimeout(debounce);
  }, [
    searchQuery,
    entitiesTab,
    classHierarchy,
    objectProperties,
    dataProperties,
    annotationProperties,
    individuals,
    datatypes,
    objectPropertyHierarchy,
    dataPropertyHierarchy,
    showHierarchicalView,
  ]);

  const handleScroll = useCallback(() => {
    const container = classListRef.current;
    if (
      !container ||
      !topLevelHasMore ||
      isLoadingMore ||
      entitiesTab !== "Classes"
    )
      return;

    const { scrollTop, scrollHeight, clientHeight } = container;
    const scrollPercentage = (scrollTop + clientHeight) / scrollHeight;

    if (scrollPercentage > 0.8) {
      loadTopLevelClasses(topLevelPage + 1, true);
    }
  }, [
    topLevelHasMore,
    isLoadingMore,
    topLevelPage,
    loadTopLevelClasses,
    entitiesTab,
  ]);

  useEffect(() => {
    const container = classListRef.current;
    if (!container) return;

    container.addEventListener("scroll", handleScroll, { passive: true });
    return () => container.removeEventListener("scroll", handleScroll);
  }, [handleScroll]);

  const toggleNode = async (nodeId: string) => {
    if (expandedNodes.includes(nodeId)) {
      setExpandedNodes((prev) => prev.filter((id) => id !== nodeId));
    } else {
      const findNode = (nodes: TreeNode[], id: string): TreeNode | null => {
        for (const node of nodes) {
          if (node.id === id) return node;
          if (node.children) {
            const found = findNode(node.children, id);
            if (found) return found;
          }
        }
        return null;
      };

      const node = findNode(classHierarchy, nodeId);

      if (node && node.children && node.children.length === 0) {
        const children = await loadChildren(nodeId);

        if (children.length > 0) {
          const updateTree = (nodes: TreeNode[]): TreeNode[] => {
            return nodes.map((n) => {
              if (n.id === nodeId) {
                return { ...n, children };
              }
              if (n.children) {
                return { ...n, children: updateTree(n.children) };
              }
              return n;
            });
          };

          setClassHierarchy(updateTree(classHierarchy));
          setFilteredData(updateTree(filteredData as TreeNode[]));
        }
      }

      setExpandedNodes((prev) => [...prev, nodeId]);
    }
  };

  const mainTabs = [
    { id: "ActiveOntology", label: "Active ontology", icon: FileText },
    { id: "Entities", label: "Entities", icon: List },
    { id: "IndividualsByClass", label: "Individuals by class", icon: Eye },
    { id: "DLQuery", label: "DL Query", icon: Code },
  ];

  const entitiesTabs = [
    {
      id: "Classes",
      label: "Classes",
      icon: FileText,
      count: metadata?.classCount,
    },
    {
      id: "ObjectProperties",
      label: "Object properties",
      icon: Share2,
      count: metadata?.objectPropertyCount,
    },
    {
      id: "DataProperties",
      label: "Data properties",
      icon: Database,
      count: metadata?.dataPropertyCount,
    },
    {
      id: "AnnotationProperties",
      label: "Annotation properties",
      icon: Tag,
      count: annotationProperties.length || null,
    },
    {
      id: "Datatypes",
      label: "Datatypes",
      icon: Settings,
      count: datatypes.length || null,
    },
    {
      id: "Individuals",
      label: "Individuals",
      icon: Eye,
      count: metadata?.individualCount,
    },
  ];

  const renderTreeNode = (node: TreeNode, level = 0): JSX.Element => {
    const hasChildren = node.children !== null;
    const isExpanded = expandedNodes.includes(node.id);
    const isSelected = selectedItem?.id === node.id;
    const isLoading =
      hasChildren &&
      Array.isArray(node.children) &&
      node.children.length === 0 &&
      isExpanded;

    return (
      <div key={node.id}>
        <div
          data-class-id={node.id} // ✅ Add data attribute for scrolling
          className={`flex items-center px-2 py-1.5 rounded cursor-pointer transition-colors ${
            isSelected
              ? "bg-purple-100 border-l-2 border-purple-600"
              : "hover:bg-gray-50"
          }`}
          style={{ paddingLeft: `${level * 20 + 8}px` }}
          onClick={() => setSelectedItem(node)}
        >
          {hasChildren ? (
            <button
              className="p-0.5 rounded hover:bg-gray-200 mr-1"
              onClick={(e) => {
                e.stopPropagation();
                toggleNode(node.id);
              }}
            >
              {isLoading ? (
                <Loader2 size={14} className="animate-spin text-purple-600" />
              ) : isExpanded ? (
                <ChevronDown size={14} />
              ) : (
                <ChevronRight size={14} />
              )}
            </button>
          ) : (
            <span className="w-[22px]" />
          )}
          <span
            className={`text-sm ${
              isSelected ? "text-purple-900 font-medium" : "text-gray-700"
            }`}
          >
            {node.label}
          </span>
        </div>
        {hasChildren &&
          isExpanded &&
          node.children &&
          node.children.length > 0 &&
          node.children.map((child) => renderTreeNode(child, level + 1))}
      </div>
    );
  };

  const renderPropertyTreeNode = (
    property: Property,
    level = 0
  ): JSX.Element => {
    const hasChildren = property.children && property.children.length > 0;
    const isExpanded = expandedNodes.includes(property.id);
    const isSelected = selectedItem?.id === property.id;

    return (
      <div key={property.id}>
        <div
          className={`flex items-center px-2 py-1.5 rounded cursor-pointer transition-colors ${
            isSelected
              ? "bg-purple-100 border-l-2 border-purple-600"
              : "hover:bg-gray-50"
          }`}
          style={{ paddingLeft: `${level * 20 + 8}px` }}
          onClick={() => setSelectedItem(property)}
        >
          {hasChildren ? (
            <button
              className="p-0.5 rounded hover:bg-gray-200 mr-1"
              onClick={(e) => {
                e.stopPropagation();
                toggleNode(property.id);
              }}
            >
              {isExpanded ? (
                <ChevronDown size={14} />
              ) : (
                <ChevronRight size={14} />
              )}
            </button>
          ) : (
            <span className="w-[22px]" />
          )}

          <span
            className={`text-xs px-1.5 py-0.5 rounded mr-2 ${
              property.type === "ObjectProperty"
                ? "bg-blue-100 text-blue-700"
                : "bg-green-100 text-green-700"
            }`}
          >
            {property.type === "ObjectProperty" ? "OP" : "DP"}
          </span>

          <span
            className={`text-sm ${
              isSelected ? "text-purple-900 font-medium" : "text-gray-700"
            }`}
          >
            {property.label}
          </span>

          {property.characteristics && property.characteristics.length > 0 && (
            <span className="ml-2 text-xs text-gray-500">
              {property.characteristics.slice(0, 2).join(", ")}
              {property.characteristics.length > 2 && "..."}
            </span>
          )}
        </div>

        {hasChildren &&
          isExpanded &&
          property.children?.map((child) =>
            renderPropertyTreeNode(child, level + 1)
          )}
      </div>
    );
  };

  const renderEntitiesContent = () => {
    if (filteredData.length === 0) {
      return (
        <div className="p-4 text-center text-gray-400">
          No {entitiesTab.toLowerCase()} found
        </div>
      );
    }

    if (entitiesTab === "Classes") {
      return filteredData.map((node) => renderTreeNode(node as TreeNode));
    }

    if (
      (entitiesTab === "ObjectProperties" ||
        entitiesTab === "DataProperties") &&
      showHierarchicalView &&
      !searchQuery
    ) {
      return filteredData.map((property) =>
        renderPropertyTreeNode(property as Property)
      );
    }

    return filteredData.map((item) => (
      <div
        key={item.id}
        className={`flex items-center px-2 py-1.5 rounded cursor-pointer hover:bg-gray-50 ${
          selectedItem?.id === item.id
            ? "bg-purple-100 border-l-2 border-purple-600"
            : ""
        }`}
        onClick={() => setSelectedItem(item)}
      >
        {"type" in item && item.type && (
          <span
            className={`text-xs px-1.5 py-0.5 rounded mr-2 ${
              item.type === "ObjectProperty"
                ? "bg-blue-100 text-blue-700"
                : item.type === "DataProperty"
                ? "bg-green-100 text-green-700"
                : "bg-gray-100 text-gray-700"
            }`}
          >
            {item.type === "ObjectProperty"
              ? "OP"
              : item.type === "DataProperty"
              ? "DP"
              : item.type}
          </span>
        )}

        <span
          className={`text-sm ${
            selectedItem?.id === item.id
              ? "text-purple-900 font-medium"
              : "text-gray-700"
          }`}
        >
          {item.label}
        </span>
      </div>
    ));
  };

  const renderMainContent = () => {
    if (mainTab === "ActiveOntology") {
      return (
        <div className="flex h-full">
          <div className="flex-1 flex flex-col bg-white border-r border-gray-200">
            <div className="border-b border-gray-200 bg-purple-50">
              <div className="px-4 py-2 bg-purple-600 text-white text-xs font-semibold">
                Ontology header:
              </div>
              <div className="p-4 space-y-3">
                <div>
                  <div className="text-sm font-semibold mb-1">Ontology IRI</div>
                  <a
                    href={metadata?.ontologyIRI || "#"}
                    target="_blank"
                    rel="noopener noreferrer"
                    className="text-blue-600 hover:underline text-sm break-all"
                  >
                    {metadata?.ontologyIRI || "Not specified"}
                  </a>
                </div>
                <div>
                  <div className="text-sm font-semibold mb-1">
                    Ontology Version IRI
                  </div>
                  <div className="text-sm text-gray-700 break-all">
                    {metadata?.versionIRI || "Not specified"}
                  </div>
                </div>
              </div>
            </div>
            <div className="flex-1 overflow-y-auto p-4">
              <div className="flex items-center justify-between mb-3">
                <h3 className="text-sm font-semibold text-gray-700">
                  Annotations
                </h3>
                <button className="w-6 h-6 rounded-full bg-gray-200 hover:bg-gray-300 flex items-center justify-center">
                  <Plus size={14} className="text-gray-600" />
                </button>
              </div>
              {metadata?.annotations &&
              Object.keys(metadata.annotations).length > 0 ? (
                <div className="space-y-4">
                  {Object.entries(metadata.annotations).map(([key, value]) => (
                    <div key={key} className="border-b border-gray-200 pb-3">
                      <div className="font-semibold text-sm mb-1">{key}</div>
                      <div className="text-sm text-gray-700">{value}</div>
                    </div>
                  ))}
                </div>
              ) : (
                <p className="text-sm text-gray-400 italic">No annotations</p>
              )}
            </div>
            <div className="border-t border-gray-200 bg-gray-50">
              <div className="flex text-xs">
                <button className="px-4 py-2 font-medium border-r border-gray-300 bg-white text-gray-900">
                  Ontology imports
                </button>
                <button className="px-4 py-2 font-medium border-r border-gray-300 bg-gray-100 text-gray-600 hover:bg-gray-200">
                  Ontology Prefixes
                </button>
                <button className="px-4 py-2 font-medium bg-gray-100 text-gray-600 hover:bg-gray-200">
                  General class axioms
                </button>
              </div>
              <div className="bg-white p-4 min-h-24 border-t border-gray-200">
                <p className="text-sm text-gray-400 italic">
                  No direct imports
                </p>
              </div>
            </div>
          </div>
          <div className="w-96 bg-white">
            <div className="px-4 py-2 bg-purple-600 text-white text-xs font-semibold border-b border-purple-700">
              Ontology metrics:
            </div>
            <div className="p-4">
              <h3 className="font-semibold mb-4 text-sm">Metrics</h3>
              <div className="space-y-2 text-xs">
                <div className="flex justify-between">
                  <span className="text-gray-600">Axiom</span>
                  <span className="font-semibold">
                    {metadata?.axiomCount.toLocaleString()}
                  </span>
                </div>
                <div className="flex justify-between">
                  <span className="text-gray-600">Class count</span>
                  <span className="font-semibold">
                    {metadata?.classCount.toLocaleString()}
                  </span>
                </div>
                <div className="flex justify-between">
                  <span className="text-gray-600">Object property count</span>
                  <span className="font-semibold">
                    {metadata?.objectPropertyCount.toLocaleString()}
                  </span>
                </div>
                <div className="flex justify-between">
                  <span className="text-gray-600">Data property count</span>
                  <span className="font-semibold">
                    {metadata?.dataPropertyCount}
                  </span>
                </div>
                <div className="flex justify-between">
                  <span className="text-gray-600">Individual count</span>
                  <span className="font-semibold">
                    {metadata?.individualCount}
                  </span>
                </div>
                <div className="flex justify-between">
                  <span className="text-gray-600">
                    Annotation Property count
                  </span>
                  <span className="font-semibold">
                    {annotationProperties.length}
                  </span>
                </div>
              </div>
            </div>
          </div>
        </div>
      );
    }

    if (mainTab === "IndividualsByClass") {
      return (
        <div className="h-full flex items-center justify-center">
          <div className="text-center text-gray-400">
            <Eye size={48} className="mx-auto mb-3 opacity-50" />
            <p className="text-lg">Individuals by Class</p>
            <p className="text-sm mt-2">
              Browse individuals organized by their types
            </p>
          </div>
        </div>
      );
    }

    if (mainTab === "DLQuery") {
      return (
        <div className="h-full p-6">
          <div className="bg-white rounded-lg border border-gray-200 p-6 shadow-sm">
            <h3 className="text-lg font-bold mb-4">DL Query</h3>
            <textarea
              className="w-full h-32 border border-gray-300 rounded-lg p-3 font-mono text-sm"
              placeholder="Enter DL query..."
            />
            <div className="flex gap-2 mt-4">
              <button className="px-4 py-2 bg-purple-600 text-white rounded-lg hover:bg-purple-700 text-sm">
                Execute
              </button>
              <button className="px-4 py-2 bg-gray-200 text-gray-700 rounded-lg hover:bg-gray-300 text-sm">
                Add to ontology
              </button>
            </div>
            <div className="mt-6">
              <h4 className="font-semibold mb-2 text-sm">Query results</h4>
              <div className="border border-gray-300 rounded-lg p-4 bg-gray-50 min-h-32 text-sm text-gray-400">
                No results
              </div>
            </div>
          </div>
        </div>
      );
    }

    return null;
  };

  const renderSelectedItemDetails = () => {
    if (!selectedItem) {
      return (
        <div className="h-full flex items-center justify-center">
          <div className="text-center text-gray-400">
            <FileText size={48} className="mx-auto mb-3 opacity-50" />
            <p className="text-lg">Select an item to view details</p>
          </div>
        </div>
      );
    }

    const isProperty = (item: SelectableItem): item is Property => {
      return "type" in item && ("domains" in item || "ranges" in item);
    };

    const isIndividual = (item: SelectableItem): item is Individual => {
      return "types" in item && !("domains" in item);
    };

    const isClass = (item: SelectableItem): item is TreeNode => {
      return "children" in item || (!isProperty(item) && !isIndividual(item));
    };

    return (
      <div className="space-y-6">
        {/* Header Card */}
        <div className="bg-white rounded-lg border border-gray-200 p-6 shadow-sm">
          <h2 className="text-2xl font-bold text-gray-900 mb-3">
            {selectedItem.label}
          </h2>
          <div className="flex items-start gap-2 text-xs">
            <Link2 size={14} className="text-gray-400 mt-0.5" />
            <code className="bg-gray-100 px-3 py-1.5 rounded text-purple-700 break-all flex-1">
              {selectedItem.id}
            </code>
          </div>

          {isProperty(selectedItem) && (
            <div className="mt-3">
              <span
                className={`inline-block text-xs px-3 py-1 rounded-full font-medium ${
                  selectedItem.type === "ObjectProperty"
                    ? "bg-blue-100 text-blue-800"
                    : "bg-green-100 text-green-800"
                }`}
              >
                {selectedItem.type}
              </span>
            </div>
          )}
        </div>

        {/* Tabs for Classes */}
        {isClass(selectedItem) && (
          <div className="bg-white rounded-lg border border-gray-200 shadow-sm">
            <div className="flex border-b border-gray-200">
              <button
                onClick={() => setDetailTab("annotations")}
                className={`flex items-center gap-2 px-4 py-3 text-sm font-medium transition-colors ${
                  detailTab === "annotations"
                    ? "text-purple-600 border-b-2 border-purple-600 -mb-px"
                    : "text-gray-600 hover:text-gray-800"
                }`}
              >
                <Tag size={16} />
                Annotations
              </button>
              <button
                onClick={() => setDetailTab("usage")}
                className={`flex items-center gap-2 px-4 py-3 text-sm font-medium transition-colors ${
                  detailTab === "usage"
                    ? "text-purple-600 border-b-2 border-purple-600 -mb-px"
                    : "text-gray-600 hover:text-gray-800"
                }`}
              >
                <Eye size={16} />
                Usage
                {classUsage && classUsage.totalUsages > 0 && (
                  <span className="bg-purple-100 text-purple-700 px-2 py-0.5 rounded-full text-xs font-bold">
                    {classUsage.totalUsages}
                  </span>
                )}
              </button>
            </div>

            <div className="p-6">
              {detailTab === "annotations" && (
                <AnnotationsDisplay annotations={selectedItem.annotations} />
              )}

              {detailTab === "usage" && (
                <div className="space-y-4">
                  {loadingUsage ? (
                    <div className="flex items-center justify-center py-8">
                      <Loader2
                        size={24}
                        className="animate-spin text-purple-600 mr-2"
                      />
                      <span className="text-sm text-gray-500">
                        Loading usage information...
                      </span>
                    </div>
                  ) : classUsage && classUsage.totalUsages > 0 ? (
                    <>
                      <div className="mb-4 p-3 bg-purple-50 rounded-lg border border-purple-200">
                        <p className="text-sm font-semibold text-purple-900">
                          Found {classUsage.totalUsages} use
                          {classUsage.totalUsages !== 1 ? "s" : ""} of{" "}
                          {selectedItem.label}
                        </p>
                      </div>

                      {/* Group usages by category */}
                      {Object.entries(
                        classUsage.usages.reduce((acc, usage) => {
                          if (!acc[usage.category]) acc[usage.category] = [];
                          acc[usage.category].push(usage);
                          return acc;
                        }, {} as Record<string, AxiomUsage[]>)
                      ).map(([category, usages]) => (
                        <div
                          key={category}
                          className="border-l-4 border-indigo-500 pl-4 py-2"
                        >
                          <h4 className="text-sm font-bold text-gray-700 mb-3 uppercase tracking-wide">
                            {category}
                          </h4>
                          <div className="space-y-2">
                            {usages.map((usage, idx) => (
                              <div
                                key={idx}
                                className="bg-gray-50 p-3 rounded-lg border border-gray-200 hover:bg-gray-100 transition-colors"
                              >
                                {/* ✅ FIXED: Pass projectId prop */}
                                <div className="text-sm font-mono leading-relaxed">
                                  <AxiomDisplay
                                    description={usage.description}
                                    projectId={projectId!}
                                    onClassClick={handleNavigateToClass}
                                  />
                                </div>
                                <div className="mt-2 text-xs text-gray-500 flex items-center gap-2">
                                  <span className="px-2 py-0.5 bg-gray-200 rounded">
                                    {usage.axiomType}
                                  </span>
                                </div>
                              </div>
                            ))}
                          </div>
                        </div>
                      ))}
                    </>
                  ) : (
                    <div className="text-center py-8 text-gray-400">
                      <Eye size={48} className="mx-auto mb-3 opacity-30" />
                      <p className="text-sm">No usages found for this class</p>
                    </div>
                  )}
                </div>
              )}
            </div>
          </div>
        )}

        {/* Annotations for non-class items */}
        {!isClass(selectedItem) && (
          <div className="bg-white rounded-lg border border-gray-200 p-6 shadow-sm">
            <h3 className="text-sm font-bold text-gray-700 uppercase tracking-wide mb-4 flex items-center gap-2">
              <Tag size={16} className="text-purple-600" />
              Annotations
            </h3>
            <AnnotationsDisplay annotations={selectedItem.annotations} />
          </div>
        )}

        {isProperty(selectedItem) && (
          <>
            {selectedItem.superProperties &&
              selectedItem.superProperties.length > 0 && (
                <div className="bg-white rounded-lg border border-gray-200 p-6 shadow-sm">
                  <h3 className="text-sm font-bold text-gray-700 uppercase tracking-wide mb-3">
                    Super Properties
                  </h3>
                  <div className="flex flex-wrap gap-2">
                    {selectedItem.superProperties.map((superProp, i) => (
                      <span
                        key={i}
                        className="text-xs bg-indigo-100 text-indigo-800 px-3 py-1.5 rounded-full font-medium"
                      >
                        {superProp.split("#").pop() || superProp}
                      </span>
                    ))}
                  </div>
                </div>
              )}

            {selectedItem.subProperties &&
              selectedItem.subProperties.length > 0 && (
                <div className="bg-white rounded-lg border border-gray-200 p-6 shadow-sm">
                  <h3 className="text-sm font-bold text-gray-700 uppercase tracking-wide mb-3">
                    Sub Properties
                  </h3>
                  <div className="flex flex-wrap gap-2">
                    {selectedItem.subProperties.map((subProp, i) => (
                      <span
                        key={i}
                        className="text-xs bg-pink-100 text-pink-800 px-3 py-1.5 rounded-full font-medium"
                      >
                        {subProp.split("#").pop() || subProp}
                      </span>
                    ))}
                  </div>
                </div>
              )}

            {selectedItem.domains && selectedItem.domains.length > 0 && (
              <div className="bg-white rounded-lg border border-gray-200 p-6 shadow-sm">
                <h3 className="text-sm font-bold text-gray-700 uppercase tracking-wide mb-3">
                  Domains
                </h3>
                <div className="flex flex-wrap gap-2">
                  {selectedItem.domains.map((domain, i) => (
                    <span
                      key={i}
                      className="text-xs bg-blue-100 text-blue-800 px-3 py-1.5 rounded-full font-medium"
                    >
                      {domain.split("#").pop() || domain}
                    </span>
                  ))}
                </div>
              </div>
            )}

            {selectedItem.ranges && selectedItem.ranges.length > 0 && (
              <div className="bg-white rounded-lg border border-gray-200 p-6 shadow-sm">
                <h3 className="text-sm font-bold text-gray-700 uppercase tracking-wide mb-3">
                  Ranges
                </h3>
                <div className="flex flex-wrap gap-2">
                  {selectedItem.ranges.map((range, i) => (
                    <span
                      key={i}
                      className="text-xs bg-green-100 text-green-800 px-3 py-1.5 rounded-full font-medium"
                    >
                      {range.split("#").pop() || range}
                    </span>
                  ))}
                </div>
              </div>
            )}

            {selectedItem.characteristics &&
              selectedItem.characteristics.length > 0 && (
                <div className="bg-white rounded-lg border border-gray-200 p-6 shadow-sm">
                  <h3 className="text-sm font-bold text-gray-700 uppercase tracking-wide mb-3">
                    Characteristics
                  </h3>
                  <div className="flex flex-wrap gap-2">
                    {selectedItem.characteristics.map((char, i) => (
                      <span
                        key={i}
                        className="text-xs bg-purple-100 text-purple-800 px-3 py-1.5 rounded-full font-medium"
                      >
                        {char}
                      </span>
                    ))}
                  </div>
                </div>
              )}
          </>
        )}

        {isIndividual(selectedItem) &&
          selectedItem.types &&
          selectedItem.types.length > 0 && (
            <div className="bg-white rounded-lg border border-gray-200 p-6 shadow-sm">
              <h3 className="text-sm font-bold text-gray-700 uppercase tracking-wide mb-3">
                Types
              </h3>
              <div className="flex flex-wrap gap-2">
                {selectedItem.types.map((type, i) => (
                  <span
                    key={i}
                    className="text-xs bg-indigo-100 text-indigo-800 px-3 py-1.5 rounded-full font-medium"
                  >
                    {type.split("#").pop() || type}
                  </span>
                ))}
              </div>
            </div>
          )}
      </div>
    );
  };

  if (!projectId) {
    return (
      <div className="min-h-screen flex items-center justify-center bg-gray-100">
        <div className="text-center text-gray-500 p-8">
          <div className="w-20 h-20 bg-gradient-to-br from-purple-500 to-indigo-600 rounded-2xl flex items-center justify-center shadow-lg mx-auto mb-6">
            <FileText size={40} className="text-white" />
          </div>
          <h2 className="text-2xl font-bold text-gray-800 mb-3">
            Welcome to OntoCode
          </h2>
          <div className="flex items-center justify-center gap-2 text-purple-600">
            <Loader2 size={20} className="animate-spin" />
            <p className="text-sm">Initializing...</p>
          </div>
        </div>
      </div>
    );
  }

  return (
    <>
      <LoadingDialog
        isOpen={isInitialLoading}
        message="Loading Ontology Data"
      />

      <div className="min-h-screen bg-gray-50 flex flex-col text-sm">
        <header className="bg-white border-b border-gray-200 px-6 py-4 shadow-sm">
          <div className="flex items-center justify-between">
            <div className="flex items-center gap-4">
              <div className="w-10 h-10 bg-gradient-to-br from-purple-500 to-indigo-600 rounded-lg flex items-center justify-center shadow-md">
                <FileText size={20} className="text-white" />
              </div>
              <div>
                <h1 className="text-lg font-bold text-gray-800">
                  {metadata?.filename || "Ontology Editor"}
                </h1>
                <p className="text-xs text-gray-500">{projectId}</p>
              </div>
            </div>
            <div className="flex items-center gap-3">
              <button className="flex items-center gap-2 text-xs text-white bg-purple-600 hover:bg-purple-700 px-4 py-2 rounded-lg transition-colors shadow-sm">
                <Upload size={14} />
                Upload
              </button>
              <button
                onClick={fetchData}
                className="flex items-center gap-2 text-xs text-gray-600 hover:bg-gray-100 px-4 py-2 rounded-lg transition-colors"
              >
                <RefreshCw size={14} />
                Refresh
              </button>
              <button className="flex items-center gap-2 text-xs text-gray-600 hover:bg-gray-100 px-4 py-2 rounded-lg transition-colors">
                <BarChart3 size={14} />
                Metrics
              </button>
            </div>
          </div>
        </header>

        <nav className="bg-white border-b border-gray-200 shadow-sm">
          <div className="flex items-center px-6">
            {mainTabs.map((tab) => {
              const Icon = tab.icon;
              return (
                <button
                  key={tab.id}
                  className={`flex items-center gap-2 px-4 py-3 text-xs font-medium border-b-2 transition-all whitespace-nowrap ${
                    mainTab === tab.id
                      ? "text-purple-600 border-purple-600"
                      : "text-gray-600 hover:text-gray-800 border-transparent"
                  }`}
                  onClick={() => setMainTab(tab.id)}
                >
                  <Icon size={14} />
                  {tab.label}
                </button>
              );
            })}
          </div>
        </nav>

        {mainTab === "Entities" && (
          <nav className="bg-gray-50 border-b border-gray-200 px-6">
            <div className="flex items-center gap-1">
              {entitiesTabs.map((tab) => {
                const Icon = tab.icon;
                return (
                  <button
                    key={tab.id}
                    className={`flex items-center gap-2 px-3 py-2 text-xs font-medium rounded-t transition-all whitespace-nowrap ${
                      entitiesTab === tab.id
                        ? "bg-white text-purple-600 border-t-2 border-purple-600"
                        : "text-gray-600 hover:text-gray-800 hover:bg-gray-100"
                    }`}
                    onClick={() => {
                      setEntitiesTab(tab.id);
                      setSelectedItem(null);
                    }}
                  >
                    <Icon size={12} />
                    {tab.label}
                    {tab.count != null && (
                      <span className="bg-gray-200 text-gray-700 px-1.5 py-0.5 rounded text-xs font-bold">
                        {tab.count.toLocaleString()}
                      </span>
                    )}
                  </button>
                );
              })}
            </div>
          </nav>
        )}

        <main className="flex flex-1 overflow-hidden">
          {mainTab === "Entities" ? (
            <>
              <aside className="w-80 bg-white border-r border-gray-200 flex flex-col shadow-sm">
                <div className="p-4 border-b border-gray-200 bg-gray-50 space-y-3">
                  <div className="flex items-center gap-2">
                    <button className="flex items-center gap-1.5 px-3 py-2 bg-purple-600 text-white rounded-lg hover:bg-purple-700 text-xs font-medium transition-colors shadow-sm">
                      <Plus size={14} />
                      New
                    </button>
                    <button className="p-2 hover:bg-gray-200 rounded-lg transition-colors">
                      <Filter size={14} className="text-gray-600" />
                    </button>

                    {(entitiesTab === "ObjectProperties" ||
                      entitiesTab === "DataProperties") &&
                      !searchQuery && (
                        <button
                          onClick={() =>
                            setShowHierarchicalView(!showHierarchicalView)
                          }
                          className={`p-2 rounded-lg transition-colors ${
                            showHierarchicalView
                              ? "bg-purple-100 text-purple-600"
                              : "hover:bg-gray-200 text-gray-600"
                          }`}
                          title={
                            showHierarchicalView
                              ? "Show flat list"
                              : "Show hierarchy"
                          }
                        >
                          {showHierarchicalView ? (
                            <List size={14} />
                          ) : (
                            <svg
                              width="14"
                              height="14"
                              viewBox="0 0 24 24"
                              fill="none"
                              stroke="currentColor"
                              strokeWidth="2"
                            >
                              <path d="M8 6h13M8 12h13M8 18h13M3 6h.01M3 12h.01M3 18h.01" />
                            </svg>
                          )}
                        </button>
                      )}
                  </div>

                  <div className="relative">
                    <Search
                      size={14}
                      className="absolute left-3 top-1/2 transform -translate-y-1/2 text-gray-400"
                    />
                    <input
                      type="text"
                      placeholder={`Search ${entitiesTab.toLowerCase()}...`}
                      value={searchQuery}
                      onChange={(e) => setSearchQuery(e.target.value)}
                      className="w-full pl-9 pr-3 py-2 border border-gray-300 rounded-lg focus:ring-2 focus:ring-purple-500 focus:border-transparent text-sm"
                    />
                  </div>
                  {searchQuery && filteredData.length > 0 && (
                    <div className="text-xs text-gray-500 px-2">
                      Found {filteredData.length} result
                      {filteredData.length !== 1 ? "s" : ""}
                    </div>
                  )}

                  {searchQuery && filteredData.length === 0 && (
                    <div className="text-xs text-gray-400 px-2 italic">
                      No results found for "{searchQuery}"
                    </div>
                  )}
                </div>

                <div ref={classListRef} className="flex-1 overflow-y-auto p-2">
                  {renderEntitiesContent()}

                  {isLoadingMore && entitiesTab === "Classes" && (
                    <div className="flex items-center justify-center py-4">
                      <Loader2
                        size={20}
                        className="animate-spin text-purple-600 mr-2"
                      />
                      <span className="text-sm text-gray-500">
                        Loading more classes...
                      </span>
                    </div>
                  )}

                  {!topLevelHasMore &&
                    allTopLevelClasses.length > 0 &&
                    entitiesTab === "Classes" && (
                      <div className="text-center py-4 text-sm text-gray-400">
                        All {allTopLevelClasses.length} top-level classes loaded
                      </div>
                    )}
                </div>
              </aside>
              <section className="flex-1 overflow-y-auto p-6 bg-gray-50">
                {renderSelectedItemDetails()}
              </section>
            </>
          ) : (
            <section className="flex-1 overflow-y-auto bg-gray-50">
              {renderMainContent()}
            </section>
          )}
        </main>
      </div>
    </>
  );
};

export default EnhancedDashboard;

/* eslint-disable @typescript-eslint/no-explicit-any */
import React from "react";
import { Package } from "lucide-react";
import apiClient from "../../services/apiClient";
import ontologyMutationService from "../../services/ontologyMutationService";
import type { TreeNode, Property, Individual, SelectableItem, AnnotationProperty, Datatype } from "../../types";
import ClassEditor from "../details/ClassEditor";
import PropertyEditor from "../details/PropertyEditor";
import IndividualEditor from "../details/IndividualEditor";
import DatatypeEditor from "../details/DatatypeEditor";
import AnnotationPropertyEditor from "../details/AnnotationPropertyEditor";
import { AnnotationsDisplay } from "../details/common";

export const DetailsPanel = ({
  selectedItem,
  entitiesTab,
  activeTheme,
  projectId,
  onUpdate,
  onAddAnnotation,
  onEditAnnotation,
  onDeleteAnnotation,
  onAddDomainClick,
  onAddRangeClick,
  onAddSubPropertyClick,
  onAddInverseClick,
  onAddDisjointClick,
  onAddEquivalentClick,
  onAddAnnotationDomainClick,
  onAddAnnotationRangeClick,
  onAddAnnotationSuperpropertyClick,
  classHierarchy,
  objectProperties,
  expandedNodes,
  onToggleNode,
  onAddClass,
  onAddClassInline,
  onDeleteClass,
  onRefreshClasses,
  onAddObjectProperty,
  onAddDataProperty,
  dataPropertyHierarchy,
  objectPropertyHierarchy,
  dataProperties,
  metadata,
  individuals,
  setIndividuals,
  markAsUnsaved,
  viewMode = "asserted",
  isViewOnly = false,
  onViewOnlyAction,
}: {
  selectedItem: SelectableItem | null;
  entitiesTab: string;
  activeTheme?: string;
  projectId: string | null;
  onUpdate: (item: SelectableItem) => void;
  onAddAnnotation: () => void;
  onEditAnnotation: (propertyIri: string, currentValue: string) => void;
  onDeleteAnnotation: (key: string) => void;
  onAddDomainClick?: () => void;
  onAddRangeClick?: () => void;
  onAddSubPropertyClick?: () => void;
  onAddInverseClick?: () => void;
  onAddDisjointClick?: () => void;
  onAddEquivalentClick?: () => void;
  onAddAnnotationDomainClick?: () => void;
  onAddAnnotationRangeClick?: () => void;
  onAddAnnotationSuperpropertyClick?: () => void;
  classHierarchy: TreeNode[];
  objectProperties: Property[];
  expandedNodes?: string[];
  onToggleNode?: (nodeId: string) => Promise<void> | void;
  onAddClass?: (type: "subclass" | "sibling") => void;
  onAddClassInline?: (type: "subclass" | "sibling", parentId?: string, name?: string) => Promise<void>;
  onDeleteClass?: () => void;
  onRefreshClasses?: () => Promise<void>;
  onAddObjectProperty?: (type: "subclass" | "sibling", parentId?: string, name?: string) => Promise<void>;
  onAddDataProperty?: (type: "subclass" | "sibling", parentId?: string, name?: string) => Promise<void>;
  dataPropertyHierarchy: TreeNode[];
  objectPropertyHierarchy: TreeNode[];
  dataProperties: Property[];
  metadata?: { ontologyIRI?: string } | null;
  individuals: Individual[];
  setIndividuals: React.Dispatch<React.SetStateAction<Individual[]>>;
  markAsUnsaved: () => void;
  viewMode?: "asserted" | "inferred";
  isViewOnly?: boolean;
  onViewOnlyAction?: () => void;
}) => {
  if (!selectedItem) {
    return (
      <div className="h-full flex flex-col items-center justify-center text-center text-gray-400 p-4">
        <Package size={48} className="mb-4 text-gray-300" />
        <h3 className="text-lg font-semibold text-gray-600">Ontology Editor</h3>
        <p className="text-sm">
          Select an entity from the hierarchy panel on the left to view its details and make edits.
        </p>
      </div>
    );
  }

  const sharedProps = {
    onAddAnnotation,
    onEditAnnotation,
    onDeleteAnnotation,
    activeTheme,
    projectId: projectId || "",
    isViewOnly,
    onViewOnlyAction,
  };

  switch (entitiesTab) {
    case "Classes":
      return (
        <ClassEditor
          item={selectedItem as TreeNode}
          onUpdate={onUpdate}
          classHierarchy={classHierarchy}
          expandedNodes={expandedNodes}
          onToggleNode={onToggleNode}
          onAddClass={onAddClass}
          onAddClassInline={onAddClassInline}
          onDeleteClass={onDeleteClass}
          onRefreshClasses={onRefreshClasses}
          onAddObjectProperty={onAddObjectProperty}
          onAddDataProperty={onAddDataProperty}
          onDeleteProperty={() => {}}
          metadata={metadata ?? undefined}
          objectPropertyHierarchy={objectPropertyHierarchy}
          dataPropertyHierarchy={dataPropertyHierarchy}
          objectProperties={objectProperties}
          dataProperties={dataProperties}
          viewMode={viewMode}
          individuals={individuals}
          onAddIndividual={async (name: string, classIri: string) => {
            const id = `${metadata?.ontologyIRI || "http://example.org/ontology"}#${name.replace(/\s+/g, "_")}`;
            await ontologyMutationService.createIndividual(projectId || "", id, name, classIri);
            const newIndividual: Individual = {
              id,
              iri: id,
              label: name,
              annotations: { "rdfs:label": name },
              types: [classIri],
            };
            setIndividuals((prev) => [...prev, newIndividual]);
            markAsUnsaved();
          }}
          onDeleteIndividual={async (id: string) => {
            await ontologyMutationService.deleteIndividual(projectId || "", id);
            setIndividuals((prev) => prev.filter((ind) => ind.id !== id));
            markAsUnsaved();
          }}
          onRefreshIndividuals={() => {
            if (projectId) {
              apiClient
                .get<any>(`/api/ontology/individuals/${encodeURIComponent(projectId)}`)
                .then((res) => {
                  setIndividuals(
                    Array.isArray(res?.data) ? res.data : Array.isArray(res?.individuals) ? res.individuals : [],
                  );
                })
                .catch((err) => console.error("Failed to refresh individuals:", err));
            }
          }}
          {...sharedProps}
        />
      );
    case "ObjectProperties":
    case "DataProperties":
      return (
        <PropertyEditor
          item={selectedItem as Property}
          onUpdate={onUpdate}
          {...sharedProps}
          onAddDomainClick={onAddDomainClick}
          onAddRangeClick={onAddRangeClick}
          onAddSubPropertyClick={onAddSubPropertyClick}
          onAddInverseClick={onAddInverseClick}
          onAddDisjointClick={onAddDisjointClick}
          onAddEquivalentClick={onAddEquivalentClick}
          objectProperties={objectProperties}
        />
      );
    case "Individuals":
      return <IndividualEditor item={selectedItem as Individual} onUpdate={onUpdate} {...sharedProps} />;
    case "AnnotationProperties": {
      const apItem = selectedItem as AnnotationProperty;
      return (
        <AnnotationPropertyEditor
          item={apItem}
          onUpdate={onUpdate}
          onAddAnnotation={onAddAnnotation}
          onEditAnnotation={onEditAnnotation}
          onDeleteAnnotation={onDeleteAnnotation}
          activeTheme={activeTheme}
          projectId={projectId || ""}
          onAddSubPropertyClick={onAddAnnotationSuperpropertyClick}
          onAddDomainClick={onAddAnnotationDomainClick}
          onAddRangeClick={onAddAnnotationRangeClick}
          isViewOnly={isViewOnly}
          onViewOnlyAction={onViewOnlyAction}
        />
      );
    }
    case "Datatypes":
      return <DatatypeEditor item={selectedItem as Datatype} onUpdate={onUpdate} {...sharedProps} />;
    default:
      return (
        <div className="bg-white dark:bg-gray-800 rounded-lg border border-gray-200 dark:border-gray-700 p-4">
          <AnnotationsDisplay
            annotations={selectedItem.annotations}
            onDelete={onDeleteAnnotation}
            onEdit={onEditAnnotation}
            isViewOnly={isViewOnly}
            onViewOnlyAction={onViewOnlyAction}
          />
        </div>
      );
  }
};

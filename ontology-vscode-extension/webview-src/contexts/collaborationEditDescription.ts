export const getEditActionDescription = (operationType: string, edit?: any): string => {
  const actionMap: Record<string, string> = {
    CLASS_ADDED: "added a class",
    CLASS_MODIFIED: "modified a class",
    CLASS_DELETED: "deleted a class",
    CLASS_RENAMED: "renamed a class",
    PROPERTY_ADDED: "added a property",
    PROPERTY_MODIFIED: "modified a property",
    PROPERTY_DELETED: "deleted a property",
    ANNOTATION_ADDED: "added an annotation",
    ANNOTATION_MODIFIED: "modified an annotation",
    ANNOTATION_DELETED: "deleted an annotation",
    SUBCLASS_ADDED: "added a subclass relationship",
    SUBCLASS_REMOVED: "removed a subclass relationship",
    INDIVIDUAL_ADDED: "added an individual",
    INDIVIDUAL_MODIFIED: "modified an individual",
    INDIVIDUAL_DELETED: "deleted an individual",

    DISJOINT_ADDED: "made classes disjoint",
    DISJOINT_REMOVED: "removed disjoint axiom",
    EQUIVALENT_ADDED: "added equivalent class",
    EQUIVALENT_REMOVED: "removed equivalent class",

    IMPORT_ADDED: "added an import",
    IMPORT_REMOVED: "removed an import",
    ONTOLOGY_ANNOTATION_ADDED: "added an ontology annotation",
    ONTOLOGY_ANNOTATION_MODIFIED: "modified an ontology annotation",
    ONTOLOGY_ANNOTATION_DELETED: "deleted an ontology annotation",
    GCI_ADDED: "added a general class axiom",
    GCI_REMOVED: "removed a general class axiom",

    SPARQL_UPDATE: "executed a SPARQL update",
    CHANGE_REVERTED: "reverted a change",
    PROJECT_SAVED: "saved the project",

    SWRL_RULE_ADDED: "added a SWRL rule",
    SWRL_RULE_MODIFIED: "modified a SWRL rule",
    SWRL_RULE_DELETED: "deleted a SWRL rule",
  };
  if ((operationType === "ROLLBACK" || operationType === "CHANGE_SET_APPLIED") && typeof edit?.description === "string" && edit.description) {
    return edit.description.charAt(0).toLowerCase() + edit.description.slice(1);
  }
  const base = actionMap[operationType] || "made a change";

  if (operationType === "CLASS_DELETED" || operationType === "CLASS_ADDED" || operationType === "CLASS_RENAMED") {
    const label =
      edit?.metadata?.label ||
      edit?.value ||
      (typeof edit?.nodeId === "string"
        ? edit.nodeId.split(/[#/]/).pop()
        : null);
    if (label && typeof label === "string" && label.length < 80 && !label.includes("://")) {
      return `${base.replace(/ a class$/, "")} "${label}"`;
    }
  }
  return base;
};

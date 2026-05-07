export const SUPPRESS_WORKSPACE_AUTO_OPEN_KEY = "ontocode_suppress_workspace_auto_open";

const LAST_OPENED_KEYS = [
  "ontocode_lastProjectId",
  "ontocode_lastWorkspaceProjectId",
  "ontocode_lastWorkspaceProjectName",
  "ontocode_lastWorkspaceFileId",
  "ontocode_lastWorkspaceFileName",
  "ontocode_lastEditorActiveAt",
];

const SESSION_CACHE_KEYS = [
  "authToken",
  "lastWorkspaceId",
  "skipWorkspaceMode",
  SUPPRESS_WORKSPACE_AUTO_OPEN_KEY,
  ...LAST_OPENED_KEYS,
  "pendingSubscription",
  "pendingPaymentRecovery",
  "pendingUpgradeWorkspaceId",
  "pendingUpgradePlan",
  "pendingUpgradeInterval",
];

export function clearLastOpenedProjectState(): void {
  try {
    LAST_OPENED_KEYS.forEach((key) => localStorage.removeItem(key));
    localStorage.removeItem("ontocode_route_history");
  } catch (error) {
    console.warn("[sessionCleanup] Failed to clear last-opened local state:", error);
  }

  try {
    sessionStorage.removeItem("ontocode_route_history");
    sessionStorage.removeItem("project_name_id_map");
    sessionStorage.removeItem("file_name_id_map");
  } catch (error) {
    console.warn("[sessionCleanup] Failed to clear last-opened session state:", error);
  }
}

export function clearSessionCache(): void {
  try {
    const deploymentType = localStorage.getItem("deploymentType");

    SESSION_CACHE_KEYS.forEach((key) => localStorage.removeItem(key));

    Object.keys(localStorage).forEach((key) => {
      if (
        key.startsWith("ontocode_lastWorkspace") ||
        key.startsWith("pendingUpgrade") ||
        key === "ontocode_route_history"
      ) {
        localStorage.removeItem(key);
      }
    });

    if (deploymentType) {
      localStorage.setItem("deploymentType", deploymentType);
    }
  } catch (error) {
    console.warn("[sessionCleanup] Failed to clear local session cache:", error);
  }

  try {
    sessionStorage.removeItem("ontocode_route_history");
  } catch (error) {
    console.warn("[sessionCleanup] Failed to clear route history cache:", error);
  }
}

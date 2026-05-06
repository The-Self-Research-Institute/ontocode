const SESSION_CACHE_KEYS = [
  "authToken",
  "lastWorkspaceId",
  "skipWorkspaceMode",
  "ontocode_lastProjectId",
  "ontocode_lastWorkspaceProjectId",
  "ontocode_lastWorkspaceProjectName",
  "ontocode_lastWorkspaceFileId",
  "ontocode_lastWorkspaceFileName",
  "ontocode_lastEditorActiveAt",
  "pendingSubscription",
  "pendingPaymentRecovery",
  "pendingUpgradeWorkspaceId",
  "pendingUpgradePlan",
  "pendingUpgradeInterval",
];

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

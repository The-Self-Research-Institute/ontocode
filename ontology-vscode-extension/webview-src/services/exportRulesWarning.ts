import apiClient from "./apiClient";
import { notificationService } from "./notificationService";
import { isDraftScopeActive } from "../utils/draftScope";
import { resolveMutationActor } from "../utils/mutationActor";

const EXPORT_WARNING_MS = 15000;

interface ExportCheckBody {
  warning?: string | null;
}

export async function warnAboutRulesInExport(baseUrl: string, projectId: string, format: string): Promise<void> {
  try {
    const draft = isDraftScopeActive();
    const scope = draft ? `&draft=true&userId=${encodeURIComponent(resolveMutationActor().userId || "")}` : "";
    const body = await apiClient.get<ExportCheckBody>(
      `${baseUrl}/api/ontology/${encodeURIComponent(projectId)}/rules/export-check?format=${encodeURIComponent(format)}${scope}`,
    );
    if (body?.warning) {
      notificationService.notify({ title: "Rules in this export", message: body.warning, type: "warning", duration: EXPORT_WARNING_MS });
    }
  } catch {
    return;
  }
}

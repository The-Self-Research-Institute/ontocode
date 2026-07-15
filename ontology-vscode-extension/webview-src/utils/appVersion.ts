import { isDesktop } from "./desktop";

declare const __APP_VERSION__: string | undefined;

/** Web / VS Code extension version (from ontology-vscode-extension/package.json at build time). */
export function getWebAppVersion(): string {
  return typeof __APP_VERSION__ !== "undefined" ? __APP_VERSION__ : "0.0.0";
}

/** Running app version — desktop uses Electron package version; web uses extension package version. */
export async function getAppVersion(): Promise<string> {
  if (isDesktop()) {
    try {
      const api = (window as any).electronAPI;
      if (api?.getAppVersion) {
        return (await api.getAppVersion()) || getWebAppVersion();
      }
    } catch {
      // fall through
    }
  }
  return getWebAppVersion();
}

export async function fetchLatestDesktopInstallerVersion(): Promise<string | null> {
  try {
    const { default: apiClient } = await import("../services/apiClient");
    const data = await apiClient.get<any>("/api/downloads/info");
    return data?.latest?.["windows-x64"]?.version ?? null;
  } catch {
    return null;
  }
}

export const DESKTOP_LAUNCH_KEY_HEADER = "X-Ontocode-Desktop-Key";

function desktopLaunchKey(): string | null {
  if (typeof window === "undefined") {
    return null;
  }
  const key = (window as { __DESKTOP_LAUNCH_KEY__?: unknown }).__DESKTOP_LAUNCH_KEY__;
  return typeof key === "string" && key ? key : null;
}

export function assistantAuthHeaders(token: string | undefined): Record<string, string> {
  if (token) {
    return { Authorization: `Bearer ${token}` };
  }
  const launchKey = desktopLaunchKey();
  return launchKey ? { [DESKTOP_LAUNCH_KEY_HEADER]: launchKey } : {};
}

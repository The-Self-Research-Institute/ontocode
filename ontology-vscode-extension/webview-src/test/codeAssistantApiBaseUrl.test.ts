import { describe, expect, it, vi } from "vitest";

vi.mock("../utils/desktop", () => ({
  isDesktop: () => true,
  isRealVSCode: () => false,
}));

vi.mock("../config/deploymentConfig", () => ({
  getGatewayUrl: () => "http://127.0.0.1:18085",
  getRemoteApiBaseUrl: () => "https://ontocodedevapi.selfresearch.org",
  getStoredDeploymentType: () => "self-hosted",
}));

import { getApiBaseUrl } from "../components/codeAssistantPanelHelpers";

describe("getApiBaseUrl on desktop", () => {
  it("sends assistant calls to the app's own backend, not the cloud API", () => {
    expect(getApiBaseUrl()).toBe("http://127.0.0.1:18085");
  });
});

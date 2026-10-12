import { describe, expect, it } from "vitest";
import { createHmac } from "node:crypto";
import { createAssistantSession } from "../../services/codeAssistantSession";
import { runAssistantLoop } from "../../services/codeAssistantLoop";
import { getProviderConfig } from "../../services/codeAssistantProviderConfig";

const BASE = process.env.E2E_BASE_URL ?? "";
const SECRET = process.env.JWT_SECRET ?? "ZGV2ZWxvcG1lbnQtb250b2NvZGUtand0LXNlY3JldC1mb3ItbG9jYWwtZGV2ZWxvcG1lbnQ=";
const RUN = `${Date.now()}`;
const EMAIL = `e2e-${RUN}@loadtest.example.test`;
const PROJECT = `e2e-managed-${RUN}`;
const ANSWER = "Mock answer from the load-test provider.";

function b64url(value: string | Buffer): string {
  return Buffer.from(value).toString("base64").replace(/=+$/, "").replace(/\+/g, "-").replace(/\//g, "_");
}

function devToken(): string {
  const head = b64url(JSON.stringify({ alg: "HS256", typ: "JWT" }));
  const body = b64url(JSON.stringify({ email: EMAIL, sub: EMAIL, plan: "PRO" }));
  const signature = b64url(createHmac("sha256", Buffer.from(SECRET, "base64")).update(`${head}.${body}`).digest());
  return `${head}.${body}.${signature}`;
}

function multipart(boundary: string, fileName: string, content: string): string {
  return [
    `--${boundary}`,
    `Content-Disposition: form-data; name="file"; filename="${fileName}"`,
    "Content-Type: text/turtle",
    "",
    content,
    `--${boundary}--`,
    "",
  ].join("\r\n");
}

async function uploadProject(token: string): Promise<void> {
  const turtle = ["@prefix ex: <http://example.org/e2e/> .", "@prefix owl: <http://www.w3.org/2002/07/owl#> .", "ex:Dog a owl:Class ."].join("\n");
  const boundary = `e2e${RUN}`;
  const res = await fetch(`${BASE}/api/ontology/upload/${PROJECT}?ownerEmail=${encodeURIComponent(EMAIL)}`, {
    method: "POST",
    headers: { Authorization: `Bearer ${token}`, "Content-Type": `multipart/form-data; boundary=${boundary}` },
    body: multipart(boundary, `${PROJECT}.ttl`, turtle),
  });
  expect(res.status).toBe(200);
  for (let i = 0; i < 120; i++) {
    const status = await (await fetch(`${BASE}/api/ontology/status/${PROJECT}`)).json();
    if (status?.data?.status === "COMPLETED") return;
    await new Promise((resolve) => setTimeout(resolve, 1000));
  }
  throw new Error("import did not complete");
}

describe.skipIf(!BASE)("managed assistant end to end", () => {
  it("streams a managed answer through the real editor", async () => {
    const token = devToken();
    await uploadProject(token);
    const config = await getProviderConfig(BASE, token);
    if (!config.managed) throw new Error("expected the e2e target to have managed mode on");

    const session = await createAssistantSession(BASE, token, {
      projectId: PROJECT,
      documentPath: `${PROJECT}.ttl`,
      actionType: "ask",
      actionContext: "",
      provider: config.provider,
      model: config.model,
    });
    const drafts: string[] = [];
    const stages: string[] = [];
    const outcome = await runAssistantLoop(
      { apiBaseUrl: BASE, token, session, providerConfig: config },
      "You answer questions about the open ontology.",
      "What does this ontology contain?",
      (event) => stages.push(event.stage),
      undefined,
      [],
      undefined,
      undefined,
      (text) => drafts.push(text),
    );

    expect(outcome).toEqual({ kind: "answer", text: ANSWER });
    expect(drafts.filter((d) => d.length > 0).length).toBeGreaterThan(1);
    expect(drafts[drafts.length - 1]).toBe(ANSWER);
    expect(stages).toContain("answer");
  }, 180_000);
});

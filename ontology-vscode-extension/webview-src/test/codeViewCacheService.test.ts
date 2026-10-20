import { beforeEach, describe, expect, it, vi } from "vitest";

const post = vi.fn();
const del = vi.fn();
let draftMode = false;

vi.mock("../services/apiClient", () => ({
  default: { post: (...args: unknown[]) => post(...args), delete: (...args: unknown[]) => del(...args) },
}));
vi.mock("../utils/draftScope", () => ({
  draftBodyFields: () => (draftMode ? { draft: "true", userId: "me@x.org" } : {}),
  withDraftScope: (url: string) => (draftMode ? `${url}?draft=true` : url),
}));

import codeViewCacheService from "../services/codeViewCacheService";

describe("codeViewCacheService", () => {
  beforeEach(() => {
    post.mockReset();
    del.mockReset();
    draftMode = false;
  });

  it("stores the code view with the citation details and nothing extra in public mode", async () => {
    await codeViewCacheService.store("p1", { content: "<rdf/>", format: "rdfxml", citationUrn: "urn:c", referencedEntity: "http://x/E" });

    expect(post).toHaveBeenCalledWith("/api/ontology/p1/code-view-cache", {
      content: "<rdf/>",
      format: "rdfxml",
      citationUrn: "urn:c",
      referencedEntity: "http://x/E",
    });
  });

  it("tells the server a store comes from a draft so the shared cache is left alone", async () => {
    draftMode = true;

    await codeViewCacheService.store("p1", { content: "<rdf/>", format: "rdfxml" });

    expect(post).toHaveBeenCalledWith("/api/ontology/p1/code-view-cache", {
      content: "<rdf/>",
      format: "rdfxml",
      draft: "true",
      userId: "me@x.org",
    });
  });

  it("clears the shared cache in public mode and marks the request as a draft otherwise", async () => {
    await codeViewCacheService.clear("p1");
    draftMode = true;
    await codeViewCacheService.clear("p1");

    expect(del).toHaveBeenNthCalledWith(1, "/api/ontology/p1/code-view-cache");
    expect(del).toHaveBeenNthCalledWith(2, "/api/ontology/p1/code-view-cache?draft=true");
  });
});

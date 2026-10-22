import { beforeEach, describe, expect, it, vi } from "vitest";

const get = vi.fn();
const post = vi.fn();
const del = vi.fn();
let draftMode = false;

vi.mock("../services/apiClient", () => ({
  default: {
    get: (...args: unknown[]) => get(...args),
    post: (...args: unknown[]) => post(...args),
    delete: (...args: unknown[]) => del(...args),
  },
}));
vi.mock("../utils/draftScope", () => ({
  withDraftAndUser: (url: string) =>
    draftMode ? `${url}${url.includes("?") ? "&" : "?"}draft=true&userId=me%40x.org` : url,
}));

import datatypeDefinitionService from "../services/datatypeDefinitionService";

const ok = { data: { success: true, data: [] } };

describe("datatypeDefinitionService", () => {
  beforeEach(() => {
    get.mockReset().mockResolvedValue(ok);
    post.mockReset().mockResolvedValue({ data: { success: true, data: { id: "d1" } } });
    del.mockReset().mockResolvedValue({ data: { success: true } });
    draftMode = false;
  });

  it("uses the shared endpoints unchanged in public mode", async () => {
    await datatypeDefinitionService.listDefinitions("p1", "http://x/Age");
    await datatypeDefinitionService.createDefinition("p1", "http://x/Age", "e", "restriction");
    await datatypeDefinitionService.deleteDefinition("p1", "d1");

    expect(get).toHaveBeenCalledWith("/api/ontology/datatypes/definitions/p1?datatypeIri=http%3A%2F%2Fx%2FAge");
    expect(post).toHaveBeenCalledWith("/api/ontology/datatypes/definitions/p1", {
      datatypeIri: "http://x/Age",
      expression: "e",
      definitionType: "restriction",
    });
    expect(del).toHaveBeenCalledWith("/api/ontology/datatypes/definitions/p1/d1");
  });

  it("marks every call as a draft call with the user id in draft mode", async () => {
    draftMode = true;

    await datatypeDefinitionService.listDefinitions("p1", "http://x/Age");
    await datatypeDefinitionService.createDefinition("p1", "http://x/Age", "e", "restriction");
    await datatypeDefinitionService.deleteDefinition("p1", "d1");

    expect(get.mock.calls[0][0]).toContain("&draft=true&userId=me%40x.org");
    expect(post.mock.calls[0][0]).toBe("/api/ontology/datatypes/definitions/p1?draft=true&userId=me%40x.org");
    expect(del.mock.calls[0][0]).toBe("/api/ontology/datatypes/definitions/p1/d1?draft=true&userId=me%40x.org");
  });
});

import { describe, it, expect } from "vitest";
import {
  RDFS_LABEL,
  annotationText,
  entityDetailsUrl,
  entityKindOf,
  isEditingFormField,
  mergeFreshDetails,
  unwrapDetails,
} from "../components/dashboard-parts/entityRefresh";

const PIZZA = "http://ex.org/pizza#Pizza";

describe("entityKindOf", () => {
  it("treats property types and property tabs as properties", () => {
    expect(entityKindOf({ type: "ObjectProperty" }, "Classes")).toBe("property");
    expect(entityKindOf({}, "DataProperties")).toBe("property");
  });

  it("treats individuals by type or tab", () => {
    expect(entityKindOf({ type: "NamedIndividual" }, "Classes")).toBe("individual");
    expect(entityKindOf({}, "Individuals")).toBe("individual");
  });

  it("falls back to class", () => {
    expect(entityKindOf(null, "Classes")).toBe("class");
  });
});

describe("entityDetailsUrl", () => {
  it("uses the query-parameter endpoints so IRIs with slashes and hashes survive", () => {
    expect(entityDetailsUrl("class", "p1", PIZZA)).toBe(
      `/api/ontology/classes/details/p1?classIri=${encodeURIComponent(PIZZA)}`,
    );
    expect(entityDetailsUrl("individual", "p1", PIZZA)).toBe(
      `/api/ontology/individual-details/p1?individualIri=${encodeURIComponent(PIZZA)}`,
    );
    expect(entityDetailsUrl("property", "p1", PIZZA)).toBe(
      `/api/ontology/properties/detail/p1?iri=${encodeURIComponent(PIZZA)}`,
    );
  });
});

describe("annotationText", () => {
  it("reads strings, lists and literal objects", () => {
    expect(annotationText("Pizza")).toBe("Pizza");
    expect(annotationText(["Pizza", "Pizza@it"])).toBe("Pizza");
    expect(annotationText([{ value: "Pizza", lang: "en" }])).toBe("Pizza");
  });

  it("ignores blanks and missing values", () => {
    expect(annotationText("  ")).toBeUndefined();
    expect(annotationText(undefined)).toBeUndefined();
    expect(annotationText([])).toBeUndefined();
  });
});

describe("unwrapDetails", () => {
  it("unwraps the { success, data } envelope", () => {
    expect(unwrapDetails({ success: true, data: { id: PIZZA } })).toEqual({ id: PIZZA });
    expect(unwrapDetails({ data: { success: true, data: { id: PIZZA } } })).toEqual({ id: PIZZA });
  });

  it("passes a bare details object through", () => {
    expect(unwrapDetails({ id: PIZZA, types: [] })).toEqual({ id: PIZZA, types: [] });
  });

  it("returns null for nothing", () => {
    expect(unwrapDetails(undefined)).toBeNull();
  });
});

describe("mergeFreshDetails", () => {
  const stale = { id: PIZZA, label: "Pizza", annotations: { [RDFS_LABEL]: ["Pizza"] }, children: [{ id: "c" }] };

  it("takes the new label from the fetched rdfs:label when the response has no label field", () => {
    const merged = mergeFreshDetails("class", stale, { id: PIZZA, annotations: { [RDFS_LABEL]: ["Pizza Margherita"] } });

    expect(merged.label).toBe("Pizza Margherita");
    expect(merged.annotations).toEqual({ [RDFS_LABEL]: ["Pizza Margherita"] });
    expect(merged.children).toEqual([{ id: "c" }]);
  });

  it("prefers an explicit label from the response", () => {
    const merged = mergeFreshDetails("class", stale, {
      id: PIZZA,
      label: "Margherita",
      annotations: { [RDFS_LABEL]: ["Pizza Margherita"] },
    });

    expect(merged.label).toBe("Margherita");
  });

  it("keeps the current label when the entity has no rdfs:label", () => {
    const merged = mergeFreshDetails("class", stale, { id: PIZZA, annotations: {} });

    expect(merged.label).toBe("Pizza");
  });

  it("marks property details as loaded so they are not refetched with stale data", () => {
    const merged = mergeFreshDetails("property", { id: PIZZA, label: "old" }, { id: PIZZA, domains: ["d"] }) as any;

    expect(merged._propertyDetailsLoaded).toBe(true);
    expect(merged.domains).toEqual(["d"]);
  });

  it("refreshes an individual's types and assertions without dropping other fields", () => {
    const merged = mergeFreshDetails(
      "individual",
      { id: PIZZA, label: "old", extra: 1 } as any,
      { types: ["T"], propertyAssertions: [{ p: "x" }], annotations: { [RDFS_LABEL]: "New" } },
    ) as any;

    expect(merged).toMatchObject({ id: PIZZA, label: "New", extra: 1, types: ["T"], propertyAssertions: [{ p: "x" }] });
  });
});

describe("isEditingFormField", () => {
  it("is true for text inputs, textareas and selects", () => {
    for (const tag of ["input", "textarea", "select"]) {
      expect(isEditingFormField(document.createElement(tag))).toBe(true);
    }
  });

  it("is false for buttons, plain elements and nothing", () => {
    expect(isEditingFormField(document.createElement("button"))).toBe(false);
    expect(isEditingFormField(document.createElement("div"))).toBe(false);
    expect(isEditingFormField(null)).toBe(false);
    expect(isEditingFormField(undefined)).toBe(false);
  });
});

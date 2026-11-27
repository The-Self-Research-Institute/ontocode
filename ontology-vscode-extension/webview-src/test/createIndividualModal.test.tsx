import React, { act } from "react";
import { createRoot } from "react-dom/client";
import { expect, it, vi } from "vitest";
import CreateIndividualModal from "../components/dialogs/CreateIndividualModal";

it("uses the short name as the label and preserves the full individual IRI", () => {
  const host = document.createElement("div");
  document.body.append(host);
  const root = createRoot(host);
  const onCreate = vi.fn();
  const iri = "https://example.org/people#Alison";
  const environment = globalThis as { IS_REACT_ACT_ENVIRONMENT?: boolean };
  const original = environment.IS_REACT_ACT_ENVIRONMENT;
  environment.IS_REACT_ACT_ENVIRONMENT = true;
  try {
    act(() => root.render(<CreateIndividualModal isOpen onClose={() => {}} onCreate={onCreate} />));
    const input = host.querySelector("input")!;
    act(() => {
      Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, "value")!.set!.call(input, iri);
      input.dispatchEvent(new Event("input", { bubbles: true }));
    });
    const create = Array.from(host.querySelectorAll("button")).find(button => button.textContent?.trim() === "Create");
    expect(create?.disabled).toBe(false);
    act(() => create?.click());
    expect(onCreate).toHaveBeenCalledWith("Alison", iri);
  } finally {
    act(() => root.unmount());
    host.remove();
    environment.IS_REACT_ACT_ENVIRONMENT = original;
  }
});

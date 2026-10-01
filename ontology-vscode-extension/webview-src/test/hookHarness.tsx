import React, { act } from "react";
import { createRoot, type Root } from "react-dom/client";

(globalThis as { IS_REACT_ACT_ENVIRONMENT?: boolean }).IS_REACT_ACT_ENVIRONMENT = true;

export async function flush(times = 8) {
  for (let i = 0; i < times; i++) {
    await act(async () => {
      await Promise.resolve();
    });
  }
}

export function mount() {
  const container = document.createElement("div");
  document.body.appendChild(container);
  const root: Root = createRoot(container);
  return {
    container,
    render(node: React.ReactNode) {
      act(() => root.render(node));
    },
    unmount() {
      act(() => root.unmount());
      container.remove();
    },
  };
}

export function renderHook<P, R>(hook: (props: P) => R, initialProps: P) {
  const result = { current: undefined as unknown as R };
  const mounted = mount();
  const Probe = ({ props }: { props: P }) => {
    result.current = hook(props);
    return null;
  };
  mounted.render(<Probe props={initialProps} />);
  return {
    result,
    rerender(props: P) {
      mounted.render(<Probe props={props} />);
    },
    unmount: mounted.unmount,
  };
}

export function typeInto(input: HTMLInputElement, value: string) {
  const setter = Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, "value")!.set!;
  act(() => {
    setter.call(input, value);
    input.dispatchEvent(new Event("input", { bubbles: true }));
  });
}

export function click(el: Element | null | undefined) {
  if (!el) throw new Error("element not found");
  act(() => {
    el.dispatchEvent(new MouseEvent("click", { bubbles: true }));
  });
}

export function buttonByText(container: Element, text: string): HTMLButtonElement | undefined {
  return Array.from(container.querySelectorAll("button")).find((b) => b.textContent?.includes(text));
}

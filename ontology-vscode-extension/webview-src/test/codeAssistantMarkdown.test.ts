import { describe, expect, it } from "vitest";
import { renderAssistantMarkdown } from "../components/CodeAssistantMarkdown";

function toDom(html: string): HTMLElement {
  const host = document.createElement("div");
  host.innerHTML = html;
  return host;
}

describe("renderAssistantMarkdown", () => {
  it("renders emphasis, lists and code blocks", () => {
    const dom = toDom(renderAssistantMarkdown("**Dog** is a class.\n\n- one\n- two\n\n```turtle\nex:Dog a owl:Class .\n```"));

    expect(dom.querySelector("strong")?.textContent).toBe("Dog");
    expect(dom.querySelectorAll("li")).toHaveLength(2);
    expect(dom.querySelector("pre code")?.textContent).toContain("ex:Dog a owl:Class .");
  });

  it("keeps single line breaks from plain-text answers", () => {
    expect(toDom(renderAssistantMarkdown("line one\nline two")).querySelector("br")).not.toBeNull();
  });

  it("drops raw HTML the model writes", () => {
    const dom = toDom(renderAssistantMarkdown('Hi <script>alert(1)</script><img src=x onerror="alert(2)"> there'));

    expect(dom.querySelector("script")).toBeNull();
    expect(dom.querySelector("img")).toBeNull();
    expect(dom.innerHTML).not.toContain("onerror");
  });

  it("opens web links in a new tab and strips unsafe link targets", () => {
    const dom = toDom(renderAssistantMarkdown("[docs](https://example.org) and [bad](javascript:alert(1))"));
    const links = dom.querySelectorAll("a");

    expect(links[0].getAttribute("href")).toBe("https://example.org");
    expect(links[0].getAttribute("target")).toBe("_blank");
    expect(links[0].getAttribute("rel")).toBe("noopener noreferrer");
    expect(links[1].hasAttribute("href")).toBe(false);
  });
});

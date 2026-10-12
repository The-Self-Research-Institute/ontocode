import React, { useMemo } from "react";
import { Marked } from "marked";
import DOMPurify from "dompurify";

const SAFE_LINK = /^(https?:|mailto:)/i;

const markdown = new Marked({ gfm: true, breaks: true, async: false });

const purifier = DOMPurify();
purifier.addHook("afterSanitizeAttributes", (node) => {
  if (node.tagName !== "A") return;
  const href = node.getAttribute("href") ?? "";
  if (!SAFE_LINK.test(href)) {
    node.removeAttribute("href");
    return;
  }
  node.setAttribute("target", "_blank");
  node.setAttribute("rel", "noopener noreferrer");
});

export function renderAssistantMarkdown(text: string): string {
  const html = markdown.parse(text) as string;
  return purifier.sanitize(html, {
    ALLOWED_TAGS: [
      "p", "br", "strong", "em", "del", "code", "pre", "blockquote", "ul", "ol", "li",
      "h1", "h2", "h3", "h4", "h5", "h6", "a", "table", "thead", "tbody", "tr", "th", "td", "hr",
    ],
    ALLOWED_ATTR: ["href", "target", "rel", "start"],
  });
}

export const CodeAssistantMarkdown: React.FC<{ text: string }> = ({ text }) => {
  const html = useMemo(() => renderAssistantMarkdown(text), [text]);
  return <div className="assistant-markdown" dangerouslySetInnerHTML={{ __html: html }} />;
};

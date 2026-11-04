export interface NodeTooltipData {
  label: string;
  type: string;
  description?: string;
}

export interface NodeTooltipStyle {
  badgeBackground: string;
  badgeColor: string;
}

export interface EdgeTooltipData {
  sourceLabel: string;
  edgeLabel: string;
  targetLabel: string;
  edgeColor: string;
}

const DESCRIPTION_LIMIT = 140;

function appendText(parent: HTMLElement, tag: string, text: string, cssText: string): HTMLElement {
  const element = parent.ownerDocument.createElement(tag);
  element.style.cssText = cssText;
  element.textContent = text;
  parent.appendChild(element);
  return element;
}

export function renderNodeTooltip(target: HTMLElement, data: NodeTooltipData, style: NodeTooltipStyle): void {
  target.replaceChildren();
  appendText(target, 'div', String(data.label ?? ''), 'font-weight:600;font-size:13px;margin-bottom:4px;');
  appendText(
    target,
    'span',
    String(data.type ?? ''),
    `display:inline-block;padding:1px 8px;border-radius:9999px;font-size:10px;font-weight:600;letter-spacing:0.5px;text-transform:uppercase;background:${style.badgeBackground};color:${style.badgeColor};`,
  );
  if (data.description) {
    const clipped = data.description.substring(0, DESCRIPTION_LIMIT) + (data.description.length > DESCRIPTION_LIMIT ? '…' : '');
    appendText(target, 'div', clipped, 'margin-top:6px;opacity:0.75;');
  }
}

export function renderEdgeTooltip(target: HTMLElement, data: EdgeTooltipData): void {
  target.replaceChildren();
  appendText(target, 'strong', String(data.sourceLabel ?? ''), '');
  target.appendChild(target.ownerDocument.createTextNode(' '));
  appendText(target, 'span', String(data.edgeLabel ?? ''), `color:${data.edgeColor}`);
  target.appendChild(target.ownerDocument.createTextNode(' '));
  appendText(target, 'strong', String(data.targetLabel ?? ''), '');
}

export const CHANGE_SET_CSS = `
.cs-root {
  --cs-text: var(--text-primary, #111111);
  --cs-text2: var(--text-secondary, #444444);
  --cs-muted: var(--text-tertiary, #666666);
  --cs-border: var(--border, #e4e7ec);
  --cs-surface: var(--surface-1, #ffffff);
  --cs-surface2: var(--surface-2, #f6f7f9);
  --cs-hover: var(--hover-overlay, rgba(0, 0, 0, 0.04));
  --cs-focus: var(--focus-ring, #3b82f6);
  --cs-ai: #6d28d9;
  --cs-ai-tint: rgba(124, 58, 237, 0.1);
  --cs-add: #047857;
  --cs-del: #b91c1c;
  --cs-primary: #7c3aed;
  --cs-on-primary: #ffffff;
  color: var(--cs-text);
}
.dark .cs-root {
  --cs-ai: #c4b5fd;
  --cs-ai-tint: rgba(196, 181, 253, 0.14);
  --cs-add: #34d399;
  --cs-del: #f87171;
  --cs-primary: #a78bfa;
  --cs-on-primary: #0b0f14;
}
.cs-day { font-size: 12px; font-weight: 600; color: var(--cs-muted); margin: 14px 0 6px; }
.cs-root > section:first-of-type > .cs-day { margin-top: 0; }
.cs-card { border: 1px solid var(--cs-border); border-radius: 8px; background: var(--cs-surface); margin-bottom: 8px; }
.cs-head { display: flex; align-items: flex-start; gap: 8px; padding: 10px 12px; flex-wrap: wrap; }
.cs-toggle { flex: 1 1 240px; display: flex; gap: 8px; min-width: 0; padding: 0; border: 0; background: none; color: inherit; text-align: left; cursor: pointer; font: inherit; }
.cs-toggle:focus-visible, .cs-btn:focus-visible, .cs-icon-btn:focus-visible, .cs-link:focus-visible { outline: 2px solid var(--cs-focus); outline-offset: 2px; border-radius: 4px; }
.cs-chevron { flex: none; width: 14px; margin-top: 1px; color: var(--cs-muted); font-size: 12px; line-height: 18px; }
.cs-head-text { display: block; min-width: 0; flex: 1; }
.cs-title-line { display: flex; align-items: center; gap: 6px; flex-wrap: wrap; }
.cs-title { font-size: 13px; font-weight: 600; overflow-wrap: anywhere; }
.cs-name { color: var(--cs-primary); background: var(--cs-ai-tint); padding: 0 5px; border-radius: 4px; }
.cs-btn.is-small { display: inline-flex; align-items: center; gap: 4px; font-size: 11px; line-height: 16px; padding: 2px 8px; }
.cs-rollback-what { color: var(--cs-text2); font-weight: 500; }
.cs-rollback-redo { margin-left: auto; }
.cs-rollback-link, .cs-rollback-text { display: flex; align-items: center; gap: 6px; flex-wrap: wrap; flex: 1 1 auto; min-width: 0; padding: 0; border: 0; background: none; color: inherit; font: inherit; text-align: left; }
.cs-rollback-link { cursor: pointer; border-radius: 4px; }
.cs-rollback-link:hover .cs-rollback-what { text-decoration: underline; color: var(--cs-text); }
.cs-rollback-link:focus-visible { outline: 2px solid var(--cs-focus); outline-offset: 2px; }
.cs-card { transition: box-shadow 0.3s ease; scroll-margin-top: 12px; }
.cs-card.is-flash { box-shadow: 0 0 0 2px var(--cs-primary); }
.cs-rollback-target { min-width: 0; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; max-width: 40ch; }
.cs-sub { display: block; font-size: 12px; color: var(--cs-muted); margin-top: 2px; overflow-wrap: anywhere; }
.cs-badge { display: inline-flex; align-items: center; gap: 4px; flex: none; font-size: 11px; font-weight: 600; line-height: 16px; padding: 1px 7px; border-radius: 999px; background: var(--cs-surface2); color: var(--cs-text2); white-space: nowrap; }
.cs-badge.is-ai { background: var(--cs-ai-tint); color: var(--cs-ai); }
.cs-badge.is-quiet { font-weight: 500; }
.cs-actions { display: flex; align-items: center; gap: 6px; flex: none; margin-left: auto; }
.cs-btn { font: inherit; font-size: 12px; line-height: 18px; padding: 3px 10px; border: 1px solid var(--cs-border); border-radius: 6px; background: transparent; color: var(--cs-text); cursor: pointer; white-space: nowrap; }
.cs-btn:hover:not(:disabled) { background: var(--cs-hover); }
.cs-btn:disabled { opacity: 0.5; cursor: not-allowed; }
.cs-btn.is-primary { background: var(--cs-primary); border-color: var(--cs-primary); color: var(--cs-on-primary); }
.cs-btn.is-primary:hover:not(:disabled) { background: var(--cs-primary); filter: brightness(1.08); }
.cs-card.is-undone .cs-head-text { opacity: 0.65; }
.cs-card.is-undone .cs-title { text-decoration: line-through; }
.cs-body { padding: 0 12px 10px 34px; }
.cs-guide { border-left: 1px solid var(--cs-border); padding-left: 10px; }
.cs-guide .cs-guide { margin-left: 6px; }
.cs-row { display: flex; align-items: center; gap: 8px; flex-wrap: wrap; min-height: 28px; padding: 2px 4px; border-radius: 4px; font-size: 13px; }
.cs-row:hover { background: var(--cs-hover); }
.cs-row-main { display: flex; align-items: center; gap: 8px; min-width: 0; flex: 1 1 160px; }
.cs-label { font-weight: 500; min-width: 0; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.cs-prop-name { color: var(--cs-text2); flex: none; }
.cs-sep { color: var(--cs-muted); flex: none; }
.cs-sign { flex: none; width: 12px; text-align: center; font-weight: 700; }
.cs-sign.is-add { color: var(--cs-add); }
.cs-sign.is-del { color: var(--cs-del); }
.cs-value { font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace; font-size: 12px; min-width: 0; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.cs-note { font-size: 11px; color: var(--cs-muted); white-space: nowrap; }
.cs-row.is-undone .cs-label, .cs-row.is-undone .cs-value, .cs-row.is-undone .cs-prop-name { text-decoration: line-through; opacity: 0.6; }
.cs-row-tools { display: flex; align-items: center; gap: 2px; margin-left: auto; flex: none; }
.cs-icon-btn { display: inline-flex; align-items: center; justify-content: center; gap: 2px; min-width: 24px; height: 24px; padding: 0 4px; border: 0; border-radius: 4px; background: transparent; color: var(--cs-muted); cursor: pointer; opacity: 0; font-size: 11px; }
.cs-icon-btn:hover { background: var(--cs-hover); color: var(--cs-text); }
.cs-row:hover .cs-icon-btn, .cs-row:focus-within .cs-icon-btn, .cs-icon-btn.is-visible { opacity: 1; }
@media (hover: none), (pointer: coarse) { .cs-icon-btn { opacity: 1; } }
.cs-entity + .cs-entity { margin-top: 2px; }
.cs-link { font: inherit; font-size: 12px; padding: 2px 4px; margin-top: 4px; border: 0; background: none; color: var(--cs-primary); cursor: pointer; }
.cs-history { margin-top: 8px; display: flex; flex-direction: column; gap: 2px; font-size: 11px; color: var(--cs-muted); }
.cs-rollback-row { display: flex; align-items: center; gap: 6px; flex-wrap: wrap; font-size: 12px; color: var(--cs-muted); padding: 6px 12px; margin-bottom: 8px; border: 1px dashed var(--cs-border); border-radius: 8px; }
.cs-empty { text-align: center; padding: 32px 0; color: var(--cs-muted); font-size: 13px; }
.cs-overlay { position: fixed; inset: 0; z-index: 60; display: flex; align-items: center; justify-content: center; padding: 16px; background: var(--overlay, rgba(0, 0, 0, 0.4)); }
.cs-dialog { width: 100%; max-width: 420px; padding: 16px; border: 1px solid var(--cs-border); border-radius: 10px; background: var(--cs-surface); color: var(--cs-text); box-shadow: 0 10px 30px rgba(0, 0, 0, 0.25); }
.cs-dialog-title { font-size: 14px; font-weight: 600; margin: 0 0 2px; }
.cs-dialog-target { font-size: 12px; color: var(--cs-muted); margin-bottom: 10px; overflow-wrap: anywhere; }
.cs-dialog-text { font-size: 13px; color: var(--cs-text); }
.cs-dialog-skip { font-size: 12px; font-weight: 600; color: var(--cs-text2); margin-top: 10px; }
.cs-dialog-actions { display: flex; justify-content: flex-end; gap: 8px; margin-top: 16px; }
.cs-dialog-list { margin: 6px 0 0; padding-left: 18px; font-size: 12px; color: var(--cs-text2); max-height: 180px; overflow-y: auto; }
.cs-dialog-error { margin-top: 10px; font-size: 12px; color: var(--cs-del); }
`;

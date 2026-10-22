const RULES: Array<[RegExp, string]> = [
  [/still refer to/i, "Other items still use it, so it can't be removed yet."],
  [/changed since/i, "It was changed after this edit. Undo the later changes first."],
  [/no longer exists/i, "It has already been removed."],
  [/created again/i, "It was created again after this edit."],
  [/old name is in use/i, "The old name is being used again."],
  [/still running/i, "The server is still working on the last request."],
];

export function plainUndoReason(message: string | undefined): string {
  if (!message) return "";
  const rule = RULES.find(([pattern]) => pattern.test(message));
  return rule ? `${rule[1]} (${message})` : message;
}

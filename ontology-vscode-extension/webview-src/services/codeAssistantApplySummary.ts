const MAX_SUMMARY_LENGTH = 200;

function firstMeaningfulLine(text: string): string {
  for (const raw of text.split(/\r?\n/)) {
    const line = raw
      .replace(/^\s*(?:#{1,6}\s+|[-*+>]\s+|\d+[.)]\s+)/, "")
      .replace(/\*\*|__|`/g, "")
      .replace(/\s+/g, " ")
      .trim();
    if (line) return line;
  }
  return "";
}

export function toApplySummary(explanation: string | undefined | null): string | undefined {
  if (!explanation) return undefined;
  const line = firstMeaningfulLine(explanation);
  if (!line) return undefined;
  return line.length <= MAX_SUMMARY_LENGTH ? line : `${line.slice(0, MAX_SUMMARY_LENGTH - 1).trimEnd()}…`;
}

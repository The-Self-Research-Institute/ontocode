import React from "react";
import type { SpreadsheetSheet } from "../../services/spreadsheetImportService";

export interface GridHighlight {
  startColumn: number;
  endColumn: number;
  startRow: number;
  endRow: number;
}

interface SheetGridProps {
  sheet: SpreadsheetSheet;
  highlight?: GridHighlight | null;
}

const SheetGrid: React.FC<SheetGridProps> = ({ sheet, highlight }) => {
  const inRange = (row: number, col: number) =>
    !!highlight &&
    row >= highlight.startRow &&
    row <= highlight.endRow &&
    col >= highlight.startColumn &&
    col <= highlight.endColumn;

  if (sheet.rowCount === 0) {
    return <div className="p-4 text-xs text-gray-500">This sheet is empty.</div>;
  }

  return (
    <div className="overflow-auto h-full border rounded" style={{ borderColor: "var(--color-border)" }}>
      <table className="text-xs border-collapse font-mono">
        <thead className="sticky top-0 z-10">
          <tr>
            <th
              className="sticky left-0 z-20 px-2 py-1 border bg-gray-100 text-gray-500 font-normal"
              style={{ borderColor: "var(--color-border)" }}
            />
            {sheet.columns.map((letter, c) => (
              <th
                key={letter}
                className={`px-2 py-1 border font-semibold min-w-[72px] ${
                  highlight && c + 1 >= highlight.startColumn && c + 1 <= highlight.endColumn
                    ? "bg-purple-100 text-purple-700"
                    : "bg-gray-100 text-gray-600"
                }`}
                style={{ borderColor: "var(--color-border)" }}
              >
                {letter}
              </th>
            ))}
          </tr>
        </thead>
        <tbody>
          {sheet.rows.map((cells, r) => {
            const rowNumber = r + 1;
            return (
              <tr key={rowNumber}>
                <td
                  className={`sticky left-0 px-2 py-0.5 border text-right ${
                    highlight && rowNumber >= highlight.startRow && rowNumber <= highlight.endRow
                      ? "bg-purple-100 text-purple-700"
                      : "bg-gray-100 text-gray-500"
                  }`}
                  style={{ borderColor: "var(--color-border)" }}
                >
                  {rowNumber}
                </td>
                {cells.map((value, c) => (
                  <td
                    key={c}
                    title={value}
                    className={`px-2 py-0.5 border whitespace-nowrap max-w-[240px] overflow-hidden text-ellipsis ${
                      inRange(rowNumber, c + 1) ? "bg-purple-50" : ""
                    } ${rowNumber === 1 ? "font-semibold" : ""}`}
                    style={{ borderColor: "var(--color-border)" }}
                  >
                    {value}
                  </td>
                ))}
              </tr>
            );
          })}
        </tbody>
      </table>
      {sheet.truncated && (
        <div className="px-2 py-1 text-[11px] text-gray-500">
          Showing the first {sheet.rows.length} of {sheet.rowCount} rows. All rows are used when the rules run.
        </div>
      )}
    </div>
  );
};

export default SheetGrid;

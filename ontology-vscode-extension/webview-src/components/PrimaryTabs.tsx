import React from "react";

const TABS = ["Entities", "Individuals by class", "DL Query", "SPARQL Query"] as const;
export type PrimaryTab = typeof TABS[number];

export function PrimaryTabs({
  value,
  onChange,
  rightActions,
  activeOntology,
  onChangeOntology,
  ontologies = [],
}: {
  value: PrimaryTab;
  onChange: (t: PrimaryTab) => void;
  rightActions?: React.ReactNode;
  activeOntology?: string;
  ontologies?: string[];
  onChangeOntology?: (id: string) => void;
}) {
  return (
    <div className="border-b bg-white">
      <div className="mx-auto max-w-screen-2xl px-4 py-3 flex items-center gap-4">
        <div className="flex items-center gap-2 mr-6">
          <span className="text-sm text-gray-500">Active ontology</span>
          <select
            className="text-sm border rounded-md px-2 py-1"
            value={activeOntology ?? ""}
            onChange={(e) => onChangeOntology?.(e.target.value)}
          >
            {ontologies.map((o) => (
              <option key={o} value={o}>{o}</option>
            ))}
          </select>
        </div>

        <div className="flex items-center gap-6">
          {TABS.map((t) => (
            <button
              key={t}
              onClick={() => onChange(t)}
              className={`pb-2 -mb-[1px] border-b-2 text-sm ${
                value === t
                  ? "border-[#6D4AFF] text-[#6D4AFF] font-semibold"
                  : "border-transparent text-gray-600 hover:text-gray-900"
              }`}
              aria-current={value === t ? "page" : undefined}
            >
              {t}
            </button>
          ))}
        </div>

        <div className="ml-auto flex items-center gap-3">{rightActions}</div>
      </div>
    </div>
  );
}

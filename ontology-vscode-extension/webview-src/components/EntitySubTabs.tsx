import React from "react";

export type SubTab =
  | "Classes"
  | "Object properties"
  | "Data properties"
  | "Annotation properties"
  | "Individuals"
  | "Datatypes";

type Counts = {
  classes: number;
  objectProps: number;
  dataProps: number;
  annProps: number;
  individuals: number;
  datatypes: number;
};

const LABELS: { key: SubTab; badge?: keyof Counts }[] = [
  { key: "Classes", badge: "classes" },
  { key: "Object properties", badge: "objectProps" },
  { key: "Data properties", badge: "dataProps" },
  { key: "Annotation properties", badge: "annProps" },
  { key: "Individuals", badge: "individuals" },
  { key: "Datatypes", badge: "datatypes" },
];

export function EntitySubTabs({
  value,
  onChange,
  counts,
}: {
  value: SubTab;
  onChange: (t: SubTab) => void;
  counts: Counts;
}) {
  return (
    <div className="border-b bg-white">
      <div className="mx-auto max-w-screen-2xl px-4 h-11 flex items-center gap-5">
        {LABELS.map(({ key, badge }) => {
          const n = badge ? (counts[badge] ?? 0) : 0;
          return (
            <button
              key={key}
              onClick={() => onChange(key)}
              className={`relative pb-2 -mb-[1px] border-b-2 text-sm ${
                value === key
                  ? "border-[#6D4AFF] text-[#6D4AFF] font-semibold"
                  : "border-transparent text-gray-600 hover:text-gray-900"
              }`}
            >
              {key}
              {badge && (
                <span className="ml-2 inline-flex items-center justify-center text-[11px] px-2 py-0.5 rounded-full bg-gray-100 text-gray-700">
                  {n}
                </span>
              )}
            </button>
          );
        })}
      </div>
    </div>
  );
}

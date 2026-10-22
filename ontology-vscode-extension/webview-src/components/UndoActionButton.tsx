import React from "react";

type Tone = "primary" | "danger" | "neutral";

const TONES: Record<Tone, string> = {
  primary: "bg-purple-600 text-white border-purple-600 hover:bg-purple-700",
  danger: "border-red-500 text-red-600 hover:bg-red-50",
  neutral: "bg-transparent hover:bg-black/5",
};

export default function UndoActionButton({ tone, onClick, children }: { tone: Tone; onClick: () => void; children: React.ReactNode }) {
  return (
    <button
      type="button"
      onClick={onClick}
      className={`whitespace-nowrap shrink-0 rounded border px-2.5 py-1 font-semibold cursor-pointer ${TONES[tone]}`}
      style={tone === "neutral" ? { borderColor: "var(--color-border)" } : undefined}
    >
      {children}
    </button>
  );
}

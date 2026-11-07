import React from "react";
import { Search } from "lucide-react";

export interface TopBarSearchFieldProps {
  onOpen: (text?: string) => void;
}

const TopBarSearchField: React.FC<TopBarSearchFieldProps> = ({ onOpen }) => (
  <>
    <div
      className="hidden md:flex items-center gap-2 rounded-md px-2 py-1 w-56"
      style={{
        background: "var(--surface-2)",
        border: "1px solid var(--border)",
        color: "var(--text-secondary)",
      }}
    >
      <Search size={13} />
      <input
        type="text"
        value=""
        aria-label="Search ontology"
        placeholder="Search ontology…"
        onFocus={() => onOpen()}
        onChange={(e) => onOpen(e.target.value)}
        className="flex-1 min-w-0 bg-transparent outline-none text-xs"
        style={{ color: "var(--text-primary)" }}
      />
      <span
        className="text-[10px] rounded px-1"
        style={{ border: "1px solid var(--border)", color: "var(--text-tertiary)" }}
      >
        Ctrl F
      </span>
    </div>
    <button
      type="button"
      aria-label="Search ontology"
      title="Search ontology (Ctrl+F)"
      onClick={() => onOpen()}
      className="md:hidden flex items-center justify-center rounded-md p-1.5"
      style={{ border: "1px solid var(--border)", color: "var(--text-secondary)" }}
    >
      <Search size={14} />
    </button>
  </>
);

export default TopBarSearchField;

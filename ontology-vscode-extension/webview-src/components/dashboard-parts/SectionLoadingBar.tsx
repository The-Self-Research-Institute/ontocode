import React from "react";

import { Loader2 } from "lucide-react";



export type SectionLoadingBarProps = {

  /** Human-readable labels, e.g. ["classes", "metadata"] */

  sections: string[];

  /** When false, bar animates closed (parent should keep mounted briefly). */

  open?: boolean;

};



/**

 * Slim non-blocking indicator while ontology sections load in the background.

 * Shown after the full-screen import modal closes so users know work is still in progress.

 */

export const SectionLoadingBar: React.FC<SectionLoadingBarProps> = ({

  sections,

  open = true,

}) => {

  if (!sections.length) return null;



  const label =

    sections.length === 1

      ? `Loading ${sections[0]}…`

      : `Loading ${sections.slice(0, -1).join(", ")} and ${sections[sections.length - 1]}…`;



  return (

    <div

      className="grid shrink-0 transition-[grid-template-rows] duration-300 ease-out"

      style={{ gridTemplateRows: open ? "1fr" : "0fr" }}

      aria-hidden={!open}

    >

      <div className="overflow-hidden">

        <div

          className={`flex items-center gap-2 border-b px-4 py-1.5 text-xs transition-opacity duration-300 ease-out ${

            open ? "opacity-100" : "opacity-0"

          }`}

          style={{

            backgroundColor: "rgba(99, 102, 241, 0.08)",

            borderColor: "var(--color-border, #e5e7eb)",

            color: "rgb(99, 102, 241)",

          }}

          role="status"

          aria-live="polite"

        >

          <Loader2 size={14} className="animate-spin flex-shrink-0" />

          <span className="font-medium">{label}</span>

          <span className="hidden opacity-60 sm:inline">

            You can browse classes while the rest finishes.

          </span>

        </div>

      </div>

    </div>

  );

};


import React from "react";
import { BookOpen, X } from "lucide-react";

interface CodeAssistantHelpGuideProps {
  isOpen: boolean;
  onClose: () => void;
}

const SectionLabel: React.FC<{ children: React.ReactNode }> = ({ children }) => (
  <h4 className="text-xs font-semibold text-gray-500 uppercase tracking-wide mb-2">{children}</h4>
);

const ToolTags: React.FC<{ tools: string[] }> = ({ tools }) => (
  <div className="flex flex-wrap gap-1.5 mb-2">
    {tools.map((tool) => (
      <span key={tool} className="font-mono text-[10px] text-gray-500 border border-gray-200 rounded px-1.5 py-0.5">
        {tool}
      </span>
    ))}
  </div>
);

const Prompt: React.FC<{ children: React.ReactNode }> = ({ children }) => (
  <div className="font-mono text-xs bg-gray-50 border border-gray-200 rounded-md px-2.5 py-1.5 text-gray-800">
    "{children}"
  </div>
);

const ReviewNote: React.FC<{ children: React.ReactNode }> = ({ children }) => (
  <div className="mt-2 text-xs bg-green-50 border border-green-200 text-green-800 rounded-md px-2.5 py-2">
    <span className="font-semibold">Review required —</span> {children}
  </div>
);

const Capability: React.FC<{ title: string; tools: string[]; children: React.ReactNode; note?: React.ReactNode }> = ({
  title, tools, children, note,
}) => (
  <div className="border border-gray-200 rounded-lg p-3">
    <h5 className="text-sm font-semibold text-gray-800 mb-1.5">{title}</h5>
    <ToolTags tools={tools} />
    <div className="flex flex-col gap-1.5">{children}</div>
    {note && <ReviewNote>{note}</ReviewNote>}
  </div>
);

const TROUBLESHOOTING: Array<{ code: string; meaning: string; action: string }> = [
  { code: "BUDGET_EXHAUSTED", meaning: "This message used up its token or retrieval-attempt budget.", action: "Raise the limit in Settings, or split the question into smaller ones." },
  { code: "RATE_LIMITED", meaning: "Too many tool calls are running for your account at once.", action: "Wait a few seconds and retry — this clears on its own." },
  { code: "REVISION_STALE", meaning: "The document changed after the assistant pinned its snapshot.", action: "Start a new message; it'll pin a fresh snapshot automatically." },
  { code: "PLUGIN_NOT_INSTALLED", meaning: "A SWRL rule was requested, but the SWRL plugin isn't installed.", action: "Install it from the Extensions panel, then ask again." },
];

const CodeAssistantHelpGuide: React.FC<CodeAssistantHelpGuideProps> = ({ isOpen, onClose }) => {
  if (!isOpen) return null;

  return (
    <div
      className="fixed inset-0 bg-black bg-opacity-50 flex items-center justify-center z-50"
      onMouseDown={(e) => {
        if (e.target === e.currentTarget && e.button === 0) onClose();
      }}
    >
      <div className="bg-white rounded-lg shadow-xl max-w-xl w-full mx-4 flex flex-col max-h-[85vh]" onClick={(e) => e.stopPropagation()}>
        <div className="p-4 border-b border-gray-200 flex items-center justify-between flex-shrink-0">
          <div className="flex items-center gap-2">
            <BookOpen size={20} className="text-purple-600" />
            <h3 className="text-lg font-semibold text-black">Ask AI Guide</h3>
          </div>
          <button onClick={onClose} className="p-1 rounded hover:bg-gray-100 text-gray-500" title="Close">
            <X size={20} />
          </button>
        </div>

        <div className="flex-1 overflow-y-auto p-4 space-y-5">
          <p className="text-sm text-gray-600">
            The assistant reads your actual ontology before answering, and never writes anything until you review
            and approve it. Here's what to type and when.
          </p>

          <div>
            <SectionLabel>Getting started</SectionLabel>
            <ol className="text-sm text-gray-700 space-y-1.5 list-decimal list-inside">
              <li>Open the Ask AI panel while a document is open — it pins a snapshot of the project.</li>
              <li>Pick a provider (Gemini, Claude, or OpenAI) and paste an API key.</li>
              <li>Pick an action below, then type a plain-English request.</li>
            </ol>
          </div>

          <div>
            <SectionLabel>The three actions</SectionLabel>
            <div className="text-sm text-gray-700 space-y-1.5">
              <p><span className="font-semibold">Ask</span> — answers questions about the open document. Read-only.</p>
              <p><span className="font-semibold">Local edit</span> — proposes a change scoped to this document. Nothing is written until you approve it.</p>
              <p><span className="font-semibold">Project findings</span> — like Ask, but grounded in the whole project, not just the open document.</p>
            </div>
          </div>

          <div>
            <SectionLabel>What you can ask</SectionLabel>
            <div className="flex flex-col gap-3">
              <Capability title="Understand the ontology" tools={["read_context", "run_sparql"]}>
                <Prompt>Explain this OWL file — what's it modeling?</Prompt>
                <Prompt>What classes and properties does ex:Patient have?</Prompt>
                <Prompt>List every class in this ontology with its parent class.</Prompt>
                <Prompt>Are there any parse errors or warnings in this document?</Prompt>
              </Capability>

              <Capability
                title="Edit the document"
                tools={["propose_edit", "propose_rename"]}
                note="every proposed edit shows a diff in the panel — nothing touches the file until you click Apply."
              >
                <Prompt>Add a new class ex:Vegan as a subclass of ex:DietaryPreference.</Prompt>
                <Prompt>Rename ex:Diabetis to ex:Diabetes everywhere in this document.</Prompt>
              </Capability>

              <Capability title="Check consistency & reasoning" tools={["check_consistency", "explain_inconsistency"]}>
                <Prompt>Is this ontology logically consistent?</Prompt>
                <Prompt>It's inconsistent — explain exactly why, with the conflicting axioms.</Prompt>
              </Capability>

              <Capability
                title="SWRL rules"
                tools={["add_swrl_rule", "run_swrl_rule", "add_inferred_axioms"]}
                note="requires the SWRL plugin installed. Adding the rule, running it, and keeping the results are three separate steps on purpose."
              >
                <Prompt>Add a SWRL rule: if a person has a parent, and that parent has a parent, infer a grandparent relationship.</Prompt>
                <Prompt>Run the SWRL rules and show me what gets inferred.</Prompt>
                <Prompt>Add those inferred facts to the ontology as real statements.</Prompt>
              </Capability>

              <Capability
                title="Fuzzy logic"
                tools={["add_fuzzy_membership", "run_fuzzy_query"]}
                note="new memberships are reviewed before they're written. Querying is read-only. Adding only works for new memberships — change an existing one by hand in the Fuzzy tab."
              >
                <Prompt>Add a fuzzy membership: ex:Patient12 is a member of ex:HighRisk with degree 0.9.</Prompt>
                <Prompt>Also add: ex:Patient12 is a member of ex:Diabetic with degree 0.7.</Prompt>
                <Prompt>Now find individuals who are both HighRisk and Diabetic with a degree over 0.5 — Patient12 should show up.</Prompt>
              </Capability>
            </div>
          </div>

          <div>
            <SectionLabel>Settings &amp; the live status pill</SectionLabel>
            <div className="text-sm text-gray-700 space-y-1.5">
              <p><span className="font-semibold">Session budget:</span> 2,000–20,000 tokens — caps how much tool-result content one message can pull in.</p>
              <p><span className="font-semibold">Retrieval attempts:</span> 2–30 calls — caps how many tool calls one message can make.</p>
              <p>While working, a pill shows live consumption, e.g. <span className="font-mono text-xs">5 calls left · 6,200 tokens left</span>.</p>
            </div>
          </div>

          <div>
            <SectionLabel>If something goes wrong</SectionLabel>
            <div className="flex flex-col gap-2">
              {TROUBLESHOOTING.map((row) => (
                <div key={row.code} className="text-xs border border-gray-200 rounded-md p-2.5">
                  <span className="font-mono bg-gray-100 rounded px-1.5 py-0.5">{row.code}</span>
                  <p className="text-gray-600 mt-1 mb-0.5">{row.meaning}</p>
                  <p className="text-gray-800 font-medium">{row.action}</p>
                </div>
              ))}
            </div>
          </div>
        </div>

        <div className="p-4 border-t border-gray-200 flex justify-end flex-shrink-0">
          <button onClick={onClose} className="px-4 py-2 text-sm bg-purple-600 text-white rounded-md hover:bg-purple-700">
            Close
          </button>
        </div>
      </div>
    </div>
  );
};

export default CodeAssistantHelpGuide;

import { Code, Share2 } from "lucide-react";
import { OntologyPlugin, PluginContext } from "../types";
import SWRLEditor from "../components/SWRLEditor";
import ReasoningVisualizer from "../components/ReasoningVisualizer";

export const SWRLPlugin: OntologyPlugin = {
  id: "swrl-tab",
  name: "SWRL Rule Editor",
  version: "1.0.0",
  description:
    "An editor for creating, managing, and executing SWRL rules and SQWRL queries.",
  author: "OntoCode Team",
  icon: Code,
  component: SWRLEditor,
  async activate(context: PluginContext): Promise<boolean> {
    console.log("Activated with project:", context.projectId);
    return true;
  },
  async deactivate(context: PluginContext): Promise<boolean> {
    console.log("Deactivated");
    return true;
  },
};

export const ReasoningPlugin: OntologyPlugin = {
  id: "reasoning-graph",
  name: "Ontology Visualizer",
  version: "1.0.0",
  description:
    "Provides an interactive graph visualization of the ontology structure.",
  author: "OntoCode Team",
  icon: Share2,
  component: ReasoningVisualizer,
   async activate(context: PluginContext): Promise<boolean> {
    console.log("Activated with project:", context.projectId);
    return true;
  },
  async deactivate(context: PluginContext): Promise<boolean> {
    console.log("Deactivated");
    return true;
  },
};

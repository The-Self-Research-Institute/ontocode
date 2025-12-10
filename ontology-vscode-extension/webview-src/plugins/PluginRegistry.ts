import { Code, Sparkles, Network } from "lucide-react";
import { OntologyPlugin, PluginContext } from "../types";

import FuzzyOntologyEditor from "../components/FuzzyOntologyEditor";

// Import the advanced graph view from plugins folder
// Note: This requires the plugin to be built and available
let AdvancedGraphViewComponent: any = null;
try {
  // Try to load the advanced graph view
  const graphViewModule = require('../../../../plugins/graph-view-plugin/src/AdvancedGraphView');
  AdvancedGraphViewComponent = graphViewModule.AdvancedGraphView || graphViewModule.default;
} catch (error) {
  console.warn('[PluginRegistry] Advanced Graph View not available:', error);
  // Create placeholder component
  AdvancedGraphViewComponent = () => null;
}



// Advanced Graph View Plugin v2.0
export const AdvancedGraphViewPlugin: OntologyPlugin = {
  id: "graph-view-plugin",
  name: "Advanced Ontology Graph View",
  version: "2.0.0",
  description:
    "Enterprise-grade graph visualization with AI reasoning, collaborative editing, temporal modeling, provenance tracking, and 1000x features.",
  author: "OntoCode Team",
  icon: Network,
  component: AdvancedGraphViewComponent,
  async activate(context: PluginContext): Promise<boolean> {
    console.log("✅ Advanced Graph View Plugin v2.0 activated with project:", context?.projectId);
    return true;
  },
  async deactivate(context: PluginContext): Promise<boolean> {
    console.log("Advanced Graph View Plugin deactivated");
    return true;
  },
};

export const FuzzyOntologyPlugin: OntologyPlugin = {
  id: "fuzzy-ontology-plugin",
  name: "Fuzzy Ontology Advanced Plugin",
  version: "1.0.0",
  description:
    "Advanced fuzzy logic reasoning with membership degrees, fuzzy rules, and uncertainty modeling.",
  author: "OntoCode Team",
  icon: Sparkles,
  component: FuzzyOntologyEditor,
  async activate(context: PluginContext): Promise<boolean> {
    console.log("✅ Fuzzy Ontology Plugin activated with project:", context?.projectId);
    return true;
  },
  async deactivate(context: PluginContext): Promise<boolean> {
    console.log("Fuzzy Ontology Plugin deactivated");
    return true;
  },
};

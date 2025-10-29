
import { Code, Share2 } from 'lucide-react';
import { OntologyPlugin } from '../types';
import SWRLEditor from '../components/SWRLEditor';
import ReasoningVisualizer from '../components/ReasoningVisualizer';

export const SWRLPlugin: OntologyPlugin = {
  id: 'swrl-tab',
  name: 'SWRL Rule Editor',
  version: '1.0.0',
  description: 'An editor for creating, managing, and executing SWRL rules and SQWRL queries.',
  author: 'OntoCode Team',
  icon: Code,
  component: SWRLEditor,
  activate: async () => {
    console.log('SWRL Plugin is now active.');
  },
  deactivate: async () => {
    console.log('SWRL Plugin is now inactive.');
  },
};

export const ReasoningPlugin: OntologyPlugin = {
  id: 'reasoning-graph',
  name: 'Ontology Visualizer',
  version: '1.0.0',
  description: 'Provides an interactive graph visualization of the ontology structure.',
  author: 'OntoCode Team',
  icon: Share2,
  component: ReasoningVisualizer,
  activate: async () => {
    console.log('Reasoning Visualizer is now active.');
  },
  deactivate: async () => {
    console.log('Reasoning Visualizer is now inactive.');
  },
};

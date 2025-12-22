import React from 'react';
import AdvancedGraphView from '@ontocode/graph-view-plugin';

interface OntologyGraphViewerProps {
  projectId: string;
}

/**
 * Wrapper component for the Advanced Graph View Plugin
 * Uses the full-featured graph visualization plugin with:
 * - OntoGraph Protégé-style hierarchical layout
 * - WebVOWL notation support
 * - Force-directed layout
 * - Hierarchical lazy loading
 * - 60 FPS performance with 100k+ nodes
 * - Real-time collaboration
 * - SPARQL/Cypher query integration
 */

const OntologyGraphViewer: React.FC<OntologyGraphViewerProps> = ({ projectId }) => {
  return (
    <div className="h-full w-full">
      <AdvancedGraphView 
        projectId={projectId}
        readonly={false}
      />
    </div>
  );
};

export default OntologyGraphViewer;
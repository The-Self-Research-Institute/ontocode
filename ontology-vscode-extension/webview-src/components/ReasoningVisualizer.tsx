import React, { useEffect, useRef, useState } from 'react';
import { Loader2 } from 'lucide-react';
import type { TreeNode, Property, Individual, OntologyClassNode } from '../types';
import apiClient from '../services/apiClient';

// Minimal interface for the vis.js Network instance
interface VisNetwork {
    on(event: string, callback: (params: any) => void): void;
    destroy(): void;
}

// Add a declaration for the global `vis` object
declare global {
    interface Window {
        vis?: { // Make it optional in case script fails to load
            Network: new (container: HTMLElement, data: object, options: object) => VisNetwork; 
        };
    }
}

interface ReasoningVisualizerProps {
    projectId: string;
    onNodeClick: (nodeId: string) => void;
}

const ReasoningVisualizer: React.FC<ReasoningVisualizerProps> = ({ projectId, onNodeClick }) => {
    const visJsRef = useRef<HTMLDivElement>(null);
    const [isLoading, setIsLoading] = useState(true);
    
    // ========================================================================
    //                          *** BUG FIX ***
    // Store the network instance in a ref, not state.
    // This prevents the "create/destroy" loop.
    //
    // ========================================================================
    const networkRef = useRef<VisNetwork | null>(null);

    useEffect(() => {
        let cancelled = false;

        const fetchDataAndRenderGraph = async () => {
            if (!visJsRef.current || !window.vis?.Network) {
                console.warn("vis.js is not loaded or the container ref is not set.");
                return;
            }
            setIsLoading(true);

            try {
                // We will load all data for the graph.
                // We call the Neo4j endpoint for class hierarchy and SPARQL for the rest.
                const [classesRes, individualsRes, propertiesRes] = await Promise.all([
                    // Use the Neo4j endpoint for the class hierarchy
                    apiClient.get<{ classes: OntologyClassNode[] }>(`/api/graph/${projectId}/all-classes-flat`), //
                    // Use the SPARQL endpoints for entity lists
                    apiClient.get<{ data: Individual[] }>(`/api/ontology/individuals/${projectId}`), //
                    apiClient.get<{ data: Property[] }>(`/api/ontology/properties/${projectId}`) //
                ]);

                if (cancelled) return;

                const classes: OntologyClassNode[] = classesRes.classes || [];
                const individuals: Individual[] = individualsRes.data || [];
                const properties: Property[] = propertiesRes.data || [];

                const nodes = [
                    ...classes.map(c => ({ 
                        id: c.iri, 
                        label: c.label, 
                        group: 'class',
                        title: `Class: ${c.label}`
                    })),
                    ...individuals.map(i => ({ 
                        id: i.id, 
                        label: i.label, 
                        group: 'individual',
                        title: `Individual: ${i.label}`
                    }))
                ];

                const allEdges = [
                    // SubClassOf relationships (from Neo4j 'parents' field)
                    ...classes.flatMap(c => 
                        (c.parents || []).map(parent => ({
                            from: c.iri,
                            to: parent.iri,
                            dashes: true,
                            title: 'subClassOf'
                        }))
                    ),
                    
                    // rdf:type relationships
                    ...individuals.flatMap(i => (i.types || []).map(typeIri => ({
                        from: i.id,
                        to: typeIri,
                        title: 'rdf:type'
                    }))),
                    
                    // Object Property relationships (simple heuristic)
                    ...properties
                        .filter(p => p.type === 'ObjectProperty' && p.domains && p.ranges)
                        .flatMap(p => 
                            individuals.flatMap(ind => 
                                (ind.types && p.domains?.some(d => ind.types?.includes(d)))
                                ? [{
                                    from: ind.id,
                                    // Link to the first individual found that matches the range
                                    to: individuals.find(i2 => i2.types?.includes(p.ranges![0]))?.id,
                                    label: p.label,
                                    title: `property: ${p.label}`
                                }]
                                : []
                            )
                        )
                ];

                // Filter out any edges that might have an undefined 'to' or 'from'
                const edges = allEdges.filter(e => e.from && e.to);

                const data = { nodes, edges };

                const options = {
                    nodes: {
                        shape: 'dot',
                        size: 20,
                        font: { size: 12, color: '#333' },
                        borderWidth: 2,
                    },
                    edges: {
                        width: 1,
                        color: { inherit: 'from' },
                        smooth: { type: 'continuous' }
                    },
                    groups: {
                        class: { color: { background: '#F5B700', border: '#F5B700' }, shape: 'ellipse' },
                        individual: { color: { background: '#8E44AD', border: '#8E44AD' }, shape: 'box' }
                    },
                    physics: {
                        enabled: true,
                        barnesHut: {
                            gravitationalConstant: -3000,
                        },
                    },
                    interaction: {
                        hover: true,
                        tooltipDelay: 200,
                    },
                };

                // --- BUG FIX ---
                // Destroy the *previous* network (if it exists) before creating a new one
                if (networkRef.current) {
                    networkRef.current.destroy();
                }

                const net = new window.vis.Network(visJsRef.current, data, options);
                net.on('click', (params) => {
                    if (params.nodes.length > 0) {
                        onNodeClick(params.nodes[0]);
                    }
                });
                
                // Store the new network instance in the ref
                networkRef.current = net;

            } catch (error) {
                console.error("Failed to load graph data:", error);
            } finally {
                if (!cancelled) {
                    setIsLoading(false);
                }
            }
        };

        fetchDataAndRenderGraph();

        return () => {
            cancelled = true;
            // Clean up the network when the component unmounts
            if (networkRef.current) {
                networkRef.current.destroy();
                networkRef.current = null;
            }
        };
    }, [projectId, onNodeClick]); // <-- Dependency array is stable (no 'network' state)

    return (
        <div className="h-full w-full relative">
            {isLoading && (
                <div className="absolute inset-0 bg-white/70 flex items-center justify-center z-10">
                    <Loader2 size={32} className="animate-spin text-purple-600" />
                    <span className="ml-2 text-gray-600">Loading graph...</span>
                </div>
            )}
            <div ref={visJsRef} className="h-full w-full" />
        </div>
    );
};

export default ReasoningVisualizer;
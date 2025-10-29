import React, { useEffect, useRef, useState } from 'react';
import { Loader2 } from 'lucide-react';
import type { TreeNode, Property, Individual } from '../types';
import apiClient from '../services/apiClient';

// Minimal interface for the vis.js Network instance to avoid using `any`.
interface VisNetwork {
    on(event: string, callback: (params: any) => void): void;
    destroy(): void;
}

// Add a declaration for the global `vis` object provided by the UMD script
declare global {
    interface Window {
        vis: {
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
    const [network, setNetwork] = useState<VisNetwork | null>(null);

    useEffect(() => {
        const fetchDataAndRenderGraph = async () => {
            if (!visJsRef.current || !window.vis?.Network) return;
            setIsLoading(true);

            try {
                const [classesRes, individualsRes, propertiesRes] = await Promise.all([
                    apiClient.get(`/api/ontology/all-classes?projectId=${projectId}`),
                    apiClient.get(`/api/ontology/individuals?projectId=${projectId}`),
                    apiClient.get(`/api/ontology/properties?projectId=${projectId}`)
                ]);

                const classes: (TreeNode & {parent?: string})[] = classesRes.data.classes || [];
                const individuals: Individual[] = individualsRes.data.data || [];
                const properties: Property[] = propertiesRes.data.data || [];

                const nodes = [
                    ...classes.map(c => ({ 
                        id: c.id, 
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
                    // SubClassOf relationships
                    ...classes.filter(c => c.parent).map(c => ({
                        from: c.id,
                        to: c.parent,
                        dashes: true,
                        title: 'subClassOf'
                    })),
                    // rdf:type relationships
                    ...individuals.flatMap(i => (i.types || []).map(typeIri => ({
                        from: i.id,
                        to: typeIri,
                        title: 'rdf:type'
                    }))),
                    // Object Property relationships
                    ...properties
                        .filter(p => p.type === 'ObjectProperty' && p.domains && p.ranges)
                        .flatMap(p => 
                            individuals.flatMap(ind => 
                                (ind.types && p.domains?.some(d => ind.types?.includes(d)))
                                ? [{
                                    from: ind.id,
                                    to: individuals.find(i2 => i2.types?.includes(p.ranges![0]))?.id, // Simple link for demo
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

                const net = new window.vis.Network(visJsRef.current, data, options);
                net.on('click', (params) => {
                    if (params.nodes.length > 0) {
                        onNodeClick(params.nodes[0]);
                    }
                });
                setNetwork(net);

            } catch (error) {
                console.error("Failed to load graph data:", error);
            } finally {
                setIsLoading(false);
            }
        };

        fetchDataAndRenderGraph();

        return () => {
            if (network) {
                network.destroy();
            }
        };
    }, [projectId, onNodeClick, network]);

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
import React, { useEffect, useState } from 'react';
import { BarChart3, TrendingUp, Package, GitBranch, Database, Users, FileText, AlertCircle, Loader2 } from 'lucide-react';
import apiClient from '../services/apiClient';
import type { OntologyStatistics } from '../types';

interface StatisticsPanelProps {
  projectId: string;
  statistics: OntologyStatistics | null;
}

const StatisticsPanel: React.FC<StatisticsPanelProps> = ({ projectId, statistics: initialStats }) => {
  const [statistics, setStatistics] = useState<OntologyStatistics | null>(initialStats);
  const [isLoading, setIsLoading] = useState(false);

  const loadStatistics = async () => {
    setIsLoading(true);
    try {

      const response = await apiClient.get<{ data: OntologyStatistics }>(`/api/ontology/statistics/${projectId}`);

      setStatistics(response.data);
    } catch (error) {
      console.error('Failed to load statistics:', error);
    } finally {
      setIsLoading(false);
    }
  };

  useEffect(() => {

    if (initialStats) {
      setStatistics(initialStats);
    } 
    // Otherwise, if no stats were passed but we have a project ID, fetch them
    else if (!initialStats && projectId) {
      loadStatistics();
    }
  }, [projectId, initialStats]); // Re-run if props change

  if (isLoading) {
    return (
      <div className="flex items-center justify-center h-full">
        <div className="text-center">
          <Loader2 size={48} className="animate-pulse text-purple-600 mx-auto mb-4" />
          <p className="text-gray-600">Loading statistics...</p>
        </div>
      </div>
    );
  }

  if (!statistics) {
    return (
      <div className="flex items-center justify-center h-full text-gray-600">
        <div className="text-center">
          <AlertCircle size={64} className="mx-auto mb-4 opacity-20" />
          <p className="text-lg font-medium">No statistics available</p>
        </div>
      </div>
    );
  }

  const StatCard: React.FC<{
    title: string;
    value: number;
    icon: React.ReactNode;
    color: string;
    description?: string;
  }> = ({ title, value, icon, color, description }) => (
    <div className="rounded-lg shadow-sm border p-6 hover:shadow-md transition-shadow" style={{ backgroundColor: 'var(--color-surface)', borderColor: 'var(--color-border)' }}>
      <div className="flex items-start justify-between mb-4">
        <div className={`p-3 rounded-lg ${color}`}>
          {icon}
        </div>
        <div className="text-right">
          <p className="text-sm font-medium" style={{ color: 'var(--color-text-secondary)' }}>{title}</p>
          <p className="text-3xl font-bold mt-1" style={{ color: 'var(--color-text)' }}>{value.toLocaleString()}</p>
        </div>
      </div>
      {description && (
        <p className="text-sm" style={{ color: 'var(--color-text-secondary)' }}>{description}</p>
      )}
    </div>
  );

  const ProgressBar: React.FC<{
    label: string;
    value: number;
    total: number;
    color: string;
  }> = ({ label, value, total, color }) => {
    const percentage = total > 0 ? (value / total) * 100 : 0;

    return (
      <div className="mb-4">
        <div className="flex justify-between text-sm mb-2">
          <span className="font-medium" style={{ color: 'var(--color-text)' }}>{label}</span>
          <span style={{ color: 'var(--color-text-secondary)' }}>{value.toLocaleString()} ({percentage.toFixed(1)}%)</span>
        </div>
        <div className="w-full rounded-full h-3 overflow-hidden" style={{ backgroundColor: 'var(--color-border)' }}>
          <div
            className={`h-full ${color} transition-all duration-500 ease-out`}
            style={{ width: `${Math.min(percentage, 100)}%` }}
          />
        </div>
      </div>
    );
  };

  const totalAxioms = statistics.axiomCount || 0;
  const logicalAxioms = statistics.logicalAxiomCount || 0;
  const annotationAxioms = totalAxioms - logicalAxioms;

  return (
    <div className="h-full overflow-y-auto" style={{ backgroundColor: 'var(--color-background)' }}>
      <header className="bg-gradient-to-r from-purple-600 to-indigo-600 text-white p-6 shadow-lg">
        <div className="flex items-center gap-3 mb-2">
          <BarChart3 size={32} />
          <h1 className="text-2xl font-bold">Ontology Statistics</h1>
        </div>
        <p className="text-purple-100">Overview and metrics for your ontology</p>
      </header>

      <div className="p-6 space-y-6">
        {}
        <section>
          <h2 className="text-lg font-semibold mb-4 flex items-center gap-2" style={{ color: 'var(--color-text)' }}>
            <Package size={20} className="text-purple-600" />
            Entity Counts
          </h2>
          <div className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-3 gap-4">
            <StatCard
              title="Classes"
              value={statistics.classCount || 0}
              icon={<Package size={24} className="text-amber-600" />}
              color="bg-amber-50"
              description="Total number of classes defined"
            />
            <StatCard
              title="Object Properties"
              value={statistics.objectPropertyCount || 0}
              icon={<GitBranch size={24} className="text-blue-600" />}
              color="bg-blue-50"
              description="Properties relating individuals"
            />
            <StatCard
              title="Data Properties"
              value={statistics.dataPropertyCount || 0}
              icon={<Database size={24} className="text-green-600" />}
              color="bg-green-50"
              description="Properties with literal values"
            />
            <StatCard
              title="Individuals"
              value={statistics.individualCount || 0}
              icon={<Users size={24} className="text-purple-600" />}
              color="bg-purple-50"
              description="Named instances in the ontology"
            />
            <StatCard
              title="Annotation Properties"
              value={statistics.annotationPropertyCount || 0}
              icon={<FileText size={24} className="text-orange-600" />}
              color="bg-orange-50"
              description="Metadata annotation properties"
            />
            <StatCard
              title="Total Axioms"
              value={statistics.axiomCount || 0}
              icon={<TrendingUp size={24} className="text-indigo-600" />}
              color="bg-indigo-50"
              description="All statements in the ontology"
            />
          </div>
        </section>

        {}
        <section className="bg-white rounded-lg shadow-sm border border-gray-200 p-6">
          <h2 className="text-lg font-semibold text-black mb-4 flex items-center gap-2">
            <TrendingUp size={20} className="text-purple-600" />
            Axiom Distribution
          </h2>

          <div className="space-y-4">
            <ProgressBar
              label="Logical Axioms"
              value={logicalAxioms}
              total={totalAxioms}
              color="bg-purple-600"
            />
            <ProgressBar
              label="Annotation Axioms"
              value={annotationAxioms}
              total={totalAxioms}
              color="bg-orange-600"
            />
          </div>

          <div className="grid grid-cols-2 gap-4 mt-6 pt-6 border-t border-gray-200">
            <div className="text-center">
              <p className="text-sm text-gray-600 mb-1">Logical Axioms</p>
              <p className="text-2xl font-bold text-purple-600">{logicalAxioms.toLocaleString()}</p>
            </div>
            <div className="text-center">
              <p className="text-sm text-gray-600 mb-1">Annotation Axioms</p>
              <p className="text-2xl font-bold text-orange-600">{annotationAxioms.toLocaleString()}</p>
            </div>
          </div>
        </section>

        {}
        <section className="bg-white rounded-lg shadow-sm border border-gray-200 p-6">
          <h2 className="text-lg font-semibold text-black mb-4">Axiom Type Breakdown</h2>

          <div className="grid grid-cols-1 md:grid-cols-2 gap-4">
            <div className="p-4 bg-gray-50 rounded-lg">
              <div className="flex justify-between items-center mb-2">
                <span className="text-sm font-medium text-black">Declaration Axioms</span>
                <span className="text-lg font-bold text-black">
                  {statistics.declarationAxiomCount || 0}
                </span>
              </div>
              <div className="text-xs text-gray-700">Entity declarations</div>
            </div>

            <div className="p-4 bg-gray-50 rounded-lg">
              <div className="flex justify-between items-center mb-2">
                <span className="text-sm font-medium text-black">SubClass Axioms</span>
                <span className="text-lg font-bold text-black">
                  {statistics.subClassOfAxiomCount || 0}
                </span>
              </div>
              <div className="text-xs text-gray-700">Class hierarchy relationships</div>
            </div>

            <div className="p-4 bg-gray-50 rounded-lg">
              <div className="flex justify-between items-center mb-2">
                <span className="text-sm font-medium text-black">Equivalent Classes</span>
                <span className="text-lg font-bold text-black">
                  {statistics.equivalentClassesAxiomCount || 0}
                </span>
              </div>
              <div className="text-xs text-gray-700">Class equivalence statements</div>
            </div>

            <div className="p-4 bg-gray-50 rounded-lg">
              <div className="flex justify-between items-center mb-2">
                <span className="text-sm font-medium text-black">Disjoint Classes</span>
                <span className="text-lg font-bold text-black">
                  {statistics.disjointClassesAxiomCount || 0}
                </span>
              </div>
              <div className="text-xs text-gray-700">Class disjointness axioms</div>
            </div>

            <div className="p-4 bg-gray-50 rounded-lg">
              <div className="flex justify-between items-center mb-2">
                <span className="text-sm font-medium text-black">General Class Inclusions (GCI)</span>
                <span className="text-lg font-bold text-black">
                  {statistics.gciCount || 0}
                </span>
              </div>
              <div className="text-xs text-gray-700">Complex class expressions</div>
            </div>

            <div className="p-4 bg-gray-50 rounded-lg">
              <div className="flex justify-between items-center mb-2">
                <span className="text-sm font-medium text-black">Hidden GCIs</span>
                <span className="text-lg font-bold text-black">
                  {statistics.hiddenGciCount || 0}
                </span>
              </div>
              <div className="text-xs text-gray-700">Implicit complex axioms</div>
            </div>
          </div>
        </section>

        {}
        <section className="bg-white rounded-lg shadow-sm border border-gray-200 p-6">
          <h2 className="text-lg font-semibold text-black mb-4">Ontology Metrics</h2>

          <div className="space-y-3">
            <div className="flex justify-between items-center p-3 bg-gray-50 rounded">
              <span className="text-sm text-black">Average Axioms per Class</span>
              <span className="font-semibold text-black">
                {statistics.classCount > 0 
                  ? (totalAxioms / statistics.classCount).toFixed(2)
                  : '0'
                }
              </span>
            </div>

            <div className="flex justify-between items-center p-3 bg-gray-50 rounded">
              <span className="text-sm text-black">Total Properties</span>
              <span className="font-semibold text-black">
                {(statistics.objectPropertyCount || 0) + (statistics.dataPropertyCount || 0)}
              </span>
            </div>

            <div className="flex justify-between items-center p-3 bg-gray-50 rounded">
              <span className="text-sm text-black">Individuals to Classes Ratio</span>
              <span className="font-semibold text-black">
                {statistics.classCount > 0
                  ? ((statistics.individualCount || 0) / statistics.classCount).toFixed(2)
                  : '0'
                }
              </span>
            </div>
          </div>
        </section>
      </div>
    </div>
  );
};

export default StatisticsPanel;
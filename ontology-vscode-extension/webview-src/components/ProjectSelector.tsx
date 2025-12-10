import React from 'react';
import { Loader2, CheckCircle2, XCircle } from 'lucide-react';

interface ProjectInfo {
  id: string;
  name: string;
  status?: string;
  statusMessage?: string;
  updatedAt?: string;
  filename?: string;
  progress?: number;
  metadata?: {
    counts?: {
      classes?: number;
      objectProperties?: number;
      dataProperties?: number;
      individuals?: number;
      triples?: number;
    };
  };
}

interface ProjectSelectorProps {
  projects: ProjectInfo[];
  onSelectProject: (projectId: string) => void;
  onClose?: () => void;
  importStatus?: { [projectId: string]: { type: string; status: string; progress?: number } };
}

export const ProjectSelector: React.FC<ProjectSelectorProps> = ({ projects, onSelectProject, onClose, importStatus = {} }) => {
  const formatDate = (dateStr?: string) => {
    if (!dateStr) return 'Unknown';
    try {
      return new Date(dateStr).toLocaleString();
    } catch {
      return dateStr;
    }
  };

  const getStatusDisplay = (project: ProjectInfo) => {
    // Check if there's an active import status for this project
    const activeImport = importStatus[project.id];
    const currentStatus = activeImport?.status || project.status;
    const progress = activeImport?.progress || project.progress;

    switch (currentStatus) {
      case 'COMPLETED':
        // Show READY badge for completed projects
        return (
          <div className="flex items-center gap-1.5">
            <CheckCircle2 size={14} className="text-green-600" />
            <span className="px-2 py-0.5 rounded-full text-xs font-medium bg-green-100 text-green-700">
              READY
            </span>
          </div>
        );
      case 'PROCESSING':
        return (
          <div className="flex items-center gap-2">
            <Loader2 size={14} className="animate-spin text-blue-600" />
            <div className="flex flex-col gap-1">
              <span className="px-2 py-0.5 rounded-full text-xs font-medium bg-blue-100 text-blue-800">
                PROCESSING
              </span>
              {progress !== undefined && progress > 0 && (
                <div className="w-32 bg-gray-200 rounded-full h-1.5">
                  <div
                    className="bg-blue-600 h-1.5 rounded-full transition-all duration-300"
                    style={{ width: `${Math.min(100, progress)}%` }}
                  />
                </div>
              )}
            </div>
          </div>
        );
      case 'ERROR':
      case 'FAILED':
        return (
          <div className="flex items-center gap-1.5">
            <XCircle size={14} className="text-red-600" />
            <span className="px-2 py-0.5 rounded-full text-xs font-medium bg-red-100 text-red-800">
              FAILED
            </span>
          </div>
        );
      case 'UPLOADED':
        return (
          <div className="flex items-center gap-1.5">
            <span className="px-2 py-0.5 rounded-full text-xs font-medium bg-green-100 text-green-700">
              READY
            </span>
          </div>
        );
      default:
        return null;
    }
  };

  return (
    <div className="fixed inset-0 bg-black bg-opacity-50 flex items-center justify-center z-50">
      <div className="bg-white rounded-lg shadow-xl max-w-3xl w-full max-h-[80vh] overflow-hidden">
        <div className="p-6 border-b">
          <div className="flex items-center justify-between">
            <h2 className="text-2xl font-bold text-gray-800">Select a Project</h2>
            {onClose && (
              <button
                onClick={onClose}
                className="text-gray-400 hover:text-gray-600 transition-colors"
                aria-label="Close"
              >
                <svg className="w-6 h-6" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                  <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M6 18L18 6M6 6l12 12" />
                </svg>
              </button>
            )}
          </div>
          <p className="text-gray-600 mt-2">Choose an ontology project to work with</p>
        </div>

        <div className="p-6 overflow-y-auto max-h-[60vh]">
          {projects.length === 0 ? (
            <div className="text-center py-12">
              <svg className="mx-auto h-12 w-12 text-gray-400" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M9 12h6m-6 4h6m2 5H7a2 2 0 01-2-2V5a2 2 0 012-2h5.586a1 1 0 01.707.293l5.414 5.414a1 1 0 01.293.707V19a2 2 0 01-2 2z" />
              </svg>
              <h3 className="mt-2 text-sm font-medium text-gray-900">No projects found</h3>
              <p className="mt-1 text-sm text-gray-500">Upload an ontology file to create a new project</p>
            </div>
          ) : (
            <div className="space-y-3">
              {projects.map((project) => (
                <button
                  key={project.id}
                  onClick={() => onSelectProject(project.id)}
                  className="w-full text-left p-4 border border-gray-200 rounded-lg hover:border-blue-500 hover:bg-blue-50 transition-all duration-200 group"
                >
                  <div className="flex items-start justify-between">
                    <div className="flex-1">
                      <h3 className="font-semibold text-gray-900 group-hover:text-blue-600 text-lg">
                        {project.name}
                      </h3>
                      {project.filename && (
                        <p className="text-sm text-gray-600 mt-1">
                          <span className="font-medium">File:</span> {project.filename}
                        </p>
                      )}
                      <div className="flex items-center gap-4 mt-2 text-sm text-gray-500">
                        <span>Updated: {formatDate(project.updatedAt)}</span>
                        {getStatusDisplay(project)}
                      </div>
                    </div>
                    <div className="ml-4 text-right">
                      {project.metadata && (
                        <div className="text-sm space-y-1">
                          {((project.metadata as any).classCount !== undefined || (project.metadata.counts?.classes !== undefined)) && (
                            <div className="text-gray-600">
                              <span className="font-medium">{(project.metadata as any).classCount || project.metadata.counts?.classes}</span> classes
                            </div>
                          )}
                          {((project.metadata as any).objectPropertyCount !== undefined || (project.metadata.counts?.objectProperties !== undefined)) && (
                            <div className="text-gray-600">
                              <span className="font-medium">{(project.metadata as any).objectPropertyCount || project.metadata.counts?.objectProperties}</span> obj props
                            </div>
                          )}
                          {((project.metadata as any).individualCount !== undefined || (project.metadata.counts?.individuals !== undefined)) && (
                            <div className="text-gray-600">
                              <span className="font-medium">{(project.metadata as any).individualCount || project.metadata.counts?.individuals}</span> individuals
                            </div>
                          )}
                          {((project.metadata as any).tripleCount !== undefined || (project.metadata.counts?.triples !== undefined)) && (
                            <div className="text-gray-500 text-xs mt-1">
                              {(project.metadata as any).tripleCount || project.metadata.counts?.triples} triples
                            </div>
                          )}
                        </div>
                      )}
                    </div>
                  </div>
                </button>
              ))}
            </div>
          )}
        </div>
      </div>
    </div>
  );
};

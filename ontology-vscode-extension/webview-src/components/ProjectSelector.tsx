import React from 'react';

interface ProjectInfo {
  id: string;
  name: string;
  status?: string;
  statusMessage?: string;
  updatedAt?: string;
  filename?: string;
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
}

export const ProjectSelector: React.FC<ProjectSelectorProps> = ({ projects, onSelectProject, onClose }) => {
  const formatDate = (dateStr?: string) => {
    if (!dateStr) return 'Unknown';
    try {
      return new Date(dateStr).toLocaleString();
    } catch {
      return dateStr;
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
                        {project.status && (
                          <span className={`px-2 py-1 rounded-full text-xs font-medium ${
                            project.status === 'COMPLETED' ? 'bg-green-100 text-green-800' :
                            project.status === 'PROCESSING' ? 'bg-blue-100 text-blue-800' :
                            project.status === 'FAILED' ? 'bg-red-100 text-red-800' :
                            'bg-gray-100 text-gray-800'
                          }`}>
                            {project.status}
                          </span>
                        )}
                      </div>
                    </div>
                    <div className="ml-4 text-right">
                      {project.metadata?.counts && (
                        <div className="text-sm space-y-1">
                          {project.metadata.counts.classes !== undefined && (
                            <div className="text-gray-600">
                              <span className="font-medium">{project.metadata.counts.classes}</span> classes
                            </div>
                          )}
                          {project.metadata.counts.objectProperties !== undefined && (
                            <div className="text-gray-600">
                              <span className="font-medium">{project.metadata.counts.objectProperties}</span> obj props
                            </div>
                          )}
                          {project.metadata.counts.individuals !== undefined && (
                            <div className="text-gray-600">
                              <span className="font-medium">{project.metadata.counts.individuals}</span> individuals
                            </div>
                          )}
                          {project.metadata.counts.triples !== undefined && (
                            <div className="text-gray-500 text-xs mt-1">
                              {project.metadata.counts.triples} triples
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

import React from 'react';
import { Loader2, CheckCircle2, XCircle, FolderOpen, Clock } from 'lucide-react';
import { importStageLabel } from '../utils/importStatusText';

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

interface ImportStatus {
  type: string;
  status: string;
  progress?: number;
  metadata?: Record<string, unknown>;
}

interface ProjectSelectorProps {
  projects: ProjectInfo[];
  onSelectProject: (projectId: string) => void;
  onClose?: () => void;
  importStatus?: { [projectId: string]: ImportStatus };
}

function stageLabel(metadata?: Record<string, unknown>): string {
  if (!metadata) return 'Processing…';
  if (typeof metadata.message === 'string' && metadata.message) {
    return importStageLabel(undefined, metadata.message);
  }
  if (typeof metadata.stage === 'string' && metadata.stage) {
    return importStageLabel(metadata.stage);
  }
  return 'Processing…';
}

function isImporting(s: ImportStatus): boolean {
  return s.type === 'IMPORT_STARTED' || s.type === 'IMPORT_PROGRESS';
}

function isFreshlyCompleted(s: ImportStatus): boolean {
  return s.type === 'IMPORT_COMPLETED';
}

export const ProjectSelector: React.FC<ProjectSelectorProps> = ({
  projects,
  onSelectProject,
  onClose,
  importStatus = {},
}) => {
  const formatDate = (dateStr?: string) => {
    if (!dateStr) return 'Unknown';
    try {
      return new Date(dateStr).toLocaleString();
    } catch {
      return dateStr;
    }
  };

  const renderCounts = (project: ProjectInfo) => {
    const m = project.metadata;
    if (!m) return null;
    const c = (m as any).classCount ?? m.counts?.classes;
    const o = (m as any).objectPropertyCount ?? m.counts?.objectProperties;
    const i = (m as any).individualCount ?? m.counts?.individuals;
    const t = (m as any).tripleCount ?? m.counts?.triples;
    if (c == null && o == null && i == null && t == null) return null;
    return (
      <div className="text-sm space-y-0.5">
        {c != null && <div className="text-gray-600"><span className="font-medium">{c}</span> classes</div>}
        {o != null && <div className="text-gray-600"><span className="font-medium">{o}</span> obj props</div>}
        {i != null && <div className="text-gray-600"><span className="font-medium">{i}</span> individuals</div>}
        {t != null && <div className="text-gray-500 text-xs mt-1">{t} triples</div>}
      </div>
    );
  };

  return (
    <div className="fixed inset-0 bg-black bg-opacity-50 flex items-center justify-center z-50">
      <div className="bg-white rounded-lg shadow-xl max-w-3xl w-full max-h-[85vh] flex flex-col">
        {/* Header */}
        <div className="p-6 border-b shrink-0">
          <div className="flex items-center justify-between">
            <h2 className="text-2xl font-bold text-gray-800">Project Library</h2>
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
          <p className="text-gray-600 mt-1 text-sm">
            {projects.some(p => isImporting(importStatus[p.id] ?? {} as ImportStatus))
              ? 'Import in progress — you can open other files while waiting'
              : 'Choose an ontology project to open'}
          </p>
        </div>

        {/* List */}
        <div className="p-6 overflow-y-auto flex-1">
          {projects.length === 0 ? (
            <div className="text-center py-12">
              <FolderOpen className="mx-auto h-12 w-12 text-gray-300" />
              <h3 className="mt-2 text-sm font-medium text-gray-900">No projects found</h3>
              <p className="mt-1 text-sm text-gray-500">Upload an ontology file to get started</p>
            </div>
          ) : (
            <div className="space-y-3">
              {projects.map((project) => {
                const ws = importStatus[project.id];
                const importing = ws ? isImporting(ws) : false;
                const freshDone = ws ? isFreshlyCompleted(ws) : false;
                const failed = ws?.status === 'ERROR' || ws?.status === 'FAILED' || project.status === 'ERROR' || project.status === 'FAILED';
                const progress = ws?.progress ?? project.progress ?? 0;
                const label = ws ? stageLabel(ws.metadata) : undefined;

                // Determine base MongoDB status for cards with no live WS event
                const mongoProcessing = !ws && (project.status === 'PROCESSING' || project.status === 'UPLOADED');

                const locked = importing || mongoProcessing;

                return (
                  <div
                    key={project.id}
                    className={`relative overflow-hidden border rounded-lg transition-all duration-200 ${
                      locked
                        ? 'border-blue-200 bg-blue-50 cursor-default'
                        : freshDone
                        ? 'border-green-300 bg-green-50 cursor-pointer hover:border-green-500 hover:bg-green-100 group'
                        : failed
                        ? 'border-red-200 bg-red-50 cursor-default'
                        : 'border-gray-200 bg-white cursor-pointer hover:border-blue-500 hover:bg-blue-50 group'
                    }`}
                    onClick={() => !locked && !failed && onSelectProject(project.id)}
                  >
                    {/* Progress bar — full-width bottom stripe */}
                    {(importing || mongoProcessing) && (
                      <div className="absolute bottom-0 left-0 right-0 h-1 bg-blue-100">
                        <div
                          className={`h-full bg-blue-500 transition-all duration-500 ${progress > 0 ? '' : 'animate-pulse w-1/3'}`}
                          style={progress > 0 ? { width: `${Math.min(100, progress)}%` } : {}}
                        />
                      </div>
                    )}

                    <div className="p-4">
                      <div className="flex items-start justify-between gap-4">
                        {/* Left: name + meta */}
                        <div className="flex-1 min-w-0">
                          <div className="flex items-center gap-2 flex-wrap">
                            <h3 className={`font-semibold text-lg truncate ${
                              locked ? 'text-blue-800' :
                              freshDone ? 'text-green-800 group-hover:text-green-900' :
                              failed ? 'text-red-700' :
                              'text-gray-900 group-hover:text-blue-700'
                            }`}>
                              {project.name}
                            </h3>

                            {/* Status badge */}
                            {importing && (
                              <span className="flex items-center gap-1 px-2 py-0.5 rounded-full text-xs font-medium bg-blue-100 text-blue-800 shrink-0">
                                <Loader2 size={11} className="animate-spin" />
                                {progress > 0 ? `${Math.round(progress)}%` : 'Importing…'}
                              </span>
                            )}
                            {mongoProcessing && (
                              <span className="flex items-center gap-1 px-2 py-0.5 rounded-full text-xs font-medium bg-blue-100 text-blue-700 shrink-0">
                                <Clock size={11} />
                                Processing
                              </span>
                            )}
                            {freshDone && (
                              <span className="flex items-center gap-1 px-2 py-0.5 rounded-full text-xs font-medium bg-green-100 text-green-700 shrink-0">
                                <CheckCircle2 size={11} />
                                Ready
                              </span>
                            )}
                            {failed && (
                              <span className="flex items-center gap-1 px-2 py-0.5 rounded-full text-xs font-medium bg-red-100 text-red-700 shrink-0">
                                <XCircle size={11} />
                                Failed
                              </span>
                            )}
                            {!importing && !mongoProcessing && !freshDone && !failed && project.status === 'COMPLETED' && (
                              <span className="flex items-center gap-1 px-2 py-0.5 rounded-full text-xs font-medium bg-gray-100 text-gray-600 shrink-0">
                                <CheckCircle2 size={11} className="text-green-500" />
                                Ready
                              </span>
                            )}
                          </div>

                          {project.filename && (
                            <p className="text-sm text-gray-500 mt-0.5 truncate">{project.filename}</p>
                          )}

                          {/* Stage message during import */}
                          {importing && label && (
                            <p className="text-xs text-blue-600 mt-1">{label}</p>
                          )}

                          <p className="text-xs text-gray-400 mt-1">Updated: {formatDate(project.updatedAt)}</p>
                        </div>

                        {/* Right: counts or CTA */}
                        <div className="shrink-0">
                          {freshDone ? (
                            <button
                              className="flex items-center gap-1.5 px-3 py-1.5 rounded-md text-sm font-medium bg-green-600 text-white hover:bg-green-700 transition-colors"
                              onClick={(e) => { e.stopPropagation(); onSelectProject(project.id); }}
                            >
                              <FolderOpen size={14} />
                              Open
                            </button>
                          ) : locked ? (
                            <div className="text-center">
                              {progress > 0 && (
                                <div className="text-lg font-bold text-blue-600">{Math.round(progress)}%</div>
                              )}
                            </div>
                          ) : (
                            renderCounts(project)
                          )}
                        </div>
                      </div>
                    </div>
                  </div>
                );
              })}
            </div>
          )}
        </div>
      </div>
    </div>
  );
};

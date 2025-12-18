import React, { useState, useEffect, useCallback } from 'react';
import { 
  GitBranch, History, AlertTriangle, CheckCircle, XCircle, 
  Users, MessageSquare, GitMerge, Clock, Filter, Search,
  GitCommit, Eye, ThumbsUp, ThumbsDown, Undo2, FileText, BarChart3,
  Bell, Activity, Lightbulb, Edit3, Save, RefreshCw, Zap, Info, X
} from 'lucide-react';
import ChangeTimeline from './components/ChangeTimeline';
import ChangeGraph from './components/ChangeGraph';
import ConflictResolver from './components/ConflictResolver';
import AuthorActivityChart from './components/AuthorActivityChart';

// Change types
type ChangeType = 'class' | 'property' | 'individual' | 'axiom' | 'annotation' | 'import';
type ChangeAction = 'added' | 'deleted' | 'modified';
type ChangeStatus = 'pending' | 'approved' | 'rejected' | 'conflicted' | 'draft';

interface OntologyChange {
  id: string;
  timestamp: Date;
  author: string;
  authorEmail: string;
  type: ChangeType;
  action: ChangeAction;
  status: ChangeStatus;
  entityUri: string;
  entityLabel: string;
  oldValue?: string;
  newValue?: string;
  description: string;
  commitId?: string;
  branch?: string;
  comments: ChangeComment[];
  conflicts?: ConflictInfo[];
  warnings?: ChangeWarning[];
  commentCount?: number;
  operationType?: string; // Original operation type for rollback (e.g., createObjectProperty, deleteDataProperty)
}

interface ChangeComment {
  id: string;
  author: string;
  timestamp: Date;
  text: string;
  resolved: boolean;
}

interface ConflictInfo {
  conflictType: 'concurrent_edit' | 'dependency' | 'constraint_violation';
  description: string;
  conflictingChangeId?: string;
  suggestedResolution?: string;
}

interface ChangeWarning {
  type: 'best_practice' | 'consistency' | 'naming' | 'structure';
  severity: 'info' | 'warning' | 'error';
  message: string;
  suggestion?: string;
}

interface ChangeStats {
  totalChanges: number;
  pendingChanges: number;
  approvedChanges: number;
  rejectedChanges: number;
  draftChanges: number;
  conflicts: number;
  activeAuthors: number;
  warnings: number;
}

interface LiveActivity {
  id: string;
  userId: string;
  username: string;
  action: string;
  entityLabel: string;
  timestamp: Date;
  isCurrentUser: boolean;
}

interface ChangeAssistantProps {
  projectId: string;
}

const ChangeAssistant: React.FC<ChangeAssistantProps> = ({ projectId }) => {
  const [changes, setChanges] = useState<OntologyChange[]>([]);
  const [draftChanges, setDraftChanges] = useState<OntologyChange[]>([]);
  const [liveActivity, setLiveActivity] = useState<LiveActivity[]>([]);
  const [stats, setStats] = useState<ChangeStats>({
    totalChanges: 0,
    pendingChanges: 0,
    approvedChanges: 0,
    rejectedChanges: 0,
    draftChanges: 0,
    conflicts: 0,
    activeAuthors: 0,
    warnings: 0
  });
  
  const [activeTab, setActiveTab] = useState<'live' | 'drafts' | 'changes' | 'conflicts' | 'history' | 'stats'>('live');
  const [filterType, setFilterType] = useState<ChangeType | 'all'>('all');
  const [filterStatus, setFilterStatus] = useState<ChangeStatus | 'all'>('all');
  const [searchQuery, setSearchQuery] = useState('');
  const [selectedChange, setSelectedChange] = useState<OntologyChange | null>(null);
  const [showCommentDialog, setShowCommentDialog] = useState(false);
  const [newComment, setNewComment] = useState('');
  const [selectedConflict, setSelectedConflict] = useState<any>(null);
  const [showConflictResolver, setShowConflictResolver] = useState(false);
  const [showDetailsDialog, setShowDetailsDialog] = useState(false);
  const [detailsLoading, setDetailsLoading] = useState(false);
  const [changeDetails, setChangeDetails] = useState<any>(null);
  const [isLoading, setIsLoading] = useState(false);
  const [lastRefresh, setLastRefresh] = useState<Date>(new Date());
  
  // Confirmation dialog state
  const [confirmDialog, setConfirmDialog] = useState<{
    isOpen: boolean;
    title: string;
    message: string;
    details?: string[];
    onConfirm: () => void;
  }>({ isOpen: false, title: '', message: '', onConfirm: () => {} });
  
  // Notification state
  const [notification, setNotification] = useState<{
    show: boolean;
    type: 'success' | 'error' | 'info';
    message: string;
  }>({ show: false, type: 'info', message: '' });
  
  const showNotification = (message: string, type: 'success' | 'error' | 'info' = 'info') => {
    setNotification({ show: true, type, message });
    setTimeout(() => setNotification(prev => ({ ...prev, show: false })), 4000);
  };

  // Load changes from backend
  useEffect(() => {
    loadChanges();
    loadDraftChanges();
    const interval = setInterval(() => {
      loadChanges();
      loadDraftChanges();
    }, 10000); // Refresh every 10 seconds
    return () => clearInterval(interval);
  }, [projectId]);

  // Listen for remote edit events
  useEffect(() => {
    const handleRemoteEdit = (event: CustomEvent) => {
      const detail = event.detail;
      if (detail && detail.projectId === projectId) {
        // Add to live activity
        const activity: LiveActivity = {
          id: `live-${Date.now()}`,
          userId: detail.userId || 'unknown',
          username: detail.username || 'Someone',
          action: detail.type || 'modified',
          entityLabel: detail.entityLabel || detail.iri || 'Unknown entity',
          timestamp: new Date(),
          isCurrentUser: false
        };
        setLiveActivity(prev => [activity, ...prev.slice(0, 19)]);
        
        // Refresh changes after remote edit
        setTimeout(() => loadChanges(), 500);
      }
    };

    window.addEventListener('remoteEditReceived', handleRemoteEdit as EventListener);
    return () => window.removeEventListener('remoteEditReceived', handleRemoteEdit as EventListener);
  }, [projectId]);

  const loadDraftChanges = async () => {
    try {
      const apiBase = (window as any).API_BASE_URL || 'http://localhost:8082';
      console.log('[ChangeAssistant] Loading drafts for projectId:', projectId);
      console.log('[ChangeAssistant] API_BASE_URL:', apiBase);
      
      const response = await fetch(`${apiBase}/api/ontology/${projectId}/drafts/stats`);
      console.log('[ChangeAssistant] Draft stats response status:', response.status);
      if (!response.ok) return;
      
      const data = await response.json();
      console.log('[ChangeAssistant] Draft stats:', data);
      
      // Also get draft list
      const draftsResponse = await fetch(`${apiBase}/api/ontology/${projectId}/drafts`);
      console.log('[ChangeAssistant] Drafts response status:', draftsResponse.status);
      if (draftsResponse.ok) {
        const draftsData = await draftsResponse.json();
        console.log('[ChangeAssistant] Drafts data:', draftsData);
        if (draftsData.drafts && Array.isArray(draftsData.drafts)) {
          console.log('[ChangeAssistant] Found', draftsData.drafts.length, 'drafts');
          const parsedDrafts = draftsData.drafts.map((draft: any) => ({
            id: draft.id || `draft-${Date.now()}-${Math.random()}`,
            timestamp: new Date(draft.timestamp),
            author: draft.username || 'You',
            authorEmail: draft.userId || '',
            type: mapOperationToType(draft.operationType),
            action: mapOperationToAction(draft.operationType),
            status: 'draft' as ChangeStatus,
            entityUri: draft.operationData?.iri || '',
            entityLabel: draft.operationData?.label || extractLabelFromIRI(draft.operationData?.iri),
            oldValue: draft.operationData?.oldValue,
            newValue: draft.operationData?.value || draft.operationData?.newValue,
            description: formatDraftDescription(draft),
            comments: [],
            conflicts: [],
            warnings: generateWarnings(draft)
          }));
          setDraftChanges(parsedDrafts);
        }
      }
    } catch (error) {
      console.error('Failed to load draft changes:', error);
    }
  };

  const mapOperationToType = (operationType: string): ChangeType => {
    if (!operationType) return 'axiom';
    const lower = operationType.toLowerCase();
    if (lower.includes('class')) return 'class';
    if (lower.includes('property')) return 'property';
    if (lower.includes('individual')) return 'individual';
    if (lower.includes('annotation')) return 'annotation';
    if (lower.includes('import')) return 'import';
    return 'axiom';
  };

  const mapOperationToAction = (operationType: string): ChangeAction => {
    if (!operationType) return 'modified';
    const lower = operationType.toLowerCase();
    if (lower.includes('create') || lower.includes('add')) return 'added';
    if (lower.includes('delete') || lower.includes('remove')) return 'deleted';
    return 'modified';
  };

  const formatDraftDescription = (draft: any): string => {
    const opType = draft.operationType || '';
    const label = draft.operationData?.label || 'entity';
    
    const descriptions: Record<string, string> = {
      'createClass': `Created class: ${label}`,
      'deleteClass': `Deleted class: ${label}`,
      'updateClassLabel': `Renamed class to: ${label}`,
      'addAnnotation': `Added annotation to: ${label}`,
      'updateAnnotation': `Updated annotation for: ${label}`,
      'deleteAnnotation': `Removed annotation from: ${label}`,
      'createObjectProperty': `Created object property: ${label}`,
      'createDataProperty': `Created data property: ${label}`,
      'createIndividual': `Created individual: ${label}`,
      'addSubClassOf': `Added subclass axiom for: ${label}`,
    };
    
    return descriptions[opType] || `${opType} operation on ${label}`;
  };

  const generateWarnings = (draft: any): ChangeWarning[] => {
    const warnings: ChangeWarning[] = [];
    const opType = draft.operationType || '';
    const label = draft.operationData?.label || '';
    
    // Naming convention warnings
    if (label && opType.includes('Class')) {
      if (label[0] !== label[0].toUpperCase()) {
        warnings.push({
          type: 'naming',
          severity: 'warning',
          message: 'Class names should start with an uppercase letter',
          suggestion: `Consider renaming to "${label[0].toUpperCase()}${label.slice(1)}"`
        });
      }
      if (label.includes(' ')) {
        warnings.push({
          type: 'naming',
          severity: 'info',
          message: 'Class names typically use CamelCase without spaces',
          suggestion: `Consider using "${label.replace(/\s+/g, '')}"`
        });
      }
    }
    
    if (label && opType.includes('Property')) {
      if (label[0] !== label[0].toLowerCase()) {
        warnings.push({
          type: 'naming',
          severity: 'warning',
          message: 'Property names should start with a lowercase letter',
          suggestion: `Consider renaming to "${label[0].toLowerCase()}${label.slice(1)}"`
        });
      }
    }
    
    // Structure warnings
    if (opType === 'deleteClass') {
      warnings.push({
        type: 'structure',
        severity: 'warning',
        message: 'Deleting a class may affect dependent axioms and individuals',
        suggestion: 'Review dependencies before confirming deletion'
      });
    }
    
    return warnings;
  };

  const loadChanges = async () => {
    setIsLoading(true);
    try {
      // Use MongoDB as single source of truth for change tracking
      const apiBase = (window as any).API_BASE_URL || 'http://localhost:8082';
      const url = `${apiBase}/api/ontology/${projectId}/changes/recent?count=100`;
      console.log('[ChangeAssistant] Loading changes from MongoDB:', url);
      
      const response = await fetch(url);
      const data = await response.json();
      
      console.log('[ChangeAssistant] Response:', data);
      console.log('[ChangeAssistant] Changes count:', data.changes?.length || 0);
      
      if (!data.success) {
        console.error('[ChangeAssistant] Failed to load changes:', data.error);
        setIsLoading(false);
        return;
      }
      
      // Convert MongoDB format to frontend format (single source - no sync needed)
      const parsedChanges = data.changes.map((change: any) => {
        // Preserve original operation type for accurate rollback
        console.log(change,"change")
        const originalOperationType = change.changeType || change.operationType || '';
        const parsed = {
          id: change.id,
          timestamp: new Date(change.timestamp),
          author: change.username || 'System',
          authorEmail: change.userId || '',
          type: mapChangeTypeFromGraphDB(originalOperationType, change.changeCategory || change.entityType),
          action: mapActionFromGraphDB(originalOperationType),
          status: (change.status?.toLowerCase() || 'approved') as ChangeStatus,
          entityUri: change.entityIRI,
          entityLabel: change.entityLabel || extractLabelFromIRI(change.entityIRI),
          oldValue: change.oldValue,
          newValue: change.newValue,
          description: change.description || `${originalOperationType}`,
          commitId: change.editId,
          branch: undefined,
          comments: [], // Comments loaded on-demand via details
          conflicts: change.hasConflict ? [{ conflictType: 'concurrent_edit' as const, description: 'Conflict detected' }] : [],
          warnings: [],
          commentCount: change.commentCount || 0,
          operationType: originalOperationType // Preserve for rollback
        };
        return parsed;
      });
      
      setChanges(parsedChanges);
      setLastRefresh(new Date());
      
      // Calculate stats including drafts
      const totalWarnings = [...parsedChanges, ...draftChanges]
        .reduce((sum, c) => sum + (c.warnings?.length || 0), 0);
      
      const newStats: ChangeStats = {
        totalChanges: parsedChanges.length,
        pendingChanges: parsedChanges.filter((c: any) => c.status === 'pending').length,
        approvedChanges: parsedChanges.filter((c: any) => c.status === 'approved').length,
        rejectedChanges: parsedChanges.filter((c: any) => c.status === 'rejected').length,
        draftChanges: draftChanges.length,
        conflicts: parsedChanges.filter((c: any) => c.conflicts?.length > 0).length,
        activeAuthors: new Set(parsedChanges.map((c: any) => c.author)).size,
        warnings: totalWarnings
      };
      setStats(newStats);
    } catch (error) {
      console.error('Failed to load changes:', error);
    } finally {
      setIsLoading(false);
    }
  };

  // Helper functions to map GraphDB data
  const mapChangeTypeFromGraphDB = (operationType: string, entityType: string): ChangeType => {
    const opLower = operationType?.toLowerCase() || '';
    const entityLower = entityType?.toLowerCase() || '';
    
    // Check operation type first
    if (opLower.includes('class')) return 'class';
    if (opLower.includes('property')) return 'property';
    if (opLower.includes('individual')) return 'individual';
    if (opLower.includes('annotation')) return 'annotation';
    
    // Then check entity type/category
    if (entityLower.includes('class')) return 'class';
    if (entityLower.includes('property')) return 'property';
    if (entityLower.includes('individual')) return 'individual';
    if (entityLower.includes('annotation')) return 'annotation';
    if (entityLower.includes('axiom')) return 'axiom';
    if (entityLower.includes('import')) return 'import';
    
    return 'axiom';
  };

  const mapActionFromGraphDB = (operationType: string): ChangeAction => {
    const lowerOp = operationType?.toLowerCase() || '';
    if (lowerOp.includes('add') || lowerOp.includes('create') || lowerOp.includes('insert')) return 'added';
    if (lowerOp.includes('delete') || lowerOp.includes('remove')) return 'deleted';
    if (lowerOp.includes('modify') || lowerOp.includes('update') || lowerOp.includes('change') || lowerOp.includes('rename')) return 'modified';
    return 'modified';
  };

  const extractLabelFromIRI = (iri: string): string => {
    if (!iri) return 'Unknown';
    const parts = iri.split(/[#/]/);
    return parts[parts.length - 1] || 'Unknown';
  };

  const getRelativeTime = (date: Date): string => {
    const now = new Date();
    const diffMs = now.getTime() - date.getTime();
    const diffSec = Math.floor(diffMs / 1000);
    const diffMin = Math.floor(diffSec / 60);
    const diffHour = Math.floor(diffMin / 60);
    const diffDay = Math.floor(diffHour / 24);
    
    if (diffSec < 60) return 'just now';
    if (diffMin < 60) return `${diffMin}m ago`;
    if (diffHour < 24) return `${diffHour}h ago`;
    if (diffDay < 7) return `${diffDay}d ago`;
    return date.toLocaleDateString();
  };

  const approveChange = async (changeId: string) => {
    try {
      const apiBase = (window as any).API_BASE_URL || 'http://localhost:8082';
      await fetch(`${apiBase}/api/ontology/${projectId}/changes/${changeId}/approve`, { method: 'POST' });
      loadChanges();
    } catch (error) {
      console.error('Failed to approve change:', error);
    }
  };

  const rejectChange = async (changeId: string) => {
    try {
      const apiBase = (window as any).API_BASE_URL || 'http://localhost:8082';
      await fetch(`${apiBase}/api/ontology/${projectId}/changes/${changeId}/reject`, { method: 'POST' });
      loadChanges();
    } catch (error) {
      console.error('Failed to reject change:', error);
    }
  };

  // Load change details with comments
  const loadChangeDetails = async (changeId: string) => {
    setDetailsLoading(true);
    setShowDetailsDialog(true);
    try {
      const apiBase = (window as any).API_BASE_URL || 'http://localhost:8082';
      const response = await fetch(`${apiBase}/api/ontology/${projectId}/changes/${changeId}/details`);
      const data = await response.json();
      
      if (data.success && data.change) {
        setChangeDetails(data.change);
      } else {
        // Fallback: use local change data
        const localChange = changes.find(c => c.id === changeId);
        if (localChange) {
          setChangeDetails({
            ...localChange,
            comments: localChange.comments || []
          });
        }
      }
    } catch (error) {
      console.error('Failed to load change details:', error);
      // Fallback to local data
      const localChange = changes.find(c => c.id === changeId);
      if (localChange) {
        setChangeDetails({
          ...localChange,
          comments: localChange.comments || []
        });
      }
    } finally {
      setDetailsLoading(false);
    }
  };

  // Add comment to a change
  const addCommentToChange = async (changeId: string, text: string) => {
    if (!text.trim()) return;
    
    try {
      // Get current user info from window or local storage
      const currentUser = (window as any).vscodeUser || JSON.parse(localStorage.getItem('user') || '{}');
      const userId = currentUser?.id || currentUser?.email || 'anonymous';
      const username = currentUser?.username || 'Anonymous';
      
      const apiBase = (window as any).API_BASE_URL || 'http://localhost:8082';
      const response = await fetch(`${apiBase}/api/ontology/${projectId}/changes/${changeId}/comments`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ 
          text,
          userId: userId,
          username: username
        })
      });
      
      const data = await response.json();
      if (data.success) {
        showNotification('Comment added successfully', 'success');
        // Reload details to show new comment
        loadChangeDetails(changeId);
      } else {
        showNotification('Failed to add comment: ' + (data.error || 'Unknown error'), 'error');
      }
    } catch (error) {
      console.error('Failed to add comment:', error);
      showNotification('Failed to add comment', 'error');
    }
  };

  const [rollbackLoading, setRollbackLoading] = useState<string | null>(null);
  const [rollbackError, setRollbackError] = useState<string | null>(null);

  const rollbackChange = (changeId: string, change: OntologyChange) => {
    // Show custom confirmation dialog
    const details = [
      `Entity: ${change.entityLabel}`,
      `Action: ${change.action}`,
    ];
    if (change.oldValue) details.push(`Old Value: ${change.oldValue}`);
    if (change.newValue) details.push(`New Value: ${change.newValue}`);
    
    setConfirmDialog({
      isOpen: true,
      title: 'Rollback Change',
      message: 'Are you sure you want to rollback this change? This will revert the change and apply the inverse operation.',
      details,
      onConfirm: () => executeRollback(changeId, change)
    });
  };

  const executeRollback = async (changeId: string, change: OntologyChange) => {
    setConfirmDialog(prev => ({ ...prev, isOpen: false }));
    setRollbackLoading(changeId);
    setRollbackError(null);
    
    // Validate required fields
    if (!change.entityUri) {
      showNotification('Cannot rollback: Entity IRI is missing', 'error');
      setRollbackLoading(null);
      return;
    }
    
    try {
      const apiBase = (window as any).API_BASE_URL || 'http://localhost:8082';
      // Use the original operation type for accurate rollback, fallback to generic type
      const rollbackChangeType = change.operationType || change.type;
      console.log('[Rollback] Executing rollback with:', {
        changeId,
        changeType: rollbackChangeType,
        originalOperationType: change.operationType,
        genericType: change.type,
        action: change.action,
        entityIRI: change.entityUri,
        entityLabel: change.entityLabel
      });
      
      // Get current user info
      const currentUser = (window as any).vscodeUser || JSON.parse(localStorage.getItem('user') || '{}');
      const userId = currentUser?.email || 'anonymous';
      const username = currentUser?.username || 'Anonymous';
      
      // Use a simpler endpoint that accepts changeId in the body instead of URL path
      const response = await fetch(`${apiBase}/api/ontology/${projectId}/changes/rollback`, { 
        method: 'POST',
        headers: {
          'Content-Type': 'application/json'
        },
        body: JSON.stringify({
          changeId: changeId,
          changeType: rollbackChangeType,
          action: change.action,
          entityIRI: change.entityUri,
          entityLabel: change.entityLabel,
          oldValue: change.oldValue,
          newValue: change.newValue,
          userId: userId,
          username: username
        })
      });
      
      const data = await response.json();
      
      if (data.success) {
        showNotification(data.message || 'Change rolled back successfully!', 'success');
        
        // Get current user info from window or local storage
        const currentUser = (window as any).vscodeUser || JSON.parse(localStorage.getItem('user') || '{}');
        const username = currentUser?.username || 'Unknown User';
        
        // Use the entityIRI from the response if provided (updated after rollback), otherwise use original
        const updatedEntityIRI = data.entityIRI || change.entityUri;
        const updatedEntityLabel = data.entityLabel || change.oldValue || change.entityLabel; // Use old value as new label for annotation changes
        
        console.log('[Rollback] Entity info after rollback:', {
          originalEntityIRI: change.entityUri,
          updatedEntityIRI: updatedEntityIRI,
          originalLabel: change.entityLabel,
          updatedLabel: updatedEntityLabel,
          oldValue: change.newValue,
          newValue: change.oldValue
        });
        
        // Dispatch event to notify other components about the rollback
        window.dispatchEvent(new CustomEvent('ontologyRollback', {
          detail: {
            projectId,
            changeId,
            entityIRI: updatedEntityIRI, // Use potentially updated IRI from backend
            entityLabel: updatedEntityLabel, // Use updated label (reverted to old value)
            action: change.action,
            entityType: change.type, // Include entity type for proper refresh
            username: username, // Who performed the rollback
            originalAuthor: change.author, // Who made the original change
            oldValue: change.newValue, // What we're rolling back FROM (was the new value)
            newValue: change.oldValue, // What we're rolling back TO (the original old value)
            success: true
          }
        }));
        
        // Increase delay to allow GraphDB to fully process the change before refreshing
        setTimeout(() => {
          loadChanges();
          loadDraftChanges();
        }, 1200);
      } else {
        setRollbackError(data.error || 'Failed to rollback change');
        showNotification('Failed to rollback: ' + (data.error || 'Unknown error'), 'error');
      }
    } catch (error) {
      console.error('Failed to rollback change:', error);
      setRollbackError('Network error occurred');
      showNotification('Failed to rollback: Network error', 'error');
    } finally {
      setRollbackLoading(null);
    }
  };

  const addComment = async () => {
    if (!selectedChange || !newComment.trim()) return;
    
    try {
      const apiBase = (window as any).API_BASE_URL || 'http://localhost:8082';
      await fetch(`${apiBase}/api/ontology/${projectId}/changes/${selectedChange.id}/comments`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ text: newComment })
      });
      setNewComment('');
      setShowCommentDialog(false);
      loadChanges();
    } catch (error) {
      console.error('Failed to add comment:', error);
    }
  };

  const resolveConflict = async (changeId: string, resolution: string) => {
    try {
      const apiBase = (window as any).API_BASE_URL || 'http://localhost:8082';
      await fetch(`${apiBase}/api/ontology/${projectId}/changes/${changeId}/resolve-conflict`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ resolution })
      });
      setShowConflictResolver(false);
      setSelectedConflict(null);
      loadChanges();
    } catch (error) {
      console.error('Failed to resolve conflict:', error);
    }
  };

  const handleConflictClick = (change: OntologyChange) => {
    if (change.conflicts && change.conflicts.length > 0) {
      setSelectedConflict({
        id: change.id,
        type: change.conflicts[0].conflictType,
        description: change.conflicts[0].description,
        localChange: change.newValue || '',
        remoteChange: change.oldValue || '',
        baseValue: change.oldValue
      });
      setShowConflictResolver(true);
    }
  };

  // Filter changes
  const filteredChanges = changes.filter(change => {
    if (filterType !== 'all' && change.type !== filterType) return false;
    if (filterStatus !== 'all' && change.status !== filterStatus) return false;
    if (searchQuery && !change.entityLabel.toLowerCase().includes(searchQuery.toLowerCase()) &&
        !change.description.toLowerCase().includes(searchQuery.toLowerCase())) return false;
    return true;
  });

  const getChangeIcon = (type: ChangeType) => {
    switch (type) {
      case 'class': return '🔷';
      case 'property': return '🔗';
      case 'individual': return '👤';
      case 'axiom': return '📐';
      case 'annotation': return '📝';
      case 'import': return '📦';
      default: return '📄';
    }
  };

  const getActionColor = (action: ChangeAction) => {
    switch (action) {
      case 'added': return 'text-green-600';
      case 'deleted': return 'text-red-600';
      case 'modified': return 'text-blue-600';
    }
  };

  const getStatusIcon = (status: ChangeStatus) => {
    switch (status) {
      case 'approved': return <CheckCircle className="w-4 h-4 text-green-600" />;
      case 'rejected': return <XCircle className="w-4 h-4 text-red-600" />;
      case 'conflicted': return <AlertTriangle className="w-4 h-4 text-orange-600" />;
      case 'pending': return <Clock className="w-4 h-4 text-gray-600" />;
    }
  };

  return (
    <div className="h-full flex flex-col bg-white">
      {/* Header */}
      <div className="border-b p-4">
        <div className="flex items-center justify-between mb-4">
          <div className="flex items-center gap-2">
            <GitBranch className="w-5 h-5 text-purple-600" />
            <h2 className="text-lg font-semibold">Change Assistant</h2>
            {isLoading && <RefreshCw className="w-4 h-4 text-gray-400 animate-spin" />}
          </div>
          <div className="flex items-center gap-2">
            <span className="text-xs text-gray-500">
              Updated {getRelativeTime(lastRefresh)}
            </span>
            <button
              onClick={() => { loadChanges(); loadDraftChanges(); }}
              disabled={isLoading}
              className="px-3 py-1 text-sm bg-purple-600 text-white rounded hover:bg-purple-700 disabled:opacity-50"
            >
              Refresh
            </button>
          </div>
        </div>

        {/* Stats Cards */}
        <div className="grid grid-cols-4 gap-2 mb-4">
          <div className="bg-yellow-50 p-2 rounded border border-yellow-200">
            <div className="text-xs text-yellow-600 flex items-center gap-1">
              <Edit3 className="w-3 h-3" />
              Drafts
            </div>
            <div className="text-xl font-bold text-yellow-700">{stats.draftChanges}</div>
          </div>
          <div className="bg-blue-50 p-2 rounded">
            <div className="text-xs text-blue-600 flex items-center gap-1">
              <Save className="w-3 h-3" />
              Saved
            </div>
            <div className="text-xl font-bold text-blue-700">{stats.totalChanges}</div>
          </div>
          <div className="bg-purple-50 p-2 rounded">
            <div className="text-xs text-purple-600 flex items-center gap-1">
              <Users className="w-3 h-3" />
              Authors
            </div>
            <div className="text-xl font-bold text-purple-700">{stats.activeAuthors}</div>
          </div>
          <div className="bg-orange-50 p-2 rounded">
            <div className="text-xs text-orange-600 flex items-center gap-1">
              <Lightbulb className="w-3 h-3" />
              Warnings
            </div>
            <div className="text-xl font-bold text-orange-700">{stats.warnings}</div>
          </div>
        </div>

        {/* Search and Filters */}
        <div className="space-y-2">
          <div className="relative">
            <Search className="absolute left-3 top-2.5 w-4 h-4 text-gray-400" />
            <input
              type="text"
              placeholder="Search changes..."
              value={searchQuery}
              onChange={(e) => setSearchQuery(e.target.value)}
              className="w-full pl-9 pr-3 py-2 border rounded text-sm"
            />
          </div>
          
          <div className="flex gap-2">
            <select
              value={filterType}
              onChange={(e) => setFilterType(e.target.value as ChangeType | 'all')}
              className="flex-1 px-2 py-1 border rounded text-sm"
            >
              <option value="all">All Types</option>
              <option value="class">Classes</option>
              <option value="property">Properties</option>
              <option value="individual">Individuals</option>
              <option value="axiom">Axioms</option>
              <option value="annotation">Annotations</option>
              <option value="import">Imports</option>
            </select>
            
            <select
              value={filterStatus}
              onChange={(e) => setFilterStatus(e.target.value as ChangeStatus | 'all')}
              className="flex-1 px-2 py-1 border rounded text-sm"
            >
              <option value="all">All Status</option>
              <option value="draft">Drafts</option>
              <option value="pending">Pending</option>
              <option value="approved">Approved</option>
              <option value="rejected">Rejected</option>
              <option value="conflicted">Conflicted</option>
            </select>
          </div>
        </div>
      </div>

      {/* Tabs */}
      <div className="border-b">
        <div className="flex overflow-x-auto">
          {[
            { id: 'live', label: 'Live', icon: Activity, count: liveActivity.length > 0 ? liveActivity.length : undefined },
            { id: 'drafts', label: 'Drafts', icon: Edit3, count: stats.draftChanges > 0 ? stats.draftChanges : undefined },
            { id: 'changes', label: 'Saved', icon: GitCommit, count: stats.totalChanges },
            { id: 'conflicts', label: 'Conflicts', icon: AlertTriangle, count: stats.conflicts > 0 ? stats.conflicts : undefined },
            { id: 'history', label: 'Timeline', icon: History },
            { id: 'stats', label: 'Stats', icon: BarChart3 }
          ].map(tab => (
            <button
              key={tab.id}
              onClick={() => setActiveTab(tab.id as any)}
              className={`flex items-center gap-2 px-4 py-2 border-b-2 transition-colors whitespace-nowrap ${
                activeTab === tab.id
                  ? 'border-purple-600 text-purple-600'
                  : 'border-transparent text-gray-600 hover:text-purple-600'
              }`}
            >
              <tab.icon className="w-4 h-4" />
              {tab.label}
              {tab.count !== undefined && tab.count > 0 && (
                <span className={`px-1.5 py-0.5 text-xs rounded-full ${
                  tab.id === 'drafts' ? 'bg-yellow-100 text-yellow-600' :
                  tab.id === 'conflicts' ? 'bg-red-100 text-red-600' :
                  'bg-purple-100 text-purple-600'
                }`}>
                  {tab.count}
                </span>
              )}
            </button>
          ))}
        </div>
      </div>

      {/* Content Area */}
      <div className="flex-1 overflow-auto p-4">
        {/* Live Activity Tab */}
        {activeTab === 'live' && (
          <div className="space-y-3">
            <div className="flex items-center gap-2 mb-4">
              <Activity className="w-5 h-5 text-green-500" />
              <h3 className="font-medium">Live Activity</h3>
              <span className="text-xs text-gray-500">Real-time updates from collaborators</span>
            </div>
            
            {liveActivity.length === 0 ? (
              <div className="text-center py-12 text-gray-500">
                <Activity className="w-12 h-12 mx-auto mb-3 opacity-30" />
                <p className="font-medium">No recent activity</p>
                <p className="text-sm mt-1">Live updates will appear here as collaborators make changes</p>
              </div>
            ) : (
              <div className="space-y-2">
                {liveActivity.map(activity => (
                  <div 
                    key={activity.id}
                    className={`flex items-start gap-3 p-3 rounded-lg border ${
                      activity.isCurrentUser ? 'bg-purple-50 border-purple-200' : 'bg-gray-50'
                    }`}
                  >
                    <div className={`w-8 h-8 rounded-full flex items-center justify-center text-white text-sm font-medium ${
                      activity.isCurrentUser ? 'bg-purple-500' : 'bg-blue-500'
                    }`}>
                      {activity.username[0].toUpperCase()}
                    </div>
                    <div className="flex-1">
                      <div className="flex items-center gap-2">
                        <span className="font-medium text-sm">{activity.username}</span>
                        <span className="text-xs text-gray-500">{getRelativeTime(activity.timestamp)}</span>
                      </div>
                      <p className="text-sm text-gray-700">
                        <span className="capitalize">{activity.action.replace(/([A-Z])/g, ' $1').trim()}</span>
                        {' '}<span className="font-medium">{activity.entityLabel}</span>
                      </p>
                    </div>
                    <Zap className="w-4 h-4 text-yellow-500" />
                  </div>
                ))}
              </div>
            )}
          </div>
        )}

        {/* Drafts Tab */}
        {activeTab === 'drafts' && (
          <div className="space-y-3">
            <div className="flex items-center justify-between mb-4">
              <div className="flex items-center gap-2">
                <Edit3 className="w-5 h-5 text-yellow-500" />
                <h3 className="font-medium">Pending Drafts</h3>
                <span className="text-xs text-gray-500">Changes not yet saved to database</span>
              </div>
              {draftChanges.length > 0 && (
                <span className="px-2 py-1 bg-yellow-100 text-yellow-700 text-xs rounded-full">
                  {draftChanges.length} unsaved
                </span>
              )}
            </div>

            {draftChanges.length === 0 ? (
              <div className="text-center py-12 text-gray-500">
                <CheckCircle className="w-12 h-12 mx-auto mb-3 text-green-500 opacity-50" />
                <p className="font-medium">No pending drafts</p>
                <p className="text-sm mt-1">All changes have been saved</p>
              </div>
            ) : (
              <div className="space-y-2">
                {draftChanges.map(draft => (
                  <div
                    key={draft.id}
                    className="border border-yellow-200 rounded-lg p-3 bg-yellow-50"
                  >
                    <div className="flex items-start gap-2">
                      <span className="text-2xl">{getChangeIcon(draft.type)}</span>
                      <div className="flex-1">
                        <div className="flex items-center gap-2">
                          <span className="font-medium">{draft.entityLabel}</span>
                          <span className={`text-sm ${getActionColor(draft.action)}`}>
                            {draft.action}
                          </span>
                          <span className="px-1.5 py-0.5 text-xs bg-yellow-200 text-yellow-700 rounded">
                            draft
                          </span>
                        </div>
                        <p className="text-sm text-gray-600 mt-1">{draft.description}</p>
                        
                        {/* Show diff for modifications */}
                        {draft.action === 'modified' && (draft.oldValue || draft.newValue) && (
                          <div className="mt-2 p-2 bg-white rounded border border-yellow-200">
                            <div className="text-xs text-gray-500 mb-1 font-medium">Value Change:</div>
                            <div className="flex items-center gap-2 text-sm">
                              {draft.oldValue && (
                                <span className="px-2 py-1 bg-red-100 text-red-700 rounded line-through">
                                  {draft.oldValue.length > 50 ? draft.oldValue.substring(0, 50) + '...' : draft.oldValue}
                                </span>
                              )}
                              {draft.oldValue && draft.newValue && (
                                <span className="text-gray-400">→</span>
                              )}
                              {draft.newValue && (
                                <span className="px-2 py-1 bg-green-100 text-green-700 rounded font-medium">
                                  {draft.newValue.length > 50 ? draft.newValue.substring(0, 50) + '...' : draft.newValue}
                                </span>
                              )}
                            </div>
                          </div>
                        )}
                        
                        {/* Show new value for additions */}
                        {draft.action === 'added' && draft.newValue && (
                          <div className="mt-2 p-2 bg-white rounded border border-yellow-200">
                            <div className="text-xs text-gray-500 mb-1 font-medium">New Value:</div>
                            <span className="text-sm px-2 py-1 bg-green-100 text-green-700 rounded">
                              {draft.newValue.length > 80 ? draft.newValue.substring(0, 80) + '...' : draft.newValue}
                            </span>
                          </div>
                        )}
                        
                        <div className="text-xs text-gray-500 mt-2">
                          {getRelativeTime(draft.timestamp)}
                        </div>
                      </div>
                    </div>

                    {/* Warnings for drafts */}
                    {draft.warnings && draft.warnings.length > 0 && (
                      <div className="mt-3 space-y-2">
                        {draft.warnings.map((warning, idx) => (
                          <div 
                            key={idx}
                            className={`p-2 rounded border ${
                              warning.severity === 'error' ? 'bg-red-50 border-red-200' :
                              warning.severity === 'warning' ? 'bg-orange-50 border-orange-200' :
                              'bg-blue-50 border-blue-200'
                            }`}
                          >
                            <div className="flex items-start gap-2">
                              {warning.severity === 'error' ? (
                                <XCircle className="w-4 h-4 text-red-500 flex-shrink-0 mt-0.5" />
                              ) : warning.severity === 'warning' ? (
                                <AlertTriangle className="w-4 h-4 text-orange-500 flex-shrink-0 mt-0.5" />
                              ) : (
                                <Info className="w-4 h-4 text-blue-500 flex-shrink-0 mt-0.5" />
                              )}
                              <div className="flex-1">
                                <p className="text-xs font-medium">{warning.message}</p>
                                {warning.suggestion && (
                                  <p className="text-xs text-gray-600 mt-1">
                                    <Lightbulb className="w-3 h-3 inline mr-1" />
                                    {warning.suggestion}
                                  </p>
                                )}
                              </div>
                            </div>
                          </div>
                        ))}
                      </div>
                    )}
                  </div>
                ))}
                
                <div className="mt-4 p-3 bg-gray-50 rounded-lg border border-gray-200">
                  <div className="flex items-center gap-2 text-sm text-gray-600">
                    <Info className="w-4 h-4" />
                    <span>Click <strong>Save</strong> in the editor to commit these changes to the database</span>
                  </div>
                </div>
              </div>
            )}
          </div>
        )}

        {activeTab === 'changes' && (
          <div className="space-y-2">
            {filteredChanges.length === 0 ? (
              <div className="text-center py-8 text-gray-500">
                <FileText className="w-12 h-12 mx-auto mb-2 opacity-50" />
                <p>No changes found</p>
              </div>
            ) : (
              filteredChanges.map(change => (
                <div
                  key={change.id}
                  className={`border rounded-lg p-3 hover:shadow-md transition-shadow ${
                    change.status === 'conflicted' ? 'border-orange-300 bg-orange-50' : ''
                  }`}
                >
                  <div className="flex items-start justify-between mb-2">
                    <div className="flex items-start gap-2 flex-1">
                      <span className="text-2xl">{getChangeIcon(change.type)}</span>
                      <div className="flex-1">
                        <div className="flex items-center gap-2">
                          <span className="font-medium">{change.entityLabel}</span>
                          <span className={`text-sm ${getActionColor(change.action)}`}>
                            {change.action}
                          </span>
                          {getStatusIcon(change.status)}
                        </div>
                        <p className="text-sm text-gray-600 mt-1">{change.description}</p>
                        <div className="flex items-center gap-3 mt-2 text-xs text-gray-500">
                          <span className="flex items-center gap-1">
                            <Users className="w-3 h-3" />
                            {change.author}
                          </span>
                          <span className="flex items-center gap-1">
                            <Clock className="w-3 h-3" />
                            {change.timestamp.toLocaleString()}
                          </span>
                          {change.branch && (
                            <span className="flex items-center gap-1">
                              <GitBranch className="w-3 h-3" />
                              {change.branch}
                            </span>
                          )}
                        </div>
                      </div>
                    </div>
                  </div>

                  {/* Conflict Info */}
                  {change.conflicts && change.conflicts.length > 0 && (
                    <div className="mt-2 p-2 bg-orange-100 rounded border border-orange-200">
                      <div className="flex items-center gap-2 text-orange-700 font-medium text-sm mb-1">
                        <AlertTriangle className="w-4 h-4" />
                        Conflicts Detected
                      </div>
                      {change.conflicts.map((conflict, idx) => (
                        <div key={idx} className="text-xs text-orange-600 ml-6">
                          {conflict.description}
                          {conflict.suggestedResolution && (
                            <div className="mt-1">
                              <button
                                onClick={() => resolveConflict(change.id, conflict.suggestedResolution!)}
                                className="text-orange-700 underline hover:text-orange-800"
                              >
                                Apply suggested resolution
                              </button>
                            </div>
                          )}
                        </div>
                      ))}
                    </div>
                  )}

                  {/* Diff View for modifications */}
                  {(change.oldValue || change.newValue) && (
                    <div className="mt-3 p-3 bg-gray-50 rounded-lg border">
                      <div className="text-xs text-gray-500 mb-2 font-medium flex items-center gap-1">
                        <GitBranch className="w-3 h-3" />
                        Value Change:
                      </div>
                      {change.action === 'modified' ? (
                        <div className="space-y-2">
                          {change.oldValue && (
                            <div className="flex items-start gap-2">
                              <span className="text-xs text-red-500 font-medium w-16">Before:</span>
                              <div className="flex-1 px-2 py-1 bg-red-50 text-red-700 rounded text-sm font-mono border border-red-200">
                                <span className="line-through">{change.oldValue}</span>
                              </div>
                            </div>
                          )}
                          {change.newValue && (
                            <div className="flex items-start gap-2">
                              <span className="text-xs text-green-500 font-medium w-16">After:</span>
                              <div className="flex-1 px-2 py-1 bg-green-50 text-green-700 rounded text-sm font-mono border border-green-200">
                                {change.newValue}
                              </div>
                            </div>
                          )}
                        </div>
                      ) : change.action === 'added' && change.newValue ? (
                        <div className="flex items-start gap-2">
                          <span className="text-xs text-green-500 font-medium w-16">Added:</span>
                          <div className="flex-1 px-2 py-1 bg-green-50 text-green-700 rounded text-sm font-mono border border-green-200">
                            {change.newValue}
                          </div>
                        </div>
                      ) : change.action === 'deleted' && change.oldValue ? (
                        <div className="flex items-start gap-2">
                          <span className="text-xs text-red-500 font-medium w-16">Deleted:</span>
                          <div className="flex-1 px-2 py-1 bg-red-50 text-red-700 rounded text-sm font-mono border border-red-200 line-through">
                            {change.oldValue}
                          </div>
                        </div>
                      ) : (
                        <div className="text-xs font-mono">
                          {change.oldValue && (
                            <div className="bg-red-50 text-red-700 p-2 rounded">
                              <span className="text-red-500">- </span>{change.oldValue}
                            </div>
                          )}
                          {change.newValue && (
                            <div className="bg-green-50 text-green-700 p-2 rounded mt-1">
                              <span className="text-green-500">+ </span>{change.newValue}
                            </div>
                          )}
                        </div>
                      )}
                    </div>
                  )}

                  {/* Comments */}
                  {change.comments.length > 0 && (
                    <div className="mt-2 space-y-1">
                      {change.comments.map(comment => (
                        <div key={comment.id} className="text-xs bg-gray-50 p-2 rounded">
                          <div className="flex items-center gap-2 text-gray-600 mb-1">
                            <MessageSquare className="w-3 h-3" />
                            <span className="font-medium">{comment.author}</span>
                            <span>{comment.timestamp.toLocaleString()}</span>
                          </div>
                          <p className="text-gray-700 ml-5">{comment.text}</p>
                        </div>
                      ))}
                    </div>
                  )}

                  {/* Action Buttons */}
                  <div className="flex gap-2 mt-3">
                    <button
                      onClick={() => {
                        setSelectedChange(change);
                        loadChangeDetails(change.id);
                      }}
                      className="flex items-center gap-1 px-3 py-1 text-sm border rounded hover:bg-gray-50"
                    >
                      <MessageSquare className="w-3 h-3" />
                      Comment
                      {(change.commentCount || 0) > 0 && (
                        <span className="px-1.5 py-0.5 text-xs bg-purple-100 text-purple-600 rounded-full">
                          {change.commentCount}
                        </span>
                      )}
                    </button>
                    <button
                      onClick={() => rollbackChange(change.id, change)}
                      disabled={rollbackLoading === change.id || !change.entityUri}
                      title={!change.entityUri ? 'Cannot rollback: Entity IRI is missing' : 'Rollback this change'}
                      className={`flex items-center gap-1 px-3 py-1 text-sm border border-orange-600 text-orange-600 rounded hover:bg-orange-50 ${
                        (rollbackLoading === change.id || !change.entityUri) ? 'opacity-50 cursor-not-allowed' : ''
                      }`}
                    >
                      {rollbackLoading === change.id ? (
                        <>
                          <RefreshCw className="w-3 h-3 animate-spin" />
                          Rolling back...
                        </>
                      ) : (
                        <>
                          <Undo2 className="w-3 h-3" />
                          Rollback
                        </>
                      )}
                    </button>
                    <button
                      onClick={() => {
                        setSelectedChange(change);
                        loadChangeDetails(change.id);
                      }}
                      className="flex items-center gap-1 px-3 py-1 text-sm border rounded hover:bg-gray-50"
                    >
                      <Eye className="w-3 h-3" />
                      Details
                    </button>
                  </div>
                </div>
              ))
            )}
          </div>
        )}

        {activeTab === 'conflicts' && (
          <div className="space-y-2">
            {filteredChanges.filter(c => c.status === 'conflicted').length === 0 ? (
              <div className="text-center py-12 text-gray-500">
                <CheckCircle className="w-12 h-12 mx-auto mb-3 text-green-500 opacity-50" />
                <p className="font-medium">No conflicts detected</p>
                <p className="text-sm mt-1">All changes are synchronized</p>
              </div>
            ) : (
              filteredChanges.filter(c => c.status === 'conflicted').map(change => (
                <div key={change.id} className="border border-orange-300 rounded-lg p-3 bg-orange-50">
                  <div className="flex items-center gap-2 mb-2">
                    <AlertTriangle className="w-5 h-5 text-orange-600" />
                    <h3 className="font-medium text-orange-900">{change.entityLabel}</h3>
                  </div>
                  <p className="text-sm text-orange-700 mb-2">{change.description}</p>
                  {change.conflicts?.map((conflict, idx) => (
                    <div key={idx} className="bg-white p-2 rounded border border-orange-200 mb-2">
                      <div className="text-sm font-medium text-orange-800 mb-1">
                        {conflict.conflictType.replace(/_/g, ' ').toUpperCase()}
                      </div>
                      <p className="text-sm text-gray-700 mb-2">{conflict.description}</p>
                      <div className="flex gap-2">
                        <button
                          onClick={() => handleConflictClick(change)}
                          className="px-3 py-1 text-sm bg-orange-600 text-white rounded hover:bg-orange-700 flex items-center gap-1"
                        >
                          <GitMerge className="w-3 h-3" />
                          Resolve Conflict
                        </button>
                        {conflict.suggestedResolution && (
                          <button
                            onClick={() => resolveConflict(change.id, conflict.suggestedResolution!)}
                            className="px-3 py-1 text-sm border border-orange-600 text-orange-600 rounded hover:bg-orange-50"
                          >
                            Auto-Resolve
                          </button>
                        )}
                      </div>
                    </div>
                  ))}
                </div>
              ))
            )}
          </div>
        )}

        {activeTab === 'history' && (
          <ChangeTimeline 
            changes={changes.map(c => ({
              id: c.id,
              timestamp: c.timestamp,
              author: c.author,
              type: c.type,
              action: c.action,
              entityLabel: c.entityLabel,
              description: c.description,
              status: c.status,
              hasConflict: (c.conflicts?.length || 0) > 0
            }))}
            onSelectChange={(id) => {
              const change = changes.find(c => c.id === id);
              if (change) {
                setSelectedChange(change);
                setShowCommentDialog(false);
              }
            }}
          />
        )}

        {activeTab === 'stats' && (
          <div className="space-y-4">
            <div className="grid grid-cols-2 gap-3">
              <div className="border rounded-lg p-3">
                <div className="text-sm text-gray-600 mb-1">Saved Changes</div>
                <div className="text-2xl font-bold text-purple-600">{stats.totalChanges}</div>
              </div>
              <div className="border rounded-lg p-3">
                <div className="text-sm text-gray-600 mb-1">Draft Changes</div>
                <div className="text-2xl font-bold text-yellow-600">{stats.draftChanges}</div>
              </div>
              <div className="border rounded-lg p-3">
                <div className="text-sm text-gray-600 mb-1">Active Authors</div>
                <div className="text-2xl font-bold text-blue-600">{stats.activeAuthors}</div>
              </div>
              <div className="border rounded-lg p-3">
                <div className="text-sm text-gray-600 mb-1">Warnings</div>
                <div className="text-2xl font-bold text-orange-600">{stats.warnings}</div>
              </div>
            </div>

            <div className="border rounded-lg p-3">
              <h3 className="font-medium mb-2">Change Distribution</h3>
              <div className="space-y-2">
                {['class', 'property', 'individual', 'axiom', 'annotation', 'import'].map(type => {
                  const count = changes.filter(c => c.type === type).length;
                  const percentage = stats.totalChanges > 0 ? (count / stats.totalChanges * 100) : 0;
                  return (
                    <div key={type}>
                      <div className="flex justify-between text-sm mb-1">
                        <span className="capitalize">{type}</span>
                        <span className="text-gray-600">{count}</span>
                      </div>
                      <div className="h-2 bg-gray-200 rounded-full overflow-hidden">
                        <div 
                          className="h-full bg-purple-600"
                          style={{ width: `${percentage}%` }}
                        ></div>
                      </div>
                    </div>
                  );
                })}
              </div>
            </div>

            {/* Activity Graph - Real data from changes */}
            <ChangeGraph
              data={(() => {
                // Group changes by day of week
                const dayNames = ['Sun', 'Mon', 'Tue', 'Wed', 'Thu', 'Fri', 'Sat'];
                const dayStats: { [key: string]: { additions: number; deletions: number; modifications: number } } = {};
                
                // Initialize all days
                dayNames.forEach(day => {
                  dayStats[day] = { additions: 0, deletions: 0, modifications: 0 };
                });
                
                // Aggregate changes by day
                [...changes, ...draftChanges].forEach(change => {
                  const dayOfWeek = dayNames[change.timestamp.getDay()];
                  if (change.action === 'added') dayStats[dayOfWeek].additions++;
                  else if (change.action === 'deleted') dayStats[dayOfWeek].deletions++;
                  else dayStats[dayOfWeek].modifications++;
                });
                
                // Reorder to start from Monday
                const orderedDays = ['Mon', 'Tue', 'Wed', 'Thu', 'Fri', 'Sat', 'Sun'];
                return {
                  labels: orderedDays,
                  additions: orderedDays.map(day => dayStats[day].additions),
                  deletions: orderedDays.map(day => dayStats[day].deletions),
                  modifications: orderedDays.map(day => dayStats[day].modifications)
                };
              })()}
            />

            {/* Author Activity */}
            <AuthorActivityChart
              data={(() => {
                const authorMap = new Map<string, { additions: number; deletions: number; modifications: number }>();
                changes.forEach(change => {
                  const existing = authorMap.get(change.author) || { additions: 0, deletions: 0, modifications: 0 };
                  if (change.action === 'added') existing.additions++;
                  else if (change.action === 'deleted') existing.deletions++;
                  else if (change.action === 'modified') existing.modifications++;
                  authorMap.set(change.author, existing);
                });
                return Array.from(authorMap.entries()).map(([author, stats]) => ({
                  author,
                  ...stats,
                  total: stats.additions + stats.deletions + stats.modifications
                })).sort((a, b) => b.total - a.total);
              })()}
            />
          </div>
        )}
      </div>

      {/* Conflict Resolver Dialog */}
      {showConflictResolver && selectedConflict && (
        <ConflictResolver
          conflict={selectedConflict}
          onResolve={(resolution) => resolveConflict(selectedConflict.id, resolution)}
          onCancel={() => {
            setShowConflictResolver(false);
            setSelectedConflict(null);
          }}
        />
      )}

      {/* Notification Toast */}
      {notification && (
        <div className={`fixed top-4 right-4 z-50 px-4 py-3 rounded-lg shadow-lg flex items-center gap-2 ${
          notification.type === 'success' ? 'bg-green-100 border border-green-400 text-green-800' :
          notification.type === 'error' ? 'bg-red-100 border border-red-400 text-red-800' :
          'd-none'
        }`}>
          {notification.type === 'success' && <CheckCircle size={18} />}
          {notification.type === 'error' && <AlertTriangle size={18} />}
          {/* {notification.type === 'info' && <Info size={18} />} */}
          <span className="text-sm">{notification.message}</span>
          {/* <button 
            onClick={() => setNotification({ show: false, type: 'info', message: '' })}
            className="ml-2 hover:opacity-70"
          >
            <X size={16} />
          </button> */}
        </div>
      )}

      {/* Confirmation Dialog */}
      {confirmDialog.isOpen && (
        <div className="fixed inset-0 bg-black bg-opacity-50 flex items-center justify-center z-50">
          <div className="bg-white rounded-lg p-4 w-96 shadow-xl">
            <h3 className="font-medium mb-3 flex items-center gap-2">
              <AlertTriangle size={20} className="text-amber-500" />
              {confirmDialog.title}
            </h3>
            <p className="text-sm text-gray-600 mb-4 whitespace-pre-line">{confirmDialog.message}</p>
            <div className="flex gap-2">
              <button
                onClick={() => {
                  confirmDialog.onConfirm();
                  setConfirmDialog({ isOpen: false, title: '', message: '', onConfirm: () => {} });
                }}
                className="flex-1 px-4 py-2 bg-red-600 text-white rounded hover:bg-red-700"
              >
                Confirm Rollback
              </button>
              <button
                onClick={() => setConfirmDialog({ isOpen: false, title: '', message: '', onConfirm: () => {} })}
                className="px-4 py-2 border rounded hover:bg-gray-50"
              >
                Cancel
              </button>
            </div>
          </div>
        </div>
      )}

      {/* Details Dialog */}
      {showDetailsDialog && (
        <div className="fixed inset-0 bg-black bg-opacity-50 flex items-center justify-center z-50">
          <div className="bg-white rounded-lg p-4 w-[500px] max-h-[80vh] overflow-hidden flex flex-col">
            <div className="flex items-center justify-between mb-3">
              <h3 className="font-medium flex items-center gap-2">
                <FileText className="w-5 h-5 text-purple-600" />
                Change Details
              </h3>
              <button
                onClick={() => {
                  setShowDetailsDialog(false);
                  setChangeDetails(null);
                }}
                className="text-gray-500 hover:text-gray-700"
              >
                <X className="w-5 h-5" />
              </button>
            </div>
            
            {detailsLoading ? (
              <div className="py-8 text-center text-gray-500">
                <RefreshCw className="w-6 h-6 animate-spin mx-auto mb-2" />
                <p>Loading details...</p>
              </div>
            ) : changeDetails ? (
              <div className="flex-1 overflow-auto space-y-4">
                {/* Change Info */}
                <div className="bg-gray-50 rounded-lg p-3 space-y-2">
                  <div className="flex items-center gap-2">
                    <span className="text-2xl">{getChangeIcon(changeDetails.entityType?.toLowerCase() || changeDetails.type || 'axiom')}</span>
                    <div>
                      <p className="font-medium">{changeDetails.entityLabel || 'Unknown Entity'}</p>
                      <p className="text-xs text-gray-500 font-mono truncate max-w-[350px]" title={changeDetails.entityIRI}>
                        {changeDetails.entityIRI}
                      </p>
                    </div>
                  </div>
                  
                  <div className="grid grid-cols-2 gap-2 text-sm">
                    <div>
                      <span className="text-gray-500">Action:</span>
                      <span className={`ml-2 capitalize ${
                        (changeDetails.operationType || changeDetails.action || '').toLowerCase().includes('add') ? 'text-green-600' :
                        (changeDetails.operationType || changeDetails.action || '').toLowerCase().includes('delete') ? 'text-red-600' :
                        'text-blue-600'
                      }`}>
                        {changeDetails.operationType || changeDetails.action || 'Unknown'}
                      </span>
                    </div>
                    <div>
                      <span className="text-gray-500">Type:</span>
                      <span className="ml-2 capitalize">{changeDetails.entityType || changeDetails.type || 'Unknown'}</span>
                    </div>
                    <div>
                      <span className="text-gray-500">Author:</span>
                      <span className="ml-2">{changeDetails.username || changeDetails.author || 'Unknown'}</span>
                    </div>
                    <div>
                      <span className="text-gray-500">Status:</span>
                      <span className={`ml-2 px-1.5 py-0.5 text-xs rounded ${
                        changeDetails.status === 'APPROVED' ? 'bg-green-100 text-green-700' :
                        changeDetails.status === 'REJECTED' ? 'bg-red-100 text-red-700' :
                        changeDetails.status === 'PENDING' ? 'bg-yellow-100 text-yellow-700' :
                        'bg-gray-100 text-gray-700'
                      }`}>
                        {changeDetails.status || 'approved'}
                      </span>
                    </div>
                  </div>
                  
                  <div className="text-xs text-gray-500 pt-1 border-t">
                    <Clock className="w-3 h-3 inline mr-1" />
                    {changeDetails.timestamp ? new Date(changeDetails.timestamp).toLocaleString() : 'Unknown time'}
                  </div>
                </div>
                
                {/* Value Changes */}
                {(changeDetails.oldValue || changeDetails.newValue) && (
                  <div className="bg-gray-50 rounded-lg p-3">
                    <h4 className="font-medium text-sm mb-2 flex items-center gap-1">
                      <GitBranch className="w-4 h-4" />
                      Value Changes
                    </h4>
                    <div className="space-y-2">
                      {changeDetails.oldValue && (
                        <div>
                          <span className="text-xs text-red-500 font-medium">Before:</span>
                          <div className="mt-1 px-2 py-1 bg-red-50 text-red-700 rounded text-sm font-mono border border-red-200 break-all">
                            {changeDetails.oldValue}
                          </div>
                        </div>
                      )}
                      {changeDetails.newValue && (
                        <div>
                          <span className="text-xs text-green-500 font-medium">After:</span>
                          <div className="mt-1 px-2 py-1 bg-green-50 text-green-700 rounded text-sm font-mono border border-green-200 break-all">
                            {changeDetails.newValue}
                          </div>
                        </div>
                      )}
                    </div>
                  </div>
                )}
                
                {/* Description */}
                {changeDetails.description && (
                  <div className="bg-gray-50 rounded-lg p-3">
                    <h4 className="font-medium text-sm mb-1">Description</h4>
                    <p className="text-sm text-gray-600">{changeDetails.description}</p>
                  </div>
                )}
                
                {/* Comments Section */}
                <div className="bg-gray-50 rounded-lg p-3">
                  <h4 className="font-medium text-sm mb-2 flex items-center gap-1">
                    <MessageSquare className="w-4 h-4" />
                    Comments ({Array.isArray(changeDetails.comments) ? changeDetails.comments.length : 0})
                  </h4>
                  
                  {/* Existing Comments */}
                  {Array.isArray(changeDetails.comments) && changeDetails.comments.length > 0 ? (
                    <div className="space-y-2 mb-3 max-h-40 overflow-y-auto">
                      {changeDetails.comments.map((comment: any, idx: number) => (
                        <div key={comment.id || idx} className="bg-white p-2 rounded border text-sm">
                          <div className="flex items-center gap-2 text-gray-600 mb-1">
                            <span className="font-medium">{comment.username || 'User'}</span>
                            <span className="text-xs">
                              {comment.timestamp ? new Date(comment.timestamp).toLocaleString() : ''}
                            </span>
                          </div>
                          <p className="text-gray-700">{comment.text}</p>
                        </div>
                      ))}
                    </div>
                  ) : (
                    <p className="text-sm text-gray-500 mb-3">No comments yet</p>
                  )}
                  
                  {/* Add Comment */}
                  <div className="border-t pt-2">
                    <textarea
                      value={newComment}
                      onChange={(e) => setNewComment(e.target.value)}
                      placeholder="Add a comment..."
                      className="w-full border rounded p-2 text-sm h-16 resize-none bg-white text-black"
                    />
                    <button
                      onClick={() => {
                        if (changeDetails.id && newComment.trim()) {
                          addCommentToChange(changeDetails.id, newComment);
                          setNewComment('');
                        }
                      }}
                      disabled={!newComment.trim()}
                      className="mt-2 px-3 py-1 text-sm bg-purple-600 text-white rounded hover:bg-purple-700 disabled:opacity-50 disabled:cursor-not-allowed"
                    >
                      Add Comment
                    </button>
                  </div>
                </div>
              </div>
            ) : (
              <div className="py-8 text-center text-gray-500">
                <p>No details available</p>
              </div>
            )}
            
            <div className="mt-4 pt-3 border-t flex justify-end">
              <button
                onClick={() => {
                  setShowDetailsDialog(false);
                  setChangeDetails(null);
                }}
                className="px-4 py-2 border rounded hover:bg-gray-50"
              >
                Close
              </button>
            </div>
          </div>
        </div>
      )}

      {/* Comment Dialog */}
      {showCommentDialog && (
        <div className="fixed inset-0 bg-black bg-opacity-50 flex items-center justify-center z-50">
          <div className="bg-white rounded-lg p-4 w-96">
            <h3 className="font-medium mb-3">Add Comment</h3>
            <textarea
              value={newComment}
              onChange={(e) => setNewComment(e.target.value)}
              placeholder="Enter your comment..."
              className="w-full border rounded p-2 text-sm h-24 resize-none bg-white text-black"
            />
            <div className="flex gap-2 mt-3">
              <button
                onClick={addComment}
                className="flex-1 px-4 py-2 bg-purple-600 text-white rounded hover:bg-purple-700"
              >
                Add Comment
              </button>
              <button
                onClick={() => {
                  setShowCommentDialog(false);
                  setNewComment('');
                }}
                className="px-4 py-2 border rounded hover:bg-gray-50"
              >
                Cancel
              </button>
            </div>
          </div>
        </div>
      )}
    </div>
  );
};

export default ChangeAssistant;

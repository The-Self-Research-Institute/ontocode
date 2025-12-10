import React, { useState, useEffect } from 'react';
import { X, Link as LinkIcon, Mail, Trash2, Copy, Check, Loader2 } from 'lucide-react';
import apiClient from '../services/apiClient';

interface ShareDialogProps {
  isOpen: boolean;
  onClose: () => void;
  projectId: string;
  userEmail: string;
}

interface ProjectShare {
  id: string;
  projectId: string;
  ownerEmail: string;
  sharedWithEmails: string[];
  shareLink: string;
  permission: string;
  createdAt: string;
  updatedAt: string;
}

const ShareDialog: React.FC<ShareDialogProps> = ({ isOpen, onClose, projectId, userEmail }) => {
  const [shareData, setShareData] = useState<ProjectShare | null>(null);
  const [newEmail, setNewEmail] = useState('');
  const [selectedPermission, setSelectedPermission] = useState<'view' | 'edit'>('view');
  const [isLoading, setIsLoading] = useState(false);
  const [isCopied, setIsCopied] = useState(false);
  const [error, setError] = useState('');

  useEffect(() => {
    if (isOpen && projectId) {
      fetchShareData();
    }
  }, [isOpen, projectId]);

  const fetchShareData = async () => {
    setIsLoading(true);
    setError('');
    try {
      const response = await apiClient.get(`/api/shares/project/${projectId}`);
      console.log('[ShareDialog] Fetch response:', response);
      
      if (response && response.share) {
        setShareData(response.share);
      } else if (response && response.success === false) {
        // Share doesn't exist, create new one
        console.log('[ShareDialog] No share found, creating new one');
        await createShare();
      } else {
        // Share doesn't exist, create new one
        console.log('[ShareDialog] No share found, creating new one');
        await createShare();
      }
    } catch (err: any) {
      console.error('[ShareDialog] Failed to fetch share data:', err);
      // If share doesn't exist (404, 500, or any error), create it
      // Backend may return 500 if share record doesn't exist
      if (err?.status === 404 || err?.status === 500 || err?.message?.includes('not found')) {
        console.log('[ShareDialog] Share not found or error occurred, creating new one');
        await createShare();
      } else {
        setError('Failed to load share data: ' + (err?.message || 'Unknown error'));
      }
    } finally {
      setIsLoading(false);
    }
  };

  const createShare = async () => {
    setError('');
    try {
      console.log('[ShareDialog] Creating share for project:', projectId, 'owner:', userEmail);
      const response = await apiClient.post('/api/shares/create', {
        projectId,
        ownerEmail: userEmail,
        permission: 'view'
      });
      console.log('[ShareDialog] Create response:', response);
      
      if (response && response.share) {
        setShareData(response.share);
      } else {
        throw new Error('Failed to create share: Invalid response');
      }
    } catch (err: any) {
      console.error('[ShareDialog] Failed to create share:', err);
      const errorMsg = err?.response?.status === 500 
        ? 'Server error: Please ensure backend services are running'
        : (err?.message || 'Unknown error');
      setError('Failed to create share link: ' + errorMsg);
    }
  };

  const addEmail = async () => {
    if (!newEmail || !shareData) return;

    // Basic email validation
    if (!/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(newEmail)) {
      setError('Please enter a valid email address');
      return;
    }

    setIsLoading(true);
    setError('');
    
    try {
      console.log('[ShareDialog] Adding email:', newEmail, 'with permission:', selectedPermission);
      const response = await apiClient.post('/api/shares/add-email', {
        projectId,
        email: newEmail,
        permission: selectedPermission
      });
      console.log('[ShareDialog] Add email response:', response);
      
      if (response && response.share) {
        setShareData(response.share);
        setNewEmail('');
        setSelectedPermission('view'); // Reset to default
      } else {
        throw new Error('Failed to add email: Invalid response');
      }
    } catch (err: any) {
      console.error('[ShareDialog] Failed to add email:', err);
      setError('Failed to add email access: ' + (err?.message || 'Unknown error'));
    } finally {
      setIsLoading(false);
    }
  };

  const removeEmail = async (email: string) => {
    if (!shareData) return;

    setIsLoading(true);
    setError('');
    
    try {
      console.log('[ShareDialog] Removing email:', email);
      const response = await apiClient.post('/api/shares/remove-email', {
        projectId,
        email
      });
      console.log('[ShareDialog] Remove email response:', response);
      
      if (response && response.share) {
        setShareData(response.share);
      } else {
        throw new Error('Failed to remove email: Invalid response');
      }
    } catch (err: any) {
      console.error('[ShareDialog] Failed to remove email:', err);
      setError('Failed to remove email access: ' + (err?.message || 'Unknown error'));
    } finally {
      setIsLoading(false);
    }
  };

  const copyLink = () => {
    if (!shareData) return;
    
    const fullLink = `${window.location.origin}/share/${shareData.shareLink}`;
    navigator.clipboard.writeText(fullLink).then(() => {
      setIsCopied(true);
      setTimeout(() => setIsCopied(false), 2000);
    });
  };

  if (!isOpen) return null;

  return (
    <div className="fixed inset-0 bg-black bg-opacity-50 flex items-center justify-center z-50" onClick={onClose}>
      <div className="bg-white rounded-lg shadow-xl p-6 max-w-md w-full mx-4" onClick={e => e.stopPropagation()}>
        <div className="flex justify-between items-center mb-4">
          <h3 className="text-lg font-semibold text-gray-900">Share File</h3>
          <button onClick={onClose} className="text-gray-400 hover:text-gray-600">
            <X size={20} />
          </button>
        </div>

        {isLoading && !shareData ? (
          <div className="flex items-center justify-center py-8">
            <Loader2 className="animate-spin text-purple-600" size={32} />
          </div>
        ) : (
          <>
            {/* Add Email Section */}
            <div className="mb-6">
              <label className="block text-sm font-medium text-gray-700 mb-2">
                <Mail size={14} className="inline mr-1" />
                Share with specific people
              </label>
              <div className="flex gap-2 mb-2">
                <input
                  type="email"
                  value={newEmail}
                  onChange={(e) => setNewEmail(e.target.value)}
                  onKeyPress={(e) => e.key === 'Enter' && addEmail()}
                  placeholder="Enter email address"
                  className="flex-1 px-3 py-2 border border-gray-300 rounded-md text-sm focus:ring-2 focus:ring-purple-500 text-black"
                />
                <select
                  value={selectedPermission}
                  onChange={(e) => setSelectedPermission(e.target.value as 'view' | 'edit')}
                  className="px-3 py-2 border border-gray-300 rounded-md text-sm focus:ring-2 focus:ring-purple-500 bg-white"
                >
                  <option value="view">Can View</option>
                  <option value="edit">Can Edit</option>
                </select>
                <button
                  onClick={addEmail}
                  disabled={isLoading || !newEmail}
                  className="px-4 py-2 bg-purple-600 text-white rounded-md hover:bg-purple-700 disabled:opacity-50 disabled:cursor-not-allowed whitespace-nowrap"
                >
                  Add
                </button>
              </div>
              <p className="text-xs text-gray-500">
                {selectedPermission === 'edit' ? 'Users can view and edit the file' : 'Users can only view the file'}
              </p>
            </div>

            {/* Shared With List */}
            {shareData && shareData.sharedWithEmails.length > 0 && (
              <div className="mb-4">
                <label className="block text-sm font-medium text-gray-700 mb-2">
                  Shared with ({shareData.sharedWithEmails.length})
                </label>
                <div className="max-h-40 overflow-y-auto space-y-2">
                  {shareData.sharedWithEmails.map((email) => (
                    <div key={email} className="flex justify-between items-center p-2 bg-gray-50 rounded-md">
                      <span className="text-sm text-gray-700">{email}</span>
                      <button
                        onClick={() => removeEmail(email)}
                        disabled={isLoading}
                        className="text-red-500 hover:text-red-700 disabled:opacity-50"
                        title="Remove access"
                      >
                        <Trash2 size={14} />
                      </button>
                    </div>
                  ))}
                </div>
              </div>
            )}

            {/* Error Message */}
            {error && (
              <div className="mb-4 p-3 bg-red-50 border border-red-200 rounded-md">
                <p className="text-sm text-red-600">{error}</p>
              </div>
            )}

            {/* Close Button */}
            <div className="flex justify-end">
              <button
                onClick={onClose}
                className="px-4 py-2 bg-gray-200 text-gray-700 rounded-md hover:bg-gray-300"
              >
                Done
              </button>
            </div>
          </>
        )}
      </div>
    </div>
  );
};

export default ShareDialog;

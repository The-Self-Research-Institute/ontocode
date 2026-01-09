import React, { useState, useEffect } from 'react';
import { X, Users, Check } from 'lucide-react';
import apiClient from '../services/apiClient';
import { useAuth } from '../custom-hook/useAuth';

interface CreateProjectModalProps {
    isOpen: boolean;
    onClose: () => void;
    onSuccess: () => void;
}

interface WorkspaceMember {
    userId: string;
    username: string;
    email: string;
}

const CreateProjectModal: React.FC<CreateProjectModalProps> = ({ isOpen, onClose, onSuccess }) => {
    const { user } = useAuth();
    const [projectName, setProjectName] = useState('');
    const [description, setDescription] = useState('');
    const [shareWith, setShareWith] = useState<'none' | 'all' | 'specific'>('none');
    const [workspaceMembers, setWorkspaceMembers] = useState<WorkspaceMember[]>([]);
    const [selectedMembers, setSelectedMembers] = useState<string[]>([]);
    const [creating, setCreating] = useState(false);

    useEffect(() => {
        if (isOpen && shareWith === 'specific') {
            loadWorkspaceMembers();
        }
    }, [isOpen, shareWith]);

    const loadWorkspaceMembers = async () => {
        try {
            // Get workspace members from workspace endpoint
            const response = await apiClient.get(`/api/workspaces/${user?.workspaceId}`);
            const workspace = response?.data?.workspace || response?.data;
            setWorkspaceMembers(workspace?.members || []);
        } catch (error) {
            console.error('Error loading workspace members:', error);
        }
    };

    const handleSubmit = async (e: React.FormEvent) => {
        e.preventDefault();
        
        if (!projectName.trim()) return;
        
        try {
            setCreating(true);
            
            await apiClient.post('/api/projects', {
                workspaceId: user?.workspaceId || 'default',
                name: projectName.trim(),
                description: description.trim(),
                shareWith: shareWith,
                memberUsernames: shareWith === 'specific' ? selectedMembers : undefined
            });
            
            setProjectName('');
            setDescription('');
            setShareWith('none');
            setSelectedMembers([]);
            onSuccess();
            onClose();
            
        } catch (error) {
            console.error('Error creating project:', error);
            alert('Failed to create project');
        } finally {
            setCreating(false);
        }
    };

    const toggleMember = (username: string) => {
        setSelectedMembers(prev =>
            prev.includes(username)
                ? prev.filter(m => m !== username)
                : [...prev, username]
        );
    };

    if (!isOpen) return null;

    return (
        <div className="fixed inset-0 bg-black bg-opacity-50 flex items-center justify-center z-50 p-4">
            <div className="bg-white rounded-xl shadow-xl max-w-2xl w-full max-h-[90vh] overflow-y-auto">
                <div className="flex items-center justify-between p-6 border-b border-gray-200">
                    <h2 className="text-2xl font-bold text-gray-900">Create New Project</h2>
                    <button
                        onClick={onClose}
                        className="p-2 hover:bg-gray-100 rounded-lg transition-colors"
                    >
                        <X size={20} />
                    </button>
                </div>

                <form onSubmit={handleSubmit} className="p-6 space-y-6">
                    <div>
                        <label className="block text-sm font-medium text-gray-700 mb-2">
                            Project Name *
                        </label>
                        <input
                            type="text"
                            value={projectName}
                            onChange={(e) => setProjectName(e.target.value)}
                            placeholder="Enter project name"
                            className="w-full px-4 py-2 border border-gray-300 rounded-lg focus:ring-2 focus:ring-purple-500 focus:border-transparent"
                            required
                        />
                    </div>

                    <div>
                        <label className="block text-sm font-medium text-gray-700 mb-2">
                            Description
                        </label>
                        <textarea
                            value={description}
                            onChange={(e) => setDescription(e.target.value)}
                            placeholder="Enter project description"
                            rows={3}
                            className="w-full px-4 py-2 border border-gray-300 rounded-lg focus:ring-2 focus:ring-purple-500 focus:border-transparent resize-none"
                        />
                    </div>

                    <div>
                        <label className="block text-sm font-medium text-gray-700 mb-3">
                            Share with
                        </label>
                        <div className="space-y-3">
                            <label className="flex items-start gap-3 p-3 border border-gray-200 rounded-lg cursor-pointer hover:bg-gray-50">
                                <input
                                    type="radio"
                                    name="shareWith"
                                    value="none"
                                    checked={shareWith === 'none'}
                                    onChange={() => setShareWith('none')}
                                    className="mt-0.5"
                                />
                                <div>
                                    <div className="font-medium text-gray-900">Private</div>
                                    <div className="text-sm text-gray-500">Only you can access this project</div>
                                </div>
                            </label>

                            <label className="flex items-start gap-3 p-3 border border-gray-200 rounded-lg cursor-pointer hover:bg-gray-50">
                                <input
                                    type="radio"
                                    name="shareWith"
                                    value="all"
                                    checked={shareWith === 'all'}
                                    onChange={() => setShareWith('all')}
                                    className="mt-0.5"
                                />
                                <div>
                                    <div className="font-medium text-gray-900 flex items-center gap-2">
                                        <Users size={16} />
                                        All Workspace Members
                                    </div>
                                    <div className="text-sm text-gray-500">All members will have view access</div>
                                </div>
                            </label>

                            <label className="flex items-start gap-3 p-3 border border-gray-200 rounded-lg cursor-pointer hover:bg-gray-50">
                                <input
                                    type="radio"
                                    name="shareWith"
                                    value="specific"
                                    checked={shareWith === 'specific'}
                                    onChange={() => setShareWith('specific')}
                                    className="mt-0.5"
                                />
                                <div className="flex-1">
                                    <div className="font-medium text-gray-900">Specific Members</div>
                                    <div className="text-sm text-gray-500 mb-2">Choose who can view this project</div>
                                    
                                    {shareWith === 'specific' && (
                                        <div className="mt-3 space-y-2 max-h-48 overflow-y-auto">
                                            {workspaceMembers.filter(m => m.userId !== user?.id).map((member) => (
                                                <label
                                                    key={member.userId}
                                                    className="flex items-center gap-2 p-2 hover:bg-gray-50 rounded cursor-pointer"
                                                >
                                                    <input
                                                        type="checkbox"
                                                        checked={selectedMembers.includes(member.username)}
                                                        onChange={() => toggleMember(member.username)}
                                                        className="rounded text-purple-600"
                                                    />
                                                    <div className="text-sm">
                                                        <div className="font-medium text-gray-900">{member.username}</div>
                                                        <div className="text-gray-500 text-xs">{member.email}</div>
                                                    </div>
                                                </label>
                                            ))}
                                        </div>
                                    )}
                                </div>
                            </label>
                        </div>
                    </div>

                    <div className="flex justify-end gap-3 pt-4 border-t border-gray-200">
                        <button
                            type="button"
                            onClick={onClose}
                            className="px-6 py-2 text-gray-700 bg-gray-100 hover:bg-gray-200 rounded-lg transition-colors"
                        >
                            Cancel
                        </button>
                        <button
                            type="submit"
                            disabled={creating}
                            className="px-6 py-2 bg-purple-600 text-white rounded-lg hover:bg-purple-700 transition-colors disabled:opacity-50"
                        >
                            {creating ? 'Creating...' : 'Create Project'}
                        </button>
                    </div>
                </form>
            </div>
        </div>
    );
};

export default CreateProjectModal;

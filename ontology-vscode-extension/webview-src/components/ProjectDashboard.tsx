import React, { useState, useEffect } from 'react';
import { 
    FolderOpen, 
    Users, 
    Plus, 
    Search, 
    Grid,
    List,
    Settings,
    LogOut,
    ChevronRight,
    Calendar,
    Tag,
    MoreVertical,
    Edit,
    Trash2,
    Archive,
    UserPlus
} from 'lucide-react';
import apiClient from '../services/apiClient';
import { useAuth } from '../custom-hook/useAuth';
import { useSubscription } from '../hooks/useSubscription';
import InviteMemberModal from './InviteMemberModal';
import SettingsModal from './SettingsModal';
import CreateProjectModal from './CreateProjectModal';

interface ProjectMember {
    userId: string;
    username: string;
    email: string;
    role: string;
    joinedAt: string;
}

interface Project {
    id: string;
    projectId: string;
    name: string;
    description: string;
    workspaceId: string;
    ownerId: string;
    members: ProjectMember[];
    memberCount: number;
    status: string;
    tags: string[];
    fileCount: number;
    createdAt: string;
    updatedAt: string;
}

interface TeamMember {
    id: string;
    username: string;
    email: string;
    roles: string[];
    lastLoginAt?: string;
}

interface ProjectDashboardProps {
    onSelectProject: (projectId: string, projectName: string) => void;
}

const ProjectDashboard: React.FC<ProjectDashboardProps> = ({ onSelectProject }) => {
    const { user, logout } = useAuth();
    const subscription = useSubscription();
    const [projects, setProjects] = useState<Project[]>([]);
    const [teamMembers, setTeamMembers] = useState<TeamMember[]>([]);
    const [loading, setLoading] = useState(true);
    const [viewMode, setViewMode] = useState<'grid' | 'list'>('grid');
    const [searchQuery, setSearchQuery] = useState('');
    const [showCreateProject, setShowCreateProject] = useState(false);
    const [showInviteMember, setShowInviteMember] = useState(false);
    const [showSettings, setShowSettings] = useState(false);
    const [renaming, setRenaming] = useState<{ projectId: string; currentName: string } | null>(null);
    const [newName, setNewName] = useState('');
    const [newProjectName, setNewProjectName] = useState('');
    const [newProjectDescription, setNewProjectDescription] = useState('');
    const [creating, setCreating] = useState(false);

    useEffect(() => {
        loadData();
    }, []);

    const loadData = async () => {
        try {
            setLoading(true);
            
            // Load projects for current workspace
            const projectsResponse = await apiClient.get(`/api/projects/my`);
            const projectsData = projectsResponse?.data || projectsResponse;
            setProjects(projectsData?.projects || []);
            
            // Load team members (placeholder - you'd implement a users endpoint)
            // const membersResponse = await apiClient.get(`/api/users/workspace/${user?.workspaceId}`);
            // setTeamMembers(membersResponse?.data?.members || []);
            
        } catch (error) {
            console.error('Error loading dashboard data:', error);
        } finally {
            setLoading(false);
        }
    };

    const handleCreateProject = async (e: React.FormEvent) => {
        e.preventDefault();
        
        if (!newProjectName.trim()) return;
        
        try {
            setCreating(true);
            
            const response = await apiClient.post('/api/projects', {
                workspaceId: user?.workspaceId || 'default',
                name: newProjectName.trim(),
                description: newProjectDescription.trim()
            });
            
            setShowCreateProject(false);
            setNewProjectName('');
            setNewProjectDescription('');
            loadData();
            
        } catch (error) {
            console.error('Error creating project:', error);
            alert('Failed to create project');
        } finally {
            setCreating(false);
        }
    };

    const handleInviteMember = async (email: string, role: string) => {
        try {
            const response = await apiClient.post('/api/invitations/send', {
                workspaceId: user?.workspaceId || 'default',
                email: email,
                role: role
            });
            
            console.log('Invitation sent successfully:', response?.message || 'Invitation sent');
            return response;
        } catch (error: any) {
            console.error('Error inviting member:', error);
            // Re-throw with proper error structure
            throw {
                message: error?.error || error?.message || 'Failed to send invitation',
                response: error
            };
        }
    };

    const handleRenameProject = async () => {
        if (!renaming || !newName.trim()) return;
        
        try {
            await apiClient.patch(`/api/projects/${renaming.projectId}/rename`, {
                name: newName.trim()
            });
            
            setRenaming(null);
            setNewName('');
            loadData();
        } catch (error) {
            console.error('Error renaming project:', error);
            alert('Failed to rename project');
        }
    };

    const startRename = (projectId: string, currentName: string) => {
        setRenaming({ projectId, currentName });
        setNewName(currentName);
    };

    const filteredProjects = projects.filter(project =>
        project.name.toLowerCase().includes(searchQuery.toLowerCase()) ||
        project.description.toLowerCase().includes(searchQuery.toLowerCase())
    );

    if (loading) {
        return (
            <div className="min-h-screen flex items-center justify-center bg-gray-50">
                <div className="text-center">
                    <div className="animate-spin rounded-full h-12 w-12 border-b-2 border-purple-600 mx-auto mb-4"></div>
                    <p className="text-gray-600">Loading dashboard...</p>
                </div>
            </div>
        );
    }

    return (
        <div className="min-h-screen bg-gray-50">
            {/* Header */}
            <header className="bg-white border-b border-gray-200">
                <div className="max-w-7xl mx-auto px-4 sm:px-6 lg:px-8">
                    <div className="flex justify-between items-center py-4">
                        <div>
                            <h1 className="text-2xl font-bold text-gray-900">OntoCode</h1>
                            <p className="text-sm text-gray-500">
                                Welcome, {user?.username}
                                {user?.workspaceName && (
                                    <span className="ml-2 px-2 py-0.5 bg-purple-100 text-purple-700 rounded text-xs">
                                        {user.workspaceName}
                                    </span>
                                )}
                            </p>
                        </div>
                        <div className="flex items-center gap-3">
                            <button
                                onClick={() => setShowCreateProject(true)}
                                className="flex items-center gap-2 px-4 py-2 bg-purple-600 text-white rounded-lg hover:bg-purple-700 transition-colors"
                            >
                                <Plus size={20} />
                                New Project
                            </button>
                            <button
                                onClick={() => setShowSettings(true)}
                                className="p-2 text-gray-600 hover:bg-gray-100 rounded-lg"
                                title="Settings"
                            >
                                <Settings size={20} />
                            </button>
                            <button
                                onClick={logout}
                                className="flex items-center gap-2 px-4 py-2 text-red-600 hover:bg-red-50 rounded-lg transition-colors"
                            >
                                <LogOut size={20} />
                                Logout
                            </button>
                        </div>
                    </div>
                </div>
            </header>

            {/* Main Content */}
            <main className="max-w-7xl mx-auto px-4 sm:px-6 lg:px-8 py-8">
                {/* Search and View Controls */}
                <div className="flex justify-between items-center mb-6">
                    <div className="flex-1 max-w-lg">
                        <div className="relative">
                            <Search className="absolute left-3 top-1/2 transform -translate-y-1/2 text-gray-400" size={20} />
                            <input
                                type="text"
                                placeholder="Search projects..."
                                value={searchQuery}
                                onChange={(e) => setSearchQuery(e.target.value)}
                                className="w-full pl-10 pr-4 py-2 border border-gray-300 rounded-lg focus:ring-2 focus:ring-purple-500 focus:border-transparent"
                            />
                        </div>
                    </div>
                    <div className="flex gap-2 ml-4">
                        <button
                            onClick={() => setViewMode('grid')}
                            className={`p-2 rounded-lg ${viewMode === 'grid' ? 'bg-purple-100 text-purple-600' : 'text-gray-600 hover:bg-gray-100'}`}
                        >
                            <Grid size={20} />
                        </button>
                        <button
                            onClick={() => setViewMode('list')}
                            className={`p-2 rounded-lg ${viewMode === 'list' ? 'bg-purple-100 text-purple-600' : 'text-gray-600 hover:bg-gray-100'}`}
                        >
                            <List size={20} />
                        </button>
                    </div>
                </div>

                {/* Projects Grid/List */}
                <div className="bg-white rounded-lg shadow-sm border border-gray-200 p-6">
                    <div className="flex items-center justify-between mb-6">
                        <h2 className="text-xl font-semibold text-gray-900 flex items-center gap-2">
                            <FolderOpen size={24} className="text-purple-600" />
                            Projects
                            <span className="text-sm font-normal text-gray-500">({filteredProjects.length})</span>
                        </h2>
                    </div>

                    {filteredProjects.length === 0 ? (
                        <div className="text-center py-12">
                            <FolderOpen size={64} className="text-gray-300 mx-auto mb-4" />
                            <p className="text-gray-500 mb-4">
                                {searchQuery ? 'No projects found matching your search' : 'No projects yet'}
                            </p>
                            {!searchQuery && (
                                <button
                                    onClick={() => setShowCreateProject(true)}
                                    className="px-4 py-2 bg-purple-600 text-white rounded-lg hover:bg-purple-700"
                                >
                                    Create Your First Project
                                </button>
                            )}
                        </div>
                    ) : (
                        <div className={viewMode === 'grid' ? 'grid grid-cols-1 md:grid-cols-2 lg:grid-cols-3 gap-4' : 'space-y-3'}>
                            {filteredProjects.map((project) => (
                                <div
                                    key={project.id}
                                    onClick={() => onSelectProject(project.projectId, project.name)}
                                    className={`
                                        border border-gray-200 rounded-lg p-4 cursor-pointer
                                        hover:border-purple-400 hover:shadow-md transition-all
                                        ${viewMode === 'list' ? 'flex items-center justify-between' : ''}
                                    `}
                                >
                                    <div className="flex-1">
                                        <div className="flex items-start justify-between mb-2">
                                            <h3 className="font-semibold text-gray-900 flex items-center gap-2">
                                                <FolderOpen size={20} className="text-purple-600" />
                                                {project.name}
                                            </h3>
                                            {viewMode === 'grid' && (
                                                <div className="relative group">
                                                    <button className="p-1 hover:bg-gray-100 rounded">
                                                        <MoreVertical size={16} className="text-gray-400" />
                                                    </button>
                                                    <div className="absolute right-0 mt-1 w-40 bg-white border border-gray-200 rounded-lg shadow-lg hidden group-hover:block z-10">
                                                        <button
                                                            onClick={(e) => {
                                                                e.stopPropagation();
                                                                startRename(project.projectId, project.name);
                                                            }}
                                                            className="w-full px-4 py-2 text-left text-sm text-gray-700 hover:bg-gray-50 rounded-lg flex items-center gap-2"
                                                        >
                                                            <Edit size={14} />
                                                            Rename
                                                        </button>
                                                    </div>
                                                </div>
                                            )}
                                        </div>
                                        <p className="text-sm text-gray-600 mb-3 line-clamp-2">
                                            {project.description || 'No description'}
                                        </p>
                                        <div className="flex items-center gap-4 text-xs text-gray-500">
                                            <span className="flex items-center gap-1">
                                                <Users size={14} />
                                                {project.memberCount} members
                                            </span>
                                            <span className="flex items-center gap-1">
                                                <FolderOpen size={14} />
                                                {project.fileCount} files
                                            </span>
                                            <span className="flex items-center gap-1">
                                                <Calendar size={14} />
                                                {new Date(project.updatedAt).toLocaleDateString()}
                                            </span>
                                        </div>
                                    </div>
                                    {viewMode === 'list' && (
                                        <ChevronRight size={20} className="text-gray-400 ml-4" />
                                    )}
                                </div>
                            ))}
                        </div>
                    )}
                </div>

                {/* Team Members Section */}
                <div className="mt-8 bg-white rounded-lg shadow-sm border border-gray-200 p-6">
                    <div className="flex items-center justify-between mb-6">
                        <h2 className="text-xl font-semibold text-gray-900 flex items-center gap-2">
                            <Users size={24} className="text-purple-600" />
                            Team Members
                            <span className="text-sm font-normal text-gray-500">({teamMembers.length})</span>
                        </h2>
                        <button 
                            onClick={() => {
                                if (!subscription.isWithinLimit(teamMembers.length, 'maxTeamMembers')) {
                                    alert(subscription.getUpgradeMessage(`more than ${subscription.limits.maxTeamMembers} team members`));
                                    return;
                                }
                                setShowInviteMember(true);
                            }}
                            className={`flex items-center gap-2 px-3 py-2 text-sm border border-gray-300 rounded-lg hover:bg-gray-50 ${
                                !subscription.isWithinLimit(teamMembers.length, 'maxTeamMembers') ? 'opacity-60 cursor-not-allowed' : ''
                            }`}
                            title={!subscription.isWithinLimit(teamMembers.length, 'maxTeamMembers') 
                                ? `Limit reached (${subscription.limits.maxTeamMembers} members). ${subscription.getUpgradeMessage('more team members')}`
                                : 'Invite a new team member'
                            }
                        >
                            <UserPlus size={16} />
                            Invite Member
                            {!subscription.isWithinLimit(teamMembers.length, 'maxTeamMembers') && (
                                <span className="bg-amber-500 text-white text-[10px] px-1 py-0.5 rounded">LIMIT</span>
                            )}
                        </button>
                    </div>

                    {teamMembers.length === 0 ? (
                        <div className="text-center py-8 text-gray-500">
                            No team members yet
                        </div>
                    ) : (
                        <div className="space-y-2">
                            {teamMembers.map((member) => (
                                <div
                                    key={member.id}
                                    className="flex items-center justify-between p-3 border border-gray-200 rounded-lg hover:bg-gray-50"
                                >
                                    <div className="flex items-center gap-3">
                                        <div className="w-10 h-10 rounded-full bg-purple-100 flex items-center justify-center text-purple-600 font-semibold">
                                            {member.username.charAt(0).toUpperCase()}
                                        </div>
                                        <div>
                                            <p className="font-medium text-gray-900">{member.username}</p>
                                            <p className="text-sm text-gray-500">{member.email}</p>
                                        </div>
                                    </div>
                                    <div className="flex items-center gap-2">
                                        <span className="px-2 py-1 text-xs bg-gray-100 text-gray-700 rounded">
                                            {member.roles.join(', ')}
                                        </span>
                                        <button className="p-1 hover:bg-gray-100 rounded">
                                            <MoreVertical size={16} className="text-gray-400" />
                                        </button>
                                    </div>
                                </div>
                            ))}
                        </div>
                    )}
                </div>
            </main>

            {/* Create Project Modal */}
            <CreateProjectModal
                isOpen={showCreateProject}
                onClose={() => setShowCreateProject(false)}
                onSuccess={loadData}
            />

            {/* Rename Project Modal */}
            {renaming && (
                <div className="fixed inset-0 bg-black bg-opacity-50 flex items-center justify-center z-50 p-4">
                    <div className="bg-white rounded-lg shadow-xl max-w-md w-full p-6">
                        <h3 className="text-xl font-semibold mb-4">Rename Project</h3>
                        <div className="mb-4">
                            <label className="block text-sm font-medium text-gray-700 mb-2">
                                New Project Name *
                            </label>
                            <input
                                type="text"
                                value={newName}
                                onChange={(e) => setNewName(e.target.value)}
                                className="w-full px-3 py-2 border border-gray-300 rounded-lg focus:ring-2 focus:ring-purple-500"
                                placeholder="Enter new name"
                                autoFocus
                                onKeyPress={(e) => {
                                    if (e.key === 'Enter') {
                                        handleRenameProject();
                                    }
                                }}
                            />
                        </div>
                        <div className="flex gap-3">
                            <button
                                onClick={() => {
                                    setRenaming(null);
                                    setNewName('');
                                }}
                                className="flex-1 px-4 py-2 border border-gray-300 rounded-lg hover:bg-gray-50"
                            >
                                Cancel
                            </button>
                            <button
                                onClick={handleRenameProject}
                                className="flex-1 px-4 py-2 bg-purple-600 text-white rounded-lg hover:bg-purple-700"
                            >
                                Rename
                            </button>
                        </div>
                    </div>
                </div>
            )}

            {/* Invite Member Modal */}
            <InviteMemberModal
                isOpen={showInviteMember}
                onClose={() => setShowInviteMember(false)}
                workspaceId={user?.workspaceId || 'default'}
                workspaceName={user?.workspaceName || 'Workspace'}
                onInvite={handleInviteMember}
            />

            {/* Settings Modal */}
            <SettingsModal
                isOpen={showSettings}
                onClose={() => setShowSettings(false)}
                user={{
                    username: user?.username || '',
                    email: user?.email,
                    workspaceName: user?.workspaceName
                }}
            />
        </div>
    );
};

export default ProjectDashboard;


import React, { useState, useEffect } from 'react';
import { useAuth } from './custom-hook/useAuth';
import { CollaborationProvider } from './contexts/CollaborationContext';
import { EntityPreferencesProvider } from './contexts/EntityPreferencesContext';
import { ThemeProvider } from './contexts/ThemeContext';
import Dashboard from './components/Dashboard';
import LoginForm from './components/LoginForm';
import SignupForm from './components/SignupForm';
import WorkspaceSelection from './components/WorkspaceSelection';
import ProjectDashboard from './components/ProjectDashboard';
import ProjectLibrary from './components/ProjectLibrary';
import SubscriptionPlanSelection from './components/SubscriptionPlanSelection';
import InviteAcceptPage from './components/InviteAcceptPage';
import { Loader2 } from 'lucide-react';

const AppContent = () => {
    const { user, loading, needsWorkspaceSelection, selectWorkspace, logout, updateSubscriptionPlan } = useAuth();
    const [isLoginView, setIsLoginView] = useState(true);
    const [selectedProjectId, setSelectedProjectId] = useState<string | null>(null);
    const [selectedProjectName, setSelectedProjectName] = useState<string>('');
    const [selectedFileId, setSelectedFileId] = useState<string | null>(null);
    const [selectedFileName, setSelectedFileName] = useState<string>('');
    const [showSubscriptionPlan, setShowSubscriptionPlan] = useState(false);
    const [inviteToken, setInviteToken] = useState<string | null>(null);
    const [inviteEmail, setInviteEmail] = useState<string | null>(null);

    useEffect(() => {
        // Check for invitation parameters in URL
        const params = new URLSearchParams(window.location.search);
        const token = params.get('token') || params.get('invite');
        const email = params.get('email');
        
        if (token) {
            setInviteToken(token);
            if (email) {
                setInviteEmail(email);
            }
        }
    }, []);

    const toggleFormView = () => setIsLoginView(!isLoginView);

    const handleWorkspaceSelected = (workspaceData: any) => {
        selectWorkspace(workspaceData);
        // Show subscription plan selection for admins only if they don't have a plan yet
        if (user?.isAdmin && !workspaceData.subscriptionPlan) {
            setShowSubscriptionPlan(true);
        }
    };

    const handleProjectSelected = (projectId: string, projectName: string) => {
        setSelectedProjectId(projectId);
        setSelectedProjectName(projectName);
    };

    const handleBackToProjects = () => {
        setSelectedProjectId(null);
        setSelectedProjectName('');
        setSelectedFileId(null);
        setSelectedFileName('');
    };

    const handleFileSelected = (fileId: string, fileName: string) => {
        setSelectedFileId(fileId);
        setSelectedFileName(fileName);
    };

    const handlePlanSelected = async (planId: string) => {
        console.log('Selected plan:', planId);
        try {
            // Save plan to backend via auth context
            await updateSubscriptionPlan(planId);
            setShowSubscriptionPlan(false);
        } catch (error) {
            console.error('Failed to save subscription plan:', error);
        }
    };

    const handleSkipPlan = () => {
        setShowSubscriptionPlan(false);
    };

    const handleLogout = () => {
        // Reset all navigation state before logout
        setSelectedProjectId(null);
        setSelectedProjectName('');
        setSelectedFileId(null);
        setSelectedFileName('');
        setShowSubscriptionPlan(false);
        setInviteToken(null);
        setInviteEmail(null);
        logout();
    };

    const handleBackToProjectDashboard = () => {
        setSelectedProjectId(null);
        setSelectedProjectName('');
        setSelectedFileId(null);
        setSelectedFileName('');
    };

    const handleInvitationAccepted = () => {
        // Clear invitation state and force re-login to get new workspace context
        setInviteToken(null);
        setInviteEmail(null);
        // Logout to force workspace reselection with new membership
        handleLogout();
    };

    const handleInvitationLoginRequired = (email: string) => {
        // Go to login with prefilled email but keep invitation token
        setInviteEmail(email);
        setIsLoginView(true);
    };

    const handleInvitationError = () => {
        // Clear invitation and go to login
        setInviteToken(null);
        setInviteEmail(null);
        setIsLoginView(true);
    };

    if (loading) {
        return (
            <div className="min-h-screen flex items-center justify-center" style={{ backgroundColor: 'var(--color-background)' }}>
                <div className="text-center p-8">
                    <div className="w-20 h-20 rounded-2xl flex items-center justify-center shadow-lg mx-auto mb-6" style={{ background: 'linear-gradient(to bottom right, var(--color-primary), var(--color-secondary))' }}>
                        <Loader2 size={40} className="text-white animate-spin" />
                    </div>
                    <h2 className="text-2xl font-bold mb-3" style={{ color: 'var(--color-text)' }}>
                        Initializing OntoCode
                    </h2>
                    <p className="text-sm" style={{ color: 'var(--color-text-secondary)' }}>
                        Connecting to your workspace...
                    </p>
                </div>
            </div>
        );
    }

    // Show invitation acceptance page if there's an invite token and user is logged in
    if (user && inviteToken) {
        return (
            <InviteAcceptPage 
                onAccepted={handleInvitationAccepted}
                onLoginRequired={handleInvitationLoginRequired}
                onError={handleInvitationError}
            />
        );
    }

    // Show workspace selection if user is logged in but hasn't selected a workspace
    if (user && needsWorkspaceSelection) {
        return (
            <WorkspaceSelection
                username={user.username}
                onWorkspaceSelected={handleWorkspaceSelected}
                onLogout={handleLogout}
            />
        );
    }

    // Show subscription plan selection for admins after workspace selection
    if (user && user.isAdmin && user.workspaceId && showSubscriptionPlan) {
        return (
            <SubscriptionPlanSelection
                username={user.username}
                workspaceId={user.workspaceId}
                workspaceName={user.workspaceName || 'Workspace'}
                onPlanSelected={handlePlanSelected}
                onSkip={handleSkipPlan}
                onLogout={handleLogout}
            />
        );
    }

    // Show Project Dashboard for admins with workspace selected (unless a project is selected)
    if (user && user.isAdmin && user.workspaceId && !showSubscriptionPlan && !selectedProjectId) {
        return <ProjectDashboard onSelectProject={handleProjectSelected} />;
    }

    // Show Project Library when a project is selected but no file is selected
    if (user && user.isAdmin && selectedProjectId && !selectedFileId) {
        return (
            <ProjectLibrary
                projectId={selectedProjectId}
                projectName={selectedProjectName}
                onBack={handleBackToProjects}
                onFileSelect={handleFileSelected}
            />
        );
    }

    // Show main Dashboard/Editor when:
    // 1. Admin has selected a file, OR
    // 2. Non-admin user (goes directly to editor)
    if (user) {
        return (
            <Dashboard 
                onBackToProjects={user.isAdmin ? handleBackToProjectDashboard : undefined}
                selectedFileId={selectedFileId || undefined}
                selectedFileName={selectedFileName || undefined}
                projectId={selectedProjectId || undefined}
            />
        );
    } else {
        // If there's an invitation, prefill the email in login/signup
        return isLoginView ? (
            <LoginForm 
                onToggleForm={toggleFormView} 
                prefillEmail={inviteEmail || undefined}
            />
        ) : (
            <SignupForm 
                onToggleForm={toggleFormView}
                prefillEmail={inviteEmail || undefined}
            />
        );
    }
};

const App = () => {
    return (
        <ThemeProvider>
        <CollaborationProvider>
            <EntityPreferencesProvider>
                <AppContent />
            </EntityPreferencesProvider>
        </CollaborationProvider>
        </ThemeProvider>
    );
};

export default App;

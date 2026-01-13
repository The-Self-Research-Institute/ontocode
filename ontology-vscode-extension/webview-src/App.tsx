
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
    const [pendingFile, setPendingFile] = useState<{ fileName: string; fileContent: string; fileSize: number } | null>(null);
    const [showAuthForInvitation, setShowAuthForInvitation] = useState(false); // Show login/signup form while keeping invite token

    // Send webviewReady on mount to ensure extension knows webview is loaded
    useEffect(() => {
        console.log('[App] Webview mounted, sending webviewReady signal');
        if (window.vscode) {
            window.vscode.postMessage({ type: 'webviewReady' });
        }
    }, []);

    useEffect(() => {
        // Check for invitation parameters in URL
        const params = new URLSearchParams(window.location.search);
        const token = params.get('token') || params.get('invite');
        const email = params.get('email');
        
        if (token) {
            console.log('[App] Found invitation token in URL:', token);
            setInviteToken(token);
            if (email) {
                setInviteEmail(email);
            }
        }
    }, []);

    // Listen for pending file upload and invitation token messages from extension
    useEffect(() => {
        const handleMessage = (event: MessageEvent) => {
            const message = event.data;
            console.log('[App] Received message from extension:', message.type, message);
            
            if (message.type === 'pendingFileUpload') {
                console.log('[App] 📎 Received pending file upload:', message.fileName);
                setPendingFile({
                    fileName: message.fileName,
                    fileContent: message.fileContent,
                    fileSize: message.fileSize
                });
            } else if (message.type === 'invitationToken') {
                console.log('[App] 📧 Received invitation token from extension:', message.token);
                setInviteToken(message.token);
            }
        };

        window.addEventListener('message', handleMessage);
        return () => window.removeEventListener('message', handleMessage);
    }, []);

    const toggleFormView = () => setIsLoginView(!isLoginView);

    const handleWorkspaceSelected = (workspaceData: any) => {
        selectWorkspace(workspaceData);
        // Subscription plan is now selected during workspace creation
        // No need to show separate subscription plan screen
    };

    const handleProjectSelected = (projectId: string, projectName: string) => {
        console.log('[App] Project selected:', projectId, projectName);
        
        // If there's a pending file, upload it to this project
        if (pendingFile) {
            console.log('[App] Uploading pending file to project:', pendingFile.fileName);
            if (window.vscode) {
                window.vscode.postMessage({
                    type: 'uploadFileToProject',
                    projectId: projectId,
                    fileName: pendingFile.fileName,
                    fileContent: pendingFile.fileContent,
                    fileSize: pendingFile.fileSize
                });
            }
            // Clear pending file immediately after sending upload message
            setPendingFile(null);
        }
        
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
        console.log('[App] File selected:', fileId, fileName);
        // Update file selection - the key prop on Dashboard will force remount
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

    const handleInvitationAccepted = (workspaceData?: any) => {
        console.log('[App] ✅ Invitation accepted, workspace data:', workspaceData);
        // Clear invitation state
        setInviteToken(null);
        setInviteEmail(null);
        
        if (workspaceData) {
            console.log('[App] Successfully joined workspace:', workspaceData.workspaceId || workspaceData.workspace?.id);
            // Select the workspace the user just joined
            if (workspaceData.workspaceId) {
                // Trigger workspace selection to get proper JWT with workspace context
                // This will automatically navigate to the Project Dashboard
                selectWorkspace({
                    workspaceId: workspaceData.workspaceId,
                    workspaceName: workspaceData.workspaceName,
                    jwt: workspaceData.jwt || workspaceData.workspace?.jwt
                });
                console.log('[App] Workspace selected, navigating to Project Dashboard...');
            }
        }
    };

    const handleInvitationLoginRequired = (email: string) => {
        console.log('[App] ⚠️  Login required for invitation, email:', email);
        // Store invitation token to restore after login
        const currentToken = inviteToken;
        setInviteEmail(email);
        // Keep the token so user can accept invitation after logging in
        console.log('[App] Keeping invitation token for post-login acceptance:', currentToken);
        // Show auth form while keeping the invite token for later
        setShowAuthForInvitation(true);
        setIsLoginView(true);
    };

    const handleInvitationSignupRequired = (email: string) => {
        console.log('[App] 📝 Signup required for invitation, email:', email);
        setInviteEmail(email);
        // Show signup form while keeping the invite token for later
        setShowAuthForInvitation(true);
        setIsLoginView(false); // Show signup form instead of login
    };

    const handleInvitationError = () => {
        console.log('[App] ❌ Invitation error, clearing state and showing login');
        // Clear invitation and go to login
        setInviteToken(null);
        setInviteEmail(null);
        setShowAuthForInvitation(false);
        setIsLoginView(true);
    };

    // When user logs in successfully while having an invite, go back to invitation page
    useEffect(() => {
        if (user && showAuthForInvitation && inviteToken) {
            console.log('[App] User logged in with pending invitation, returning to invitation page');
            setShowAuthForInvitation(false); // Show invitation page again now that user is logged in
        }
    }, [user, showAuthForInvitation, inviteToken]);

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

    // Show invitation acceptance page if there's an invite token (whether logged in or not)
    // But if user clicked login/signup, show auth form first
    if (inviteToken && !showAuthForInvitation) {
        return (
            <InviteAcceptPage
                token={inviteToken}
                onAccepted={handleInvitationAccepted}
                onLoginRequired={handleInvitationLoginRequired}
                onSignupRequired={handleInvitationSignupRequired}
                onError={handleInvitationError}
            />
        );
    }

    // Show workspace selection if user is logged in but hasn't selected a workspace
    if (user && needsWorkspaceSelection) {
        return (
            <WorkspaceSelection
                username={user.username}
                isAdmin={user.isAdmin || false}
                onWorkspaceSelected={handleWorkspaceSelected}
                onLogout={handleLogout}
            />
        );
    }

    // Show subscription plan selection for admins only (not for workspace members)
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

    // Show Project Dashboard for workspace members (both admins and non-admins)
    // Show only when no file is selected AND (no project selected OR has pending file to upload)
    if (user && user.workspaceId && !showSubscriptionPlan && !selectedFileId && (!selectedProjectId || pendingFile)) {
        console.log('[App] Routing to ProjectDashboard - isAdmin:', user.isAdmin, 'selectedFileId:', selectedFileId, 'selectedProjectId:', selectedProjectId, 'pendingFile:', !!pendingFile);
        return <ProjectDashboard onSelectProject={handleProjectSelected} />;
    }

    // Show Project Library when a project is selected but no file is selected
    // Available to all workspace members (both admins and non-admins)
    if (user && user.workspaceId && selectedProjectId && !selectedFileId) {
        console.log('[App] Routing to ProjectLibrary - isAdmin:', user.isAdmin, 'projectId:', selectedProjectId);
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
    // 1. Workspace member (admin or non-admin) has selected a file, OR
    // 2. Non-workspace user (goes directly to editor without workspace flow)
    if (user) {
        console.log('[App] Routing to Dashboard - isAdmin:', user.isAdmin, 'workspaceId:', user.workspaceId, 'selectedFileId:', selectedFileId, 'selectedProjectId:', selectedProjectId);
        return (
            <Dashboard 
                key={selectedFileId || 'default'} // Force remount when file changes
                onBackToProjects={user.workspaceId ? handleBackToProjectDashboard : undefined}
                selectedFileId={selectedFileId || undefined}
                selectedFileName={selectedFileName || undefined}
                projectId={selectedProjectId || undefined}
            />
        );
    } else {
        // If there's an invitation, prefill the email in login/signup and show back button
        const handleBackToInvitation = () => {
            setShowAuthForInvitation(false);
        };
        
        return isLoginView ? (
            <LoginForm 
                onToggleForm={toggleFormView} 
                prefillEmail={inviteEmail || undefined}
                onBackToInvitation={inviteToken ? handleBackToInvitation : undefined}
            />
        ) : (
            <SignupForm 
                onToggleForm={toggleFormView}
                prefillEmail={inviteEmail || undefined}
                onBackToInvitation={inviteToken ? handleBackToInvitation : undefined}
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


import React, { createContext, useState, useEffect, useCallback, useRef, ReactNode } from 'react';
import apiClient from '../services/apiClient';
import {
    clearLastOpenedProjectState,
    clearSessionCache,
    SUPPRESS_WORKSPACE_AUTO_OPEN_KEY,
} from '../utils/sessionCleanup';
import {
    isDesktop,
    getDesktopLicense,
    DesktopLicense,
    DESKTOP_LICENSE_UPDATED_EVENT,
} from '../utils/desktop';

interface User {
    token: string;
    userId?: string;
    username: string;
    email?: string;
    roles?: string[];
    isAdmin?: boolean;
    workspaceId?: string;
    workspaceName?: string;
    workspaceRole?: string;
    subscriptionPlan?: string; // Workspace subscription plan: 'free', 'pro', or 'enterprise'
    enterpriseDomainBypass?: boolean; // true = access granted via allowed-domain list, not payment
}

interface AuthContextType {
    user: User | null;
    loading: boolean;
    needsWorkspaceSelection: boolean;
    maintenanceActive: boolean;
    maintenanceMessage: string;
    login: (username: string, password: string) => Promise<void>;
    signup: (username: string, email: string, password: string) => Promise<{ requiresVerification: boolean; email?: string; message?: string }>;
    forgotPassword: (email: string) => Promise<string>;
    resetPassword: (token: string, password: string) => Promise<string>;
    resendVerification: (email: string) => Promise<string>;
    verifyEmailAndLogin: (token: string) => Promise<string>;
    selectWorkspace: (workspaceData: any) => void;
    switchWorkspace: () => void;
    updateSubscriptionPlan: (planId: string) => Promise<void>;
    updateUserRole: (deploymentType: 'self-hosted' | 'cloud') => Promise<void>;
    refreshPermissions: () => Promise<void>;
    patchEnterpriseBypass: (bypass: boolean) => void;
    logout: (showExpiredMessage?: boolean) => void;
    sessionExpiredMessage: string | null;
}

type DeploymentType = 'self-hosted' | 'cloud';
const SKIP_WORKSPACE_MODE_KEY = 'skipWorkspaceMode';

const isSkipWorkspaceMode = (): boolean => {
    try {
        return localStorage.getItem(SKIP_WORKSPACE_MODE_KEY) === 'true';
    } catch {
        return false;
    }
};

const isTokenExpired = (token: string): boolean => {
    try {
        const parts = token.split('.');
        if (parts.length !== 3) return true;

        const payload = JSON.parse(atob(parts[1].replace(/-/g, '+').replace(/_/g, '/')));
        const exp = payload.exp;

        if (!exp) return false; // No expiration set

        return Date.now() >= exp * 1000;
    } catch (error) {
        console.error('[AuthContext] Error decoding token:', error);
        return true; // If we can't decode, assume expired
    }
};

const decodeToken = (token: string): { userId?: string; username: string; email?: string; roles?: string[]; isAdmin?: boolean; workspaceId?: string; workspaceName?: string; workspaceRole?: string; subscriptionPlan?: string } => {
    try {
        const parts = token.split('.');
        if (parts.length !== 3) return { username: 'unknown' };

        const payload = JSON.parse(atob(parts[1].replace(/-/g, '+').replace(/_/g, '/')));
        return {
            userId: payload.userId || payload.id,
            username: payload.sub || 'unknown',
            email: payload.email,
            roles: payload.roles || [],
            isAdmin: payload.isAdmin || false,
            workspaceId: payload.workspaceId,
            workspaceName: payload.workspaceName,
            workspaceRole: payload.workspaceRole,
            subscriptionPlan: payload.subscriptionPlan || payload.plan
        };
    } catch (e) {
        console.error('[AuthContext] Error decoding token:', e);
        return { username: 'unknown' };
    }
};

const getStoredEnterpriseDomainBypass = (): boolean => {
    try { return localStorage.getItem('enterpriseDomainBypass') === 'true'; } catch { return false; }
};

const buildDesktopUser = (license: DesktopLicense | null): User => ({
    token: '',
    userId: 'desktop-user-local',
    username: license?.name || 'Desktop User',
    email: license?.email || 'local@ontocode.desktop',
    roles: ['ROLE_USER'],
    isAdmin: false,
    workspaceId: 'desktop-workspace-local',
    workspaceName: 'My projects',
    workspaceRole: 'OWNER',
    subscriptionPlan: (license?.plan || 'FREE'),
    enterpriseDomainBypass: false,
});

const getStoredDeploymentType = (): DeploymentType | null => {
    try {
        const value = localStorage.getItem('deploymentType');
        if (value === 'self-hosted' || value === 'cloud') {
            return value;
        }
    } catch (error) {
        console.warn('[AuthContext] Unable to read deployment type from storage:', error);
    }
    return null;
};

const shouldRequireWorkspaceSelection = (
    deploymentType: DeploymentType | null,
    isAdmin: boolean,
    workspaceId?: string
): boolean => {
    if (deploymentType === 'self-hosted') {

        return !workspaceId;
    }
    if (deploymentType === 'cloud') {
        return !workspaceId;
    }
    return isAdmin && !workspaceId;
};

export const AuthContext = createContext<AuthContextType | undefined>(undefined);

export const AuthProvider: React.FC<{ children: ReactNode }> = ({ children }) => {
    const [user, setUser] = useState<User | null>(null);
    const [loading, setLoading] = useState(true);
    const [needsWorkspaceSelection, setNeedsWorkspaceSelection] = useState(false);
    const [sessionExpiredMessage, setSessionExpiredMessage] = useState<string | null>(null);
    const [maintenanceActive, setMaintenanceActive] = useState(false);
    const [maintenanceMessage, setMaintenanceMessage] = useState('');

    const ignoringWorkspaceRef = useRef(false);
    const logout = useCallback((showExpiredMessage = false) => {

        clearSessionCache();
        clearLastOpenedProjectState();
        ignoringWorkspaceRef.current = false;
        try { localStorage.removeItem('enterpriseDomainBypass'); } catch {}

        setUser(null);
        setNeedsWorkspaceSelection(false);
        if (showExpiredMessage) {
            setSessionExpiredMessage('Session expired. Please login again.');
        }
        if (window.vscode) {
            window.vscode.postMessage({ type: 'logout' });
        }
    }, []);

    useEffect(() => {
        apiClient.setUnauthorizedCallback(() => {

            if (isDesktop()) return;
            const token = localStorage.getItem('authToken');
            if (!token || isTokenExpired(token)) {

                logout(true);
            } else {

                console.warn('[AuthContext] 401 received but token is still valid — ignoring (not a session expiry)');
            }
        });
    }, [logout]);

    useEffect(() => {
        apiClient.setMaintenanceCallback((msg: string) => {
            setMaintenanceActive(true);
            setMaintenanceMessage(msg);
        });
    }, []);

    useEffect(() => {
        if (isDesktop()) return; // desktop has no cloud maintenance window
        apiClient.get('/api/maintenance/status').then((data: any) => {
            if (data?.active) {
                setMaintenanceActive(true);
                setMaintenanceMessage(data.message || 'System is under maintenance.');
            }
        }).catch(() => { /* ignore — server unreachable, don't block the app */ });
    }, []);

    const requestTokenFromVSCode = useCallback(() => {
        if (window.vscode) {
            window.vscode.postMessage({ type: 'requestAuthToken' });
        } else {

            const token = localStorage.getItem('authToken');

            if (token) {

                if (isTokenExpired(token)) {
                    localStorage.removeItem('authToken');
                    setLoading(false);
                    return;
                }

                const userInfo = decodeToken(token);
                const skipWorkspaceMode = isSkipWorkspaceMode();
                const deploymentType = getStoredDeploymentType();

                const isAdmin = userInfo.isAdmin || false;

                const requiresWorkspace = shouldRequireWorkspaceSelection(
                    deploymentType,
                    isAdmin,
                    skipWorkspaceMode ? undefined : userInfo.workspaceId
                );

                setUser({
                    token: token,
                    userId: userInfo.userId,
                    username: userInfo.username,
                    email: userInfo.email,
                    roles: userInfo.roles,
                    isAdmin: isAdmin,
                    workspaceId: skipWorkspaceMode ? undefined : userInfo.workspaceId,
                    workspaceName: skipWorkspaceMode ? undefined : userInfo.workspaceName,
                    workspaceRole: skipWorkspaceMode ? undefined : userInfo.workspaceRole,
                    subscriptionPlan: userInfo.subscriptionPlan,
                    enterpriseDomainBypass: getStoredEnterpriseDomainBypass()
                });

                setNeedsWorkspaceSelection(skipWorkspaceMode ? false : requiresWorkspace);
                setSessionExpiredMessage(null);
            }

            setLoading(false);
        }
    }, [logout]);

    useEffect(() => {

        if (isDesktop()) {
            let cancelled = false;
            const applyLicense = async () => {
                let license: DesktopLicense | null = null;
                try {
                    license = await getDesktopLicense();
                } catch {
                    license = null;
                }
                if (cancelled) return;
                setUser(buildDesktopUser(license));
                setNeedsWorkspaceSelection(false);
                setSessionExpiredMessage(null);
                setLoading(false);
            };
            applyLicense();
            const onLicenseUpdated = () => applyLicense();
            window.addEventListener(DESKTOP_LICENSE_UPDATED_EVENT, onLicenseUpdated);
            return () => {
                cancelled = true;
                window.removeEventListener(DESKTOP_LICENSE_UPDATED_EVENT, onLicenseUpdated);
            };
        }

        requestTokenFromVSCode();

        const handleMessage = (event: MessageEvent) => {
            const message = event.data;
            switch (message.type) {
                case 'storedAuthToken':
                    if (message.token) {

                        if (isTokenExpired(message.token)) {
                            logout(true);
                            setLoading(false);
                            return;
                        }

                        if (ignoringWorkspaceRef.current) {

                            localStorage.setItem('authToken', message.token);
                            ignoringWorkspaceRef.current = false; // Reset flag
                            setLoading(false);
                            return;
                        }

                        const userInfo = decodeToken(message.token);
                        const skipWorkspaceMode = isSkipWorkspaceMode();
                        const deploymentType = getStoredDeploymentType();

                        const isAdmin = userInfo.isAdmin || false;

                        const requiresWorkspace = shouldRequireWorkspaceSelection(
                            deploymentType,
                            isAdmin,
                            skipWorkspaceMode ? undefined : userInfo.workspaceId
                        );

                        localStorage.setItem('authToken', message.token);

                        setUser({
                            token: message.token,
                            userId: userInfo.userId,
                            username: userInfo.username,
                            email: userInfo.email,
                            roles: userInfo.roles,
                            isAdmin: isAdmin,
                            workspaceId: skipWorkspaceMode ? undefined : userInfo.workspaceId,
                            workspaceName: skipWorkspaceMode ? undefined : userInfo.workspaceName,
                            workspaceRole: skipWorkspaceMode ? undefined : userInfo.workspaceRole,
                            subscriptionPlan: userInfo.subscriptionPlan,
                            enterpriseDomainBypass: getStoredEnterpriseDomainBypass()
                        });

                        setNeedsWorkspaceSelection(skipWorkspaceMode ? false : requiresWorkspace);

                        setSessionExpiredMessage(null);
                    }
                    setLoading(false);
                    break;
                case 'loggedOut':
                    setUser(null);
                    break;
            }
        };

        window.addEventListener('message', handleMessage);
        return () => {
            window.removeEventListener('message', handleMessage);
        };
    }, [requestTokenFromVSCode, logout]);

    useEffect(() => {
        if (!user?.token) return;

        const interval = setInterval(() => {
            if (isTokenExpired(user.token)) {
                logout(true);
                return;
            }

            if (!isDesktop()) {
                apiClient.get('/api/maintenance/status').then((data: any) => {
                    if (data?.active) {
                        setMaintenanceActive(true);
                        setMaintenanceMessage(data.message || 'System is under maintenance.');
                    }
                }).catch(() => { /* ignore — don't disrupt the session on network hiccup */ });
            }
        }, 60000); // Check every 60 seconds

        return () => clearInterval(interval);
    }, [user?.token, logout]);

    const login = async (username: string, password: string) => {
        try {

            const response = await apiClient.post('/api/auth/login', { 
                username, 
                password 
            });

            const token = response?.jwt || response?.token || response?.data?.jwt || response?.data?.token;
            const responseData = response?.data || response;
            const roles = responseData?.roles || [];
            const email = responseData?.email || '';

            const deploymentType = getStoredDeploymentType();
            const isAdmin = responseData?.isAdmin || false;
            const enterpriseDomainBypass = responseData?.enterpriseDomainBypass || false;
            try { localStorage.setItem('enterpriseDomainBypass', String(enterpriseDomainBypass)); } catch {}

            if (!token) {

                if (response?.error || response?.data?.error) {
                    throw new Error(response?.error || response?.data?.error);
                }
                throw new Error('No token received from server');
            }

            localStorage.setItem('authToken', token);

            if (window.vscode) {

                window.vscode.postMessage({ type: 'saveAuthToken', token });
            }

            const userInfo = decodeToken(token);

            const lastWorkspaceId = localStorage.getItem('lastWorkspaceId');
            const skipWorkspaceMode = isSkipWorkspaceMode();

            if (!skipWorkspaceMode && !userInfo.workspaceId && lastWorkspaceId && (deploymentType === 'cloud' || isAdmin)) {
                try {
                    const selectResponse = await apiClient.post(`/api/workspaces/${lastWorkspaceId}/select`);

                    if (selectResponse.jwt) {

                        localStorage.setItem('authToken', selectResponse.jwt);
                        if (window.vscode) {
                            window.vscode.postMessage({ type: 'saveAuthToken', token: selectResponse.jwt });
                        }

                        const wsUserInfo = decodeToken(selectResponse.jwt);
                        const userData = {
                            token: selectResponse.jwt,
                            userId: wsUserInfo.userId || userInfo.userId,
                            username: wsUserInfo.username || username,
                            email: wsUserInfo.email || email,
                            roles: wsUserInfo.roles || roles,
                            isAdmin: wsUserInfo.isAdmin || isAdmin,
                            workspaceId: selectResponse.workspaceId,
                            workspaceName: selectResponse.workspaceName,
                            workspaceRole: selectResponse.role,
                            subscriptionPlan: selectResponse.subscriptionPlan || wsUserInfo.subscriptionPlan,
                            enterpriseDomainBypass
                        };
                        setUser(userData);
                        setNeedsWorkspaceSelection(false);
                        setSessionExpiredMessage(null);

                        return;
                    } else {
                        console.warn('[AuthContext] ⚠️ No JWT in workspace select response');
                    }
                } catch (wsError: any) {
                    console.error('[AuthContext] ❌ Failed to auto-select workspace:', wsError);
                    console.error('[AuthContext] Error details:', wsError?.message, wsError?.status, wsError?.data);
                    // Fall through to normal login flow without workspace
                }
            } else {
            }

            setUser({
                token,
                userId: userInfo.userId,
                username: userInfo.username || username,
                email: userInfo.email || email,
                roles: userInfo.roles || roles,
                isAdmin: userInfo.isAdmin || isAdmin,
                workspaceId: userInfo.workspaceId,
                workspaceName: userInfo.workspaceName,
                workspaceRole: userInfo.workspaceRole,
                subscriptionPlan: userInfo.subscriptionPlan,
                enterpriseDomainBypass
            });

            if (deploymentType === 'self-hosted' || deploymentType === 'cloud') {
                try {
                    const roleResponse = await apiClient.put('/api/auth/update-role', {
                        username,
                        deploymentType
                    });

                    const newToken = roleResponse?.jwt || roleResponse?.token || roleResponse?.data?.jwt || roleResponse?.data?.token;
                    if (newToken) {

                        localStorage.setItem('authToken', newToken);

                        if (window.vscode) {

                            window.vscode.postMessage({ type: 'saveAuthToken', token: newToken });
                        }

                        const roleData = roleResponse?.data || roleResponse;

                        const newIsAdmin = roleData?.isAdmin || false;
                        const newRoles = roleData?.roles || [];

                        setUser({
                            token: newToken,
                            userId: userInfo.userId,
                            username: userInfo.username || username,
                            email: userInfo.email || email,
                            roles: newRoles,
                            isAdmin: newIsAdmin,
                            workspaceId: userInfo.workspaceId,
                            workspaceName: userInfo.workspaceName,
                            workspaceRole: userInfo.workspaceRole,
                            subscriptionPlan: userInfo.subscriptionPlan,
                            enterpriseDomainBypass
                        });

                        const requiresWorkspace = shouldRequireWorkspaceSelection(
                            deploymentType,
                            newIsAdmin || (newRoles && newRoles.includes('ROLE_ADMIN')),
                            userInfo.workspaceId
                        );
                        setNeedsWorkspaceSelection(requiresWorkspace);
                    }
                } catch (roleError) {
                    console.error('[AuthContext] Failed to update role after login:', roleError);

                    const requiresWorkspace = shouldRequireWorkspaceSelection(
                        deploymentType,
                        isAdmin || (roles && roles.includes('ROLE_ADMIN')),
                        userInfo.workspaceId
                    );
                    setNeedsWorkspaceSelection(requiresWorkspace);
                }
            } else {

                const requiresWorkspace = shouldRequireWorkspaceSelection(
                    deploymentType,
                    isAdmin || (roles && roles.includes('ROLE_ADMIN')),
                    userInfo.workspaceId
                );
                setNeedsWorkspaceSelection(requiresWorkspace);
            }

            setSessionExpiredMessage(null);
        } catch (error: any) {
            console.error('[AuthContext]  Login failed:', error);

            if (error?.status === 503 || error?.data?.maintenance === true || error?.maintenance === true) {
                const msg = error?.data?.message || error?.data?.error || error?.message || 'System is under maintenance.';
                setMaintenanceActive(true);
                setMaintenanceMessage(msg);
                return;
            }
            const message = error?.message || error?.data?.message || error?.data?.error || 'Invalid username or password';
            throw new Error(message.includes('Login failed:') ? message : `Login failed: ${message}`);
        }
    };

    const signup = async (username: string, email: string, password: string) => {
        try {

            const response = await apiClient.post('/api/auth/signup', { 
                username, 
                email, 
                password
            });

            const token = response?.jwt || response?.token || response?.data?.jwt || response?.data?.token;

            if (response?.error || response?.data?.error) {
                throw new Error(response?.error || response?.data?.error);
            }

            if (token) {

                localStorage.setItem('authToken', token);

                if (window.vscode) {

                    window.vscode.postMessage({ type: 'saveAuthToken', token });
                }

                const userInfo = decodeToken(token);
                const responseData = response?.data || response;
                const roles = responseData?.roles || userInfo.roles || [];

                const deploymentType = getStoredDeploymentType();
                const isAdmin = responseData?.isAdmin || userInfo.isAdmin || false;

                setUser({ 
                    token,
                    userId: userInfo.userId,
                    username: userInfo.username || username, 
                    email: userInfo.email || email,
                    roles: roles,
                    isAdmin: isAdmin,
                    subscriptionPlan: userInfo.subscriptionPlan
                });

                if (deploymentType === 'self-hosted' || deploymentType === 'cloud') {
                    try {
                        const roleResponse = await apiClient.put('/api/auth/update-role', {
                            username,
                            deploymentType
                        });

                        const newToken = roleResponse?.jwt || roleResponse?.token || roleResponse?.data?.jwt || roleResponse?.data?.token;
                        if (newToken) {

                            localStorage.setItem('authToken', newToken);

                            if (window.vscode) {

                                window.vscode.postMessage({ type: 'saveAuthToken', token: newToken });
                            }

                            const roleData = roleResponse?.data || roleResponse;

                            const newIsAdmin = roleData?.isAdmin || false;
                            const newRoles = roleData?.roles || [];

                            setUser({ 
                                token: newToken,
                                userId: userInfo.userId,
                                username: userInfo.username || username, 
                                email: userInfo.email || email,
                                roles: newRoles,
                                isAdmin: newIsAdmin,
                                subscriptionPlan: userInfo.subscriptionPlan
                            });

                            const requiresWorkspace = shouldRequireWorkspaceSelection(
                                deploymentType,
                                newIsAdmin || (newRoles && newRoles.includes('ROLE_ADMIN')),
                                userInfo.workspaceId
                            );
                            setNeedsWorkspaceSelection(requiresWorkspace);
                        }
                    } catch (roleError) {
                        console.error('[AuthContext] Failed to update role after signup:', roleError);

                        const requiresWorkspace = shouldRequireWorkspaceSelection(
                            deploymentType,
                            isAdmin || (roles && roles.includes('ROLE_ADMIN')),
                            userInfo.workspaceId
                        );
                        setNeedsWorkspaceSelection(requiresWorkspace);
                    }
                } else {

                    const requiresWorkspace = shouldRequireWorkspaceSelection(
                        deploymentType,
                        isAdmin || (roles && roles.includes('ROLE_ADMIN')),
                        userInfo.workspaceId
                    );
                    setNeedsWorkspaceSelection(requiresWorkspace);
                }

                setSessionExpiredMessage(null);
                return { requiresVerification: false };
            }

            const responseData = response?.data || response;
            const message = responseData?.message || 'Registration successful! Please check your email to verify your account.';
            const verificationEmail = responseData?.email || email;
            return { requiresVerification: true, email: verificationEmail, message };
        } catch (error: any) {
            console.error('[AuthContext] Signup failed:', error);

            if (error?.requiresVerification) throw error;
            if (error?.status === 503 || error?.data?.maintenance === true || error?.maintenance === true) {
                const msg = error?.data?.message || error?.data?.error || error?.message || 'System is under maintenance.';
                setMaintenanceActive(true);
                setMaintenanceMessage(msg);
                return { requiresVerification: false };
            }
            const message = error?.message || error?.data?.message || error?.data?.error || 'Could not create account';
            throw new Error(message);
        }
    };

    const forgotPassword = async (email: string): Promise<string> => {
        try {
            const response = await apiClient.post('/api/auth/forgot-password', { email });
            const data = response?.data || response;
            return data?.message || 'If the email exists in our system, a password reset link has been sent.';
        } catch (error: any) {
            const message = error?.message || error?.data?.message || error?.data?.error || 'Failed to process request';
            throw new Error(message);
        }
    };

    const resetPassword = async (token: string, password: string): Promise<string> => {
        try {
            const response = await apiClient.post('/api/auth/reset-password', { token, password });
            const data = response?.data || response;
            return data?.message || 'Password reset successfully!';
        } catch (error: any) {
            const message = error?.message || error?.data?.message || error?.data?.error || 'Failed to reset password';
            throw new Error(message);
        }
    };

    const resendVerification = async (email: string): Promise<string> => {
        try {
            const response = await apiClient.post('/api/auth/resend-verification', { email });
            const data = response?.data || response;
            return data?.message || 'Verification email sent.';
        } catch (error: any) {
            const message = error?.message || error?.data?.message || error?.data?.error || 'Failed to resend verification email';
            throw new Error(message);
        }
    };

    const verifyEmailAndLogin = async (token: string): Promise<string> => {
        const response = await apiClient.get('/api/auth/verify-email', { token });
        const data = response?.data || response;

        const jwt = data?.jwt;
        if (!jwt) {
            if (data?.error) throw new Error(data.error);
            throw new Error('Verification failed - no token received');
        }

        const verifiedEmail = data?.email || decodeToken(jwt).email || '';

        localStorage.setItem('authToken', jwt);
        if (window.vscode) {
            window.vscode.postMessage({ type: 'saveAuthToken', token: jwt });
        }

        const userInfo = decodeToken(jwt);
        const isAdmin = data?.isAdmin || false;

        setUser({
            token: jwt,
            userId: userInfo.userId,
            username: data?.username || userInfo.username,
            email: verifiedEmail || userInfo.email,
            roles: data?.roles || userInfo.roles || [],
            isAdmin,
            workspaceId: userInfo.workspaceId,
            workspaceName: userInfo.workspaceName,
            workspaceRole: userInfo.workspaceRole,
            subscriptionPlan: userInfo.subscriptionPlan,
        });
        setNeedsWorkspaceSelection(!userInfo.workspaceId);
        setSessionExpiredMessage(null);
        return verifiedEmail;
    };

    const applyWorkspaceSession = (workspaceData: any) => {
        if (!workspaceData?.jwt) {
            throw new Error('No token received from workspace selection');
        }
        if (workspaceData.workspaceId) {
            localStorage.setItem('lastWorkspaceId', workspaceData.workspaceId);
        }
        localStorage.removeItem(SKIP_WORKSPACE_MODE_KEY);
        localStorage.removeItem(SUPPRESS_WORKSPACE_AUTO_OPEN_KEY);
        localStorage.setItem('authToken', workspaceData.jwt);
        if (window.vscode) {
            window.vscode.postMessage({ type: 'saveAuthToken', token: workspaceData.jwt });
        }
        const userInfo = decodeToken(workspaceData.jwt);
        const roles = userInfo.roles || user?.roles || [];
        const isAdmin = userInfo.isAdmin || user?.isAdmin || roles.includes('ROLE_ADMIN');
        setUser({
            token: workspaceData.jwt,
            userId: userInfo.userId || user?.userId,
            username: workspaceData.username || userInfo.username,
            email: userInfo.email || user?.email,
            roles,
            isAdmin,
            workspaceId: workspaceData.workspaceId,
            workspaceName: workspaceData.workspaceName,
            workspaceRole: workspaceData.role,
            subscriptionPlan: workspaceData.subscriptionPlan || userInfo.subscriptionPlan || 'FREE',
            enterpriseDomainBypass: user?.enterpriseDomainBypass || getStoredEnterpriseDomainBypass(),
        });
        setNeedsWorkspaceSelection(false);
    };

    const selectWorkspace = (workspaceData: any) => {

        if (workspaceData.skipWorkspace) {

            clearLastOpenedProjectState();
            localStorage.setItem(SKIP_WORKSPACE_MODE_KEY, 'true');
            localStorage.removeItem(SUPPRESS_WORKSPACE_AUTO_OPEN_KEY);
            localStorage.removeItem('lastWorkspaceId');
            apiClient.put('/api/auth/last-opened', { projectId: null, projectName: null, fileId: null, fileName: null }).catch(() => {});

            ignoringWorkspaceRef.current = true;

            if (user) {
                setUser({
                    ...user,
                    workspaceId: undefined,
                    workspaceName: undefined,
                    workspaceRole: undefined
                });
            }
            setNeedsWorkspaceSelection(false);

            return;
        }

        if (!workspaceData.jwt) {
            console.error('[AuthContext] ❌ No JWT in workspaceData:', workspaceData);
            throw new Error('No token received from workspace selection');
        }

        applyWorkspaceSession(workspaceData);
    };

    const switchWorkspace = () => {

        if (!user) {
            console.warn('[AuthContext] No user to switch workspace for');
            return;
        }

        ignoringWorkspaceRef.current = true;
        clearLastOpenedProjectState();
        localStorage.removeItem(SKIP_WORKSPACE_MODE_KEY);
        localStorage.setItem(SUPPRESS_WORKSPACE_AUTO_OPEN_KEY, 'true');
        apiClient.put('/api/auth/last-opened', { projectId: null, projectName: null, fileId: null, fileName: null }).catch(() => {});
        if (window.vscode) {
            window.vscode.postMessage({ type: 'clearLastProjectState' });
        }

        const updatedUser = {
            ...user,
            workspaceId: undefined,
            workspaceName: undefined,
            workspaceRole: undefined
        };
        setUser(updatedUser);

        setNeedsWorkspaceSelection(true);
    };

    const updateSubscriptionPlan = async (planId: string) => {
        if (!user || !user.workspaceId) {
            throw new Error('No workspace selected');
        }

        try {

            const response = await apiClient.patch(`/api/workspaces/${user.workspaceId}/subscription`, {
                subscriptionPlan: planId
            });

            setUser({
                ...user,
                subscriptionPlan: planId
            });

        } catch (error: any) {
            console.error('[AuthContext] Failed to update workspace subscription plan:', error);
            throw error;
        }
    };

    const refreshPermissions = async () => {
        try {
            if (isDesktop()) {
                return;
            }
            const storedToken = localStorage.getItem('authToken');
            if (!storedToken || isTokenExpired(storedToken)) {
                return;
            }

            if (user?.workspaceId) {
                const response = await apiClient.post(`/api/workspaces/${user.workspaceId}/select`);
                const workspaceData = response?.data || response;
                if (workspaceData?.jwt) {
                    applyWorkspaceSession(workspaceData);
                    return;
                }
            }

            const response = await apiClient.get('/api/auth/refresh');
            const data = response?.data || response;

            const newToken = data.jwt;
            if (newToken) {
                localStorage.setItem('authToken', newToken);
                if (window.vscode) {
                    window.vscode.postMessage({ type: 'saveAuthToken', token: newToken });
                }

                const userInfo = decodeToken(newToken);
                setUser({
                    token: newToken,
                    userId: userInfo.userId || user?.userId,
                    username: userInfo.username || user?.username || 'unknown',
                    email: userInfo.email || user?.email,
                    roles: userInfo.roles || user?.roles || [],
                    isAdmin: userInfo.isAdmin || user?.isAdmin || false,
                    workspaceId: userInfo.workspaceId || user?.workspaceId,
                    workspaceName: userInfo.workspaceName || user?.workspaceName,
                    workspaceRole: userInfo.workspaceRole || user?.workspaceRole,
                    subscriptionPlan: userInfo.subscriptionPlan || user?.subscriptionPlan,
                    enterpriseDomainBypass: user?.enterpriseDomainBypass || getStoredEnterpriseDomainBypass()
                });

                const deploymentType = getStoredDeploymentType();
                const requiresWorkspace = shouldRequireWorkspaceSelection(
                    deploymentType,
                    userInfo.isAdmin || (userInfo.roles && userInfo.roles.includes('ROLE_ADMIN')),
                    userInfo.workspaceId
                );
                setNeedsWorkspaceSelection(requiresWorkspace);
            }
        } catch (error: any) {
            console.error('[AuthContext] ❌ Failed to refresh permissions:', error);
            if (error?.status === 503 || error?.data?.maintenance === true || error?.maintenance === true) {
                const msg = error?.data?.message || error?.data?.error || error?.message || 'System is under maintenance.';
                setMaintenanceActive(true);
                setMaintenanceMessage(msg);
            }
        }
    };

    const updateUserRole = async (deploymentType: 'self-hosted' | 'cloud') => {
        if (!user) {
            throw new Error('No user logged in');
        }

        try {

            const response = await apiClient.put('/api/auth/update-role', {
                username: user.username,
                deploymentType
            });

            const token = response?.jwt || response?.token || response?.data?.jwt || response?.data?.token;
            if (token) {

                localStorage.setItem('authToken', token);

                if (window.vscode) {

                    window.vscode.postMessage({ type: 'saveAuthToken', token });
                }

                const responseData = response?.data || response;

                const isAdmin = responseData?.isAdmin || false;
                const roles = responseData?.roles || [];

                setUser({
                    ...user,
                    token,
                    roles,
                    isAdmin
                });

                const requiresWorkspace = shouldRequireWorkspaceSelection(
                    deploymentType,
                    isAdmin || (roles && roles.includes('ROLE_ADMIN')),
                    user?.workspaceId
                );
                setNeedsWorkspaceSelection(requiresWorkspace);
            }
        } catch (error: any) {
            console.error('[AuthContext] Failed to update user role:', error);
            throw error;
        }
    };

    const patchEnterpriseBypass = (bypass: boolean) => {
        try { localStorage.setItem('enterpriseDomainBypass', String(bypass)); } catch {}
        setUser(prev => {
            if (!prev) return prev;

            return { ...prev, enterpriseDomainBypass: bypass };
        });
    };

    const value = {
        user,
        loading,
        needsWorkspaceSelection,
        maintenanceActive,
        maintenanceMessage,
        login,
        signup,
        forgotPassword,
        resetPassword,
        resendVerification,
        verifyEmailAndLogin,
        selectWorkspace,
        switchWorkspace,
        updateSubscriptionPlan,
        updateUserRole,
        refreshPermissions,
        patchEnterpriseBypass,
        logout: () => logout(false),
        sessionExpiredMessage,
    };

    return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>;
};

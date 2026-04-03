
import React, { createContext, useState, useEffect, useCallback, useRef, ReactNode } from 'react';
import apiClient from '../services/apiClient';

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
}

interface AuthContextType {
    user: User | null;
    loading: boolean;
    needsWorkspaceSelection: boolean;
    login: (username: string, password: string) => Promise<void>;
    signup: (username: string, email: string, password: string) => Promise<{ requiresVerification: boolean; email?: string; message?: string }>;
    forgotPassword: (email: string) => Promise<string>;
    resetPassword: (token: string, password: string) => Promise<string>;
    resendVerification: (email: string) => Promise<string>;
    verifyEmailAndLogin: (token: string) => Promise<void>;
    selectWorkspace: (workspaceData: any) => void;
    switchWorkspace: () => void;
    updateSubscriptionPlan: (planId: string) => Promise<void>;
    updateUserRole: (deploymentType: 'self-hosted' | 'cloud') => Promise<void>;
    logout: () => void;
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

// Decode JWT token to check expiration
const isTokenExpired = (token: string): boolean => {
    try {
        const parts = token.split('.');
        if (parts.length !== 3) return true;
        
        const payload = JSON.parse(atob(parts[1].replace(/-/g, '+').replace(/_/g, '/')));
        const exp = payload.exp;
        
        if (!exp) return false; // No expiration set
        
        // Check if token is expired (exp is in seconds, Date.now() is in milliseconds)
        return Date.now() >= exp * 1000;
    } catch (error) {
        console.error('[AuthContext] Error decoding token:', error);
        return true; // If we can't decode, assume expired
    }
};

// Decode JWT token to get user info
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
            subscriptionPlan: payload.subscriptionPlan
        };
    } catch (e) {
        console.error('[AuthContext] Error decoding token:', e);
        return { username: 'unknown' };
    }
};

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
        return false;
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
    // Flag to ignore workspace restoration when switching workspaces
    const ignoringWorkspaceRef = useRef(false);
    const logout = useCallback((showExpiredMessage = false) => {
        console.log('[AuthContext] Logging out...');

        // Always start fresh after logout: do not carry workspace context into next login.
        localStorage.removeItem('lastWorkspaceId');
        localStorage.removeItem(SKIP_WORKSPACE_MODE_KEY);
        ignoringWorkspaceRef.current = false;
        
        setUser(null);
        setNeedsWorkspaceSelection(false);
        if (showExpiredMessage) {
            setSessionExpiredMessage('Session expired. Please login again.');
        }
        if (window.vscode) {
            window.vscode.postMessage({ type: 'logout' });
        } else {
            // Clear local token in browser/web mode.
            localStorage.removeItem('authToken');
        }
        console.log('[AuthContext]  Logout successful');
    }, []);

    // Register unauthorized callback with apiClient
    useEffect(() => {
        apiClient.setUnauthorizedCallback(() => {
            console.log('[AuthContext] API returned 401 - Auto logout');
            logout(true);
        });
    }, [logout]);

    const requestTokenFromVSCode = useCallback(() => {
        if (window.vscode) {
            window.vscode.postMessage({ type: 'requestAuthToken' });
        } else {
            // Browser/Web mode - check localStorage directly
            console.log('[AuthContext] Browser mode - checking localStorage for auth token');
            const token = localStorage.getItem('authToken');
            
            if (token) {
                // Check if token is expired
                if (isTokenExpired(token)) {
                    console.log('[AuthContext] Stored token is expired, clearing');
                    localStorage.removeItem('authToken');
                    setLoading(false);
                    return;
                }
                
                // Decode JWT to get user info
                const userInfo = decodeToken(token);
                const skipWorkspaceMode = isSkipWorkspaceMode();
                const deploymentType = getStoredDeploymentType();
                
                // Cloud users are always admins
                const isAdmin = deploymentType === 'cloud' ? true : (userInfo.isAdmin || false);
                
                const requiresWorkspace = shouldRequireWorkspaceSelection(
                    deploymentType,
                    isAdmin,
                    skipWorkspaceMode ? undefined : userInfo.workspaceId
                );

                // Persist user state from token
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
                    subscriptionPlan: userInfo.subscriptionPlan
                });

                // Workspace selection based on deployment choice and role
                setNeedsWorkspaceSelection(skipWorkspaceMode ? false : requiresWorkspace);
                setSessionExpiredMessage(null);
            }
            
            setLoading(false);
        }
    }, [logout]);

    useEffect(() => {
        requestTokenFromVSCode();

        const handleMessage = (event: MessageEvent) => {
            const message = event.data;
            switch (message.type) {
                case 'storedAuthToken':
                    if (message.token) {
                        // Check if token is expired
                        if (isTokenExpired(message.token)) {
                            console.log('[AuthContext]  Stored token is expired, logging out');
                            logout(true);
                            setLoading(false);
                            return;
                        }
                        
                        // If we're switching workspaces, ignore workspace info from token
                        if (ignoringWorkspaceRef.current) {
                            console.log('[AuthContext] 🚫 Ignoring workspace from storedAuthToken (switching workspaces)');
                            // Keep token but don't restore workspace
                            localStorage.setItem('authToken', message.token);
                            ignoringWorkspaceRef.current = false; // Reset flag
                            setLoading(false);
                            return;
                        }
                        
                        // Decode JWT to get user info
                        const userInfo = decodeToken(message.token);
                        const skipWorkspaceMode = isSkipWorkspaceMode();
                        const deploymentType = getStoredDeploymentType();
                        
                        // Cloud users are always admins
                        const isAdmin = deploymentType === 'cloud' ? true : (userInfo.isAdmin || false);
                        
                        const requiresWorkspace = shouldRequireWorkspaceSelection(
                            deploymentType,
                            isAdmin,
                            skipWorkspaceMode ? undefined : userInfo.workspaceId
                        );

                        // Persist user state from token
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
                            subscriptionPlan: userInfo.subscriptionPlan
                        });

                        // Workspace selection based on deployment choice and role
                        setNeedsWorkspaceSelection(skipWorkspaceMode ? false : requiresWorkspace);
                        // Clear expired message on successful login
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

    // Check token expiration every minute
    useEffect(() => {
        if (!user?.token) return;

        const interval = setInterval(() => {
            if (isTokenExpired(user.token)) {
                console.log('[AuthContext]  Token expired, logging out');
                logout(true);
            }
        }, 60000); // Check every 60 seconds

        return () => clearInterval(interval);
    }, [user?.token, logout]);

    const login = async (username: string, password: string) => {
        try {
            console.log('[AuthContext] Attempting login for user:', username);
            
            // Call actual authentication endpoint through VS Code proxy
            const response = await apiClient.post('/api/auth/login', { 
                username, 
                password 
            });
            
            console.log('[AuthContext] Login response:', response);
            // Backend returns 'jwt' field, not 'token'
            const token = response?.jwt || response?.token || response?.data?.jwt || response?.data?.token;
            const responseData = response?.data || response;
            const roles = responseData?.roles || [];
            const email = responseData?.email || '';
            
            // Cloud users are always admins
            const deploymentType = getStoredDeploymentType();
            const isAdmin = deploymentType === 'cloud' ? true : (responseData?.isAdmin || false);

            if (!token) {
                // Check if it's an error response
                if (response?.error || response?.data?.error) {
                    throw new Error(response?.error || response?.data?.error);
                }
                throw new Error('No token received from server');
            }

            console.log('[AuthContext] User isAdmin:', isAdmin);
            console.log('[AuthContext] Saving token to localStorage...');
            
            // Always save to localStorage for webview API client
            localStorage.setItem('authToken', token);
            console.log('[AuthContext] Token saved. Verify:', !!localStorage.getItem('authToken'));
            
            if (window.vscode) {
                // Also save to VS Code secure storage for persistence
                window.vscode.postMessage({ type: 'saveAuthToken', token });
            }
            
            // Decode JWT to get user info (for workspace data if present)
            const userInfo = decodeToken(token);
            
            // Auto-select last workspace if available and user doesn't have workspace in JWT
            const lastWorkspaceId = localStorage.getItem('lastWorkspaceId');
            const skipWorkspaceMode = isSkipWorkspaceMode();
            console.log('[AuthContext] Checking auto-select: lastWorkspaceId=', lastWorkspaceId, 'userInfo.workspaceId=', userInfo.workspaceId, 'deploymentType=', deploymentType, 'isAdmin=', isAdmin, 'skipWorkspaceMode=', skipWorkspaceMode);
            
            if (!skipWorkspaceMode && !userInfo.workspaceId && lastWorkspaceId && (deploymentType === 'cloud' || isAdmin)) {
                console.log('[AuthContext] 🔄 Auto-selecting last workspace after login:', lastWorkspaceId);
                try {
                    const selectResponse = await apiClient.post(`/api/workspaces/${lastWorkspaceId}/select`);
                    console.log('[AuthContext] 📥 Workspace select response:', selectResponse);
                    
                    if (selectResponse.jwt) {
                        console.log('[AuthContext] ✅ Auto-selected workspace successfully');
                        // Update with workspace-scoped token
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
                            subscriptionPlan: selectResponse.subscriptionPlan || wsUserInfo.subscriptionPlan
                        };
                        console.log('[AuthContext] 👤 Setting user with workspace:', userData);
                        setUser(userData);
                        setNeedsWorkspaceSelection(false);
                        setSessionExpiredMessage(null);
                        console.log('[AuthContext] ✅ Login complete with auto-selected workspace');
                        // Skip the role update flow since we have workspace-scoped token
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
                console.log('[AuthContext] ℹ️ Skipping auto-select (already has workspace or no last workspace)');
            }
            
            // Set user data (either workspace wasn't auto-selected, or user already has workspace in JWT)
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
                subscriptionPlan: userInfo.subscriptionPlan
            });
            
            // After login, update user role based on deployment type
            if (deploymentType === 'self-hosted' || deploymentType === 'cloud') {
                try {
                    console.log('[AuthContext] Updating role based on deployment type:', deploymentType);
                    const roleResponse = await apiClient.put('/api/auth/update-role', {
                        username,
                        deploymentType
                    });
                    
                    const newToken = roleResponse?.jwt || roleResponse?.token || roleResponse?.data?.jwt || roleResponse?.data?.token;
                    if (newToken) {
                        // Always save to localStorage for webview API client
                        localStorage.setItem('authToken', newToken);
                        console.log('[AuthContext] Updated token saved. Verify:', !!localStorage.getItem('authToken'));
                        
                        if (window.vscode) {
                            // Also save to VS Code secure storage
                            window.vscode.postMessage({ type: 'saveAuthToken', token: newToken });
                        }
                        
                        const roleData = roleResponse?.data || roleResponse;
                        // Cloud users are always admins
                        const newIsAdmin = deploymentType === 'cloud' ? true : (roleData?.isAdmin || false);
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
                            subscriptionPlan: userInfo.subscriptionPlan
                        });
                        
                        // Determine workspace selection based on deployment type and role
                        const requiresWorkspace = shouldRequireWorkspaceSelection(
                            deploymentType,
                            newIsAdmin || (newRoles && newRoles.includes('ROLE_ADMIN')),
                            userInfo.workspaceId
                        );
                        setNeedsWorkspaceSelection(requiresWorkspace);
                    }
                } catch (roleError) {
                    console.error('[AuthContext] Failed to update role after login:', roleError);
                    // Continue with existing role
                    const requiresWorkspace = shouldRequireWorkspaceSelection(
                        deploymentType,
                        isAdmin || (roles && roles.includes('ROLE_ADMIN')),
                        userInfo.workspaceId
                    );
                    setNeedsWorkspaceSelection(requiresWorkspace);
                }
            } else {
                // No deployment type - use existing role
                const requiresWorkspace = shouldRequireWorkspaceSelection(
                    deploymentType,
                    isAdmin || (roles && roles.includes('ROLE_ADMIN')),
                    userInfo.workspaceId
                );
                setNeedsWorkspaceSelection(requiresWorkspace);
            }
            
            // Clear expired message on successful login
            setSessionExpiredMessage(null);
        } catch (error: any) {
            console.error('[AuthContext]  Login failed:', error);
            const message = error?.message || error?.data?.message || error?.data?.error || 'Invalid username or password';
            throw new Error(message.includes('Login failed:') ? message : `Login failed: ${message}`);
        }
    };
    
    const signup = async (username: string, email: string, password: string) => {
        try {
            console.log('[AuthContext] Attempting signup for user:', username);
            
            // Call actual signup endpoint through VS Code proxy (without role)
            const response = await apiClient.post('/api/auth/signup', { 
                username, 
                email, 
                password
            });
            
            console.log('[AuthContext] Signup response:', response);
            
            // Backend returns 'jwt' field if immediate login, not 'token'
            const token = response?.jwt || response?.token || response?.data?.jwt || response?.data?.token;

            // Check for errors first
            if (response?.error || response?.data?.error) {
                throw new Error(response?.error || response?.data?.error);
            }

            // If we have a token, user is logged in immediately (no email verification)
            if (token) {
                console.log('[AuthContext] Saving token to localStorage...');
                // Always save to localStorage for webview API client
                localStorage.setItem('authToken', token);
                
                if (window.vscode) {
                    // Also save to VS Code secure storage
                    window.vscode.postMessage({ type: 'saveAuthToken', token });
                }
                
                // Decode JWT to get user info
                const userInfo = decodeToken(token);
                const responseData = response?.data || response;
                const roles = responseData?.roles || userInfo.roles || [];
                
                // Cloud users are always admins
                const deploymentType = getStoredDeploymentType();
                const isAdmin = deploymentType === 'cloud' ? true : (responseData?.isAdmin || userInfo.isAdmin || false);
                
                console.log('[AuthContext] Initial signup - deploymentType:', deploymentType, 'isAdmin:', isAdmin);
                
                setUser({ 
                    token,
                    userId: userInfo.userId,
                    username: userInfo.username || username, 
                    email: userInfo.email || email,
                    roles: roles,
                    isAdmin: isAdmin,
                    subscriptionPlan: userInfo.subscriptionPlan
                });
                
                // After signup, update user role based on deployment type
                if (deploymentType === 'self-hosted' || deploymentType === 'cloud') {
                    try {
                        console.log('[AuthContext] Updating role based on deployment type:', deploymentType);
                        const roleResponse = await apiClient.put('/api/auth/update-role', {
                            username,
                            deploymentType
                        });
                        
                        const newToken = roleResponse?.jwt || roleResponse?.token || roleResponse?.data?.jwt || roleResponse?.data?.token;
                        if (newToken) {
                            // Always save to localStorage for webview API client
                            localStorage.setItem('authToken', newToken);
                            
                            if (window.vscode) {
                                // Also save to VS Code secure storage
                                window.vscode.postMessage({ type: 'saveAuthToken', token: newToken });
                            }
                            
                            const roleData = roleResponse?.data || roleResponse;
                            // Cloud users are always admins, don't let role update override this
                            const newIsAdmin = deploymentType === 'cloud' ? true : (roleData?.isAdmin || false);
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
                            console.log('[AuthContext] After role update - isAdmin:', newIsAdmin, 'needsWorkspaceSelection:', requiresWorkspace);
                        }
                    } catch (roleError) {
                        console.error('[AuthContext] Failed to update role after signup:', roleError);
                        // Continue with default role assignment
                        const requiresWorkspace = shouldRequireWorkspaceSelection(
                            deploymentType,
                            isAdmin || (roles && roles.includes('ROLE_ADMIN')),
                            userInfo.workspaceId
                        );
                        setNeedsWorkspaceSelection(requiresWorkspace);
                    }
                } else {
                    // No deployment type - use default role assignment
                    const requiresWorkspace = shouldRequireWorkspaceSelection(
                        deploymentType,
                        isAdmin || (roles && roles.includes('ROLE_ADMIN')),
                        userInfo.workspaceId
                    );
                    setNeedsWorkspaceSelection(requiresWorkspace);
                }
                
                // Clear expired message on successful signup
                setSessionExpiredMessage(null);
                return { requiresVerification: false };
            }

            // No token means email verification required
            const responseData = response?.data || response;
            const message = responseData?.message || 'Registration successful! Please check your email to verify your account.';
            const verificationEmail = responseData?.email || email;
            console.log('[AuthContext] Signup successful - awaiting email verification:', message);
            return { requiresVerification: true, email: verificationEmail, message };
        } catch (error: any) {
            console.error('[AuthContext] Signup failed:', error);
            // Re-throw verification results as-is
            if (error?.requiresVerification) throw error;
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

    const verifyEmailAndLogin = async (token: string): Promise<void> => {
        console.log('[AuthContext] Verifying email and auto-logging in...');
        const response = await apiClient.get('/api/auth/verify', { token });
        const data = response?.data || response;

        const jwt = data?.jwt;
        if (!jwt) {
            if (data?.error) throw new Error(data.error);
            throw new Error('Verification failed - no token received');
        }

        // Save token
        localStorage.setItem('authToken', jwt);
        if (window.vscode) {
            window.vscode.postMessage({ type: 'saveAuthToken', token: jwt });
        }

        const userInfo = decodeToken(jwt);
        const deploymentType = getStoredDeploymentType();
        const isAdmin = deploymentType === 'cloud' ? true : (data?.isAdmin || false);

        setUser({
            token: jwt,
            userId: userInfo.userId,
            username: data?.username || userInfo.username,
            email: data?.email || userInfo.email,
            roles: data?.roles || userInfo.roles || [],
            isAdmin,
            workspaceId: userInfo.workspaceId,
            workspaceName: userInfo.workspaceName,
            workspaceRole: userInfo.workspaceRole,
            subscriptionPlan: userInfo.subscriptionPlan,
        });
        setNeedsWorkspaceSelection(!userInfo.workspaceId);
        setSessionExpiredMessage(null);
        console.log('[AuthContext] ✅ Email verified and auto-logged in as', data?.username);
    };

    const selectWorkspace = (workspaceData: any) => {
        console.log('[AuthContext] 📥 selectWorkspace called with:', workspaceData);
        console.log('[AuthContext] Current user before selection:', user);
        
        // Handle skip workspace case - user continues without workspace
        if (workspaceData.skipWorkspace) {
            console.log('[AuthContext] ✅ User skipped workspace selection, continuing to editor');
            console.log('[AuthContext] Setting needsWorkspaceSelection to false');
            console.log('[AuthContext] User will proceed to editor without workspace context');

            localStorage.setItem(SKIP_WORKSPACE_MODE_KEY, 'true');
            localStorage.removeItem('lastWorkspaceId');
            
            // Set flag to ignore workspace restoration from next storedAuthToken message
            ignoringWorkspaceRef.current = true;
            console.log('[AuthContext] 🚫 Set ignoringWorkspaceRef to prevent workspace restoration');
            
            if (user) {
                setUser({
                    ...user,
                    workspaceId: undefined,
                    workspaceName: undefined,
                    workspaceRole: undefined
                });
            }
            setNeedsWorkspaceSelection(false);
            // User stays logged in but without workspace context
            // The editor will work in non-workspace mode
            return;
        }
        
        if (!workspaceData.jwt) {
            console.error('[AuthContext] ❌ No JWT in workspaceData:', workspaceData);
            throw new Error('No token received from workspace selection');
        }

        // Save workspace ID for auto-selection on next login
        if (workspaceData.workspaceId) {
            localStorage.setItem('lastWorkspaceId', workspaceData.workspaceId);
            console.log('[AuthContext] 💾 Saved workspace for future auto-login:', workspaceData.workspaceId);
        }
        localStorage.removeItem(SKIP_WORKSPACE_MODE_KEY);

        // Save new workspace-scoped token
        // Always save to localStorage for webview API client
        localStorage.setItem('authToken', workspaceData.jwt);
        
        if (window.vscode) {
            // Also save to VS Code secure storage
            window.vscode.postMessage({ type: 'saveAuthToken', token: workspaceData.jwt });
        }

        // Decode JWT to get all user info
        const userInfo = decodeToken(workspaceData.jwt);
        
        // Preserve roles and isAdmin from current user or use decoded values
        const roles = userInfo.roles || user?.roles || [];
        const isAdmin = userInfo.isAdmin || user?.isAdmin || roles.includes('ROLE_ADMIN');
        
        setUser({ 
            token: workspaceData.jwt,
            userId: userInfo.userId || user?.userId,
            username: workspaceData.username || userInfo.username, 
            email: userInfo.email || user?.email,
            roles: roles,
            isAdmin: isAdmin,
            workspaceId: workspaceData.workspaceId,
            workspaceName: workspaceData.workspaceName,
            workspaceRole: workspaceData.role,
            subscriptionPlan: workspaceData.subscriptionPlan || 'FREE' // Workspace subscription plan
        });
        setNeedsWorkspaceSelection(false);
        console.log('[AuthContext]  Workspace selection complete');
    };

    const switchWorkspace = () => {
        console.log('[AuthContext] 🔄 switchWorkspace called');
        console.log('[AuthContext] Current user:', user);
        console.log('[AuthContext] Current needsWorkspaceSelection:', needsWorkspaceSelection);
        
        if (!user) {
            console.warn('[AuthContext] No user to switch workspace for');
            return;
        }

        // Set flag to ignore workspace restoration from next storedAuthToken message
        ignoringWorkspaceRef.current = true;
        console.log('[AuthContext] 🚫 Set ignoringWorkspaceRef to prevent workspace restoration');
        localStorage.removeItem(SKIP_WORKSPACE_MODE_KEY);
        
        // Clear workspace-specific data but keep the user logged in with token
        const updatedUser = {
            ...user,
            workspaceId: undefined,
            workspaceName: undefined,
            workspaceRole: undefined
        };
        console.log('[AuthContext] Setting user to (no workspace):', updatedUser);
        setUser(updatedUser);
        
        console.log('[AuthContext] Setting needsWorkspaceSelection to true');
        setNeedsWorkspaceSelection(true);
        console.log('[AuthContext] ✅ Workspace switch initiated - should show workspace selection');
    };

    const updateSubscriptionPlan = async (planId: string) => {
        if (!user || !user.workspaceId) {
            throw new Error('No workspace selected');
        }

        try {
            // Update the workspace subscription plan
            const response = await apiClient.patch(`/api/workspaces/${user.workspaceId}/subscription`, {
                subscriptionPlan: planId
            });

            // Update user context with new workspace subscription plan
            setUser({
                ...user,
                subscriptionPlan: planId
            });

            console.log('[AuthContext] Workspace subscription plan updated to:', planId);
        } catch (error: any) {
            console.error('[AuthContext] Failed to update workspace subscription plan:', error);
            throw error;
        }
    };

    const updateUserRole = async (deploymentType: 'self-hosted' | 'cloud') => {
        if (!user) {
            throw new Error('No user logged in');
        }

        try {
            console.log('[AuthContext] Updating user role for deployment type:', deploymentType);
            
            const response = await apiClient.put('/api/auth/update-role', {
                username: user.username,
                deploymentType
            });

            console.log('[AuthContext] Role update response:', response);
            
            // Update token and user state with new role
            const token = response?.jwt || response?.token || response?.data?.jwt || response?.data?.token;
            if (token) {
                // Always save to localStorage for webview API client
                localStorage.setItem('authToken', token);
                
                if (window.vscode) {
                    // Also save to VS Code secure storage
                    window.vscode.postMessage({ type: 'saveAuthToken', token });
                }
                
                const responseData = response?.data || response;
                // Cloud users are always admins
                const isAdmin = deploymentType === 'cloud' ? true : (responseData?.isAdmin || false);
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

    const value = {
        user,
        loading,
        needsWorkspaceSelection,
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
        logout: () => logout(false),
        sessionExpiredMessage,
    };

    return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>;
};

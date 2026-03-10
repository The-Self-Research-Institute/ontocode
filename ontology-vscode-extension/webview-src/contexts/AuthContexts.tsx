
import React, { createContext, useState, useEffect, useCallback, ReactNode } from 'react';
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
    signup: (username: string, email: string, password: string) => Promise<void>;
    selectWorkspace: (workspaceData: any) => void;
    switchWorkspace: () => void;
    updateSubscriptionPlan: (planId: string) => Promise<void>;
    updateUserRole: (deploymentType: 'self-hosted' | 'cloud') => Promise<void>;
    logout: () => void;
    sessionExpiredMessage: string | null;
}

type DeploymentType = 'self-hosted' | 'cloud';

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

    const logout = useCallback((showExpiredMessage = false) => {
        console.log('[AuthContext] Logging out...');
        setUser(null);
        setNeedsWorkspaceSelection(false);
        if (showExpiredMessage) {
            setSessionExpiredMessage('Session expired. Please login again.');
        }
        if (window.vscode) {
            window.vscode.postMessage({ type: 'logout' });
        } else {
            // Clear localStorage in browser/web mode
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
                const deploymentType = getStoredDeploymentType();
                
                // Cloud users are always admins
                const isAdmin = deploymentType === 'cloud' ? true : (userInfo.isAdmin || false);
                
                const requiresWorkspace = shouldRequireWorkspaceSelection(
                    deploymentType,
                    isAdmin,
                    userInfo.workspaceId
                );

                // Persist user state from token
                setUser({ 
                    token: token,
                    userId: userInfo.userId,
                    username: userInfo.username, 
                    email: userInfo.email,
                    roles: userInfo.roles,
                    isAdmin: isAdmin,
                    workspaceId: userInfo.workspaceId,
                    workspaceName: userInfo.workspaceName,
                    workspaceRole: userInfo.workspaceRole,
                    subscriptionPlan: userInfo.subscriptionPlan
                });

                // Workspace selection based on deployment choice and role
                setNeedsWorkspaceSelection(requiresWorkspace);
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
                        // Decode JWT to get user info
                        const userInfo = decodeToken(message.token);
                        const deploymentType = getStoredDeploymentType();
                        
                        // Cloud users are always admins
                        const isAdmin = deploymentType === 'cloud' ? true : (userInfo.isAdmin || false);
                        
                        const requiresWorkspace = shouldRequireWorkspaceSelection(
                            deploymentType,
                            isAdmin,
                            userInfo.workspaceId
                        );

                        // Persist user state from token
                        setUser({ 
                            token: message.token,
                            userId: userInfo.userId,
                            username: userInfo.username, 
                            email: userInfo.email,
                            roles: userInfo.roles,
                            isAdmin: isAdmin,
                            workspaceId: userInfo.workspaceId,
                            workspaceName: userInfo.workspaceName,
                            workspaceRole: userInfo.workspaceRole,
                            subscriptionPlan: userInfo.subscriptionPlan
                        });

                        // Workspace selection based on deployment choice and role
                        setNeedsWorkspaceSelection(requiresWorkspace);
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
            console.log('[AuthContext] Saving token...');
            if (window.vscode) {
                // VS Code extension mode - save to secure storage
                window.vscode.postMessage({ type: 'saveAuthToken', token });
            } else {
                // Browser/Web mode - save to localStorage
                localStorage.setItem('authToken', token);
            }
            
            // Decode JWT to get user info (for workspace data if present)
            const userInfo = decodeToken(token);
            
            // Set user data
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
                        if (window.vscode) {
                            window.vscode.postMessage({ type: 'saveAuthToken', token: newToken });
                        } else {
                            localStorage.setItem('authToken', newToken);
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
                console.log('[AuthContext] Saving token...');
                if (window.vscode) {
                    window.vscode.postMessage({ type: 'saveAuthToken', token });
                } else {
                    localStorage.setItem('authToken', token);
                }
                
                // Decode JWT to get user info
                const userInfo = decodeToken(token);
                const responseData = response?.data || response;
                const roles = responseData?.roles || userInfo.roles || [];
                
                // Cloud users are always admins
                const deploymentType = getStoredDeploymentType();
                const isAdmin = deploymentType === 'cloud' ? true : (responseData?.isAdmin || userInfo.isAdmin || false);
                
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
                            if (window.vscode) {
                                window.vscode.postMessage({ type: 'saveAuthToken', token: newToken });
                            } else {
                                localStorage.setItem('authToken', newToken);
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
                return;
            }

            // No token means email verification required
            const message = response?.message || response?.data?.message || 'Registration successful! Please check your email to verify your account.';
            console.log('[AuthContext]  Signup successful - awaiting email verification:', message);
            // Show success message to user through a custom result
            throw { success: true, message };
        } catch (error: any) {
            console.error('[AuthContext]  Signup failed:', error);
            const message = error?.message || error?.data?.message || error?.data?.error || 'Could not create account';
            throw new Error(message);
        }
    };

    const selectWorkspace = (workspaceData: any) => {
        console.log('[AuthContext] Workspace selected:', workspaceData);
        
        // Handle skip workspace case - user continues without workspace
        if (workspaceData.skipWorkspace) {
            console.log('[AuthContext] User skipped workspace selection, continuing to editor');
            setNeedsWorkspaceSelection(false);
            // User stays logged in but without workspace context
            return;
        }
        
        if (!workspaceData.jwt) {
            throw new Error('No token received from workspace selection');
        }

        // Save new workspace-scoped token
        if (window.vscode) {
            window.vscode.postMessage({ type: 'saveAuthToken', token: workspaceData.jwt });
        } else {
            localStorage.setItem('authToken', workspaceData.jwt);
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
        console.log('[AuthContext] Switching workspace - going back to workspace selection');
        // Clear workspace-specific data but keep the user logged in
        if (user) {
            setUser({
                ...user,
                workspaceId: undefined,
                workspaceName: undefined,
                workspaceRole: undefined
            });
        }
        setNeedsWorkspaceSelection(true);
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
                if (window.vscode) {
                    window.vscode.postMessage({ type: 'saveAuthToken', token });
                } else {
                    localStorage.setItem('authToken', token);
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
        selectWorkspace,
        switchWorkspace,
        updateSubscriptionPlan,
        updateUserRole,
        logout: () => logout(false),
        sessionExpiredMessage,
    };

    return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>;
};



import React, { createContext, useState, useEffect, useCallback, ReactNode } from 'react';
import apiClient from '../services/apiClient';

interface User {
    token: string;
    username: string;
}

interface AuthContextType {
    user: User | null;
    loading: boolean;
    login: (username: string, password: string) => Promise<void>;
    signup: (username: string, email: string, password: string) => Promise<void>;
    logout: () => void;
}

export const AuthContext = createContext<AuthContextType | undefined>(undefined);

export const AuthProvider: React.FC<{ children: ReactNode }> = ({ children }) => {
    const [user, setUser] = useState<User | null>(null);
    const [loading, setLoading] = useState(true);

    const requestTokenFromVSCode = useCallback(() => {
        if (window.vscode) {
            window.vscode.postMessage({ type: 'requestAuthToken' });
        } else {
            console.warn("Not in a VSCode webview environment. Authentication will not persist.");
            setLoading(false);
        }
    }, []);

    useEffect(() => {
        requestTokenFromVSCode();

        const handleMessage = (event: MessageEvent) => {
            const message = event.data;
            switch (message.type) {
                case 'storedAuthToken':
                    if (message.token) {
                        // In a real app, decode JWT to get user info. For now, we'll mock it.
                        setUser({ token: message.token, username: 'vscode_user' });
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
    }, [requestTokenFromVSCode]);

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

            if (!token) {
                // Check if it's an error response
                if (response?.error || response?.data?.error) {
                    throw new Error(response?.error || response?.data?.error);
                }
                throw new Error('No token received from server');
            }

            console.log('[AuthContext] Saving token to VS Code...');
            if (window.vscode) {
                window.vscode.postMessage({ type: 'saveAuthToken', token });
            }
            
            setUser({ token, username });
            console.log('[AuthContext] ✅ Login successful');
        } catch (error: any) {
            console.error('[AuthContext] ❌ Login failed:', error);
            const message = error?.message || error?.data?.message || error?.data?.error || 'Invalid username or password';
            throw new Error(message.includes('Login failed:') ? message : `Login failed: ${message}`);
        }
    };
    
    const signup = async (username: string, email: string, password: string) => {
        try {
            console.log('[AuthContext] Attempting signup for user:', username);
            
            // Call actual signup endpoint through VS Code proxy
            const response = await apiClient.post('/api/auth/signup', { 
                username, 
                email, 
                password 
            });
            
            console.log('[AuthContext] Signup response:', response);
            
            // Check if signup requires email verification
            if (response?.message || response?.data?.message) {
                const message = response?.message || response?.data?.message;
                console.log('[AuthContext] ℹ️ Signup requires verification:', message);
                // Show verification message to user
                throw new Error(message);
            }

            // Backend returns 'jwt' field if immediate login, not 'token'
            const token = response?.jwt || response?.token || response?.data?.jwt || response?.data?.token;

            if (!token) {
                // Check if it's an error response
                if (response?.error || response?.data?.error) {
                    throw new Error(response?.error || response?.data?.error);
                }
                throw new Error('Registration successful! Please check your email to verify your account.');
            }

            console.log('[AuthContext] Saving token to VS Code...');
            if (window.vscode) {
                window.vscode.postMessage({ type: 'saveAuthToken', token });
            }
            
            setUser({ token, username });
            console.log('[AuthContext] ✅ Signup successful');
        } catch (error: any) {
            console.error('[AuthContext] ❌ Signup failed:', error);
            const message = error?.message || error?.data?.message || error?.data?.error || 'Could not create account';
            throw new Error(message);
        }
    };

    const logout = () => {
        console.log('[AuthContext] Logging out...');
        setUser(null);
        if (window.vscode) {
            window.vscode.postMessage({ type: 'logout' });
        }
        console.log('[AuthContext] ✅ Logout successful');
    };

    const value = {
        user,
        loading,
        login,
        signup,
        logout,
    };

    return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>;
};



import React, { createContext, useState, useEffect, useCallback, ReactNode } from 'react';
import axios from 'axios';

const GATEWAY_URL = 'http://localhost:8082';

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
        // This is a mock API call. Replace with your actual authentication endpoint.
        // The endpoint should return a JWT token upon successful login.
        try {
            // const response = await axios.post(`${GATEWAY_URL}/api/auth/login`, { username, password });
            // const { token } = response.data;
            
            // Mocking successful login
            await new Promise(res => setTimeout(res, 500));
            const token = `mock-token-for-${username}`;

            if (window.vscode) {
                window.vscode.postMessage({ type: 'saveAuthToken', token });
            }
            setUser({ token, username });
        } catch (error) {
            console.error("Login failed:", error);
            throw new Error("Invalid username or password.");
        }
    };
    
    const signup = async (username: string, email: string, password: string) => {
        try {
            // Replace with your actual signup endpoint.
            // const response = await axios.post(`${GATEWAY_URL}/api/auth/signup`, { username, email, password });
            // const { token } = response.data;
            
            // Mocking successful signup
            await new Promise(res => setTimeout(res, 500));
            const token = `mock-token-for-${username}`;

            if (window.vscode) {
                window.vscode.postMessage({ type: 'saveAuthToken', token });
            }
            setUser({ token, username });
        } catch (error) {
            console.error("Signup failed:", error);
            throw new Error("Could not create account. The username or email may already be taken.");
        }
    };

    const logout = () => {
        setUser(null);
        if (window.vscode) {
            window.vscode.postMessage({ type: 'logout' });
        }
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

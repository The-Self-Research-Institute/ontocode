
import React, { useState, useEffect, useCallback } from 'react';
import { AuthContext } from '../custom-hook/useAuth';
import apiClient from '../services/apiClient'; 

declare global {
    interface Window {
        vscode?: {
            postMessage: (message: unknown) => void;
        };
    }
}

interface User {
    id: number;
    username: string;
    email: string;
}

const fetchUser = async (token: string): Promise<User | null> => {
    try {

        apiClient.defaults.headers.common['Authorization'] = `Bearer ${token}`;

        console.warn("Using mock user data. Please implement a '/api/auth/me' endpoint.");
        return { id: 1, username: 'user_from_token', email: 'user@example.com' };

    } catch (error) {
        console.error("Failed to fetch user data:", error);
        return null;
    }
};


export const AuthProvider: React.FC<{ children: React.ReactNode }> = ({ children }) => {
    const [user, setUser] = useState<User | null>(null);
    const [loading, setLoading] = useState(true);

    const handleAuthentication = useCallback(async (token: string | null) => {
        if (token) {
            localStorage.setItem('authToken', token);
            const userData = await fetchUser(token);
            setUser(userData);
        } else {
            localStorage.removeItem('authToken');
            apiClient.defaults.headers.common['Authorization'] = '';
            setUser(null);
        }
        setLoading(false);
    }, []);

    useEffect(() => {
        const handleMessage = (event: MessageEvent) => {
            const message = event.data;
            switch (message.type) {
                case 'storedAuthToken':
                    handleAuthentication(message.token);
                    break;
            }
        };
        window.addEventListener('message', handleMessage);

        if (window.vscode) {
            window.vscode.postMessage({ type: 'requestAuthToken' });
        } else {
            const token = localStorage.getItem('authToken');
            handleAuthentication(token);
        }
        return () => window.removeEventListener('message', handleMessage);
    }, [handleAuthentication]);

    const login = async (username: string, password: string) => {
        try {
            const response = await fetch('http://localhost:8082/api/auth/login', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ username, password })
            });

            if (response.ok) {
                const data = await response.json();
                const token = data.jwt;

                if (window.vscode) {
                    window.vscode.postMessage({ type: 'saveAuthToken', token: token });
                    window.vscode.postMessage({ type: 'info', value: 'Login successful! Token sent to VS Code secure storage.' });
                }
                await handleAuthentication(token);

            } else {
                const errorData = await response.json();
                throw new Error(errorData.message || 'Login failed');
            }
        } catch (error) {
            if (window.vscode) {
                window.vscode.postMessage({ type: 'error', value: `Login failed: ${error instanceof Error ? error.message : String(error)}` });
            }
            throw error;
        }
    };

    const signup = async (username: string, email: string, password: string) => {
        try {
            const response = await fetch('http://localhost:8082/api/auth/signup', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ username, email, password })
            });

            if (response.ok) {
                const data = await response.json();
                if (window.vscode) {
                    window.vscode.postMessage({
                        type: 'info',
                        value: data.message || 'Signup successful! Please check your email to verify your account.'
                    });
                }
            } else {
                const errorData = await response.json();
                throw new Error(errorData.message || 'Signup failed.');
            }
        } catch (error) {
            if (window.vscode) {
                window.vscode.postMessage({
                    type: 'error',
                    value: `Signup failed: ${error instanceof Error ? error.message : String(error)}`
                });
            }
            throw error;
        }
    };

    const logout = () => {
        handleAuthentication(null);
        if (window.vscode) {
            window.vscode.postMessage({ type: 'logout' });
            window.vscode.postMessage({ type: 'info', value: 'Logged out successfully' });
        }
    };

    return (
        <AuthContext.Provider value={{ user, loading, login, signup, logout }}>
            {children}
        </AuthContext.Provider>
    );
};
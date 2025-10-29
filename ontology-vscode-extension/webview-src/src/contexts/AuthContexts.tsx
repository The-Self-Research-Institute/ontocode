

import React, { useState, useEffect, useCallback, ReactNode } from 'react';
import { AuthContext } from '../custom-hook/useAuth';

interface User {
    id: number;
    email: string;
    token: string;
    username: string;
}

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
                        setUser({ token: message.token, username: 'vscode_user', id: 1, email: 'praneshkk1@gmail.com' });
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

    const login = async (username: string) => {
        try {
            await new Promise(res => setTimeout(res, 500));
            const token = `mock-token-for-${username}`;

            if (window.vscode) {
                window.vscode.postMessage({ type: 'saveAuthToken', token });
            }
            setUser({ token, username, id: 1, email: 'praneshkk1@gmail.com' });
        } catch (error) {
            console.error("Login failed:", error);
            throw new Error("Invalid username or password.");
        }
    };
    
    const signup = async (username: string) => {
        try {
            await new Promise(res => setTimeout(res, 500));
            const token = `mock-token-for-${username}`;

            if (window.vscode) {
                window.vscode.postMessage({ type: 'saveAuthToken', token });
            }
            setUser({ token, username, id: 1, email: 'praneshkk1@gmail.com' });
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

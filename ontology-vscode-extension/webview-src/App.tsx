
import React, { useState } from 'react';
import { useAuth } from './custom-hook/useAuth';
import { CollaborationProvider } from './contexts/CollaborationContext';
import { EntityPreferencesProvider } from './contexts/EntityPreferencesContext';
import { ThemeProvider } from './contexts/ThemeContext';
import Dashboard from './components/Dashboard';
import LoginForm from './components/LoginForm';
import SignupForm from './components/SignupForm';
import { Loader2 } from 'lucide-react';

const AppContent = () => {
    const { user, loading } = useAuth();
    const [isLoginView, setIsLoginView] = useState(true);

    const toggleFormView = () => setIsLoginView(!isLoginView);

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

    if (user) {
        return <Dashboard />;
    } else {
        return isLoginView ? (
            <LoginForm onToggleForm={toggleFormView} />
        ) : (
            <SignupForm onToggleForm={toggleFormView} />
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

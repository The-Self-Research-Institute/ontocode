import { useState } from 'react';
import LoginForm from './components/LoginForm';
import SignupForm from './components/SignupForm';
import Dashboard from './components/DahboardForm';
import { useAuth } from './custom-hook/useAuth';
import { FileProvider } from './contexts/FileContexts';
import ErrorBoundary from './components/ErrorBoundary';

const AppContent = () => {
    const { user, loading } = useAuth();
    const [isLoginView, setIsLoginView] = useState(true);

    const toggleFormView = () => setIsLoginView(!isLoginView);

    if (loading) {
        return (
            <div className="min-h-screen flex items-center justify-center bg-gray-100">
                <div className="text-lg text-gray-600">Loading...</div>
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
        <ErrorBoundary>
            <FileProvider>
                <AppContent />
            </FileProvider>
        </ErrorBoundary>
    );
};

export default App;
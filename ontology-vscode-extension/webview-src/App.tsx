
import React, { useState } from 'react';
import { useAuth } from './custom-hook/useAuth';
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
            <div className="min-h-screen flex items-center justify-center bg-gray-100">
                <div className="text-center p-8">
                    <div className="w-20 h-20 bg-gradient-to-br from-purple-500 to-indigo-600 rounded-2xl flex items-center justify-center shadow-lg mx-auto mb-6">
                        <Loader2 size={40} className="text-white animate-spin" />
                    </div>
                    <h2 className="text-2xl font-bold text-gray-800 mb-3">
                        Initializing OntoCode
                    </h2>
                    <p className="text-sm text-gray-500">
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
    return <AppContent />;
};

export default App;

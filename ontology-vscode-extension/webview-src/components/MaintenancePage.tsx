import React from 'react';
import { Wrench } from 'lucide-react';

interface MaintenancePageProps {
    message?: string;
}

const MaintenancePage: React.FC<MaintenancePageProps> = ({ message }) => {
    const defaultMessage =
        "We're performing scheduled maintenance. Please check back shortly.";

    return (
        <div
            className="min-h-screen flex items-center justify-center p-4"
            style={{ backgroundColor: 'var(--color-background)' }}
        >
            <div
                className="max-w-md w-full rounded-2xl shadow-xl p-10 text-center"
                style={{
                    backgroundColor: 'var(--color-surface)',
                    border: '1px solid var(--color-border)',
                }}
            >
                <div
                    className="w-20 h-20 rounded-full flex items-center justify-center mx-auto mb-6"
                    style={{ background: 'linear-gradient(135deg, var(--color-primary), var(--color-secondary))' }}
                >
                    <Wrench size={36} className="text-white" />
                </div>

                <h1
                    className="text-2xl font-bold mb-3"
                    style={{ color: 'var(--color-text)' }}
                >
                    Under Maintenance
                </h1>

                <p
                    className="text-sm leading-relaxed mb-8"
                    style={{ color: 'var(--color-text-secondary)' }}
                >
                    {message && message.trim() ? message : defaultMessage}
                </p>

                <button
                    onClick={() => window.location.reload()}
                    className="inline-flex items-center gap-2 px-6 py-2.5 rounded-lg text-sm font-medium text-white transition-all"
                    style={{
                        background: 'linear-gradient(to right, var(--color-primary), var(--color-secondary))',
                    }}
                >
                    Try Again
                </button>
            </div>
        </div>
    );
};

export default MaintenancePage;

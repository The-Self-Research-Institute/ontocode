import React, { useEffect, useState } from 'react';
import { CheckCircle, AlertCircle, Info, X } from 'lucide-react';
import './ToastNotification.css';

export interface Toast {
    id: string;
    type: 'success' | 'error' | 'info' | 'warning';
    message: string;
    username?: string;
    color?: string;
    duration?: number;
}

interface ToastNotificationProps {
    toasts: Toast[];
    onDismiss: (id: string) => void;
}

export const ToastNotification: React.FC<ToastNotificationProps> = ({ toasts = [], onDismiss }) => {
    useEffect(() => {
        // Auto-dismiss toasts after their duration
        if (!toasts || toasts.length === 0) return;
        
        toasts.forEach(toast => {
            const duration = toast.duration || 5000;
            const timer = setTimeout(() => {
                onDismiss(toast.id);
            }, duration);

            return () => clearTimeout(timer);
        });
    }, [toasts, onDismiss]);

    const getIcon = (type: Toast['type']) => {
        switch (type) {
            case 'success':
                return <CheckCircle size={16} />;
            case 'error':
                return <AlertCircle size={16} />;
            case 'warning':
                return <AlertCircle size={16} />;
            case 'info':
            default:
                return <Info size={16} />;
        }
    };

    if (!toasts || toasts.length === 0) {
        return null;
    }

    return (
        <div className="toast-container">
            {toasts.map(toast => (
                <div key={toast.id} className={`toast toast-${toast.type}`}>
                    {toast.username && toast.color && (
                        <div 
                            className="toast-avatar" 
                            style={{ backgroundColor: toast.color }}
                        >
                            {toast.username.charAt(0).toUpperCase()}
                        </div>
                    )}
                    <div className="toast-icon">{getIcon(toast.type)}</div>
                    <div className="toast-message">{toast.message}</div>
                    <button 
                        className="toast-close" 
                        onClick={() => onDismiss(toast.id)}
                        aria-label="Dismiss"
                    >
                        <X size={14} />
                    </button>
                </div>
            ))}
        </div>
    );
};

export default ToastNotification;

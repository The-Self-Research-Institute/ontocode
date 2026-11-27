import React, { useEffect, useId, useRef, useState } from 'react';
import { Bell, CheckCircle, AlertCircle, Info, X } from 'lucide-react';
import { readingTimeMs } from '../services/notificationService';
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
    showHistory?: boolean;
}

const HISTORY_LIMIT = 100;
const PREVIEW_LIMIT = 120;

function previewMessage(message: string): string {
    const firstLine = message.trim().split(/\r?\n/, 1)[0].trim();
    if (firstLine === message.trim() && firstLine.length <= PREVIEW_LIMIT) return firstLine;
    return `${firstLine.slice(0, PREVIEW_LIMIT).trimEnd()}…`;
}

export const ToastNotification: React.FC<ToastNotificationProps> = ({ toasts, onDismiss, showHistory = false }) => {
    const timers = useRef(new Map<string, ReturnType<typeof setTimeout>>());
    const dismiss = useRef(onDismiss);
    dismiss.current = onDismiss;
    const [history, setHistory] = useState<Toast[]>([]);
    const previousToastIds = useRef(new Set<string>());
    const dialog = useRef<HTMLDialogElement>(null);
    const titleId = useId();

    useEffect(() => {
        if (!showHistory) return;
        const added = toasts.filter(toast => !previousToastIds.current.has(toast.id)).reverse();
        previousToastIds.current = new Set(toasts.map(toast => toast.id));
        if (added.length) {
            setHistory(previous => [...added, ...previous].slice(0, HISTORY_LIMIT));
        }
    }, [showHistory, toasts]);

    const openHistory = (id?: string) => {
        const element = dialog.current;
        if (!element) return;
        element.showModal();
        const list = element.querySelector('ol');
        if (list) list.scrollTop = 0;
        if (id) {
            const entry = Array.from(element.querySelectorAll<HTMLElement>('[data-toast-id]'))
                .find(item => item.dataset.toastId === id);
            entry?.scrollIntoView?.({ block: 'nearest' });
        }
    };

    useEffect(() => {
        const showing = new Set(toasts.map(toast => toast.id));
        toasts.forEach(toast => {
            if (!timers.current.has(toast.id)) {
                const duration = toast.duration || readingTimeMs(toast.message, toast.type);
                timers.current.set(toast.id, setTimeout(() => {
                    timers.current.delete(toast.id);
                    dismiss.current(toast.id);
                }, duration));
            }
        });
        timers.current.forEach((timer, id) => {
            if (!showing.has(id)) {
                clearTimeout(timer);
                timers.current.delete(id);
            }
        });
    }, [toasts]);

    useEffect(() => {
        const running = timers.current;
        return () => {
            running.forEach(timer => clearTimeout(timer));
            running.clear();
        };
    }, []);

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

    return (
        <>
        {showHistory && (
            <>
                <button type="button" className="notification-history-button" onClick={() => openHistory()}
                        title="Notifications" aria-label="Notifications" aria-haspopup="dialog">
                    <Bell size={15} /> <span>Notifications</span>
                </button>
                <dialog ref={dialog} className="notification-history" aria-labelledby={titleId}>
                    <header>
                        <h2 id={titleId}>Notifications</h2>
                        <button type="button" className="toast-close" onClick={() => dialog.current?.close()} aria-label="Close notifications">
                            <X size={16} />
                        </button>
                    </header>
                    {history.length > 0 ? (
                        <ol>
                            {history.map(toast => (
                                <li key={toast.id} data-toast-id={toast.id} className={`toast-${toast.type}`}>
                                    <div className="toast-icon">{getIcon(toast.type)}</div>
                                    <div>
                                        <div className="notification-history-type">{toast.type}</div>
                                        <div className="notification-history-message">{toast.message}</div>
                                    </div>
                                </li>
                            ))}
                        </ol>
                    ) : <p>No notifications yet.</p>}
                </dialog>
            </>
        )}
        {toasts.length > 0 && <div className="toast-container" role="log" aria-label="Recent notifications">
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
                    <div className="toast-message">{showHistory ? previewMessage(toast.message) : toast.message}</div>
                    {showHistory && (
                        <button type="button" className="toast-view" aria-label={`View ${toast.type} notification`}
                                onClick={() => openHistory(toast.id)}>View</button>
                    )}
                    <button
                        className="toast-close"
                        onClick={() => onDismiss(toast.id)}
                        aria-label="Dismiss"
                    >
                        <X size={14} />
                    </button>
                </div>
            ))}
        </div>}
        </>
    );
};

export default ToastNotification;

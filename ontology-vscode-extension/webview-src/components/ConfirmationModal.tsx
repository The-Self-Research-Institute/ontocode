import React from 'react';
import { AlertTriangle, Info, X } from 'lucide-react';

interface ConfirmationModalProps {
    isOpen: boolean;
    title: string;
    message: string;
    onConfirm: () => void;
    onCancel: () => void;
    confirmText?: string;
    cancelText?: string;
    type?: 'danger' | 'warning' | 'info';
}

const ConfirmationModal: React.FC<ConfirmationModalProps> = ({
    isOpen,
    title,
    message,
    onConfirm,
    onCancel,
    confirmText = 'Confirm',
    cancelText = 'Cancel',
    type = 'warning'
}) => {
    if (!isOpen) return null;

    const getIcon = () => {
        switch (type) {
            case 'danger':
                return <AlertTriangle className="w-6 h-6 text-red-600" />;
            case 'warning':
                return <AlertTriangle className="w-6 h-6 text-yellow-600" />;
            case 'info':
                return <Info className="w-6 h-6 text-blue-600" />;
            default:
                return <AlertTriangle className="w-6 h-6 text-yellow-600" />;
        }
    };

    const getButtonClass = () => {
        switch (type) {
            case 'danger':
                return 'bg-red-600 hover:bg-red-700 text-white';
            case 'warning':
                return 'bg-yellow-600 hover:bg-yellow-700 text-white';
            case 'info':
                return 'bg-blue-600 hover:bg-blue-700 text-white';
            default:
                return 'bg-yellow-600 hover:bg-yellow-700 text-white';
        }
    };

    return (
        <div className="fixed inset-0 bg-black bg-opacity-50 flex items-center justify-center z-[60]">
            <div className="bg-white rounded-xl shadow-2xl max-w-md w-full mx-4 animate-fade-in">
                <div className="p-6">
                    {/* Header */}
                    <div className="flex items-start gap-4 mb-4">
                        <div className="flex-shrink-0">
                            {getIcon()}
                        </div>
                        <div className="flex-1">
                            <h3 className="text-lg font-semibold text-gray-900">{title}</h3>
                        </div>
                        <button
                            onClick={onCancel}
                            className="text-gray-400 hover:text-gray-600 transition-colors"
                            title="Close"
                        >
                            <X size={20} />
                        </button>
                    </div>

                    {/* Message */}
                    <div className="ml-10 mb-6">
                        <p className="text-gray-600">{message}</p>
                    </div>

                    {/* Actions */}
                    <div className="flex gap-3 justify-end">
                        <button
                            onClick={onCancel}
                            className="px-4 py-2 border border-gray-300 rounded-lg hover:bg-gray-50 transition-colors"
                        >
                            {cancelText}
                        </button>
                        <button
                            onClick={onConfirm}
                            className={`px-4 py-2 rounded-lg transition-colors ${getButtonClass()}`}
                        >
                            {confirmText}
                        </button>
                    </div>
                </div>
            </div>
        </div>
    );
};

export default ConfirmationModal;

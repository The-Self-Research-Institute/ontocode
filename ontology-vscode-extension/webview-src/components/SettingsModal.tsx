import React, { useState } from 'react';
import { X, Settings, User, Bell, Lock, Palette, Globe } from 'lucide-react';

interface SettingsModalProps {
    isOpen: boolean;
    onClose: () => void;
    user: {
        username: string;
        email?: string;
        workspaceName?: string;
    };
}

const SettingsModal: React.FC<SettingsModalProps> = ({ isOpen, onClose, user }) => {
    const [activeTab, setActiveTab] = useState('profile');
    const [settings, setSettings] = useState({
        displayName: user.username,
        email: user.email || '',
        notifications: true,
        emailNotifications: true,
        theme: 'light',
        language: 'en'
    });

    if (!isOpen) return null;

    const tabs = [
        { id: 'profile', label: 'Profile', icon: User },
        { id: 'notifications', label: 'Notifications', icon: Bell },
        { id: 'security', label: 'Security', icon: Lock },
        { id: 'appearance', label: 'Appearance', icon: Palette },
        { id: 'preferences', label: 'Preferences', icon: Globe }
    ];

    const handleSave = () => {
        // TODO: Implement save settings
        console.log('Saving settings:', settings);
        onClose();
    };

    return (
        <div className="fixed inset-0 bg-black bg-opacity-50 flex items-center justify-center z-50 p-4">
            <div className="bg-white rounded-xl shadow-2xl max-w-4xl w-full max-h-[90vh] overflow-hidden flex flex-col">
                {/* Header */}
                <div className="flex items-center justify-between p-6 border-b">
                    <div className="flex items-center gap-3">
                        <div className="w-10 h-10 bg-purple-100 rounded-full flex items-center justify-center">
                            <Settings size={20} className="text-purple-600" />
                        </div>
                        <div>
                            <h3 className="text-xl font-semibold text-gray-900">Settings</h3>
                            <p className="text-sm text-gray-500">{user.workspaceName || 'Workspace Settings'}</p>
                        </div>
                    </div>
                    <button
                        onClick={onClose}
                        className="p-2 hover:bg-gray-100 rounded-lg transition-colors"
                    >
                        <X size={20} className="text-gray-500" />
                    </button>
                </div>

                <div className="flex flex-1 overflow-hidden">
                    {/* Sidebar */}
                    <div className="w-64 border-r bg-gray-50 p-4">
                        <nav className="space-y-1">
                            {tabs.map((tab) => {
                                const Icon = tab.icon;
                                return (
                                    <button
                                        key={tab.id}
                                        onClick={() => setActiveTab(tab.id)}
                                        className={`
                                            w-full flex items-center gap-3 px-4 py-3 rounded-lg text-left transition-colors
                                            ${activeTab === tab.id
                                                ? 'bg-purple-100 text-purple-700 font-medium'
                                                : 'text-gray-700 hover:bg-gray-100'
                                            }
                                        `}
                                    >
                                        <Icon size={18} />
                                        {tab.label}
                                    </button>
                                );
                            })}
                        </nav>
                    </div>

                    {/* Content */}
                    <div className="flex-1 overflow-y-auto p-6">
                        {activeTab === 'profile' && (
                            <div className="space-y-6">
                                <div>
                                    <h4 className="text-lg font-semibold text-gray-900 mb-4">Profile Information</h4>
                                    <div className="space-y-4">
                                        <div>
                                            <label className="block text-sm font-medium text-gray-700 mb-2">
                                                Display Name
                                            </label>
                                            <input
                                                type="text"
                                                value={settings.displayName}
                                                onChange={(e) => setSettings({ ...settings, displayName: e.target.value })}
                                                className="w-full px-4 py-2 border border-gray-300 rounded-lg focus:ring-2 focus:ring-purple-500 focus:border-transparent"
                                            />
                                        </div>
                                        <div>
                                            <label className="block text-sm font-medium text-gray-700 mb-2">
                                                Email Address
                                            </label>
                                            <input
                                                type="email"
                                                value={settings.email}
                                                onChange={(e) => setSettings({ ...settings, email: e.target.value })}
                                                className="w-full px-4 py-2 border border-gray-300 rounded-lg focus:ring-2 focus:ring-purple-500 focus:border-transparent"
                                            />
                                        </div>
                                    </div>
                                </div>
                            </div>
                        )}

                        {activeTab === 'notifications' && (
                            <div className="space-y-6">
                                <div>
                                    <h4 className="text-lg font-semibold text-gray-900 mb-4">Notification Preferences</h4>
                                    <div className="space-y-4">
                                        <label className="flex items-center justify-between p-4 bg-gray-50 rounded-lg cursor-pointer">
                                            <div>
                                                <p className="font-medium text-gray-900">Push Notifications</p>
                                                <p className="text-sm text-gray-500">Receive notifications in the app</p>
                                            </div>
                                            <input
                                                type="checkbox"
                                                checked={settings.notifications}
                                                onChange={(e) => setSettings({ ...settings, notifications: e.target.checked })}
                                                className="w-5 h-5 text-purple-600 rounded focus:ring-purple-500"
                                            />
                                        </label>
                                        <label className="flex items-center justify-between p-4 bg-gray-50 rounded-lg cursor-pointer">
                                            <div>
                                                <p className="font-medium text-gray-900">Email Notifications</p>
                                                <p className="text-sm text-gray-500">Receive updates via email</p>
                                            </div>
                                            <input
                                                type="checkbox"
                                                checked={settings.emailNotifications}
                                                onChange={(e) => setSettings({ ...settings, emailNotifications: e.target.checked })}
                                                className="w-5 h-5 text-purple-600 rounded focus:ring-purple-500"
                                            />
                                        </label>
                                    </div>
                                </div>
                            </div>
                        )}

                        {activeTab === 'security' && (
                            <div className="space-y-6">
                                <div>
                                    <h4 className="text-lg font-semibold text-gray-900 mb-4">Security Settings</h4>
                                    <div className="space-y-4">
                                        <button className="w-full px-4 py-3 bg-gray-100 hover:bg-gray-200 text-gray-900 rounded-lg font-medium transition-colors text-left">
                                            Change Password
                                        </button>
                                        <button className="w-full px-4 py-3 bg-gray-100 hover:bg-gray-200 text-gray-900 rounded-lg font-medium transition-colors text-left">
                                            Enable Two-Factor Authentication
                                        </button>
                                        <button className="w-full px-4 py-3 bg-gray-100 hover:bg-gray-200 text-gray-900 rounded-lg font-medium transition-colors text-left">
                                            Manage Sessions
                                        </button>
                                    </div>
                                </div>
                            </div>
                        )}

                        {activeTab === 'appearance' && (
                            <div className="space-y-6">
                                <div>
                                    <h4 className="text-lg font-semibold text-gray-900 mb-4">Appearance</h4>
                                    <div className="space-y-4">
                                        <div>
                                            <label className="block text-sm font-medium text-gray-700 mb-2">
                                                Theme
                                            </label>
                                            <select
                                                value={settings.theme}
                                                onChange={(e) => setSettings({ ...settings, theme: e.target.value })}
                                                className="w-full px-4 py-2 border border-gray-300 rounded-lg focus:ring-2 focus:ring-purple-500 focus:border-transparent"
                                            >
                                                <option value="light">Light</option>
                                                <option value="dark">Dark</option>
                                                <option value="auto">Auto (System)</option>
                                            </select>
                                        </div>
                                    </div>
                                </div>
                            </div>
                        )}

                        {activeTab === 'preferences' && (
                            <div className="space-y-6">
                                <div>
                                    <h4 className="text-lg font-semibold text-gray-900 mb-4">Preferences</h4>
                                    <div className="space-y-4">
                                        <div>
                                            <label className="block text-sm font-medium text-gray-700 mb-2">
                                                Language
                                            </label>
                                            <select
                                                value={settings.language}
                                                onChange={(e) => setSettings({ ...settings, language: e.target.value })}
                                                className="w-full px-4 py-2 border border-gray-300 rounded-lg focus:ring-2 focus:ring-purple-500 focus:border-transparent"
                                            >
                                                <option value="en">English</option>
                                                <option value="es">Spanish</option>
                                                <option value="fr">French</option>
                                                <option value="de">German</option>
                                            </select>
                                        </div>
                                    </div>
                                </div>
                            </div>
                        )}
                    </div>
                </div>

                {/* Footer */}
                <div className="flex items-center justify-end gap-3 p-6 border-t bg-gray-50">
                    <button
                        onClick={onClose}
                        className="px-6 py-2 border border-gray-300 rounded-lg hover:bg-gray-100 font-medium transition-colors"
                    >
                        Cancel
                    </button>
                    <button
                        onClick={handleSave}
                        className="px-6 py-2 bg-purple-600 text-white rounded-lg hover:bg-purple-700 font-medium transition-colors"
                    >
                        Save Changes
                    </button>
                </div>
            </div>
        </div>
    );
};

export default SettingsModal;

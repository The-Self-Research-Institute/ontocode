import React, { useState, useEffect } from 'react';
import { X, Settings, User, Bell, Lock, Palette, Globe, Check, Loader2, Eye, EyeOff, Building2, KeyRound, Upload, Info, Zap } from 'lucide-react';
import apiClient from '../services/apiClient';
import { isDesktop, getDesktopLicense, isLicenseExpired, licensePlan, DesktopLicense, DESKTOP_LICENSE_UPDATED_EVENT } from '../utils/desktop';
import { fetchLatestDesktopInstallerVersion, getAppVersion } from '../utils/appVersion';
import LLMSettingsPanel from './LLMSettingsPanel';

interface SettingsModalProps {
    isOpen: boolean;
    onClose: () => void;
    onLogout?: () => void;
    user: {
        username: string;
        email?: string;
        workspaceName?: string;
        workspaceId?: string;
    };
    isWorkspaceOwner?: boolean;
    onWorkspaceRenamed?: (workspaceName: string) => void;
}

const SettingsModal: React.FC<SettingsModalProps> = ({ isOpen, onClose, onLogout, user, isWorkspaceOwner = false, onWorkspaceRenamed }) => {
    const [activeTab, setActiveTab] = useState('profile');
    const [saving, setSaving] = useState(false);
    const [saveMessage, setSaveMessage] = useState<{ type: 'success' | 'error'; text: string } | null>(null);
    const [settings, setSettings] = useState({
        displayName: user.username,
        email: user.email || '',
        notifications: true,
        emailNotifications: true,
        theme: 'light',
        language: 'en',
        workspaceName: user.workspaceName || ''
    });
    const [passwordData, setPasswordData] = useState({
        currentPassword: '',
        newPassword: '',
        confirmPassword: ''
    });
    const [showPassword, setShowPassword] = useState({
        currentPassword: false,
        newPassword: false,
        confirmPassword: false
    });
    const desktop = isDesktop();
    const [license, setLicense] = useState<DesktopLicense | null>(null);
    const [licenseImporting, setLicenseImporting] = useState(false);
    const [licenseMessage, setLicenseMessage] = useState<{ type: 'success' | 'error'; text: string } | null>(null);
    const [appVersion, setAppVersion] = useState<string>('');
    const [latestDesktopVersion, setLatestDesktopVersion] = useState<string | null>(null);

    useEffect(() => {
        if (!desktop || !isOpen) return;
        getDesktopLicense().then(setLicense).catch(() => setLicense(null));
    }, [desktop, isOpen]);

    useEffect(() => {
        if (!isOpen) return;
        getAppVersion().then(setAppVersion).catch(() => setAppVersion(''));
        if (!desktop) {
            fetchLatestDesktopInstallerVersion().then(setLatestDesktopVersion).catch(() => setLatestDesktopVersion(null));
        }
    }, [desktop, isOpen]);

    const handleLicenseFile = async (event: React.ChangeEvent<HTMLInputElement>) => {
        const file = event.target.files?.[0];
        event.target.value = '';
        if (!file) return;
        setLicenseMessage(null);
        setLicenseImporting(true);
        try {
            const text = await file.text();
            const api = (window as any).electronAPI;
            const result = await api?.importLicense?.(text);
            if (result?.ok) {
                setLicense(result.license || null);
                setLicenseMessage({ type: 'success', text: 'License imported. Restart the app to fully apply the new plan.' });
                window.dispatchEvent(new CustomEvent(DESKTOP_LICENSE_UPDATED_EVENT));
            } else {
                setLicenseMessage({ type: 'error', text: result?.error || 'Could not import this license file.' });
            }
        } catch (e: any) {
            setLicenseMessage({ type: 'error', text: e?.message || 'Could not read the license file.' });
        } finally {
            setLicenseImporting(false);
        }
    };

    // Reset settings when user changes or modal opens
    useEffect(() => {
        // Try to load saved settings from localStorage
        const savedSettings = localStorage.getItem('userSettings');
        if (savedSettings) {
            try {
                const parsed = JSON.parse(savedSettings);
                setSettings({
                    displayName: parsed.displayName || user.username,
                    email: parsed.email || user.email || '',
                    notifications: parsed.notifications ?? true,
                    emailNotifications: parsed.emailNotifications ?? true,
                    theme: parsed.theme || 'light',
                    language: parsed.language || 'en',
                    workspaceName: user.workspaceName || ''
                });
            } catch (e) {
                // Fall back to defaults
                setSettings({
                    displayName: user.username,
                    email: user.email || '',
                    notifications: true,
                    emailNotifications: true,
                    theme: 'light',
                    language: 'en',
                    workspaceName: user.workspaceName || ''
                });
            }
        } else {
            setSettings({
                displayName: user.username,
                email: user.email || '',
                notifications: true,
                emailNotifications: true,
                theme: 'light',
                language: 'en',
                workspaceName: user.workspaceName || ''
            });
        }
    }, [user, isOpen]);

    if (!isOpen) return null;

    const tabs = [
        { id: 'profile', label: 'Profile', icon: User },
        // Desktop: no shared workspace settings / password — show License instead.
        ...(desktop
            ? [{ id: 'license', label: 'License', icon: KeyRound }]
            : [
                { id: 'workspace', label: 'Workspace', icon: Building2 },
                { id: 'security', label: 'Security', icon: Lock },
              ]),
        { id: 'ai', label: 'AI & Integrations', icon: Zap },
        // { id: 'notifications', label: 'Notifications', icon: Bell },
        // { id: 'appearance', label: 'Appearance', icon: Palette },
        // { id: 'preferences', label: 'Preferences', icon: Globe }
        { id: 'about', label: 'About', icon: Info },
    ];

    const showMessage = (type: 'success' | 'error', text: string) => {
        setSaveMessage({ type, text });
        setTimeout(() => setSaveMessage(null), 3000);
    };

    const handleSave = async () => {
        try {
            setSaving(true);
            console.log('Saving settings:', settings);

            if (activeTab === 'workspace') {
                if (!user.workspaceId) {
                    showMessage('error', 'No workspace selected');
                    return;
                }
                const workspaceName = settings.workspaceName.trim();
                if (!workspaceName) {
                    showMessage('error', 'Workspace name is required');
                    return;
                }

                const response = await apiClient.patch(`/api/workspaces/${user.workspaceId}`, {
                    name: workspaceName
                });
                const data = response?.data || response;
                const updatedName = data?.workspace?.name || workspaceName;
                onWorkspaceRenamed?.(updatedName);
                showMessage('success', 'Workspace name updated successfully!');
                setTimeout(() => onClose(), 1500);
                return;
            }
            
            // Try to save profile settings - handle gracefully if endpoint doesn't exist
            try {
                await apiClient.patch('/api/users/profile', {
                    displayName: settings.displayName,
                    email: settings.email
                });
            } catch (profileError: any) {
                console.log('Profile endpoint not available, saving locally');
            }
            
            // Try to save preferences - handle gracefully if endpoint doesn't exist
            try {
                await apiClient.patch('/api/users/preferences', {
                    notifications: settings.notifications,
                    emailNotifications: settings.emailNotifications,
                    theme: settings.theme,
                    language: settings.language
                });
            } catch (prefError: any) {
                console.log('Preferences endpoint not available, saving locally');
            }
            
            // Store settings locally
            localStorage.setItem('userSettings', JSON.stringify(settings));
            
            showMessage('success', 'Settings saved successfully!');
            setTimeout(() => onClose(), 1500);
        } catch (error: any) {
            console.error('Error saving settings:', error);
            showMessage('error', error?.error || error?.message || 'Failed to save settings');
        } finally {
            setSaving(false);
        }
    };

    const handleChangePassword = async () => {
        if (!passwordData.currentPassword || !passwordData.newPassword) {
            showMessage('error', 'Please fill in all password fields');
            return;
        }
        
        if (passwordData.newPassword !== passwordData.confirmPassword) {
            showMessage('error', 'New passwords do not match');
            return;
        }
        
        if (passwordData.newPassword.length < 6) {
            showMessage('error', 'Password must be at least 6 characters');
            return;
        }
        
        // Temporarily suppress the global 401 handler so a failed change-password
        // shows an inline error instead of redirecting to the sign-in page.
        const prevCallback = (apiClient as any).onUnauthorized;
        apiClient.setUnauthorizedCallback(() => {
            console.log('[SettingsModal] Suppressed 401 redirect during change-password');
        });

        try {
            setSaving(true);
            const response = await apiClient.post('/api/auth/change-password', {
                currentPassword: passwordData.currentPassword,
                newPassword: passwordData.newPassword
            });
            
            showMessage('success', response.message || 'Password changed successfully! Signing out...');
            setPasswordData({ currentPassword: '', newPassword: '', confirmPassword: '' });
            
            // Wait 2 seconds before logging out to show the success message
            setTimeout(() => {
                if (onLogout) {
                    onLogout();
                } else if (window.vscode) {
                    window.vscode.postMessage({ type: 'logout' });
                }
            }, 2000);
        } catch (error: any) {
            console.error('Error changing password:', error);
            const status = error?.status;
            const msg = status === 401
                ? 'Session expired. Please sign in again and retry.'
                : (error?.error || error?.message || 'Failed to change password');
            showMessage('error', msg);
        } finally {
            setSaving(false);
            // Restore the global 401 handler
            apiClient.setUnauthorizedCallback(prevCallback);
        }
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
                <p className="text-sm text-gray-500">{user.workspaceName || "Workspace Settings"}</p>
              </div>
            </div>
            <button onClick={onClose} className="p-2 hover:bg-gray-100 rounded-lg transition-colors">
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
                                            ${
                                              activeTab === tab.id
                                                ? "bg-purple-100 text-purple-700 font-medium"
                                                : "text-gray-700 hover:bg-gray-100"
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
              {activeTab === "profile" && (
                <div className="space-y-6">
                  <div>
                    <h4 className="text-lg font-semibold text-gray-900 mb-4">Profile Information</h4>
                    <div className="space-y-4">
                      <div>
                        <label className="block text-sm font-medium text-gray-700 mb-2">Display Name</label>
                        <input
                          type="text"
                          value={settings.displayName}
                          disabled
                          className="w-full px-4 py-2 border border-gray-300 rounded-lg bg-gray-100 text-gray-600 cursor-not-allowed"
                        />
                        <p className="text-xs text-gray-500 mt-1">Username cannot be changed</p>
                      </div>
                      <div>
                        <label className="block text-sm font-medium text-gray-700 mb-2">Email Address</label>
                        <input
                          type="email"
                          value={settings.email}
                          disabled
                          className="w-full px-4 py-2 border border-gray-300 rounded-lg bg-gray-100 text-gray-600 cursor-not-allowed"
                        />
                        <p className="text-xs text-gray-500 mt-1">Email address cannot be changed</p>
                      </div>
                    </div>
                  </div>
                </div>
              )}

              {activeTab === "license" && (
                <div className="space-y-6">
                  <div>
                    <h4 className="text-lg font-semibold text-gray-900 mb-1">License</h4>
                    <p className="text-sm text-gray-500 mb-4">
                      Your plan and account details come from your license file. Get a license from the web app
                      (Billing → Desktop License), then import it here. The free tier needs no file.
                    </p>

                    <div className="rounded-lg border border-gray-200 bg-gray-50 p-4 space-y-2">
                      <div className="flex justify-between text-sm">
                        <span className="text-gray-500">Name</span>
                        <span className="font-medium text-gray-900">{license?.name || 'Desktop User'}</span>
                      </div>
                      <div className="flex justify-between text-sm">
                        <span className="text-gray-500">Email</span>
                        <span className="font-medium text-gray-900">{license?.email || 'local@ontocode.desktop'}</span>
                      </div>
                      <div className="flex justify-between text-sm">
                        <span className="text-gray-500">Plan</span>
                        <span className="font-semibold text-purple-700">{licensePlan(license)}</span>
                      </div>
                      <div className="flex justify-between text-sm">
                        <span className="text-gray-500">Expires</span>
                        <span className={`font-medium ${isLicenseExpired(license) ? 'text-red-600' : 'text-gray-900'}`}>
                          {license?.expiresAt
                            ? new Date(license.expiresAt).toLocaleDateString() + (isLicenseExpired(license) ? ' (expired)' : '')
                            : 'Never'}
                        </span>
                      </div>
                    </div>

                    <div className="mt-4">
                      <label className="inline-flex items-center gap-2 px-4 py-2 rounded-lg bg-purple-600 hover:bg-purple-500 text-white text-sm font-medium cursor-pointer transition-colors">
                        {licenseImporting ? <Loader2 size={16} className="animate-spin" /> : <Upload size={16} />}
                        Import license file
                        <input
                          type="file"
                          accept=".lic,application/json,application/octet-stream"
                          onChange={handleLicenseFile}
                          disabled={licenseImporting}
                          className="hidden"
                        />
                      </label>
                      <button
                        onClick={() => (window as any).electronAPI?.openPurchase?.(licensePlan(license).toLowerCase())}
                        className="ml-3 px-4 py-2 rounded-lg border border-gray-300 text-gray-700 text-sm font-medium hover:bg-gray-100 transition-colors"
                      >
                        Get a license on the web
                      </button>
                    </div>

                    {licenseMessage && (
                      <p className={`text-sm mt-3 ${licenseMessage.type === 'success' ? 'text-green-600' : 'text-red-600'}`}>
                        {licenseMessage.text}
                      </p>
                    )}
                  </div>
                </div>
              )}

              {activeTab === "workspace" && (
                <div className="space-y-6">
                  <div>
                    <h4 className="text-lg font-semibold text-gray-900 mb-4">Workspace Settings</h4>
                    <div className="space-y-4">
                      <div>
                        <label className="block text-sm font-medium text-gray-700 mb-2">Workspace Name</label>
                        <input
                          type="text"
                          value={settings.workspaceName}
                          onChange={(e) => setSettings({ ...settings, workspaceName: e.target.value })}
                          disabled={!isWorkspaceOwner}
                          className={`w-full px-4 py-2 border border-gray-300 rounded-lg focus:ring-2 focus:ring-purple-500 focus:border-transparent ${
                            isWorkspaceOwner ? "bg-white text-gray-900" : "bg-gray-100 text-gray-600 cursor-not-allowed"
                          }`}
                          placeholder="Workspace name"
                        />
                        <p className="text-xs text-gray-500 mt-1">
                          {isWorkspaceOwner
                            ? "This name is shown across the project dashboard and workspace switcher."
                            : "Only the workspace owner can change the workspace name."}
                        </p>
                      </div>
                    </div>
                  </div>
                </div>
              )}

              {/* Notifications tab - commented out
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
                        */}

              {activeTab === "security" && (
                <div className="space-y-6">
                  <div>
                    <h4 className="text-lg font-semibold text-gray-900 mb-4">Change Password</h4>
                    <div className="space-y-4">
                      <div>
                        <label className="block text-sm font-medium text-gray-700 mb-2">Current Password</label>
                        <div className="relative">
                          <input
                            type={showPassword.currentPassword ? 'text' : 'password'}
                            value={passwordData.currentPassword}
                            onChange={(e) => setPasswordData({ ...passwordData, currentPassword: e.target.value })}
                            placeholder="Enter current password"
                            className="w-full px-4 py-2 pr-10 border border-gray-300 rounded-lg focus:ring-2 focus:ring-purple-500 focus:border-transparent"
                          />
                          <button
                            type="button"
                            onClick={() => setShowPassword({ ...showPassword, currentPassword: !showPassword.currentPassword })}
                            className="absolute right-3 top-1/2 -translate-y-1/2 text-gray-400 hover:text-gray-600"
                            tabIndex={-1}
                          >
                            {showPassword.currentPassword ? <EyeOff size={18} /> : <Eye size={18} />}
                          </button>
                        </div>
                      </div>
                      <div>
                        <label className="block text-sm font-medium text-gray-700 mb-2">New Password</label>
                        <div className="relative">
                          <input
                            type={showPassword.newPassword ? 'text' : 'password'}
                            value={passwordData.newPassword}
                            onChange={(e) => setPasswordData({ ...passwordData, newPassword: e.target.value })}
                            placeholder="Enter new password"
                            className="w-full px-4 py-2 pr-10 border border-gray-300 rounded-lg focus:ring-2 focus:ring-purple-500 focus:border-transparent"
                          />
                          <button
                            type="button"
                            onClick={() => setShowPassword({ ...showPassword, newPassword: !showPassword.newPassword })}
                            className="absolute right-3 top-1/2 -translate-y-1/2 text-gray-400 hover:text-gray-600"
                            tabIndex={-1}
                          >
                            {showPassword.newPassword ? <EyeOff size={18} /> : <Eye size={18} />}
                          </button>
                        </div>
                      </div>
                      <div>
                        <label className="block text-sm font-medium text-gray-700 mb-2">Confirm New Password</label>
                        <div className="relative">
                          <input
                            type={showPassword.confirmPassword ? 'text' : 'password'}
                            value={passwordData.confirmPassword}
                            onChange={(e) => setPasswordData({ ...passwordData, confirmPassword: e.target.value })}
                            placeholder="Confirm new password"
                            className="w-full px-4 py-2 pr-10 border border-gray-300 rounded-lg focus:ring-2 focus:ring-purple-500 focus:border-transparent"
                          />
                          <button
                            type="button"
                            onClick={() => setShowPassword({ ...showPassword, confirmPassword: !showPassword.confirmPassword })}
                            className="absolute right-3 top-1/2 -translate-y-1/2 text-gray-400 hover:text-gray-600"
                            tabIndex={-1}
                          >
                            {showPassword.confirmPassword ? <EyeOff size={18} /> : <Eye size={18} />}
                          </button>
                        </div>
                      </div>
                      <button
                        onClick={handleChangePassword}
                        disabled={saving}
                        className="w-full px-4 py-3 bg-purple-600 hover:bg-purple-700 text-white rounded-lg font-medium transition-colors disabled:opacity-50 flex items-center justify-center gap-2"
                      >
                        {saving ? <Loader2 size={18} className="animate-spin" /> : <Lock size={18} />}
                        Change Password
                      </button>
                    </div>
                  </div>
                </div>
              )}

              {activeTab === "ai" && (
                <div>
                  <h4 className="text-lg font-semibold text-gray-900 mb-1">AI & Integrations</h4>
                  <p className="text-sm text-gray-500 mb-4">
                    Configure your LLM provider for AI-powered graph insights.
                  </p>
                  <LLMSettingsPanel onSave={onClose} />
                </div>
              )}

              {activeTab === "about" && (
                <div className="space-y-6">
                  <div>
                    <h4 className="text-lg font-semibold text-gray-900 mb-1">About OntoCode Studio</h4>
                    <p className="text-sm text-gray-500 mb-4">
                      Version information for the app you are running now.
                    </p>
                    <div className="rounded-lg border border-gray-200 bg-gray-50 p-4 space-y-2">
                      <div className="flex justify-between text-sm">
                        <span className="text-gray-500">Edition</span>
                        <span className="font-medium text-gray-900">{desktop ? "Desktop" : "Web"}</span>
                      </div>
                      <div className="flex justify-between text-sm">
                        <span className="text-gray-500">App version</span>
                        <span className="font-medium text-gray-900">{appVersion ? `v${appVersion}` : "…"}</span>
                      </div>
                      {!desktop && latestDesktopVersion && (
                        <div className="flex justify-between text-sm">
                          <span className="text-gray-500">Latest desktop installer</span>
                          <span className="font-medium text-gray-900">v{latestDesktopVersion}</span>
                        </div>
                      )}
                    </div>
                    {!desktop && (
                      <p className="text-xs text-gray-500 mt-4 leading-relaxed">
                        Desktop download analytics use a privacy-friendly hashed IP (never stored in plain text).
                        See the download page for details.
                      </p>
                    )}
                  </div>
                </div>
              )}

              {/* Appearance tab - commented out
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
                        */}

              {/* Preferences tab - commented out
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
                        */}
            </div>
          </div>

          {/* Footer */}
          <div className="flex items-center justify-between p-6 border-t bg-gray-50">
            {/* Save Message */}
            <div className="flex-1">
              {saveMessage && (
                <div
                  className={`flex items-center gap-2 text-sm ${
                    saveMessage.type === "success" ? "text-green-600" : "text-red-600"
                  }`}
                >
                  {saveMessage.type === "success" ? <Check size={16} /> : <X size={16} />}
                  {saveMessage.text}
                </div>
              )}
            </div>
            {activeTab !== "profile" && activeTab !== "security" && activeTab !== "about" && (
              <div className="flex items-center gap-3">
                <button
                  onClick={onClose}
                  disabled={saving}
                  className="px-6 py-2 border border-gray-300 rounded-lg hover:bg-gray-100 font-medium transition-colors disabled:opacity-50"
                >
                  Cancel
                </button>
                <button
                  onClick={handleSave}
                  disabled={saving || (activeTab === "workspace" && !isWorkspaceOwner)}
                  className="px-6 py-2 bg-purple-600 text-white rounded-lg hover:bg-purple-700 font-medium transition-colors disabled:opacity-50 flex items-center gap-2"
                >
                  {saving && <Loader2 size={16} className="animate-spin" />}
                  Save Changes
                </button>
              </div>
            )}
          </div>
        </div>
      </div>
    );
};

export default SettingsModal;

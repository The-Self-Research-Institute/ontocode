import React, { useEffect, useState, useCallback } from 'react';
import { X, Shield, Wrench, Building2, Plus, Loader2, CheckCircle, AlertTriangle, ToggleLeft, ToggleRight, Users, Wifi, WifiOff, RefreshCw } from 'lucide-react';
import apiClient from '../services/apiClient';

interface SystemSettings {
    maintenanceModeEnabled: boolean;
    maintenanceAllowedDomains: string[];
    enterpriseDomains: string[];
    updatedAt?: string;
    updatedBy?: string;
}

interface AdminSettingsModalProps {
    isOpen: boolean;
    onClose: () => void;
}

const AdminSettingsModal: React.FC<AdminSettingsModalProps> = ({ isOpen, onClose }) => {
    const [settings, setSettings] = useState<SystemSettings>({
        maintenanceModeEnabled: false,
        maintenanceAllowedDomains: [],
        enterpriseDomains: [],
    });
    const [loading, setLoading] = useState(true);
    const [saving, setSaving] = useState(false);
    const [toast, setToast] = useState<{ type: 'success' | 'error'; message: string } | null>(null);

    const [newAllowedDomain, setNewAllowedDomain] = useState('');
    const [newEnterpriseDomain, setNewEnterpriseDomain] = useState('');

    const [connections, setConnections] = useState<{
        totalConnections: number;
        uniqueUsers: number;
        safeToMigrate: boolean;
        users: { userId: string; username: string; projectId: string; lastActivity: number }[];
    } | null>(null);
    const [connectionsLoading, setConnectionsLoading] = useState(false);

    const showToast = (type: 'success' | 'error', message: string) => {
        setToast({ type, message });
        setTimeout(() => setToast(null), 3500);
    };

    const load = useCallback(async () => {
        try {
            setLoading(true);
            const data = await apiClient.get('/api/admin/settings');
            setSettings(data.data || data);
        } catch (e: any) {
            showToast('error', e.response?.data?.error || 'Failed to load settings');
        } finally {
            setLoading(false);
        }
    }, []);

    const loadConnections = useCallback(async () => {
        try {
            setConnectionsLoading(true);
            const data = await apiClient.get('/api/ontology/admin/active-connections');
            setConnections(data.data || data);
        } catch {
            // non-fatal — editor service may be unreachable
        } finally {
            setConnectionsLoading(false);
        }
    }, []);

    useEffect(() => {
        if (isOpen) {
            load();
            loadConnections();
        }
    }, [isOpen, load, loadConnections]);

    const toggleMaintenance = async () => {
        try {
            setSaving(true);
            const updated: SystemSettings = {
                ...settings,
                maintenanceModeEnabled: !settings.maintenanceModeEnabled,
            };
            await apiClient.patch('/api/admin/settings/maintenance', {
                enabled: updated.maintenanceModeEnabled,
                allowedDomains: updated.maintenanceAllowedDomains,
            });
            setSettings(updated);
            showToast('success', `Maintenance mode ${updated.maintenanceModeEnabled ? 'ENABLED' : 'DISABLED'}`);
        } catch (e: any) {
            showToast('error', e.response?.data?.error || 'Save failed');
        } finally {
            setSaving(false);
        }
    };

    const saveAllowedDomains = async (domains: string[]) => {
        try {
            setSaving(true);
            await apiClient.patch('/api/admin/settings/maintenance', {
                enabled: settings.maintenanceModeEnabled,
                allowedDomains: domains,
            });
            setSettings(prev => ({ ...prev, maintenanceAllowedDomains: domains }));
            showToast('success', 'Allowed domains updated');
        } catch (e: any) {
            showToast('error', e.response?.data?.error || 'Save failed');
        } finally {
            setSaving(false);
        }
    };

    const saveEnterpriseDomains = async (domains: string[]) => {
        try {
            setSaving(true);
            await apiClient.patch('/api/admin/settings/enterprise-domains', { domains });
            setSettings(prev => ({ ...prev, enterpriseDomains: domains }));
            showToast('success', 'Enterprise domains updated — existing workspaces upgraded');
        } catch (e: any) {
            showToast('error', e.response?.data?.error || 'Save failed');
        } finally {
            setSaving(false);
        }
    };

    const addAllowedDomain = () => {
        const d = newAllowedDomain.trim().toLowerCase().replace(/^@/, '');
        if (!d || settings.maintenanceAllowedDomains.includes(d)) return;
        const updated = [...settings.maintenanceAllowedDomains, d];
        setNewAllowedDomain('');
        saveAllowedDomains(updated);
    };

    const removeAllowedDomain = (domain: string) => {
        saveAllowedDomains(settings.maintenanceAllowedDomains.filter(d => d !== domain));
    };

    const addEnterpriseDomain = () => {
        const d = newEnterpriseDomain.trim().toLowerCase().replace(/^@/, '');
        if (!d || settings.enterpriseDomains.includes(d)) return;
        const updated = [...settings.enterpriseDomains, d];
        setNewEnterpriseDomain('');
        saveEnterpriseDomains(updated);
    };

    const removeEnterpriseDomain = (domain: string) => {
        saveEnterpriseDomains(settings.enterpriseDomains.filter(d => d !== domain));
    };

    if (!isOpen) return null;

    return (
        <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/60 p-4">
            <div className="bg-white rounded-2xl shadow-2xl w-full max-w-2xl max-h-[90vh] flex flex-col">

                {/* Header */}
                <div className="flex items-center justify-between px-6 py-4 border-b border-gray-200">
                    <div className="flex items-center gap-3">
                        <div className="w-9 h-9 bg-purple-100 rounded-lg flex items-center justify-center">
                            <Shield className="w-5 h-5 text-purple-600" />
                        </div>
                        <div>
                            <h2 className="text-lg font-semibold text-gray-900">Admin Settings</h2>
                            <p className="text-xs text-gray-500">System-wide configuration</p>
                        </div>
                    </div>
                    <button onClick={onClose} className="text-gray-400 hover:text-gray-600 p-1 rounded-lg hover:bg-gray-100">
                        <X size={20} />
                    </button>
                </div>

                {/* Toast */}
                {toast && (
                    <div className={`mx-6 mt-4 px-4 py-3 rounded-lg flex items-center gap-2 text-sm font-medium
                        ${toast.type === 'success' ? 'bg-green-50 text-green-700 border border-green-200' : 'bg-red-50 text-red-700 border border-red-200'}`}>
                        {toast.type === 'success' ? <CheckCircle size={16} /> : <AlertTriangle size={16} />}
                        {toast.message}
                    </div>
                )}

                {/* Body */}
                <div className="overflow-y-auto flex-1 px-6 py-5 space-y-8">

                    {loading ? (
                        <div className="flex items-center justify-center py-12">
                            <Loader2 className="w-6 h-6 animate-spin text-purple-500" />
                        </div>
                    ) : (
                        <>
                            {/* ── Maintenance Mode ────────────────────────────────── */}
                            <section>
                                <div className="flex items-center gap-2 mb-1">
                                    <Wrench className="w-4 h-4 text-yellow-500" />
                                    <h3 className="font-semibold text-gray-900">Maintenance Mode</h3>
                                </div>
                                <p className="text-sm text-gray-500 mb-4">
                                    When ON, only users from the allowed domains below can log in. All others see the maintenance page.
                                </p>

                                {/* Toggle */}
                                <button
                                    onClick={toggleMaintenance}
                                    disabled={saving}
                                    className={`flex items-center gap-3 px-4 py-3 rounded-xl border-2 w-full transition-all
                                        ${settings.maintenanceModeEnabled
                                            ? 'border-yellow-400 bg-yellow-50 text-yellow-800'
                                            : 'border-gray-200 bg-gray-50 text-gray-600 hover:border-gray-300'}`}
                                >
                                    {settings.maintenanceModeEnabled
                                        ? <ToggleRight className="w-6 h-6 text-yellow-500 shrink-0" />
                                        : <ToggleLeft className="w-6 h-6 text-gray-400 shrink-0" />}
                                    <div className="text-left">
                                        <p className="font-medium text-sm">
                                            {settings.maintenanceModeEnabled ? 'Maintenance mode is ON' : 'Maintenance mode is OFF'}
                                        </p>
                                        <p className="text-xs opacity-70">
                                            {settings.maintenanceModeEnabled
                                                ? 'Non-allowed users will see the maintenance page'
                                                : 'All users can log in normally'}
                                        </p>
                                    </div>
                                    {saving && <Loader2 className="w-4 h-4 animate-spin ml-auto" />}
                                </button>

                                {/* Allowed domains during maintenance */}
                                <div className="mt-4">
                                    <p className="text-sm font-medium text-gray-700 mb-2">
                                        Domains that can still access during maintenance
                                    </p>
                                    <div className="flex flex-wrap gap-2 mb-3 min-h-[2rem]">
                                        {settings.maintenanceAllowedDomains.length === 0 && (
                                            <span className="text-sm text-gray-400 italic">No domains added yet</span>
                                        )}
                                        {settings.maintenanceAllowedDomains.map(d => (
                                            <span key={d} className="inline-flex items-center gap-1 px-3 py-1 bg-blue-100 text-blue-700 rounded-full text-sm">
                                                @{d}
                                                <button onClick={() => removeAllowedDomain(d)} className="hover:text-blue-900 ml-1">
                                                    <X size={12} />
                                                </button>
                                            </span>
                                        ))}
                                    </div>
                                    <div className="flex gap-2">
                                        <input
                                            type="text"
                                            value={newAllowedDomain}
                                            onChange={e => setNewAllowedDomain(e.target.value)}
                                            onKeyDown={e => e.key === 'Enter' && addAllowedDomain()}
                                            placeholder="coretopia.com"
                                            className="flex-1 px-3 py-2 text-sm border border-gray-300 rounded-lg focus:ring-2 focus:ring-purple-500 focus:border-transparent"
                                        />
                                        <button
                                            onClick={addAllowedDomain}
                                            disabled={!newAllowedDomain.trim() || saving}
                                            className="px-3 py-2 bg-blue-600 text-white rounded-lg hover:bg-blue-700 disabled:opacity-50 disabled:cursor-not-allowed"
                                        >
                                            <Plus size={16} />
                                        </button>
                                    </div>
                                </div>
                            </section>

                            <hr className="border-gray-100" />

                            {/* ── Enterprise Domain Bypass ────────────────────────── */}
                            <section>
                                <div className="flex items-center gap-2 mb-1">
                                    <Building2 className="w-4 h-4 text-purple-500" />
                                    <h3 className="font-semibold text-gray-900">Enterprise Domain Bypass</h3>
                                </div>
                                <p className="text-sm text-gray-500 mb-4">
                                    Users from these domains automatically receive an Enterprise plan — no purchase required.
                                    Existing workspaces are upgraded immediately when you add a domain.
                                </p>

                                <div className="flex flex-wrap gap-2 mb-3 min-h-[2rem]">
                                    {settings.enterpriseDomains.length === 0 && (
                                        <span className="text-sm text-gray-400 italic">No enterprise domains added yet</span>
                                    )}
                                    {settings.enterpriseDomains.map(d => (
                                        <span key={d} className="inline-flex items-center gap-1 px-3 py-1 bg-purple-100 text-purple-700 rounded-full text-sm">
                                            <Building2 size={11} />
                                            @{d}
                                            <button onClick={() => removeEnterpriseDomain(d)} className="hover:text-purple-900 ml-1">
                                                <X size={12} />
                                            </button>
                                        </span>
                                    ))}
                                </div>
                                <div className="flex gap-2">
                                    <input
                                        type="text"
                                        value={newEnterpriseDomain}
                                        onChange={e => setNewEnterpriseDomain(e.target.value)}
                                        onKeyDown={e => e.key === 'Enter' && addEnterpriseDomain()}
                                        placeholder="university.edu"
                                        className="flex-1 px-3 py-2 text-sm border border-gray-300 rounded-lg focus:ring-2 focus:ring-purple-500 focus:border-transparent"
                                    />
                                    <button
                                        onClick={addEnterpriseDomain}
                                        disabled={!newEnterpriseDomain.trim() || saving}
                                        className="px-3 py-2 bg-purple-600 text-white rounded-lg hover:bg-purple-700 disabled:opacity-50 disabled:cursor-not-allowed"
                                    >
                                        <Plus size={16} />
                                    </button>
                                </div>

                                {settings.enterpriseDomains.length > 0 && (
                                    <div className="mt-3 p-3 bg-purple-50 rounded-lg border border-purple-100">
                                        <p className="text-xs text-purple-700">
                                            Users from these domains get Enterprise on login. Their workspaces are upgraded automatically.
                                            Removing a domain here does <strong>not</strong> downgrade existing workspaces.
                                        </p>
                                    </div>
                                )}
                            </section>

                            <hr className="border-gray-100" />

                            {/* ── Active Users ─────────────────────────────────── */}
                            <section>
                                <div className="flex items-center justify-between mb-1">
                                    <div className="flex items-center gap-2">
                                        <Users className="w-4 h-4 text-green-500" />
                                        <h3 className="font-semibold text-gray-900">Active Users</h3>
                                    </div>
                                    <button
                                        onClick={loadConnections}
                                        disabled={connectionsLoading}
                                        className="p-1.5 text-gray-400 hover:text-gray-600 hover:bg-gray-100 rounded-lg"
                                        title="Refresh"
                                    >
                                        <RefreshCw size={14} className={connectionsLoading ? 'animate-spin' : ''} />
                                    </button>
                                </div>
                                <p className="text-sm text-gray-500 mb-3">
                                    Live WebSocket connections — use this to decide if it's safe to restart or migrate.
                                </p>

                                {connectionsLoading && !connections && (
                                    <div className="flex items-center justify-center py-6">
                                        <Loader2 className="w-5 h-5 animate-spin text-gray-400" />
                                    </div>
                                )}

                                {connections && (
                                    <>
                                        {/* Safe-to-migrate banner */}
                                        <div className={`flex items-center gap-3 px-4 py-3 rounded-xl border-2 mb-4
                                            ${connections.safeToMigrate
                                                ? 'border-green-300 bg-green-50'
                                                : 'border-orange-300 bg-orange-50'}`}>
                                            {connections.safeToMigrate
                                                ? <WifiOff className="w-5 h-5 text-green-600 shrink-0" />
                                                : <Wifi className="w-5 h-5 text-orange-500 shrink-0" />}
                                            <div>
                                                <p className={`font-semibold text-sm ${connections.safeToMigrate ? 'text-green-800' : 'text-orange-800'}`}>
                                                    {connections.safeToMigrate
                                                        ? 'Safe to migrate — no active sessions'
                                                        : `${connections.uniqueUsers} user${connections.uniqueUsers !== 1 ? 's' : ''} currently active (${connections.totalConnections} connection${connections.totalConnections !== 1 ? 's' : ''})`}
                                                </p>
                                                <p className={`text-xs mt-0.5 ${connections.safeToMigrate ? 'text-green-600' : 'text-orange-600'}`}>
                                                    {connections.safeToMigrate
                                                        ? 'No one is in the editor right now'
                                                        : 'Restarting now will disconnect active editors'}
                                                </p>
                                            </div>
                                        </div>

                                        {/* User list */}
                                        {connections.users.length > 0 && (
                                            <div className="space-y-2">
                                                {connections.users.map(u => (
                                                    <div key={u.userId} className="flex items-center justify-between px-3 py-2 bg-gray-50 rounded-lg text-sm">
                                                        <div className="flex items-center gap-2">
                                                            <div className="w-2 h-2 bg-green-400 rounded-full" />
                                                            <span className="font-medium text-gray-800">{u.username}</span>
                                                            <span className="text-gray-400 text-xs">project: {u.projectId.slice(0, 8)}…</span>
                                                        </div>
                                                        <span className="text-xs text-gray-400">
                                                            {Math.round((Date.now() - u.lastActivity) / 60000)}m ago
                                                        </span>
                                                    </div>
                                                ))}
                                            </div>
                                        )}
                                    </>
                                )}
                            </section>

                            {/* Last updated */}
                            {settings.updatedAt && (
                                <p className="text-xs text-gray-400 text-right">
                                    Last updated by {settings.updatedBy} at {new Date(settings.updatedAt).toLocaleString()}
                                </p>
                            )}
                        </>
                    )}
                </div>
            </div>
        </div>
    );
};

export default AdminSettingsModal;

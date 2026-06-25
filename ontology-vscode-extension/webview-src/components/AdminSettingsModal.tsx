import React, { useEffect, useState, useCallback } from 'react';
import { X, Shield, Wrench, Building2, Plus, Loader2, CheckCircle, AlertTriangle, ToggleLeft, ToggleRight, Users, Wifi, WifiOff, RefreshCw, LogOut, Calendar, Clock } from 'lucide-react';
import apiClient from '../services/apiClient';

interface SystemSettings {
    maintenanceModeEnabled: boolean;
    maintenanceMessage?: string;
    maintenanceAllowedDomains: string[];
    maintenanceAllowedEmails?: string[];
    maintenanceScheduleEnabled?: boolean;
    maintenanceAllDayDate?: string;
    maintenanceStartTime?: string;
    maintenanceEndTime?: string;
    enterpriseDomains: string[];
    enterpriseEmails: string[];
    updatedAt?: string;
    updatedBy?: string;
}

interface AdminSettingsModalProps {
    isOpen: boolean;
    onClose: () => void;
    pageMode?: boolean;
    onLogout?: () => void;
}

const AdminSettingsModal: React.FC<AdminSettingsModalProps> = ({ isOpen, onClose, pageMode = false, onLogout }) => {
    const [settings, setSettings] = useState<SystemSettings>({
        maintenanceModeEnabled: false,
        maintenanceAllowedDomains: [],
        enterpriseDomains: [],
        enterpriseEmails: [],
    });
    const [loading, setLoading] = useState(true);
    const [saving, setSaving] = useState(false);
    const [toast, setToast] = useState<{ type: 'success' | 'error'; message: string } | null>(null);

    const [newAllowedDomain, setNewAllowedDomain] = useState('');
    const [newAllowedEmail, setNewAllowedEmail] = useState('');
    const [newEnterpriseDomain, setNewEnterpriseDomain] = useState('');
    const [newEnterpriseEmail, setNewEnterpriseEmail] = useState('');
    const [scheduleMode, setScheduleMode] = useState<'allday' | 'range'>('allday');

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
        const next = !settings.maintenanceModeEnabled;
        try {
            await saveMaintenance({ maintenanceModeEnabled: next });
            showToast('success', `Maintenance mode ${next ? 'ENABLED' : 'DISABLED'}`);
        } catch {
            // error already shown in saveMaintenance
        }
    };

    const saveMaintenance = async (patch: Partial<SystemSettings>) => {
        try {
            setSaving(true);
            const merged = { ...settings, ...patch };
            await apiClient.patch('/api/admin/settings/maintenance', {
                enabled: merged.maintenanceModeEnabled,
                message: merged.maintenanceMessage,
                allowedDomains: merged.maintenanceAllowedDomains,
                allowedEmails: merged.maintenanceAllowedEmails ?? [],
                scheduleEnabled: merged.maintenanceScheduleEnabled ?? false,
                allDayDate: merged.maintenanceAllDayDate ?? null,
                startTime: merged.maintenanceStartTime ?? null,
                endTime: merged.maintenanceEndTime ?? null,
            });
            setSettings(prev => ({ ...prev, ...patch }));
        } catch (e: any) {
            showToast('error', e.response?.data?.error || 'Save failed');
            throw e;
        } finally {
            setSaving(false);
        }
    };

    const saveAllowedDomains = async (domains: string[]) => {
        await saveMaintenance({ maintenanceAllowedDomains: domains });
        showToast('success', 'Allowed domains updated');
    };

    const saveAllowedEmails = async (emails: string[]) => {
        await saveMaintenance({ maintenanceAllowedEmails: emails });
        showToast('success', 'Allowed emails updated');
    };

    const saveEnterpriseDomains = async (domains: string[]) => {
        try {
            setSaving(true);
            await apiClient.patch('/api/admin/settings/enterprise-domains', { domains });
            setSettings(prev => ({ ...prev, enterpriseDomains: domains }));
            showToast('success', 'Enterprise domains updated');
        } catch (e: any) {
            showToast('error', e.response?.data?.error || 'Save failed');
        } finally {
            setSaving(false);
        }
    };

    const saveEnterpriseEmails = async (emails: string[]) => {
        try {
            setSaving(true);
            await apiClient.patch('/api/admin/settings/enterprise-emails', { emails });
            setSettings(prev => ({ ...prev, enterpriseEmails: emails }));
            showToast('success', 'Beta / partner emails updated');
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

    const addAllowedEmail = () => {
        const e = newAllowedEmail.trim().toLowerCase();
        if (!e || !e.includes('@')) return;
        const existing = settings.maintenanceAllowedEmails ?? [];
        if (existing.includes(e)) return;
        setNewAllowedEmail('');
        saveAllowedEmails([...existing, e]);
    };

    const removeAllowedEmail = (email: string) => {
        saveAllowedEmails((settings.maintenanceAllowedEmails ?? []).filter(e => e !== email));
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

    const addEnterpriseEmail = () => {
        const e = newEnterpriseEmail.trim().toLowerCase();
        if (!e || !e.includes('@') || settings.enterpriseEmails.includes(e)) return;
        const updated = [...settings.enterpriseEmails, e];
        setNewEnterpriseEmail('');
        saveEnterpriseEmails(updated);
    };

    const removeEnterpriseEmail = (email: string) => {
        saveEnterpriseEmails(settings.enterpriseEmails.filter(e => e !== email));
    };

    if (!isOpen) return null;

    const content = (
        <>
            {/* Toast */}
            {toast && (
                <div className={`mx-6 mt-4 px-4 py-3 rounded-lg flex items-center gap-2 text-sm font-medium
                    ${toast.type === 'success' ? 'bg-green-50 text-green-700 border border-green-200' : 'bg-red-50 text-red-700 border border-red-200'}`}>
                    {toast.type === 'success' ? <CheckCircle size={16} /> : <AlertTriangle size={16} />}
                    {toast.message}
                </div>
            )}

            {/* Body */}
            <div className={`overflow-y-auto flex-1 px-6 py-5 space-y-8 ${pageMode ? 'max-w-2xl mx-auto w-full' : ''}`}>

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
                                    When ON, only allowed users can log in. Everyone else sees the maintenance page.
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

                                {/* Custom message */}
                                <div className="mt-4">
                                    <p className="text-sm font-medium text-gray-700 mb-2">Custom message (shown to blocked users)</p>
                                    <div className="flex gap-2">
                                        <input
                                            type="text"
                                            value={settings.maintenanceMessage ?? ''}
                                            onChange={e => setSettings(prev => ({ ...prev, maintenanceMessage: e.target.value }))}
                                            onBlur={() => saveMaintenance({ maintenanceMessage: settings.maintenanceMessage }).catch(() => showToast('error', 'Save failed'))}
                                            placeholder="We're performing scheduled maintenance. Back shortly."
                                            className="flex-1 px-3 py-2 text-sm border border-gray-300 rounded-lg focus:ring-2 focus:ring-yellow-400 focus:border-transparent"
                                        />
                                    </div>
                                </div>

                                {/* Schedule */}
                                <div className="mt-4 p-4 border border-gray-200 rounded-xl">
                                    <div className="flex items-center justify-between mb-3">
                                        <div className="flex items-center gap-2">
                                            <Calendar className="w-4 h-4 text-gray-500" />
                                            <p className="text-sm font-medium text-gray-700">Schedule maintenance window</p>
                                        </div>
                                        <button
                                            onClick={() => {
                                                const next = !settings.maintenanceScheduleEnabled;
                                                saveMaintenance({ maintenanceScheduleEnabled: next })
                                                    .then(() => showToast('success', next ? 'Schedule enabled' : 'Schedule disabled'))
                                                    .catch(() => {});
                                            }}
                                            className={`text-xs px-3 py-1 rounded-full border font-medium transition-colors
                                                ${settings.maintenanceScheduleEnabled
                                                    ? 'bg-yellow-100 border-yellow-400 text-yellow-700'
                                                    : 'bg-gray-100 border-gray-300 text-gray-500 hover:border-gray-400'}`}
                                        >
                                            {settings.maintenanceScheduleEnabled ? 'Schedule ON' : 'Schedule OFF'}
                                        </button>
                                    </div>

                                    {settings.maintenanceScheduleEnabled && (
                                        <div className="space-y-3">
                                            {/* All-day vs range toggle */}
                                            <div className="flex gap-2">
                                                <button
                                                    onClick={() => setScheduleMode('allday')}
                                                    className={`flex-1 py-1.5 text-xs rounded-lg border font-medium transition-colors
                                                        ${scheduleMode === 'allday' ? 'bg-blue-600 text-white border-blue-600' : 'bg-white text-gray-600 border-gray-300 hover:border-gray-400'}`}
                                                >All day
                                                </button>
                                                <button
                                                    onClick={() => setScheduleMode('range')}
                                                    className={`flex-1 py-1.5 text-xs rounded-lg border font-medium transition-colors
                                                        ${scheduleMode === 'range' ? 'bg-blue-600 text-white border-blue-600' : 'bg-white text-gray-600 border-gray-300 hover:border-gray-400'}`}
                                                >Time range
                                                </button>
                                            </div>

                                            {scheduleMode === 'allday' ? (
                                                <div>
                                                    <label className="text-xs text-gray-500 block mb-1">Maintenance date</label>
                                                    <input
                                                        type="date"
                                                        value={settings.maintenanceAllDayDate ?? ''}
                                                        onChange={e => setSettings(prev => ({ ...prev, maintenanceAllDayDate: e.target.value }))}
                                                        onBlur={() => saveMaintenance({ maintenanceAllDayDate: settings.maintenanceAllDayDate, maintenanceStartTime: undefined, maintenanceEndTime: undefined }).catch(() => showToast('error', 'Save failed'))}
                                                        className="w-full px-3 py-2 text-sm border border-gray-300 rounded-lg focus:ring-2 focus:ring-yellow-400"
                                                    />
                                                </div>
                                            ) : (
                                                <div className="grid grid-cols-2 gap-3">
                                                    <div>
                                                        <label className="text-xs text-gray-500 block mb-1">Start</label>
                                                        <input
                                                            type="datetime-local"
                                                            value={settings.maintenanceStartTime ?? ''}
                                                            onChange={e => setSettings(prev => ({ ...prev, maintenanceStartTime: e.target.value }))}
                                                            onBlur={() => saveMaintenance({ maintenanceStartTime: settings.maintenanceStartTime, maintenanceAllDayDate: undefined }).catch(() => showToast('error', 'Save failed'))}
                                                            className="w-full px-3 py-2 text-sm border border-gray-300 rounded-lg focus:ring-2 focus:ring-yellow-400"
                                                        />
                                                    </div>
                                                    <div>
                                                        <label className="text-xs text-gray-500 block mb-1">End</label>
                                                        <input
                                                            type="datetime-local"
                                                            value={settings.maintenanceEndTime ?? ''}
                                                            onChange={e => setSettings(prev => ({ ...prev, maintenanceEndTime: e.target.value }))}
                                                            onBlur={() => saveMaintenance({ maintenanceEndTime: settings.maintenanceEndTime, maintenanceAllDayDate: undefined }).catch(() => showToast('error', 'Save failed'))}
                                                            className="w-full px-3 py-2 text-sm border border-gray-300 rounded-lg focus:ring-2 focus:ring-yellow-400"
                                                        />
                                                    </div>
                                                </div>
                                            )}
                                        </div>
                                    )}
                                </div>

                                {/* Allowed emails during maintenance */}
                                <div className="mt-4">
                                    <p className="text-sm font-medium text-gray-700 mb-2">
                                        Individual emails allowed during maintenance
                                    </p>
                                    <div className="flex flex-wrap gap-2 mb-3 min-h-[2rem]">
                                        {(settings.maintenanceAllowedEmails ?? []).length === 0 && (
                                            <span className="text-sm text-gray-400 italic">No emails added yet</span>
                                        )}
                                        {(settings.maintenanceAllowedEmails ?? []).map(e => (
                                            <span key={e} className="inline-flex items-center gap-1 px-3 py-1 bg-green-100 text-green-700 rounded-full text-sm">
                                                {e}
                                                <button onClick={() => removeAllowedEmail(e)} className="hover:text-green-900 ml-1">
                                                    <X size={12} />
                                                </button>
                                            </span>
                                        ))}
                                    </div>
                                    <div className="flex gap-2">
                                        <input
                                            type="email"
                                            value={newAllowedEmail}
                                            onChange={e => setNewAllowedEmail(e.target.value)}
                                            onKeyDown={e => e.key === 'Enter' && addAllowedEmail()}
                                            placeholder="admin@example.com"
                                            className="flex-1 px-3 py-2 text-sm border border-gray-300 rounded-lg focus:ring-2 focus:ring-green-500 focus:border-transparent"
                                        />
                                        <button
                                            onClick={addAllowedEmail}
                                            disabled={!newAllowedEmail.trim() || saving}
                                            className="px-3 py-2 bg-green-600 text-white rounded-lg hover:bg-green-700 disabled:opacity-50 disabled:cursor-not-allowed"
                                        >
                                            <Plus size={16} />
                                        </button>
                                    </div>
                                </div>

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

                            {/* ── Enterprise bypass (beta / partner) ───────────────── */}
                            <section>
                                <div className="flex items-center gap-2 mb-1">
                                    <Building2 className="w-4 h-4 text-purple-500" />
                                    <h3 className="font-semibold text-gray-900">Enterprise Bypass (Beta / Partner)</h3>
                                </div>
                                <p className="text-sm text-gray-500 mb-4">
                                    Grant Enterprise features without Stripe billing. Add individual emails for beta testers
                                    or entire domains for partner organisations. Removing an email or domain downgrades
                                    affected accounts to Free (unless they have an active paid subscription).
                                </p>

                                <p className="text-sm font-medium text-gray-700 mb-2">Individual emails</p>
                                <div className="flex flex-wrap gap-2 mb-3 min-h-[2rem]">
                                    {(settings.enterpriseEmails || []).length === 0 && (
                                        <span className="text-sm text-gray-400 italic">No beta emails added yet</span>
                                    )}
                                    {(settings.enterpriseEmails || []).map(e => (
                                        <span key={e} className="inline-flex items-center gap-1 px-3 py-1 bg-indigo-100 text-indigo-700 rounded-full text-sm">
                                            {e}
                                            <button onClick={() => removeEnterpriseEmail(e)} className="hover:text-indigo-900 ml-1">
                                                <X size={12} />
                                            </button>
                                        </span>
                                    ))}
                                </div>
                                <div className="flex gap-2 mb-6">
                                    <input
                                        type="email"
                                        value={newEnterpriseEmail}
                                        onChange={ev => setNewEnterpriseEmail(ev.target.value)}
                                        onKeyDown={ev => ev.key === 'Enter' && addEnterpriseEmail()}
                                        placeholder="beta.user@company.com"
                                        className="flex-1 px-3 py-2 text-sm border border-gray-300 rounded-lg focus:ring-2 focus:ring-purple-500 focus:border-transparent"
                                    />
                                    <button
                                        onClick={addEnterpriseEmail}
                                        disabled={!newEnterpriseEmail.trim() || saving}
                                        className="px-3 py-2 bg-indigo-600 text-white rounded-lg hover:bg-indigo-700 disabled:opacity-50 disabled:cursor-not-allowed"
                                    >
                                        <Plus size={16} />
                                    </button>
                                </div>

                                <p className="text-sm font-medium text-gray-700 mb-2">Email domains</p>
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

                                {(settings.enterpriseDomains.length > 0 || (settings.enterpriseEmails || []).length > 0) && (
                                    <div className="mt-3 p-3 bg-purple-50 rounded-lg border border-purple-100">
                                        <p className="text-xs text-purple-700">
                                            Matching users receive Enterprise on login. Removing an email or domain
                                            downgrades bypass-only accounts to Free and syncs their workspaces.
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
        </>
    );

    if (pageMode) {
        return (
            <div className="min-h-screen flex flex-col" style={{ backgroundColor: 'var(--color-background, #fff)' }}>
                <header className="flex items-center justify-between px-6 py-4 border-b border-gray-200 bg-white sticky top-0 z-10">
                    <div className="flex items-center gap-3">
                        <div className="w-9 h-9 bg-purple-100 rounded-lg flex items-center justify-center">
                            <Shield className="w-5 h-5 text-purple-600" />
                        </div>
                        <div>
                            <h2 className="text-lg font-semibold text-gray-900">Admin Settings</h2>
                            <p className="text-xs text-gray-500">System-wide configuration</p>
                        </div>
                    </div>
                    {onLogout && (
                        <button
                            onClick={onLogout}
                            className="flex items-center gap-2 text-sm text-red-600 hover:bg-red-50 px-3 py-1.5 rounded-lg transition-colors"
                        >
                            <LogOut size={16} />
                            Sign out
                        </button>
                    )}
                </header>
                <div className="flex-1 flex flex-col">
                    {content}
                </div>
            </div>
        );
    }

    return (
        <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/60 p-4">
            <div className="bg-white rounded-2xl shadow-2xl w-full max-w-2xl max-h-[90vh] flex flex-col">
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
                {content}
            </div>
        </div>
    );
};

export default AdminSettingsModal;

import React, { useState, useEffect } from 'react';
import { X, Settings, CheckCircle, AlertCircle, Loader2, ExternalLink } from 'lucide-react';
import { sci2CodeBrowserService } from '../services/sci2CodeBrowserService';
import { isRealVSCode } from '../utils/desktop';

declare global {
  interface Window {
    vscode?: { postMessage: (message: any) => void };
  }
}

interface ZoteroSettingsDialogProps {
  isOpen: boolean;
  onClose: () => void;

  embedded?: boolean;
}

const ZoteroSettingsDialog: React.FC<ZoteroSettingsDialogProps> = ({ isOpen, onClose, embedded = false }) => {
  const [apiKey, setApiKey] = useState('');
  const [userId, setUserId] = useState('');
  const [libraryType, setLibraryType] = useState<'user' | 'group'>('user');
  const [groupId, setGroupId] = useState('');
  const [testing, setTesting] = useState(false);
  const [saving, setSaving] = useState(false);
  const [testResult, setTestResult] = useState<'success' | 'error' | null>(null);
  const [testError, setTestError] = useState('');

  useEffect(() => {
    if (!isRealVSCode()) return;
    const handler = (event: MessageEvent) => {
      const msg = event.data;
      if (msg?.type === 'zoteroConfigData' && msg.config) {
        setApiKey(msg.config.apiKey || '');
        setUserId(msg.config.userId || '');
        setLibraryType(msg.config.libraryType || 'user');
        setGroupId(msg.config.groupId || '');
      }
    };
    window.addEventListener('message', handler);
    return () => window.removeEventListener('message', handler);
  }, []);

  useEffect(() => {
    if (isOpen) {
      if (isRealVSCode()) {

        setApiKey('');
        setUserId('');
        setLibraryType('user');
        setGroupId('');
        window.vscode.postMessage({ type: 'requestZoteroConfig' });
      } else {

        const cfg = sci2CodeBrowserService.getConfig();
        if (cfg) {
          setApiKey(cfg.apiKey);
          setUserId(cfg.userId);
          setLibraryType(cfg.libraryType);
          setGroupId(cfg.groupId || '');
        }
      }
      setTestResult(null);
      setTestError('');
    }
  }, [isOpen]);

  const handleSave = async () => {
    if (!apiKey.trim()) {
      setTestResult('error');
      setTestError('API Key is required');
      return;
    }
    setSaving(true);
    setTestResult(null);
    try {
      const resolvedUserId = await sci2CodeBrowserService.saveConfigAutoResolve({
        apiKey: apiKey.trim(),
        libraryType,
        groupId: libraryType === 'group' ? groupId.trim() : undefined,
      });
      if (!resolvedUserId) {
        setTestResult('error');
        setTestError('Invalid API key — could not retrieve your user ID.');
        setSaving(false);
        return;
      }
      setUserId(resolvedUserId);

      if (isRealVSCode()) {
        window.vscode!.postMessage({
          type: 'saveZoteroConfig',
          config: {
            apiKey: apiKey.trim(),
            userId: resolvedUserId,
            libraryType,
            groupId: libraryType === 'group' ? groupId.trim() : '',
          },
        });
      }
      onClose();
    } catch (err: any) {
      setTestResult('error');
      setTestError(err?.message || 'Failed to validate API key');
    } finally {
      setSaving(false);
    }
  };

  const handleTest = async () => {
    if (!apiKey.trim()) {
      setTestResult('error');
      setTestError('API Key is required');
      return;
    }
    setTesting(true);
    setTestResult(null);
    try {

      const resolvedUserId = await sci2CodeBrowserService.fetchUserIdFromApiKey(apiKey.trim());
      if (!resolvedUserId) {
        setTestResult('error');
        setTestError('Invalid API key — could not retrieve your user ID.');
        setTesting(false);
        return;
      }
      setUserId(resolvedUserId);

      sci2CodeBrowserService.saveConfig({
        apiKey: apiKey.trim(),
        userId: resolvedUserId,
        libraryType,
        groupId: libraryType === 'group' ? groupId.trim() : undefined,
      });
      if (isRealVSCode()) {
        window.vscode!.postMessage({
          type: 'saveZoteroConfig',
          config: {
            apiKey: apiKey.trim(),
            userId: resolvedUserId,
            libraryType,
            groupId: libraryType === 'group' ? groupId.trim() : '',
          },
        });
      }
      const ok = await sci2CodeBrowserService.testConnection();
      setTestResult(ok ? 'success' : 'error');
      if (!ok) setTestError('Connection failed. Check your credentials.');
    } catch (err: any) {
      setTestResult('error');
      setTestError(err?.message || 'Connection failed');
    } finally {
      setTesting(false);
    }
  };

  const handleClear = () => {
    sci2CodeBrowserService.clearConfig();
    if (isRealVSCode()) {
      window.vscode!.postMessage({ type: 'clearZoteroConfig' });
    }
    setApiKey('');
    setUserId('');
    setLibraryType('user');
    setGroupId('');
    setTestResult(null);
  };

  if (!isOpen) return null;

  const dialog = (
    <div className={embedded ? "w-full flex flex-col" : "bg-white rounded-lg shadow-2xl w-full max-w-lg max-h-[90vh] flex flex-col"}>
        {}
        <div className="flex items-center justify-between p-4 border-b border-gray-200">
          <div className="flex items-center gap-2">
            <Settings className="text-blue-600" size={24} />
            <h2 className="text-xl font-bold text-gray-800">
              {embedded ? "Connect Your Zotero Library" : "Zotero API Key Settings"}
            </h2>
          </div>
          {!embedded && (
            <button onClick={onClose} className="p-1 hover:bg-gray-100 rounded-lg transition-colors">
              <X size={20} className="text-gray-500" />
            </button>
          )}
        </div>

        {}
        <div className="flex-1 overflow-y-auto p-6 space-y-5">
          {}
          <div className="p-3 bg-blue-50 border border-blue-200 rounded-lg text-sm text-blue-800">
            <p className="font-medium mb-1">How to get your API key:</p>
            <ol className="list-decimal ml-4 space-y-1">
              <li>
                Go to{' '}
                <a
                  href="https://www.zotero.org/settings/keys/new"
                  target="_blank"
                  rel="noopener noreferrer"
                  className="text-blue-600 underline inline-flex items-center gap-1"
                >
                  API key settings <ExternalLink size={12} />
                </a>{' '}
                and create a new key with Read access.
              </li>
              <li>Paste the key below — your User ID will be detected automatically.</li>
            </ol>
          </div>

          {}
          <div>
            <label className="block text-sm font-medium text-gray-700 mb-1">
              Configure Zotero API Key <span className="text-red-500">*</span>
            </label>
            <input
              type="password"
              value={apiKey}
              onChange={(e) => setApiKey(e.target.value)}
              placeholder="Enter your Zotero API key"
              className="w-full px-3 py-2 border border-gray-300 rounded-lg focus:outline-none focus:ring-2 focus:ring-blue-500 text-sm"
            />
          </div>

          {}
          {userId && (
            <div className="flex items-center gap-2 p-3 bg-gray-50 border border-gray-200 rounded-lg text-sm text-gray-600">
              <CheckCircle size={14} className="text-green-500" />
              <span>User ID: <span className="font-mono font-medium text-gray-800">{userId}</span> (auto-detected)</span>
            </div>
          )}

          {}
          <div>
            <label className="block text-sm font-medium text-gray-700 mb-1">Library Type</label>
            <select
              value={libraryType}
              onChange={(e) => setLibraryType(e.target.value as 'user' | 'group')}
              className="w-full px-3 py-2 border border-gray-300 rounded-lg focus:outline-none focus:ring-2 focus:ring-blue-500 text-sm"
            >
              <option value="user">Personal Library</option>
              <option value="group">Group Library</option>
            </select>
          </div>

          {}
          {libraryType === 'group' && (
            <div>
              <label className="block text-sm font-medium text-gray-700 mb-1">Group ID</label>
              <input
                type="text"
                value={groupId}
                onChange={(e) => setGroupId(e.target.value)}
                placeholder="Enter your group ID"
                className="w-full px-3 py-2 border border-gray-300 rounded-lg focus:outline-none focus:ring-2 focus:ring-blue-500 text-sm"
              />
            </div>
          )}

          {}
          {testResult === 'success' && (
            <div className="flex items-center gap-2 p-3 bg-green-50 border border-green-200 rounded-lg text-green-700 text-sm">
              <CheckCircle size={16} />
              <span>Connection successful! Your citation library is accessible.</span>
            </div>
          )}
          {testResult === 'error' && (
            <div className="flex items-center gap-2 p-3 bg-red-50 border border-red-200 rounded-lg text-red-700 text-sm">
              <AlertCircle size={16} />
              <span>{testError}</span>
            </div>
          )}
        </div>

        {}
        <div className="flex items-center justify-between p-4 border-t border-gray-200">
          <button
            onClick={handleClear}
            className="px-4 py-2 text-sm text-red-600 hover:bg-red-50 rounded-lg transition-colors"
          >
            Clear Credentials
          </button>
          <div className="flex items-center gap-2">
            <button
              onClick={handleTest}
              disabled={testing}
              className="px-4 py-2 text-sm border border-gray-300 rounded-lg hover:bg-gray-50 transition-colors disabled:opacity-50 flex items-center gap-1"
            >
              {testing ? <Loader2 size={14} className="animate-spin" /> : null}
              Test Connection
            </button>
            {!embedded && (
              <button
                onClick={onClose}
                className="px-4 py-2 text-sm border border-gray-300 rounded-lg hover:bg-gray-50 transition-colors"
              >
                Cancel
              </button>
            )}
            <button
              onClick={handleSave}
              disabled={saving}
              className="px-4 py-2 text-sm bg-blue-600 text-white rounded-lg hover:bg-blue-700 transition-colors disabled:opacity-50 flex items-center gap-1"
            >
              {saving ? <Loader2 size={14} className="animate-spin" /> : null}
              Save
            </button>
          </div>
        </div>
    </div>
  );

  if (embedded) return dialog;

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center bg-black bg-opacity-50">
      {dialog}
    </div>
  );
};

export default ZoteroSettingsDialog;

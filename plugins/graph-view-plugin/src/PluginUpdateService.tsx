

import React, { useEffect, useState, useCallback } from 'react';
import { Download, X, RefreshCw, CheckCircle, AlertTriangle } from 'lucide-react';

interface PluginVersion {
  version: string;
  releaseDate: string;
  changelog: string[];
  downloadUrl?: string;
}

interface PluginUpdateServiceProps {
  currentVersion: string;
  pluginId: string;
  checkInterval?: number; // in milliseconds, default 1 hour
}

export const PluginUpdateService: React.FC<PluginUpdateServiceProps> = ({
  currentVersion,
  pluginId,
  checkInterval = 60 * 60 * 1000 // 1 hour
}) => {
  const [latestVersion, setLatestVersion] = useState<PluginVersion | null>(null);
  const [updateAvailable, setUpdateAvailable] = useState(false);
  const [checking, setChecking] = useState(false);
  const [showNotification, setShowNotification] = useState(false);
  const [updating, setUpdating] = useState(false);
  const [updateSuccess, setUpdateSuccess] = useState(false);
  const [updateError, setUpdateError] = useState<string | null>(null);

  const isNewerVersion = useCallback((latest: string, current: string): boolean => {
    const latestParts = latest.split('.').map(Number);
    const currentParts = current.split('.').map(Number);

    for (let i = 0; i < Math.max(latestParts.length, currentParts.length); i++) {
      const latestPart = latestParts[i] || 0;
      const currentPart = currentParts[i] || 0;

      if (latestPart > currentPart) return true;
      if (latestPart < currentPart) return false;
    }

    return false;
  }, []);

  const checkForUpdates = useCallback(async () => {
    setChecking(true);
    setUpdateError(null);

    try {
      console.log('[PluginUpdateService] Checking for updates for plugin:', pluginId);

      const response = await fetch(
        `${(window as any).API_BASE_URL}/api/plugins/${pluginId}/latest-version`,
        {
          headers: {
            'Authorization': `Bearer ${localStorage.getItem('authToken')}`
          }
        }
      );

      if (response.ok) {
        const data = await response.json();
        console.log('[PluginUpdateService] Latest version info:', data);

        setLatestVersion(data);

        if (isNewerVersion(data.version, currentVersion)) {
          console.log('[PluginUpdateService] Update available:', data.version);
          setUpdateAvailable(true);
          setShowNotification(true);

          localStorage.setItem(`plugin-update-check-${pluginId}`, Date.now().toString());
        } else {
          console.log('[PluginUpdateService] Plugin is up to date');
          setUpdateAvailable(false);
        }
      } else {
        console.log('[PluginUpdateService] Failed to check for updates:', response.statusText);
      }
    } catch (err) {
      console.error('[PluginUpdateService] Error checking for updates:', err);
    } finally {
      setChecking(false);
    }
  }, [pluginId, currentVersion, isNewerVersion]);

  const installUpdate = useCallback(async () => {
    if (!latestVersion) return;

    setUpdating(true);
    setUpdateError(null);

    try {
      console.log('[PluginUpdateService] Installing update:', latestVersion.version);

      const response = await fetch(
        `${(window as any).API_BASE_URL}/api/plugins/${pluginId}/update`,
        {
          method: 'POST',
          headers: {
            'Content-Type': 'application/json',
            'Authorization': `Bearer ${localStorage.getItem('authToken')}`
          },
          body: JSON.stringify({
            version: latestVersion.version
          })
        }
      );

      if (response.ok) {
        console.log('[PluginUpdateService] Update installed successfully');
        setUpdateSuccess(true);
        setUpdateAvailable(false);
        setShowNotification(false);

        setTimeout(() => {
          window.location.reload();
        }, 2000);
      } else {
        throw new Error('Failed to install update');
      }
    } catch (err) {
      const errorMsg = err instanceof Error ? err.message : 'Unknown error';
      console.error('[PluginUpdateService] Update installation failed:', errorMsg);
      setUpdateError(errorMsg);
    } finally {
      setUpdating(false);
    }
  }, [pluginId, latestVersion]);

  useEffect(() => {

    const lastCheck = localStorage.getItem(`plugin-update-check-${pluginId}`);
    if (!lastCheck || Date.now() - parseInt(lastCheck) > checkInterval) {
      checkForUpdates();
    }

    const interval = setInterval(() => {
      checkForUpdates();
    }, checkInterval);

    return () => clearInterval(interval);
  }, [pluginId, checkInterval, checkForUpdates]);

  if (!showNotification && !updateSuccess) {
    return null;
  }

  return (
    <>
      {}
      {showNotification && updateAvailable && latestVersion && (
        <div style={styles.notification}>
          <div style={styles.notificationContent}>
            <div style={styles.notificationHeader}>
              <div style={styles.notificationTitle}>
                <Download size={20} style={{ color: '#667eea' }} />
                <span style={{ fontWeight: '600' }}>Update Available</span>
              </div>
              <button onClick={() => setShowNotification(false)} style={styles.closeBtn}>
                <X size={16} />
              </button>
            </div>

            <div style={styles.notificationBody}>
              <div style={styles.versionInfo}>
                <span style={{ color: '#6b7280' }}>Current: v{currentVersion}</span>
                <span style={{ margin: '0 8px' }}>→</span>
                <span style={{ color: '#667eea', fontWeight: '600' }}>New: v{latestVersion.version}</span>
              </div>

              {latestVersion.changelog && latestVersion.changelog.length > 0 && (
                <div style={styles.changelog}>
                  <div style={{ fontSize: '12px', fontWeight: '600', marginBottom: '6px', color: '#374151' }}>
                    What's New:
                  </div>
                  <ul style={styles.changelogList}>
                    {latestVersion.changelog.slice(0, 3).map((change, i) => (
                      <li key={i} style={styles.changelogItem}>{change}</li>
                    ))}
                  </ul>
                </div>
              )}

              {updateError && (
                <div style={styles.error}>
                  <AlertTriangle size={14} />
                  <span>{updateError}</span>
                </div>
              )}

              <button
                onClick={installUpdate}
                disabled={updating}
                style={updating ? styles.btnUpdateDisabled : styles.btnUpdate}
              >
                {updating ? (
                  <>
                    <RefreshCw size={16} className="spinning" />
                    Installing...
                  </>
                ) : (
                  <>
                    <Download size={16} />
                    Update Now
                  </>
                )}
              </button>
            </div>
          </div>
        </div>
      )}

      {}
      {updateSuccess && (
        <div style={styles.successNotification}>
          <CheckCircle size={20} style={{ color: '#10b981' }} />
          <span>Update installed! Reloading...</span>
        </div>
      )}
    </>
  );
};

const styles: Record<string, React.CSSProperties> = {
  notification: {
    position: 'fixed',
    bottom: '20px',
    right: '20px',
    width: '360px',
    backgroundColor: 'white',
    borderRadius: '12px',
    boxShadow: '0 10px 40px rgba(0,0,0,0.15)',
    zIndex: 1000,
    animation: 'slideInRight 0.3s ease-out',
    border: '1px solid #e5e7eb'
  },
  notificationContent: {
    padding: '0'
  },
  notificationHeader: {
    padding: '16px',
    borderBottom: '1px solid #e5e7eb',
    display: 'flex',
    justifyContent: 'space-between',
    alignItems: 'center'
  },
  notificationTitle: {
    display: 'flex',
    alignItems: 'center',
    gap: '10px',
    fontSize: '16px',
    color: '#111827'
  },
  closeBtn: {
    background: 'transparent',
    border: 'none',
    color: '#9ca3af',
    cursor: 'pointer',
    padding: '4px',
    borderRadius: '4px',
    display: 'flex',
    alignItems: 'center',
    justifyContent: 'center'
  },
  notificationBody: {
    padding: '16px'
  },
  versionInfo: {
    display: 'flex',
    alignItems: 'center',
    fontSize: '14px',
    marginBottom: '12px'
  },
  changelog: {
    marginBottom: '12px',
    padding: '12px',
    backgroundColor: '#f9fafb',
    borderRadius: '8px'
  },
  changelogList: {
    margin: 0,
    paddingLeft: '20px',
    fontSize: '12px',
    color: '#6b7280'
  },
  changelogItem: {
    marginBottom: '4px'
  },
  error: {
    display: 'flex',
    alignItems: 'center',
    gap: '8px',
    padding: '8px 12px',
    backgroundColor: '#fef2f2',
    border: '1px solid #fecaca',
    borderRadius: '6px',
    fontSize: '12px',
    color: '#dc2626',
    marginBottom: '12px'
  },
  btnUpdate: {
    width: '100%',
    padding: '10px',
    backgroundColor: '#667eea',
    color: 'white',
    border: 'none',
    borderRadius: '8px',
    fontSize: '14px',
    fontWeight: '600',
    cursor: 'pointer',
    display: 'flex',
    alignItems: 'center',
    justifyContent: 'center',
    gap: '8px',
    transition: 'all 0.2s'
  },
  btnUpdateDisabled: {
    width: '100%',
    padding: '10px',
    backgroundColor: '#d1d5db',
    color: '#6b7280',
    border: 'none',
    borderRadius: '8px',
    fontSize: '14px',
    fontWeight: '600',
    cursor: 'not-allowed',
    display: 'flex',
    alignItems: 'center',
    justifyContent: 'center',
    gap: '8px'
  },
  successNotification: {
    position: 'fixed',
    bottom: '20px',
    right: '20px',
    padding: '16px 20px',
    backgroundColor: 'white',
    borderRadius: '12px',
    boxShadow: '0 10px 40px rgba(0,0,0,0.15)',
    border: '1px solid #10b981',
    display: 'flex',
    alignItems: 'center',
    gap: '12px',
    fontSize: '14px',
    fontWeight: '600',
    color: '#047857',
    zIndex: 1000,
    animation: 'slideInRight 0.3s ease-out'
  }
};

export default PluginUpdateService;



import apiClient from './apiClient';

type SyncCallback = (projectId: string) => void;

class SyncService {
    private pollingInterval: number = 5000; // Poll every 5 seconds
    private monitoringDuration: number = 300000; // Monitor for 5 minutes for shared files
    private lastKnownTimestamps: Map<string, number> = new Map();
    private activePolling: Map<string, NodeJS.Timeout> = new Map();
    private monitoringTimeouts: Map<string, NodeJS.Timeout> = new Map();
    private callbacks: Map<string, Set<SyncCallback>> = new Map();
    private ignoreNextUpdate: Map<string, boolean> = new Map();

    startMonitoring(projectId: string, callback: SyncCallback, initialTimestamp?: number) {
        console.log('[SyncService] Starting monitoring for project:', projectId);

        if (initialTimestamp) {
            this.lastKnownTimestamps.set(projectId, initialTimestamp);
        }

        if (!this.callbacks.has(projectId)) {
            this.callbacks.set(projectId, new Set());
        }
        this.callbacks.get(projectId)!.add(callback);

        this.stopPolling(projectId);

        const intervalId = setInterval(() => {
            this.checkForUpdates(projectId);
        }, this.pollingInterval);

        this.activePolling.set(projectId, intervalId);

        const timeoutId = setTimeout(() => {
            console.log('[SyncService] ⏱️ Monitoring timeout reached (30s), stopping polling');
            this.stopPolling(projectId);
        }, this.monitoringDuration);

        this.monitoringTimeouts.set(projectId, timeoutId);
        console.log('[SyncService] 🔄 Started polling every 5 seconds for 30 seconds');
    }

    stopMonitoring(projectId: string, callback?: SyncCallback) {
        if (callback) {

            this.callbacks.get(projectId)?.delete(callback);

            if (this.callbacks.get(projectId)?.size === 0) {
                this.stopPolling(projectId);
                this.callbacks.delete(projectId);
                this.lastKnownTimestamps.delete(projectId);
            }
        } else {

            this.stopPolling(projectId);
            this.callbacks.delete(projectId);
            this.lastKnownTimestamps.delete(projectId);
        }
    }

    notifyLocalSave(projectId: string) {
        console.log('[SyncService] ⚠️ Local save notification for:', projectId);
        this.ignoreNextUpdate.set(projectId, true);

        setTimeout(() => {
            this.ignoreNextUpdate.delete(projectId);
            console.log('[SyncService] ✓ Local save ignore flag cleared for:', projectId);
        }, 15000); // 15 seconds should be enough
    }

    updateTimestamp(projectId: string, timestamp: number) {
        console.log('[SyncService] Updating timestamp for:', projectId, 'to:', new Date(timestamp).toISOString());
        this.lastKnownTimestamps.set(projectId, timestamp);
    }

    private async checkForUpdates(projectId: string) {
        try {
            const response = await apiClient.get<{ updatedAt: string }>(`/api/ontology/metadata/${projectId}/timestamp`);

            if (response && response.updatedAt) {
                const serverTimestamp = new Date(response.updatedAt).getTime();
                const lastKnown = this.lastKnownTimestamps.get(projectId);

                if (lastKnown && serverTimestamp > lastKnown) {

                    if (this.ignoreNextUpdate.get(projectId)) {
                        console.log('[SyncService] ⏭️ Ignoring update (local save in progress)');
                        this.lastKnownTimestamps.set(projectId, serverTimestamp);
                        this.ignoreNextUpdate.delete(projectId);
                        return;
                    }

                    console.log('[SyncService] 🔄 Change detected for project:', projectId);
                    console.log('[SyncService] Last known:', new Date(lastKnown).toISOString());
                    console.log('[SyncService] Server:', new Date(serverTimestamp).toISOString());

                    this.lastKnownTimestamps.set(projectId, serverTimestamp);

                    console.log('[SyncService] ✅ Change detected, continuing to monitor...');

                    const callbacks = this.callbacks.get(projectId);
                    if (callbacks) {
                        callbacks.forEach(callback => {
                            try {
                                callback(projectId);
                            } catch (error) {
                                console.error('[SyncService] Error in callback:', error);
                            }
                        });
                    }
                }
            }
        } catch (error: any) {

            const status = error?.status || error?.response?.status;
            if (status === 404) {
                console.warn('[SyncService] ⚠️ Project no longer exists:', projectId);
                this.stopPolling(projectId);

                const callbacks = this.callbacks.get(projectId);
                if (callbacks) {
                    callbacks.forEach(callback => {
                        try { callback(`__deleted__:${projectId}`); } catch { /* ignore */ }
                    });
                }
                return;
            }
            // Silently fail on other network errors to avoid console spam
        }
    }

    private stopPolling(projectId: string) {
        const intervalId = this.activePolling.get(projectId);
        if (intervalId) {
            clearInterval(intervalId);
            this.activePolling.delete(projectId);
        }

        const timeoutId = this.monitoringTimeouts.get(projectId);
        if (timeoutId) {
            clearTimeout(timeoutId);
            this.monitoringTimeouts.delete(projectId);
        }
    }

    cleanup() {
        console.log('[SyncService] Cleaning up all monitoring');
        this.activePolling.forEach((intervalId) => clearInterval(intervalId));
        this.activePolling.clear();
        this.monitoringTimeouts.forEach((timeoutId) => clearTimeout(timeoutId));
        this.monitoringTimeouts.clear();
        this.callbacks.clear();
        this.lastKnownTimestamps.clear();
        this.ignoreNextUpdate.clear();
    }
}

export const syncService = new SyncService();

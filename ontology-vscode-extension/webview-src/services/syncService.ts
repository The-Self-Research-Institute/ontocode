/**
 * Service to detect when the ontology data has been updated by other users
 * Polls every 10 seconds to check for changes
 */

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

    /**
     * Start monitoring a project for changes
     * Polls every 5 seconds for 30 seconds, stops after first change or timeout
     */
    startMonitoring(projectId: string, callback: SyncCallback, initialTimestamp?: number) {
        console.log('[SyncService] Starting monitoring for project:', projectId);
        
        // Store initial timestamp if provided
        if (initialTimestamp) {
            this.lastKnownTimestamps.set(projectId, initialTimestamp);
        }

        // Register callback
        if (!this.callbacks.has(projectId)) {
            this.callbacks.set(projectId, new Set());
        }
        this.callbacks.get(projectId)!.add(callback);

        // Cancel existing polling if any
        this.stopPolling(projectId);

        // Start polling every 5 seconds
        const intervalId = setInterval(() => {
            this.checkForUpdates(projectId);
        }, this.pollingInterval);

        this.activePolling.set(projectId, intervalId);
        
        // Set timeout to stop monitoring after 30 seconds
        const timeoutId = setTimeout(() => {
            console.log('[SyncService] ⏱️ Monitoring timeout reached (30s), stopping polling');
            this.stopPolling(projectId);
        }, this.monitoringDuration);
        
        this.monitoringTimeouts.set(projectId, timeoutId);
        console.log('[SyncService] 🔄 Started polling every 5 seconds for 30 seconds');
    }

    /**
     * Stop monitoring a project
     */
    stopMonitoring(projectId: string, callback?: SyncCallback) {
        if (callback) {
            // Remove specific callback
            this.callbacks.get(projectId)?.delete(callback);
            
            // If no more callbacks, stop polling
            if (this.callbacks.get(projectId)?.size === 0) {
                this.stopPolling(projectId);
                this.callbacks.delete(projectId);
                this.lastKnownTimestamps.delete(projectId);
            }
        } else {
            // Stop all monitoring for this project
            this.stopPolling(projectId);
            this.callbacks.delete(projectId);
            this.lastKnownTimestamps.delete(projectId);
        }
    }

    /**
     * Notify that the current user is about to save
     * This prevents triggering a refresh for their own changes
     */
    notifyLocalSave(projectId: string) {
        console.log('[SyncService] ⚠️ Local save notification for:', projectId);
        this.ignoreNextUpdate.set(projectId, true);
        
        // Clear the ignore flag after a longer delay to ensure it catches the timestamp update
        setTimeout(() => {
            this.ignoreNextUpdate.delete(projectId);
            console.log('[SyncService] ✓ Local save ignore flag cleared for:', projectId);
        }, 15000); // 15 seconds should be enough
    }

    /**
     * Update the known timestamp after loading fresh data
     */
    updateTimestamp(projectId: string, timestamp: number) {
        console.log('[SyncService] Updating timestamp for:', projectId, 'to:', new Date(timestamp).toISOString());
        this.lastKnownTimestamps.set(projectId, timestamp);
    }

    /**
     * Check if the project has been updated
     */
    private async checkForUpdates(projectId: string) {
        try {
            const response = await apiClient.get<{ updatedAt: string }>(`/api/ontology/metadata/${projectId}/timestamp`);
            
            if (response && response.updatedAt) {
                const serverTimestamp = new Date(response.updatedAt).getTime();
                const lastKnown = this.lastKnownTimestamps.get(projectId);

                if (lastKnown && serverTimestamp > lastKnown) {
                    // Check if we should ignore this update (user just saved)
                    if (this.ignoreNextUpdate.get(projectId)) {
                        console.log('[SyncService] ⏭️ Ignoring update (local save in progress)');
                        this.lastKnownTimestamps.set(projectId, serverTimestamp);
                        this.ignoreNextUpdate.delete(projectId);
                        return;
                    }

                    console.log('[SyncService] 🔄 Change detected for project:', projectId);
                    console.log('[SyncService] Last known:', new Date(lastKnown).toISOString());
                    console.log('[SyncService] Server:', new Date(serverTimestamp).toISOString());
                    
                    // Update timestamp
                    this.lastKnownTimestamps.set(projectId, serverTimestamp);

                    // Continue polling to detect subsequent changes
                    console.log('[SyncService] ✅ Change detected, continuing to monitor...');

                    // Notify all callbacks
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
            // Handle 404 (project deleted) specifically
            const status = error?.status || error?.response?.status;
            if (status === 404) {
                console.warn('[SyncService] ⚠️ Project no longer exists:', projectId);
                this.stopPolling(projectId);
                // Notify callbacks with a special deleted signal
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

    /**
     * Stop polling for a project
     */
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

    /**
     * Stop all monitoring
     */
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

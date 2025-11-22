/**
 * Service to detect when the ontology data has been updated by other users
 * Checks for updates 60 seconds after the last known save
 */

import apiClient from './apiClient';

type SyncCallback = (projectId: string) => void;

class SyncService {
    private checkDelay: number = 60000; // Check 60 seconds after last save
    private lastKnownTimestamps: Map<string, number> = new Map();
    private scheduledChecks: Map<string, NodeJS.Timeout> = new Map();
    private callbacks: Map<string, Set<SyncCallback>> = new Map();
    private ignoreNextUpdate: Map<string, boolean> = new Map();

    /**
     * Start monitoring a project for changes
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

        // Cancel existing check if any
        this.cancelScheduledCheck(projectId);

        // Schedule a check after 60 seconds
        const timeoutId = setTimeout(() => {
            this.checkForUpdates(projectId);
        }, this.checkDelay);

        this.scheduledChecks.set(projectId, timeoutId);
        console.log('[SyncService] 📅 Scheduled check in 60 seconds');
    }

    /**
     * Stop monitoring a project
     */
    stopMonitoring(projectId: string, callback?: SyncCallback) {
        if (callback) {
            // Remove specific callback
            this.callbacks.get(projectId)?.delete(callback);
            
            // If no more callbacks, stop check
            if (this.callbacks.get(projectId)?.size === 0) {
                this.cancelScheduledCheck(projectId);
                this.callbacks.delete(projectId);
                this.lastKnownTimestamps.delete(projectId);
            }
        } else {
            // Stop all monitoring for this project
            this.cancelScheduledCheck(projectId);
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
        }, 65000); // 65 seconds to cover the 60 second check delay
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

                console.log('[SyncService] 📊 Timestamp check for:', projectId);
                console.log('[SyncService]   Last known:', lastKnown ? new Date(lastKnown).toISOString() : 'none');
                console.log('[SyncService]   Server:', new Date(serverTimestamp).toISOString());

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
                } else if (lastKnown && serverTimestamp <= lastKnown) {
                    console.log('[SyncService] ⏱️ No changes detected');
                }
            }
        } catch (error) {
            console.error('[SyncService] ❌ Error checking for updates:', error);
        }
    }

    /**
     * Cancel scheduled check for a project
     */
    private cancelScheduledCheck(projectId: string) {
        const timeoutId = this.scheduledChecks.get(projectId);
        if (timeoutId) {
            clearTimeout(timeoutId);
            this.scheduledChecks.delete(projectId);
            console.log('[SyncService] Cancelled scheduled check for project:', projectId);
        }
    }

    /**
     * Stop all monitoring
     */
    cleanup() {
        console.log('[SyncService] Cleaning up all monitoring');
        this.scheduledChecks.forEach((timeoutId) => clearTimeout(timeoutId));
        this.scheduledChecks.clear();
        this.callbacks.clear();
        this.lastKnownTimestamps.clear();
        this.ignoreNextUpdate.clear();
    }
}

export const syncService = new SyncService();

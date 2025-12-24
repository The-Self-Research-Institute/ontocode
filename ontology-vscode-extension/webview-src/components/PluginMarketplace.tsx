/**
 * =============================================================================
 * PLUGIN MARKETPLACE COMPONENT
 * =============================================================================
 * 
 * Displays available plugins with REAL data from backend:
 * - Fetches plugins from /api/plugins
 * - Shows real download counts and active installs
 * - Displays actual user ratings (1-5 stars) 
 * - Allows users to rate installed plugins
 * - Tracks installations/uninstallations
 * 
 * Organization:
 * 1. Imports & Type Definitions
 * 2. Main Component
 * 3. Data Fetching Functions
 * 4. Event Handlers
 * 5. Render Helpers
 * 6. Main Render JSX
 */

import React, { useState, useEffect } from 'react';
import { Download, Trash2, Search, Package, X, Star } from 'lucide-react';
import { pluginLoader } from '../services/pluginLoader';
import { RatingModal } from './RatingModal';

// =============================================================================
// TYPE DEFINITIONS
// =============================================================================

interface PluginStats {
  pluginId: string;
  totalInstalls: number;
  activeInstalls: number;
  totalDownloads: number;
  averageRating: number;
  totalRatings: number;
  ratingDistribution: Record<number, number>;
  recommendedCount: number;
  totalReviews: number;
}

interface Plugin {
  pluginId: string;
  name: string;
  description: string;
  version: string;
  author: string;
  category: string;
  icon?: string;
  screenshots?: string[];
  stats?: PluginStats;
  verified: boolean;
  installed: boolean;
  installedVersion?: string; // Version currently installed
  hasUpdate?: boolean; // True if marketplace version is newer
}

interface PluginMarketplaceProps {
  isOpen: boolean;
  onClose: () => void;
  onInstall: (pluginId: string) => Promise<void>;
  onUninstall: (pluginId: string) => Promise<void>;
  installedPlugins: Set<string>;
}

// =============================================================================
// MAIN COMPONENT
// =============================================================================

export const PluginMarketplace: React.FC<PluginMarketplaceProps> = ({
  isOpen,
  onClose,
  onInstall,
  onUninstall,
  installedPlugins
}) => {
  // ---------------------------------------------------------------------------
  // STATE
  // ---------------------------------------------------------------------------
  const [plugins, setPlugins] = useState<Plugin[]>([]);
  const [loading, setLoading] = useState(false);
  const [searchQuery, setSearchQuery] = useState('');
  const [selectedCategory, setSelectedCategory] = useState<string>('All');
  const [installingPlugin, setInstallingPlugin] = useState<string | null>(null);
  const [showRatingModal, setShowRatingModal] = useState(false);
  const [ratingPlugin, setRatingPlugin] = useState<Plugin | null>(null);
  const [currentUserRating, setCurrentUserRating] = useState<any>(null);
  const [showUninstallConfirm, setShowUninstallConfirm] = useState(false);
  const [uninstallPluginId, setUninstallPluginId] = useState<string | null>(null);
  const [toast, setToast] = useState<{ message: string; type: 'success' | 'error' } | null>(null);
  const [updatingPlugin, setUpdatingPlugin] = useState<string | null>(null);
  const [sortBy, setSortBy] = useState<'name' | 'downloads' | 'rating'>('downloads');

  const categories = ['All', 'Visualization', 'Editor', 'Reasoning', 'Query', 'Import/Export', 'Utility'];

  // Show toast helper
  const showToast = (message: string, type: 'success' | 'error' = 'success') => {
    setToast({ message, type });
    setTimeout(() => setToast(null), 3000);
  };

  // ---------------------------------------------------------------------------
  // EFFECTS
  // ---------------------------------------------------------------------------
  useEffect(() => {
    if (isOpen) {
      fetchPluginsWithStats();
    }
  }, [isOpen]);

  // =============================================================================
  // HELPER FUNCTIONS
  // =============================================================================

  /**
   * Compare semantic versions (e.g., "1.2.3" vs "1.2.4")
   * Returns true if newVersion is greater than currentVersion
   */
  const isNewerVersion = (newVersion: string, currentVersion: string): boolean => {
    const parseVersion = (v: string) => v.split('.').map(n => parseInt(n) || 0);
    const [newMajor, newMinor, newPatch] = parseVersion(newVersion);
    const [curMajor, curMinor, curPatch] = parseVersion(currentVersion);
    
    if (newMajor !== curMajor) return newMajor > curMajor;
    if (newMinor !== curMinor) return newMinor > curMinor;
    return newPatch > curPatch;
  };

  /**
   * Get installed plugin version from pluginLoader
   */
  const getInstalledVersion = (pluginId: string): string | undefined => {
    const installedPlugins = pluginLoader.getInstalledPlugins();
    const plugin = installedPlugins.find(p => p.id === pluginId);
    return plugin?.manifest?.version;
  };

  // =============================================================================
  // DATA FETCHING
  // =============================================================================

  /**
   * Fetch all plugins with real stats from backend
   */
  const fetchPluginsWithStats = async () => {
    setLoading(true);
    try {
      const token = localStorage.getItem('token');
      const headers: HeadersInit = { 'Content-Type': 'application/json' };
      
      if (token) {
        headers['Authorization'] = `Bearer ${token}`;
      }

      // Fetch plugins list via gateway
      const apiBaseUrl = (window as any).API_BASE_URL || 'http://localhost:8087';
      console.log('[PluginMarketplace] Fetching from:', `${apiBaseUrl}/api/plugins?size=50`);
      const response = await fetch(`${apiBaseUrl}/api/plugins?size=50`, { headers });
      
      console.log('[PluginMarketplace] Response status:', response.status);
      
      if (!response.ok) {
        const errorText = await response.text();
        console.error('[PluginMarketplace] Error response:', errorText);
        throw new Error(`HTTP ${response.status}: ${errorText}`);
      }

      const data = await response.json();
      console.log('[PluginMarketplace] Received data:', data);

      // Handle both paginated and non-paginated responses
      const pluginsList = Array.isArray(data) ? data : (data.content || []);
      console.log('[PluginMarketplace] Processing plugins:', pluginsList.length);

      // Fetch real stats for each plugin
      const pluginsWithStats = await Promise.all(
        pluginsList.map(async (p: any) => {
          let stats: PluginStats | undefined;
          
          try {
            const statsResponse = await fetch(
              `${apiBaseUrl}/api/plugins/${p.pluginId}/stats`, 
              { headers }
            );
            if (statsResponse.ok) {
              stats = await statsResponse.json();
            }
          } catch (error) {
            console.warn(`Failed to fetch stats for ${p.pluginId}:`, error);
          }

          const isInstalled = installedPlugins.has(p.pluginId);
          const installedVersion = isInstalled ? getInstalledVersion(p.pluginId) : undefined;
          const hasUpdate = isInstalled && installedVersion 
            ? isNewerVersion(p.latestVersion, installedVersion) 
            : false;

          return {
            pluginId: p.pluginId,
            name: p.name,
            description: p.description,
            version: p.latestVersion,
            author: p.author,
            category: p.category || 'Utility',
            icon: p.icon,
            screenshots: p.screenshots || [],
            stats,
            verified: p.verified || false,
            installed: isInstalled,
            installedVersion,
            hasUpdate
          };
        })
      );

      console.log('[PluginMarketplace] Final plugins with stats:', pluginsWithStats);
      setPlugins(pluginsWithStats);
    } catch (error) {
      console.error('[PluginMarketplace] Failed to fetch plugins:', error);
      showToast(`Failed to load plugins: ${error instanceof Error ? error.message : 'Unknown error'}`, 'error');
      setPlugins([]);
    } finally {
      setLoading(false);
    }
  };

  /**
   * Refresh stats for a specific plugin after install/uninstall/rating
   */
  const refreshPluginStats = async (pluginId: string) => {
    try {
      const token = localStorage.getItem('token');
      const headers: HeadersInit = { 'Content-Type': 'application/json' };
      
      if (token) {
        headers['Authorization'] = `Bearer ${token}`;
      }

      const apiBaseUrl = (window as any).API_BASE_URL || 'http://localhost:8087';
      const statsResponse = await fetch(
        `${apiBaseUrl}/api/plugins/${pluginId}/stats`, 
        { headers }
      );
      
      if (statsResponse.ok) {
        const stats = await statsResponse.json();
        
        setPlugins(prev => prev.map(p =>
          p.pluginId === pluginId ? { ...p, stats } : p
        ));
      }
    } catch (error) {
      console.warn('Failed to refresh stats:', error);
    }
  };

  // =============================================================================
  // EVENT HANDLERS
  // =============================================================================

  /**
   * Install a plugin
   */
  const handleInstall = async (pluginId: string) => {
    setInstallingPlugin(pluginId);
    try {
      await onInstall(pluginId);
      
      // Only mark as installed if onInstall succeeded (no error thrown)
      setPlugins(prev => prev.map(p =>
        p.pluginId === pluginId ? { ...p, installed: true, installedVersion: p.version, hasUpdate: false } : p
      ));

      await refreshPluginStats(pluginId);
      showToast('Plugin installed successfully!');
    } catch (error) {
      console.error('Failed to install plugin:', error);
      // Don't mark as installed on error
      showToast(`Failed to install plugin: ${error instanceof Error ? error.message : 'Unknown error'}`, 'error');
    } finally {
      setInstallingPlugin(null);
    }
  };

  /**
   * Update a plugin to the latest version
   */
  const handleUpdate = async (pluginId: string) => {
    setUpdatingPlugin(pluginId);
    try {
      // Uninstall old version
      await pluginLoader.uninstallPlugin(pluginId);
      
      // Install new version
      await onInstall(pluginId);
      
      setPlugins(prev => prev.map(p =>
        p.pluginId === pluginId ? { ...p, installedVersion: p.version, hasUpdate: false } : p
      ));

      await refreshPluginStats(pluginId);
      showToast('Plugin updated successfully!');
    } catch (error) {
      console.error('Failed to update plugin:', error);
      showToast('Failed to update plugin. Please try again.', 'error');
    } finally {
      setUpdatingPlugin(null);
    }
  };

  /**
   * Uninstall a plugin
   */
  const handleUninstall = async (pluginId: string) => {
    const plugin = plugins.find(p => p.pluginId === pluginId);
    setUninstallPluginId(pluginId);
    setShowUninstallConfirm(true);
  };

  const confirmUninstall = async () => {
    if (!uninstallPluginId) return;
    
    setInstallingPlugin(uninstallPluginId);
    setShowUninstallConfirm(false);
    
    try {
      await onUninstall(uninstallPluginId);
      
      setPlugins(prev => prev.map(p =>
        p.pluginId === uninstallPluginId ? { ...p, installed: false } : p
      ));

      await refreshPluginStats(uninstallPluginId);
    } catch (error) {
      console.error('Failed to uninstall plugin:', error);
      // Use a toast notification instead of alert
      console.error('Failed to uninstall plugin. Please try again.');
    } finally {
      setInstallingPlugin(null);
      setUninstallPluginId(null);
    }
  };

  const cancelUninstall = () => {
    setShowUninstallConfirm(false);
    setUninstallPluginId(null);
  };

  /**
   * Open rating modal
   */
  const handleOpenRating = async (plugin: Plugin) => {
    setRatingPlugin(plugin);
    
    try {
      const rating = await pluginLoader.getUserRating(plugin.pluginId);
      setCurrentUserRating(rating);
    } catch (error) {
      console.error('Failed to fetch user rating:', error);
      setCurrentUserRating(null);
    }
    
    setShowRatingModal(true);
  };

  /**
   * Submit a rating
   */
  const handleSubmitRating = async (rating: {
    stars: number;
    review?: string;
    merits?: string;
    demerits?: string;
    recommended?: boolean;
  }) => {
    if (!ratingPlugin) return;

    try {
      await pluginLoader.ratePlugin(
        ratingPlugin.pluginId,
        rating.stars,
        rating.review,
        rating.merits,
        rating.demerits,
        rating.recommended
      );

      await refreshPluginStats(ratingPlugin.pluginId);
      
      showToast('Thank you for your rating!');
      setShowRatingModal(false);
      setRatingPlugin(null);
    } catch (error: any) {
      console.error('Failed to submit rating:', error);
      const errorMessage = error?.message || 'Failed to submit rating';
      showToast(errorMessage, 'error');
      
      // If authentication error, close modal
      if (errorMessage.includes('log in')) {
        setShowRatingModal(false);
        setRatingPlugin(null);
      }
    }
  };

  // =============================================================================
  // COMPUTED VALUES
  // =============================================================================

  const filteredPlugins = plugins
    .filter(plugin => {
      const matchesSearch = 
        plugin.name.toLowerCase().includes(searchQuery.toLowerCase()) ||
        plugin.description.toLowerCase().includes(searchQuery.toLowerCase());
      const matchesCategory = 
        selectedCategory === 'All' || plugin.category === selectedCategory;
      return matchesSearch && matchesCategory;
    })
    .sort((a, b) => {
      switch (sortBy) {
        case 'name':
          return a.name.localeCompare(b.name);
        case 'downloads':
          const aDownloads = a.stats?.totalDownloads || 0;
          const bDownloads = b.stats?.totalDownloads || 0;
          return bDownloads - aDownloads; // Descending order
        case 'rating':
          const aRating = a.stats?.averageRating || 0;
          const bRating = b.stats?.averageRating || 0;
          return bRating - aRating; // Descending order
        default:
          return 0;
      }
    });

  // =============================================================================
  // RENDER HELPERS
  // =============================================================================

  /**
   * Render star rating display (shows real ratings from backend)
   */
  const renderStars = (rating: number, totalRatings: number) => {
    return (
      <div className="flex items-center gap-1">
        {[1, 2, 3, 4, 5].map((star) => (
          <Star
            key={star}
            size={14}
            className={
              star <= Math.round(rating) 
                ? 'fill-yellow-400 text-yellow-400' 
                : 'text-gray-300'
            }
          />
        ))}
        <span className="text-xs text-gray-600 ml-1">
          {rating > 0 ? rating.toFixed(1) : '—'}
        </span>
        <span className="text-xs text-gray-700">
          ({totalRatings})
        </span>
      </div>
    );
  };

  // =============================================================================
  // MAIN RENDER
  // =============================================================================

  if (!isOpen) return null;

  return (
    <div className="fixed inset-0 bg-black bg-opacity-50 flex items-center justify-center z-50">
      <div className="bg-white rounded-lg shadow-2xl max-w-6xl w-full mx-4 h-[90vh] flex flex-col">
        
        {/* ===================================================================
            HEADER
            =================================================================== */}
        <div className="border-b border-gray-200 px-6 py-4 flex items-center justify-between">
          <div className="flex items-center gap-3">
            <Package className="w-6 h-6 text-purple-600" />
            <h2 className="text-2xl font-bold text-black">Plugin Marketplace</h2>
          </div>
          <button
            onClick={onClose}
            className="text-gray-400 hover:text-gray-600 transition-colors"
          >
            <X size={24} />
          </button>
        </div>

        {/* ===================================================================
            SEARCH & FILTERS
            =================================================================== */}
        <div className="border-b border-gray-200 px-6 py-4">
          {/* Search Bar */}
          <div className="flex items-center gap-4 mb-4">
            <div className="flex-1 relative">
              <Search 
                className="absolute left-3 top-1/2 transform -translate-y-1/2 text-gray-400" 
                size={18} 
              />
              <input
                type="text"
                placeholder="Search plugins..."
                value={searchQuery}
                onChange={(e) => setSearchQuery(e.target.value)}
                className="w-full pl-10 pr-4 py-2 border border-gray-300 rounded-lg focus:ring-2 focus:ring-purple-500 focus:border-transparent text-black bg-white"
              />
            </div>
            
            {/* Sort Dropdown */}
            <select
              value={sortBy}
              onChange={(e) => setSortBy(e.target.value as 'name' | 'downloads' | 'rating')}
              className="px-4 py-2 border border-gray-300 rounded-lg focus:ring-2 focus:ring-purple-500 focus:border-transparent text-black bg-white font-medium"
            >
              <option value="downloads">Sort by Downloads</option>
              <option value="rating">Sort by Rating</option>
              <option value="name">Sort by Name</option>
            </select>
          </div>

          {/* Category Filters */}
          <div className="flex gap-2 overflow-x-auto pb-2">
            {categories.map(category => (
              <button
                key={category}
                onClick={() => setSelectedCategory(category)}
                className={`px-4 py-2 rounded-lg text-sm font-medium whitespace-nowrap transition-colors ${
                  selectedCategory === category
                    ? 'bg-purple-600 text-white'
                    : 'bg-white text-black hover:bg-gray-100 border border-gray-300'
                }`}
              >
                {category}
              </button>
            ))}
          </div>
        </div>

        {/* ===================================================================
            PLUGIN LIST
            =================================================================== */}
        <div className="flex-1 overflow-y-auto p-6">
          {loading ? (
            /* Loading State */
            <div className="flex items-center justify-center h-64">
              <div className="text-center">
                <Package className="w-12 h-12 text-gray-400 animate-pulse mx-auto mb-4" />
                <p className="text-black">Loading plugins...</p>
              </div>
            </div>
          ) : filteredPlugins.length === 0 ? (
            /* Empty State */
            <div className="flex items-center justify-center h-64">
              <div className="text-center">
                <Package className="w-12 h-12 text-gray-400 mx-auto mb-4" />
                <p className="text-black font-semibold">No plugins found</p>
                <p className="text-gray-700 text-sm mt-2">
                  Try adjusting your search or filters
                </p>
              </div>
            </div>
          ) : (
            /* Plugin Grid */
            <div className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-3 gap-6">
              {filteredPlugins.map(plugin => (
                <div
                  key={plugin.pluginId}
                  className="border border-gray-200 rounded-lg p-6 hover:shadow-lg transition-shadow"
                >
                  {/* Plugin Header */}
                  <div className="flex items-start justify-between mb-3">
                    <div className="flex items-center gap-2">
                      {plugin.icon ? (
                        <img 
                          src={plugin.icon} 
                          alt={plugin.name} 
                          className="w-10 h-10 rounded" 
                        />
                      ) : (
                        <div className="w-10 h-10 bg-purple-100 rounded flex items-center justify-center">
                          <Package className="w-6 h-6 text-purple-600" />
                        </div>
                      )}
                      {plugin.verified && (
                        <div className="bg-blue-100 text-blue-700 px-2 py-0.5 rounded text-xs font-medium">
                          ✓ Verified
                        </div>
                      )}
                      {plugin.hasUpdate && (
                        <div className="bg-green-100 text-green-700 px-2 py-0.5 rounded text-xs font-medium animate-pulse">
                          Update Available
                        </div>
                      )}
                    </div>
                  </div>

                  {/* Plugin Info */}
                  <h3 className="text-lg font-semibold text-black mb-2">
                    {plugin.name}
                  </h3>
                  <p className="text-sm text-gray-800 mb-3 line-clamp-2">
                    {plugin.description}
                  </p>
                  
                  {/* Author and Version */}
                  <div className="text-xs text-gray-700 mb-3">
                    <div>by {plugin.author}</div>
                    <div className="flex items-center gap-2">
                      {plugin.installed && plugin.installedVersion && (
                        <span className="text-gray-500">
                          Installed: v{plugin.installedVersion}
                        </span>
                      )}
                      {plugin.hasUpdate && (
                        <span className="text-green-600 font-semibold">
                          → v{plugin.version}
                        </span>
                      )}
                      {!plugin.installed && (
                        <span>Latest: v{plugin.version}</span>
                      )}
                      {plugin.installed && !plugin.hasUpdate && (
                        <span className="text-green-600">✓ Up to date</span>
                      )}
                    </div>
                  </div>

                  {/* Real Stats from Backend */}
                  <div className="space-y-2 mb-4 bg-gray-50 p-3 rounded">
                    {/* Rating (REAL DATA) */}
                    <div className="flex items-center justify-between">
                      <span className="text-xs text-gray-800 font-medium">Rating:</span>
                      {plugin.stats ? (
                        renderStars(plugin.stats.averageRating, plugin.stats.totalRatings)
                      ) : (
                        <span className="text-xs text-gray-400">No ratings yet</span>
                      )}
                    </div>

                    {/* Downloads (REAL DATA) */}
                    <div className="flex items-center justify-between text-xs">
                      <span className="text-gray-800 font-medium">Downloads:</span>
                      <div className="flex items-center gap-1">
                        <Download size={12} className="text-gray-400" />
                        <span className="text-black font-semibold">
                          {plugin.stats?.totalDownloads?.toLocaleString() || 0}
                        </span>
                      </div>
                    </div>

                    {/* Active Installs (REAL DATA) */}
                    {plugin.stats && plugin.stats.activeInstalls > 0 && (
                      <div className="flex items-center justify-between text-xs">
                        <span className="text-gray-800 font-medium">Active Users:</span>
                        <span className="text-green-600 font-semibold">
                          {plugin.stats.activeInstalls.toLocaleString()}
                        </span>
                      </div>
                    )}
                  </div>

                  {/* Action Buttons */}
                  <div className="space-y-2">
                    {plugin.installed ? (
                      <>
                        {plugin.hasUpdate && (
                          <button
                            onClick={() => handleUpdate(plugin.pluginId)}
                            disabled={updatingPlugin === plugin.pluginId || installingPlugin === plugin.pluginId}
                            className="w-full px-4 py-2 bg-blue-600 text-white rounded-lg hover:bg-blue-700 transition-colors flex items-center justify-center gap-2 text-sm font-medium disabled:opacity-50"
                          >
                            <Download size={16} />
                            {updatingPlugin === plugin.pluginId ? 'Updating...' : `Update to v${plugin.version}`}
                          </button>
                        )}
                        <button
                          onClick={() => handleUninstall(plugin.pluginId)}
                          disabled={installingPlugin === plugin.pluginId || updatingPlugin === plugin.pluginId}
                          className="w-full px-4 py-2 bg-red-50 text-red-600 rounded-lg hover:bg-red-100 transition-colors flex items-center justify-center gap-2 text-sm font-medium disabled:opacity-50"
                        >
                          <Trash2 size={16} />
                          {installingPlugin === plugin.pluginId ? 'Uninstalling...' : 'Uninstall'}
                        </button>
                        <button
                          onClick={() => handleOpenRating(plugin)}
                          className="w-full px-4 py-2 bg-yellow-50 text-yellow-700 rounded-lg hover:bg-yellow-100 transition-colors flex items-center justify-center gap-2 text-sm font-medium"
                        >
                          <Star size={16} />
                          Rate Plugin
                        </button>
                      </>
                    ) : (
                      <button
                        onClick={() => handleInstall(plugin.pluginId)}
                        disabled={installingPlugin === plugin.pluginId}
                        className="w-full px-4 py-2 bg-purple-600 text-white rounded-lg hover:bg-purple-700 transition-colors flex items-center justify-center gap-2 text-sm font-medium disabled:opacity-50"
                      >
                        <Download size={16} />
                        {installingPlugin === plugin.pluginId ? 'Installing...' : 'Install'}
                      </button>
                    )}
                  </div>
                </div>
              ))}
            </div>
          )}
        </div>
      </div>

      {/* ===================================================================
          RATING MODAL
          =================================================================== */}
      {showRatingModal && ratingPlugin && (
        <RatingModal
          pluginId={ratingPlugin.pluginId}
          pluginName={ratingPlugin.name}
          currentRating={currentUserRating}
          onClose={() => {
            setShowRatingModal(false);
            setRatingPlugin(null);
            setCurrentUserRating(null);
          }}
          onSubmit={handleSubmitRating}
        />
      )}

      {/* ===================================================================
          UNINSTALL CONFIRMATION MODAL
          =================================================================== */}
      {showUninstallConfirm && uninstallPluginId && (
        <div className="fixed inset-0 bg-black bg-opacity-50 flex items-center justify-center z-[60]">
          <div className="bg-white rounded-lg shadow-xl p-6 max-w-md w-full mx-4">
            <h3 className="text-lg font-semibold text-gray-900 mb-3">
              Confirm Uninstall
            </h3>
            <p className="text-gray-600 mb-6">
              Are you sure you want to uninstall{' '}
              <span className="font-semibold">
                {plugins.find(p => p.pluginId === uninstallPluginId)?.name}
              </span>
              ? This will remove the plugin and its tab from the interface.
            </p>
            <div className="flex gap-3 justify-end">
              <button
                onClick={cancelUninstall}
                className="px-4 py-2 text-gray-700 bg-gray-100 rounded-lg hover:bg-gray-200 transition-colors"
              >
                Cancel
              </button>
              <button
                onClick={confirmUninstall}
                className="px-4 py-2 text-white bg-red-600 rounded-lg hover:bg-red-700 transition-colors flex items-center gap-2"
              >
                <Trash2 size={16} />
                Uninstall
              </button>
            </div>
          </div>
        </div>
      )}

      {/* ===================================================================
          TOAST NOTIFICATION
          =================================================================== */}
      {toast && (
        <div className={`fixed bottom-4 right-4 px-6 py-3 rounded-lg shadow-lg z-[70] animate-fade-in ${
          toast.type === 'success' ? 'bg-green-600 text-white' : 'bg-red-600 text-white'
        }`}>
          {toast.message}
        </div>
      )}
    </div>
  );
};

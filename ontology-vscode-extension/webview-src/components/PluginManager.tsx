import React, { useEffect, useState } from 'react';
import { 
  Package, Search, Settings, Power, PowerOff, Download,
  Loader2, CheckCircle, XCircle, Info, Filter, RefreshCw
} from 'lucide-react';
import apiClient from '../services/apiClient';

interface PluginMetadata {
  id: string;
  name: string;
  version: string;
  description: string;
  author: string;
  authorEmail: string;
  website: string;
  tags: string[];
  type: string;
  enabled: boolean;
  iconUrl: string;
}

const PluginManager: React.FC = () => {
  const [plugins, setPlugins] = useState<PluginMetadata[]>([]);
  const [filteredPlugins, setFilteredPlugins] = useState<PluginMetadata[]>([]);
  const [isLoading, setIsLoading] = useState(false);
  const [searchQuery, setSearchQuery] = useState('');
  const [filterType, setFilterType] = useState<string>('ALL');
  const [selectedPlugin, setSelectedPlugin] = useState<PluginMetadata | null>(null);
  const [stats, setStats] = useState<any>(null);

  const pluginTypes = [
    'ALL',
    'REASONER',
    'IMPORT_EXPORT',
    'VISUALIZATION',
    'VALIDATOR',
    'TRANSFORMER',
    'QUERY',
    'UI_COMPONENT',
    'UTILITY'
  ];

  useEffect(() => {
    loadPlugins();
    loadStats();
  }, []);

  useEffect(() => {
    filterPlugins();
  }, [plugins, searchQuery, filterType]);

  const loadPlugins = async () => {
    setIsLoading(true);
    try {
      const response = await apiClient.get<any>('/api/plugins');
      setPlugins(response.plugins || []);
    } catch (error) {
      console.error('Failed to load plugins', error);
    } finally {
      setIsLoading(false);
    }
  };

  const loadStats = async () => {
    try {
      const response = await apiClient.get<any>('/api/plugins/stats');
      setStats(response);
    } catch (error) {
      console.error('Failed to load stats', error);
    }
  };

  const filterPlugins = () => {
    let filtered = plugins;

    if (filterType !== 'ALL') {
      filtered = filtered.filter(p => p.type === filterType);
    }

    if (searchQuery) {
      const query = searchQuery.toLowerCase();
      filtered = filtered.filter(p =>
        p.name.toLowerCase().includes(query) ||
        p.description.toLowerCase().includes(query) ||
        p.tags?.some(tag => tag.toLowerCase().includes(query))
      );
    }

    setFilteredPlugins(filtered);
  };

  const togglePlugin = async (pluginId: string, enabled: boolean) => {
    try {
      const endpoint = enabled 
        ? `/api/plugins/${pluginId}/disable`
        : `/api/plugins/${pluginId}/enable`;

      await apiClient.post(endpoint);
      await loadPlugins();
    } catch (error) {
      console.error('Failed to toggle plugin', error);
    }
  };

  const getTypeColor = (type: string) => {
    const colors: Record<string, string> = {
      REASONER: 'bg-blue-100 text-blue-800',
      IMPORT_EXPORT: 'bg-green-100 text-green-800',
      VISUALIZATION: 'bg-purple-100 text-purple-800',
      VALIDATOR: 'bg-yellow-100 text-yellow-800',
      TRANSFORMER: 'bg-orange-100 text-orange-800',
      QUERY: 'bg-pink-100 text-pink-800',
      UI_COMPONENT: 'bg-indigo-100 text-indigo-800',
      UTILITY: 'bg-gray-100 text-gray-800',
    };
    return colors[type] || 'bg-gray-100 text-gray-800';
  };

  return (
    <div className="h-full flex flex-col bg-gray-50">
      {}
      <header className="bg-white border-b border-gray-200 p-6">
        <div className="flex items-center justify-between mb-4">
          <div>
            <h1 className="text-3xl font-bold text-gray-900 flex items-center gap-3">
              <Package size={32} className="text-indigo-600" />
              Plugin Manager
            </h1>
            <p className="text-gray-600 mt-1">Extend OntoCode with plugins</p>
          </div>

          <button
            onClick={loadPlugins}
            disabled={isLoading}
            className="px-4 py-2 bg-indigo-600 text-white rounded-lg hover:bg-indigo-700 flex items-center gap-2 disabled:bg-indigo-300"
          >
            {isLoading ? (
              <Loader2 size={20} className="animate-spin" />
            ) : (
              <RefreshCw size={20} />
            )}
            Refresh
          </button>
        </div>

        {}
        {stats && (
          <div className="grid grid-cols-4 gap-4 mt-4">
            <div className="bg-indigo-50 p-4 rounded-lg">
              <div className="text-2xl font-bold text-indigo-900">
                {stats.totalPlugins}
              </div>
              <div className="text-sm text-indigo-600">Total Plugins</div>
            </div>
            <div className="bg-green-50 p-4 rounded-lg">
              <div className="text-2xl font-bold text-green-900">
                {stats.enabledPlugins}
              </div>
              <div className="text-sm text-green-600">Enabled</div>
            </div>
            <div className="bg-orange-50 p-4 rounded-lg">
              <div className="text-2xl font-bold text-orange-900">
                {stats.totalPlugins - stats.enabledPlugins}
              </div>
              <div className="text-sm text-orange-600">Disabled</div>
            </div>
            <div className="bg-purple-50 p-4 rounded-lg">
              <div className="text-2xl font-bold text-purple-900">
                {Object.keys(stats.pluginsByType || {}).length}
              </div>
              <div className="text-sm text-purple-600">Categories</div>
            </div>
          </div>
        )}

        {}
        <div className="flex gap-4 mt-4">
          <div className="flex-1 relative">
            <Search size={20} className="absolute left-3 top-1/2 transform -translate-y-1/2 text-gray-400" />
            <input
              type="text"
              value={searchQuery}
              onChange={(e) => setSearchQuery(e.target.value)}
              placeholder="Search plugins..."
              className="w-full pl-10 pr-4 py-2 border border-gray-300 rounded-lg focus:outline-none focus:ring-2 focus:ring-indigo-500"
            />
          </div>

          <div className="flex items-center gap-2">
            <Filter size={20} className="text-gray-600" />
            <select
              value={filterType}
              onChange={(e) => setFilterType(e.target.value)}
              className="px-4 py-2 border border-gray-300 rounded-lg focus:outline-none focus:ring-2 focus:ring-indigo-500"
            >
              {pluginTypes.map(type => (
                <option key={type} value={type}>{type.replace('_', ' ')}</option>
              ))}
            </select>
          </div>
        </div>
      </header>

      {}
      <div className="flex-1 overflow-auto p-6">
        {isLoading ? (
          <div className="flex items-center justify-center h-64">
            <div className="text-center">
              <Loader2 size={48} className="animate-spin text-indigo-600 mx-auto mb-4" />
              <p className="text-gray-600">Loading plugins...</p>
            </div>
          </div>
        ) : filteredPlugins.length === 0 ? (
          <div className="text-center py-12">
            <Package size={64} className="mx-auto text-gray-300 mb-4" />
            <p className="text-gray-600 text-lg">No plugins found</p>
            <p className="text-gray-700 text-sm mt-2">
              Try adjusting your search or filter criteria
            </p>
          </div>
        ) : (
          <div className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-3 gap-4">
            {filteredPlugins.map(plugin => (
              <div
                key={plugin.id}
                className="bg-white border border-gray-200 rounded-lg p-6 hover:shadow-lg transition-shadow cursor-pointer"
                onClick={() => setSelectedPlugin(plugin)}
              >
                {}
                <div className="flex items-start justify-between mb-3">
                  <div className="flex items-center gap-3">
                    {plugin.iconUrl ? (
                      <img src={plugin.iconUrl} alt={plugin.name} className="w-10 h-10 rounded" />
                    ) : (
                      <div className="w-10 h-10 bg-indigo-100 rounded flex items-center justify-center">
                        <Package size={24} className="text-indigo-600" />
                      </div>
                    )}
                    <div>
                      <h3 className="font-semibold text-gray-900">{plugin.name}</h3>
                      <p className="text-xs text-gray-700">v{plugin.version}</p>
                    </div>
                  </div>

                  {}
                  {plugin.enabled ? (
                    <CheckCircle size={20} className="text-green-500" />
                  ) : (
                    <XCircle size={20} className="text-gray-400" />
                  )}
                </div>

                {}
                <p className="text-sm text-gray-600 mb-3 line-clamp-2">
                  {plugin.description}
                </p>

                {}
                <div className="flex items-center gap-2 mb-3">
                  <span className={`px-2 py-1 rounded text-xs font-medium ${getTypeColor(plugin.type)}`}>
                    {plugin.type.replace('_', ' ')}
                  </span>
                </div>

                {}
                {plugin.tags && plugin.tags.length > 0 && (
                  <div className="flex flex-wrap gap-1 mb-3">
                    {plugin.tags.slice(0, 3).map(tag => (
                      <span key={tag} className="px-2 py-1 bg-gray-100 text-gray-600 rounded text-xs">
                        {tag}
                      </span>
                    ))}
                    {plugin.tags.length > 3 && (
                      <span className="px-2 py-1 bg-gray-100 text-gray-600 rounded text-xs">
                        +{plugin.tags.length - 3}
                      </span>
                    )}
                  </div>
                )}

                {}
                <div className="flex gap-2">
                  <button
                    onClick={(e) => {
                      e.stopPropagation();
                      togglePlugin(plugin.id, plugin.enabled);
                    }}
                    className={`flex-1 px-3 py-2 rounded-lg text-sm font-medium transition-colors ${
                      plugin.enabled
                        ? 'bg-red-50 text-red-700 hover:bg-red-100'
                        : 'bg-green-50 text-green-700 hover:bg-green-100'
                    }`}
                  >
                    {plugin.enabled ? (
                      <span className="flex items-center justify-center gap-1">
                        <PowerOff size={16} />
                        Disable
                      </span>
                    ) : (
                      <span className="flex items-center justify-center gap-1">
                        <Power size={16} />
                        Enable
                      </span>
                    )}
                  </button>

                  <button
                    onClick={(e) => {
                      e.stopPropagation();
                      setSelectedPlugin(plugin);
                    }}
                    className="px-3 py-2 bg-gray-100 text-gray-700 rounded-lg hover:bg-gray-200 text-sm font-medium"
                  >
                    <Settings size={16} />
                  </button>
                </div>
              </div>
            ))}
          </div>
        )}
      </div>

      {}
      {selectedPlugin && (
        <div className="fixed inset-0 bg-black bg-opacity-50 flex items-center justify-center z-50 p-4">
          <div className="bg-white rounded-lg max-w-2xl w-full max-h-[80vh] overflow-auto">
            <div className="p-6">
              <div className="flex items-start justify-between mb-4">
                <div className="flex items-center gap-3">
                  {selectedPlugin.iconUrl ? (
                    <img src={selectedPlugin.iconUrl} alt={selectedPlugin.name} className="w-16 h-16 rounded" />
                  ) : (
                    <div className="w-16 h-16 bg-indigo-100 rounded flex items-center justify-center">
                      <Package size={32} className="text-indigo-600" />
                    </div>
                  )}
                  <div>
                    <h2 className="text-2xl font-bold text-gray-900">{selectedPlugin.name}</h2>
                    <p className="text-gray-600">Version {selectedPlugin.version}</p>
                  </div>
                </div>
                <button
                  onClick={() => setSelectedPlugin(null)}
                  className="text-gray-400 hover:text-gray-600"
                >
                  ✕
                </button>
              </div>

              <div className="space-y-4">
                <div>
                  <h3 className="font-semibold text-gray-900 mb-2">Description</h3>
                  <p className="text-gray-600">{selectedPlugin.description}</p>
                </div>

                <div>
                  <h3 className="font-semibold text-gray-900 mb-2">Details</h3>
                  <dl className="space-y-2">
                    <div className="flex">
                      <dt className="w-32 text-gray-600">Type:</dt>
                      <dd className="text-gray-900">{selectedPlugin.type.replace('_', ' ')}</dd>
                    </div>
                    <div className="flex">
                      <dt className="w-32 text-gray-600">Author:</dt>
                      <dd className="text-gray-900">{selectedPlugin.author}</dd>
                    </div>
                    <div className="flex">
                      <dt className="w-32 text-gray-600">Email:</dt>
                      <dd className="text-gray-900">{selectedPlugin.authorEmail}</dd>
                    </div>
                    {selectedPlugin.website && (
                      <div className="flex">
                        <dt className="w-32 text-gray-600">Website:</dt>
                        <dd className="text-indigo-600">
                          <a href={selectedPlugin.website} target="_blank" rel="noopener noreferrer">
                            {selectedPlugin.website}
                          </a>
                        </dd>
                      </div>
                    )}
                    <div className="flex">
                      <dt className="w-32 text-gray-600">Status:</dt>
                      <dd className={selectedPlugin.enabled ? 'text-green-600' : 'text-gray-600'}>
                        {selectedPlugin.enabled ? 'Enabled' : 'Disabled'}
                      </dd>
                    </div>
                  </dl>
                </div>

                {selectedPlugin.tags && selectedPlugin.tags.length > 0 && (
                  <div>
                    <h3 className="font-semibold text-gray-900 mb-2">Tags</h3>
                    <div className="flex flex-wrap gap-2">
                      {selectedPlugin.tags.map(tag => (
                        <span key={tag} className="px-3 py-1 bg-gray-100 text-gray-700 rounded-full text-sm">
                          {tag}
                        </span>
                      ))}
                    </div>
                  </div>
                )}

                <div className="flex gap-3 pt-4">
                  <button
                    onClick={() => {
                      togglePlugin(selectedPlugin.id, selectedPlugin.enabled);
                      setSelectedPlugin(null);
                    }}
                    className={`flex-1 px-4 py-2 rounded-lg font-medium ${
                      selectedPlugin.enabled
                        ? 'bg-red-600 text-white hover:bg-red-700'
                        : 'bg-green-600 text-white hover:bg-green-700'
                    }`}
                  >
                    {selectedPlugin.enabled ? 'Disable Plugin' : 'Enable Plugin'}
                  </button>
                  <button
                    onClick={() => setSelectedPlugin(null)}
                    className="px-4 py-2 bg-gray-200 text-gray-700 rounded-lg hover:bg-gray-300 font-medium"
                  >
                    Close
                  </button>
                </div>
              </div>
            </div>
          </div>
        </div>
      )}
    </div>
  );
};

export default PluginManager;
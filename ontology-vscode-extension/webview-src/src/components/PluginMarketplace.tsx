import { useState } from 'react';
import { 
  Package, CheckCircle, XCircle,
  Search, Filter
} from 'lucide-react';
import { pluginManager } from '../plugins/PluginSystem';

const PluginMarketplace = () => {
  const [installedPlugins, setInstalledPlugins] = useState<any[]>(
    pluginManager.getAllPlugins()
  );
  const [searchQuery, setSearchQuery] = useState('');
  const [selectedPlugin, setSelectedPlugin] = useState<any | null>(null);
  const [activeTab, setActiveTab] = useState<'installed' | 'available'>('installed');

  const handleTogglePlugin = async (pluginId: string) => {
    try {
      if (pluginManager.isPluginActive(pluginId)) {
        await pluginManager.deactivatePlugin(pluginId);
      } else {
        await pluginManager.activatePlugin(pluginId);
      }
      // Refresh the list
      setInstalledPlugins([...pluginManager.getAllPlugins()]);
    } catch (error) {
      console.error('Failed to toggle plugin:', error);
    }
  };

  const filteredPlugins = installedPlugins.filter(plugin =>
    plugin.name.toLowerCase().includes(searchQuery.toLowerCase()) ||
    plugin.description.toLowerCase().includes(searchQuery.toLowerCase())
  );

  return (
    <div className="h-full flex flex-col bg-gray-50">
      {/* Header */}
      <div className="bg-white border-b border-gray-200 p-6">
        <div className="flex items-center justify-between mb-4">
          <div className="flex items-center gap-3">
            <div className="w-12 h-12 bg-gradient-to-br from-purple-500 to-indigo-600 rounded-lg flex items-center justify-center">
              <Package size={24} className="text-white" />
            </div>
            <div>
              <h1 className="text-2xl font-bold text-gray-900">Plugin Marketplace</h1>
              <p className="text-sm text-gray-500">Extend your ontology editor with powerful plugins</p>
            </div>
          </div>
        </div>

        {/* Tabs */}
        <div className="flex gap-2 border-b border-gray-200">
          <button
            onClick={() => setActiveTab('installed')}
            className={`px-4 py-2 text-sm font-medium border-b-2 transition-colors ${
              activeTab === 'installed'
                ? 'text-purple-600 border-purple-600'
                : 'text-gray-600 hover:text-gray-800 border-transparent'
            }`}
          >
            Installed ({installedPlugins.length})
          </button>
          <button
            onClick={() => setActiveTab('available')}
            className={`px-4 py-2 text-sm font-medium border-b-2 transition-colors ${
              activeTab === 'available'
                ? 'text-purple-600 border-purple-600'
                : 'text-gray-600 hover:text-gray-800 border-transparent'
            }`}
          >
            Available
          </button>
        </div>
      </div>

      {/* Search and Filter */}
      <div className="bg-white border-b border-gray-200 p-4">
        <div className="flex gap-3">
          <div className="relative flex-1">
            <Search size={18} className="absolute left-3 top-1/2 transform -translate-y-1/2 text-gray-400" />
            <input
              type="text"
              placeholder="Search plugins..."
              value={searchQuery}
              onChange={(e) => setSearchQuery(e.target.value)}
              className="w-full pl-10 pr-4 py-2 border border-gray-300 rounded-lg focus:ring-2 focus:ring-purple-500 focus:border-transparent text-sm"
            />
          </div>
          <button className="px-4 py-2 border border-gray-300 rounded-lg hover:bg-gray-50 flex items-center gap-2 text-sm">
            <Filter size={16} />
            Filter
          </button>
        </div>
      </div>

      {/* Plugin Grid */}
      <div className="flex-1 overflow-y-auto p-6">
        <div className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-3 gap-4">
          {filteredPlugins.map((plugin) => {
            const isActive = pluginManager.isPluginActive(plugin.id);
            const Icon = plugin.icon || Package;

            return (
              <div
                key={plugin.id}
                className="bg-white rounded-lg border border-gray-200 p-4 hover:shadow-lg transition-shadow cursor-pointer"
                onClick={() => setSelectedPlugin(plugin)}
              >
                <div className="flex items-start justify-between mb-3">
                  <div className="flex items-center gap-3">
                    <div className="w-10 h-10 bg-gradient-to-br from-purple-500 to-indigo-600 rounded-lg flex items-center justify-center">
                      <Icon size={20} className="text-white" />
                    </div>
                    <div>
                      <h3 className="font-semibold text-gray-900">{plugin.name}</h3>
                      <p className="text-xs text-gray-500">v{plugin.version}</p>
                    </div>
                  </div>
                  {isActive ? (
                    <CheckCircle size={20} className="text-green-500" />
                  ) : (
                    <XCircle size={20} className="text-gray-300" />
                  )}
                </div>

                <p className="text-sm text-gray-600 mb-4 line-clamp-2">
                  {plugin.description}
                </p>

                <div className="flex items-center justify-between">
                  <div className="flex items-center gap-2">
                    <span className="text-xs text-gray-500">by {plugin.author}</span>
                  </div>
                  <button
                    onClick={(e) => {
                      e.stopPropagation();
                      handleTogglePlugin(plugin.id);
                    }}
                    className={`px-3 py-1 rounded-lg text-xs font-medium transition-colors ${
                      isActive
                        ? 'bg-red-100 text-red-700 hover:bg-red-200'
                        : 'bg-purple-600 text-white hover:bg-purple-700'
                    }`}
                  >
                    {isActive ? 'Deactivate' : 'Activate'}
                  </button>
                </div>
              </div>
            );
          })}
        </div>

        {filteredPlugins.length === 0 && (
          <div className="text-center py-12">
            <Package size={48} className="mx-auto mb-4 text-gray-300" />
            <p className="text-gray-500">No plugins found</p>
          </div>
        )}
      </div>

      {/* Plugin Detail Modal */}
      {selectedPlugin && (
        <div className="fixed inset-0 bg-black bg-opacity-50 flex items-center justify-center z-50">
          <div className="bg-white rounded-lg shadow-xl max-w-2xl w-full mx-4 max-h-[80vh] overflow-y-auto">
            <div className="p-6 border-b border-gray-200">
              <div className="flex items-start justify-between">
                <div className="flex items-center gap-4">
                  {selectedPlugin.icon && (
                    <div className="w-16 h-16 bg-gradient-to-br from-purple-500 to-indigo-600 rounded-lg flex items-center justify-center">
                      <selectedPlugin.icon size={32} className="text-white" />
                    </div>
                  )}
                  <div>
                    <h2 className="text-2xl font-bold text-gray-900">{selectedPlugin.name}</h2>
                    <p className="text-sm text-gray-500">
                      v{selectedPlugin.version} by {selectedPlugin.author}
                    </p>
                  </div>
                </div>
                <button
                  onClick={() => setSelectedPlugin(null)}
                  className="text-gray-400 hover:text-gray-600"
                >
                  ✕
                </button>
              </div>
            </div>

            <div className="p-6">
              <div className="mb-6">
                <h3 className="text-sm font-semibold text-gray-700 mb-2">Description</h3>
                <p className="text-sm text-gray-600">{selectedPlugin.description}</p>
              </div>

              {selectedPlugin.settings && (
                <div className="mb-6">
                  <h3 className="text-sm font-semibold text-gray-700 mb-2">Settings</h3>
                  <div className="space-y-3">
                    {Object.entries(selectedPlugin.settings).map(([key, setting]) => (
                      <div key={key}>
                        <label className="text-sm text-gray-600">{(setting as any).label}</label>
                        {/* Add settings controls here */}
                      </div>
                    ))}
                  </div>
                </div>
              )}

              <div className="flex gap-3">
                <button
                  onClick={() => handleTogglePlugin(selectedPlugin.id)}
                  className={`flex-1 px-4 py-2 rounded-lg font-medium transition-colors ${
                    pluginManager.isPluginActive(selectedPlugin.id)
                      ? 'bg-red-100 text-red-700 hover:bg-red-200'
                      : 'bg-purple-600 text-white hover:bg-purple-700'
                  }`}
                >
                  {pluginManager.isPluginActive(selectedPlugin.id) ? 'Deactivate' : 'Activate'}
                </button>
              </div>
            </div>
          </div>
        </div>
      )}
    </div>
  );
};

export default PluginMarketplace;
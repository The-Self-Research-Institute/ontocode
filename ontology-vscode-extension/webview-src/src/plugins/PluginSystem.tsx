

import { OntologyPlugin } from '../types';

class PluginManager {
  private plugins: Map<string, OntologyPlugin> = new Map();
  private activePlugins: Set<string> = new Set();
  public context: any | null = null;

  registerPlugin(plugin: OntologyPlugin) {
    if (this.plugins.has(plugin.id)) {
      console.warn(`Plugin "${plugin.name}" (${plugin.id}) is already registered.`);
      return;
    }
    this.plugins.set(plugin.id, plugin);
    console.log(`Plugin registered: ${plugin.name}`);
  }
  
  setContext(context: any) {
    this.context = context;
  }

  async activatePlugin(id: string) {
    const plugin = this.plugins.get(id);
    if (!plugin) {
      throw new Error(`Plugin with id "${id}" not found.`);
    }
    if (this.activePlugins.has(id)) {
      return;
    }
    await plugin.activate();
    this.activePlugins.add(id);
    console.log(`Plugin activated: ${plugin.name}`);
  }

  async deactivatePlugin(id: string) {
    const plugin = this.plugins.get(id);
    if (!plugin || !this.activePlugins.has(id)) {
      return;
    }
    await plugin.deactivate();
    this.activePlugins.delete(id);
    console.log(`Plugin deactivated: ${plugin.name}`);
  }
  
  getPlugin(id: string): OntologyPlugin | undefined {
    return this.plugins.get(id);
  }
  
  getAllPlugins(): OntologyPlugin[] {
    return Array.from(this.plugins.values());
  }

  isPluginActive(id: string): boolean {
    return this.activePlugins.has(id);
  }
}

export const pluginManager = new PluginManager();

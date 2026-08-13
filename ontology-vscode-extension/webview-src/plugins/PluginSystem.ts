

import { OntologyPlugin, PluginContext } from '../types';

interface PluginState {
  plugin: OntologyPlugin;
  active: boolean;
  error?: string;
}

class PluginManager {
  private plugins: Map<string, PluginState> = new Map();
  private context: PluginContext | null = null;
  private eventListeners: Map<string, Set<Function>> = new Map();

  registerPlugin(plugin: OntologyPlugin): void {
    if (this.plugins.has(plugin.id)) {
      console.warn(`Plugin "${plugin.name}" (${plugin.id}) is already registered.`);
      return;
    }

    this.plugins.set(plugin.id, {
      plugin,
      active: false,
    });

    console.log(`✓ Plugin registered: ${plugin.name} v${plugin.version}`);
    this.emit('plugin-registered', plugin);
  }

  registerPlugins(plugins: OntologyPlugin[]): void {
    plugins.forEach(plugin => this.registerPlugin(plugin));
  }

  setContext(context: PluginContext): void {
    const previousContext = this.context;
    this.context = context;

    console.log('Plugin context updated:', context);

    if (previousContext?.projectId !== context.projectId) {
      this.handleProjectChange(previousContext?.projectId, context.projectId);
    }

    this.emit('context-changed', context);
  }

  getContext(): PluginContext | null {
    return this.context;
  }

  async activatePlugin(id: string): Promise<boolean> {
    const pluginState = this.plugins.get(id);

    if (!pluginState) {
      throw new Error(`Plugin with id "${id}" not found.`);
    }

    if (pluginState.active) {
      console.log(`Plugin "${pluginState.plugin.name}" is already active.`);
      return true;
    }

    try {
      console.log(`Activating plugin: ${pluginState.plugin.name}...`);

      const result = await pluginState.plugin.activate(this.context || undefined);

      if (result !== false) {
        pluginState.active = true;
        pluginState.error = undefined;
        console.log(`✓ Plugin activated: ${pluginState.plugin.name}`);
        this.emit('plugin-activated', pluginState.plugin);
        return true;
      }

      return false;
    } catch (error) {
      const errorMessage = error instanceof Error ? error.message : 'Unknown error';
      pluginState.error = errorMessage;
      console.error(`Failed to activate plugin "${pluginState.plugin.name}":`, error);
      this.emit('plugin-error', { plugin: pluginState.plugin, error: errorMessage });
      return false;
    }
  }

  async deactivatePlugin(id: string): Promise<boolean> {
    const pluginState = this.plugins.get(id);

    if (!pluginState || !pluginState.active) {
      return false;
    }

    try {
      console.log(`Deactivating plugin: ${pluginState.plugin.name}...`);

      const result = await pluginState.plugin.deactivate(this.context || undefined);

      if (result !== false) {
        pluginState.active = false;
        console.log(`✓ Plugin deactivated: ${pluginState.plugin.name}`);
        this.emit('plugin-deactivated', pluginState.plugin);
        return true;
      }

      return false;
    } catch (error) {
      const errorMessage = error instanceof Error ? error.message : 'Unknown error';
      console.error(`Failed to deactivate plugin "${pluginState.plugin.name}":`, error);
      this.emit('plugin-error', { plugin: pluginState.plugin, error: errorMessage });
      return false;
    }
  }

  async togglePlugin(id: string): Promise<boolean> {
    const pluginState = this.plugins.get(id);

    if (!pluginState) {
      return false;
    }

    return pluginState.active
      ? await this.deactivatePlugin(id)
      : await this.activatePlugin(id);
  }

  getPlugin(id: string): OntologyPlugin | undefined {
    return this.plugins.get(id)?.plugin;
  }

  getAllPlugins(): OntologyPlugin[] {
    return Array.from(this.plugins.values()).map(state => state.plugin);
  }

  getActivePlugins(): OntologyPlugin[] {
    return Array.from(this.plugins.values())
      .filter(state => state.active)
      .map(state => state.plugin);
  }

  isPluginActive(id: string): boolean {
    return this.plugins.get(id)?.active || false;
  }

  getPluginError(id: string): string | undefined {
    return this.plugins.get(id)?.error;
  }

  private async handleProjectChange(
    previousProjectId?: string,
    newProjectId?: string
  ): Promise<void> {
    if (!previousProjectId || !newProjectId) {
      return;
    }

    const activePlugins = this.getActivePlugins();

    for (const plugin of activePlugins) {
      await this.deactivatePlugin(plugin.id);
    }

    for (const plugin of activePlugins) {
      await this.activatePlugin(plugin.id);
    }
  }

  on(event: string, callback: Function): void {
    if (!this.eventListeners.has(event)) {
      this.eventListeners.set(event, new Set());
    }
    this.eventListeners.get(event)!.add(callback);
  }

  off(event: string, callback: Function): void {
    this.eventListeners.get(event)?.delete(callback);
  }

  private emit(event: string, data?: any): void {
    this.eventListeners.get(event)?.forEach(callback => {
      try {
        callback(data);
      } catch (error) {
        console.error(`Error in event listener for "${event}":`, error);
      }
    });
  }

  async unregisterPlugin(id: string): Promise<boolean> {
    const pluginState = this.plugins.get(id);

    if (!pluginState) {
      return false;
    }

    if (pluginState.active) {
      await this.deactivatePlugin(id);
    }

    this.plugins.delete(id);
    console.log(`Plugin unregistered: ${pluginState.plugin.name}`);
    this.emit('plugin-unregistered', pluginState.plugin);

    return true;
  }

  async clearAll(): Promise<void> {

    for (const [id, state] of this.plugins.entries()) {
      if (state.active) {
        await this.deactivatePlugin(id);
      }
    }

    this.plugins.clear();
    this.eventListeners.clear();
    this.context = null;

    console.log('All plugins cleared');
  }
}

export const pluginManager = new PluginManager();

export { PluginManager };
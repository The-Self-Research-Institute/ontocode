import React from 'react';
import type { AxiosInstance } from 'axios'; // Add 'type' keyword

/* eslint-disable @typescript-eslint/no-explicit-any */

// Plugin interface
export interface OntologyPlugin {
  id: string;
  name: string;
  version: string;
  description: string;
  author: string;
  icon?: React.ComponentType<any>;
  
  initialize(context: PluginContext): Promise<void>;
  activate(): Promise<void>;
  deactivate(): Promise<void>;
  
  component: React.ComponentType<PluginProps>;
  menuItems?: MenuItem[];
  settings?: PluginSettings;
}

export interface PluginContext {
  projectId: string;
  apiClient: AxiosInstance;
  ontologyManager?: unknown;
  notificationService?: {
    success: (message: string) => void;
    error: (message: string) => void;
    info: (message: string) => void;
  };
}

export interface PluginProps {
  projectId: string;
  context: PluginContext;
}

export interface MenuItem {
  id: string;
  label: string;
  icon?: React.ComponentType<any>;
  action: () => void;
}

export interface PluginSettings {
  [key: string]: PluginSetting;
}

export interface PluginSetting {
  type: 'string' | 'number' | 'boolean' | 'select';
  label: string;
  defaultValue: any;
  options?: string[];
}

// Plugin Manager
export class PluginManager {
  private plugins: Map<string, OntologyPlugin> = new Map();
  private activePlugins: Set<string> = new Set();
  private context: PluginContext | null = null;

  constructor() {
    this.loadInstalledPlugins();
  }

  setContext(context: PluginContext): void {
    this.context = context;
  }

  registerPlugin(plugin: OntologyPlugin): void {
    this.plugins.set(plugin.id, plugin);
    console.log(`Plugin registered: ${plugin.name}`);
  }

  async activatePlugin(pluginId: string): Promise<void> {
    const plugin = this.plugins.get(pluginId);
    if (!plugin || !this.context) {
      throw new Error(`Plugin ${pluginId} not found or context not set`);
    }

    try {
      await plugin.initialize(this.context);
      await plugin.activate();
      this.activePlugins.add(pluginId);
      this.savePluginState();
      console.log(`Plugin activated: ${plugin.name}`);
    } catch (error) {
      console.error(`Failed to activate plugin ${pluginId}:`, error);
      throw error;
    }
  }

  async deactivatePlugin(pluginId: string): Promise<void> {
    const plugin = this.plugins.get(pluginId);
    if (!plugin) return;

    try {
      await plugin.deactivate();
      this.activePlugins.delete(pluginId);
      this.savePluginState();
      console.log(`Plugin deactivated: ${plugin.name}`);
    } catch (error) {
      console.error(`Failed to deactivate plugin ${pluginId}:`, error);
      throw error;
    }
  }

  getPlugin(pluginId: string): OntologyPlugin | undefined {
    return this.plugins.get(pluginId);
  }

  getAllPlugins(): OntologyPlugin[] {
    return Array.from(this.plugins.values());
  }

  getActivePlugins(): OntologyPlugin[] {
    return Array.from(this.activePlugins)
      .map(id => this.plugins.get(id))
      .filter((p): p is OntologyPlugin => p !== undefined);
  }

  isPluginActive(pluginId: string): boolean {
    return this.activePlugins.has(pluginId);
  }

  private loadInstalledPlugins(): void {
    try {
      const savedState = localStorage.getItem('ontology-plugins-state');
      if (savedState) {
        const state = JSON.parse(savedState) as { activePlugins?: string[] };
        this.activePlugins = new Set(state.activePlugins || []);
      }
    } catch (error) {
      console.error('Failed to load plugin state:', error);
    }
  }

  private savePluginState(): void {
    try {
      const state = {
        activePlugins: Array.from(this.activePlugins)
      };
      localStorage.setItem('ontology-plugins-state', JSON.stringify(state));
    } catch (error) {
      console.error('Failed to save plugin state:', error);
    }
  }
}

// Singleton instance
export const pluginManager = new PluginManager();
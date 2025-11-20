// services/notificationService.ts
// Notification service for both web and VS Code extension environments

declare global {
  interface Window {
    vscode?: {
      postMessage: (message: any) => void;
    };
  }
}

export type NotificationType = 'info' | 'success' | 'warning' | 'error';

export interface NotificationOptions {
  title: string;
  message: string;
  type: NotificationType;
  duration?: number; // milliseconds (for web toasts)
  actions?: Array<{ label: string; action: () => void }>;
}

class NotificationService {
  private static instance: NotificationService;
  private isVSCode = typeof window !== 'undefined' && !!window.vscode;
  private toastCallbacks: Array<(options: NotificationOptions) => void> = [];

  static getInstance(): NotificationService {
    if (!this.instance) {
      this.instance = new NotificationService();
    }
    return this.instance;
  }

  /**
   * Register a callback for web-based toast notifications
   */
  onToast(callback: (options: NotificationOptions) => void): void {
    this.toastCallbacks.push(callback);
  }

  /**
   * Show a notification (system notification for VS Code, toast for web)
   */
  notify(options: NotificationOptions): void {
    if (this.isVSCode) {
      this.showVSCodeNotification(options);
    } else {
      this.showWebNotification(options);
    }
  }

  /**
   * Show a system notification in VS Code
   */
  private showVSCodeNotification(options: NotificationOptions): void {
    window.vscode?.postMessage({
      type: 'showNotification',
      notification: {
        type: options.type,
        title: options.title,
        message: options.message,
        actions: options.actions?.map(a => a.label)
      }
    });
  }

  /**
   * Show a web notification (browser notification API or toast)
   */
  private showWebNotification(options: NotificationOptions): void {
    // Try browser Notification API first (requires permission)
    if ('Notification' in window && Notification.permission === 'granted') {
      const notification = new Notification(options.title, {
        body: options.message,
        icon: this.getIconForType(options.type),
        tag: `ontocode-${Date.now()}`,
      });

      notification.onclick = () => {
        window.focus();
        notification.close();
      };

      // Auto-close after duration
      if (options.duration) {
        setTimeout(() => notification.close(), options.duration);
      }
    } 
    // Fallback to toast notifications
    else {
      this.toastCallbacks.forEach(callback => callback(options));
    }
  }

  /**
   * Request browser notification permission (web only)
   */
  async requestPermission(): Promise<NotificationPermission> {
    if (!this.isVSCode && 'Notification' in window) {
      return await Notification.requestPermission();
    }
    return 'denied';
  }

  /**
   * Check if browser notifications are supported and permitted
   */
  isWebNotificationAvailable(): boolean {
    return !this.isVSCode && 'Notification' in window && Notification.permission === 'granted';
  }

  private getIconForType(type: NotificationType): string {
    // Return data URIs or paths to icons based on notification type
    const icons = {
      info: '/info-icon.png',
      success: '/success-icon.png',
      warning: '/warning-icon.png',
      error: '/error-icon.png',
    };
    return icons[type] || icons.info;
  }

  /**
   * Show success notification
   */
  success(title: string, message: string): void {
    this.notify({ title, message, type: 'success', duration: 5000 });
  }

  /**
   * Show error notification
   */
  error(title: string, message: string): void {
    this.notify({ title, message, type: 'error', duration: 8000 });
  }

  /**
   * Show info notification
   */
  info(title: string, message: string): void {
    this.notify({ title, message, type: 'info', duration: 5000 });
  }

  /**
   * Show warning notification
   */
  warning(title: string, message: string): void {
    this.notify({ title, message, type: 'warning', duration: 6000 });
  }
}

export const notificationService = NotificationService.getInstance();

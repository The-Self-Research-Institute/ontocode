

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

export function readingTimeMs(text: string, type: NotificationType): number {
  const base = type === 'error' ? 9000 : type === 'warning' ? 7000 : 5000;
  return Math.min(30000, base + Math.max(0, text.length - 80) * 60);
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

  onToast(callback: (options: NotificationOptions) => void): () => void {
    this.toastCallbacks.push(callback);
    return () => {
      this.toastCallbacks = this.toastCallbacks.filter(cb => cb !== callback);
    };
  }

  notify(requested: NotificationOptions): void {
    const readable = readingTimeMs(`${requested.title} ${requested.message}`, requested.type);
    const options = { ...requested, duration: Math.max(requested.duration ?? 0, readable) };
    if (this.isVSCode) {
      this.showVSCodeNotification(options);

      this.toastCallbacks.forEach(cb => cb(options));
    } else {
      this.showWebNotification(options);
    }
  }

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

  private showWebNotification(options: NotificationOptions): void {

    if (this.toastCallbacks.length) {
      this.toastCallbacks.forEach(callback => callback(options));
    } else if ('Notification' in window && Notification.permission === 'granted') {
      const notification = new Notification(options.title, {
        body: options.message,
        icon: this.getIconForType(options.type),
        tag: `ontocode-${Date.now()}`,
      });

      notification.onclick = () => {
        window.focus();
        notification.close();
      };

      if (options.duration) {
        setTimeout(() => notification.close(), options.duration);
      }
    }
  }

  async requestPermission(): Promise<NotificationPermission> {
    if (!this.isVSCode && 'Notification' in window) {
      return await Notification.requestPermission();
    }
    return 'denied';
  }

  isWebNotificationAvailable(): boolean {
    return !this.isVSCode && 'Notification' in window && Notification.permission === 'granted';
  }

  private getIconForType(type: NotificationType): string {

    const icons = {
      info: '/info-icon.png',
      success: '/success-icon.png',
      warning: '/warning-icon.png',
      error: '/error-icon.png',
    };
    return icons[type] || icons.info;
  }

  success(title: string, message: string): void {
    this.notify({ title, message, type: 'success' });
  }

  error(title: string, message: string): void {
    this.notify({ title, message, type: 'error' });
  }

  info(title: string, message: string): void {
    this.notify({ title, message, type: 'info' });
  }

  warning(title: string, message: string): void {
    this.notify({ title, message, type: 'warning' });
  }
}

export const notificationService = NotificationService.getInstance();

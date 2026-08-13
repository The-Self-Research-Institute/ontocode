

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

  onToast(callback: (options: NotificationOptions) => void): () => void {
    this.toastCallbacks.push(callback);
    return () => {
      this.toastCallbacks = this.toastCallbacks.filter(cb => cb !== callback);
    };
  }

  notify(options: NotificationOptions): void {
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

      if (options.duration) {
        setTimeout(() => notification.close(), options.duration);
      }
    } 
    // Fallback to toast notifications
    else {
      this.toastCallbacks.forEach(callback => callback(options));
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
    this.notify({ title, message, type: 'success', duration: 5000 });
  }

  error(title: string, message: string): void {
    this.notify({ title, message, type: 'error', duration: 8000 });
  }

  info(title: string, message: string): void {
    this.notify({ title, message, type: 'info', duration: 5000 });
  }

  warning(title: string, message: string): void {
    this.notify({ title, message, type: 'warning', duration: 6000 });
  }
}

export const notificationService = NotificationService.getInstance();

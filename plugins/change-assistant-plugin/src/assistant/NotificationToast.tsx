import React from 'react';
import { AlertTriangle, CheckCircle } from 'lucide-react';
import { AssistantNotification } from './types';

function toastClass(type: AssistantNotification['type']): string {
  return type === 'success' ? 'bg-green-100 border border-green-400 text-green-800' :
    type === 'error' ? 'bg-red-100 border border-red-400 text-red-800' :
    'd-none';
}

const NotificationToast: React.FC<{ notification: AssistantNotification }> = ({ notification }) => (
  <div className={`fixed top-4 right-4 z-50 px-4 py-3 rounded-lg shadow-lg flex items-center gap-2 ${toastClass(notification.type)}`}>
    {notification.type === 'success' && <CheckCircle size={18} />}
    {notification.type === 'error' && <AlertTriangle size={18} />}
    <span className="text-sm">{notification.message}</span>
  </div>
);

export default NotificationToast;

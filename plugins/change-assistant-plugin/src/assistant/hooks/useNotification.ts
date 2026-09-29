import { useState } from 'react';
import { AssistantNotification } from '../types';

export function useNotification() {
  const [notification, setNotification] = useState<AssistantNotification>({ show: false, type: 'info', message: '' });

  const showNotification = (message: string, type: AssistantNotification['type'] = 'info') => {
    setNotification({ show: true, type, message });
    setTimeout(() => setNotification(prev => ({ ...prev, show: false })), 4000);
  };

  return { notification, showNotification };
}

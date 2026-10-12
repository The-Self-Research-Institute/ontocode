import { useRef, useState } from 'react';
import { AssistantNotification } from '../types';

export function useNotification() {
  const [notification, setNotification] = useState<AssistantNotification>({ show: false, type: 'info', message: '' });
  const hideTimerRef = useRef<ReturnType<typeof setTimeout> | null>(null);

  const showNotification = (message: string, type: AssistantNotification['type'] = 'info') => {
    if (hideTimerRef.current) {
      clearTimeout(hideTimerRef.current);
    }
    setNotification({ show: true, type, message });
    hideTimerRef.current = setTimeout(() => setNotification(prev => ({ ...prev, show: false })), 4000);
  };

  return { notification, showNotification };
}

import { useRef, useState } from "react";
import type { Toast } from "../components/ToastNotification";
import type { ConfirmOptions, ShowConfirm, ShowToast } from "../utils/projectDashboardTypes";

const TOAST_DURATION_MS = 4000;

export function useDashboardFeedback() {
  const [toasts, setToasts] = useState<Toast[]>([]);
  const [confirmModal, setConfirmModal] = useState<ConfirmOptions | null>(null);
  const confirmResolveRef = useRef<((value: boolean) => void) | null>(null);

  const showToast: ShowToast = (message, type = "info") => {
    const id = `${Date.now()}-${Math.random()}`;
    setToasts((prev) => [...prev, { id, message, type, duration: TOAST_DURATION_MS }]);
  };

  const dismissToast = (id: string) => setToasts((prev) => prev.filter((t) => t.id !== id));

  const settlePendingConfirm = (value: boolean) => {
    if (confirmResolveRef.current) {
      confirmResolveRef.current(value);
      confirmResolveRef.current = null;
    }
  };

  const showConfirm: ShowConfirm = (options) => {
    settlePendingConfirm(false);
    setConfirmModal(options);
  };

  const showConfirmDialog = (title: string, message: string, confirmLabel = "OK"): Promise<boolean> =>
    new Promise<boolean>((resolve) => {
      settlePendingConfirm(false);
      confirmResolveRef.current = resolve;
      setConfirmModal({
        title,
        message,
        confirmText: confirmLabel,
        cancelText: "Cancel",
        type: "warning",
        onConfirm: () => {
          setConfirmModal(null);
          settlePendingConfirm(true);
        },
      });
    });

  const cancelConfirm = () => {
    setConfirmModal(null);
    settlePendingConfirm(false);
  };

  return {
    toasts,
    dismissToast,
    showToast,
    confirmModal,
    setConfirmModal,
    showConfirm,
    showConfirmDialog,
    cancelConfirm,
  };
}

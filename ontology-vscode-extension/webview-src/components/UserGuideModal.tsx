
import React from "react";
import { UserGuideWebextension } from "./UserGuideWebextension";
// TODO: import { UserGuideDesktop } from "./UserGuideDesktop"; once it's implemented

interface UserGuideModalProps {
  isOpen: boolean;
  onClose: () => void;
}

export const UserGuideModal: React.FC<UserGuideModalProps> = ({ isOpen, onClose }) => {
  if (!isOpen) return null;

  // TODO: switch back to isDesktop() ? <UserGuideDesktop .../> : <UserGuideWebextension .../>
  // once UserGuideDesktop is implemented
  return <UserGuideWebextension isOpen={isOpen} onClose={onClose} />;
};

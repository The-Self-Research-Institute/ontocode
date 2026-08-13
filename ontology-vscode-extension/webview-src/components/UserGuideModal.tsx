import React from "react";
import { UserGuideWebextension } from "./UserGuideWebextension";

interface UserGuideModalProps {
  isOpen: boolean;
  onClose: () => void;
}

export const UserGuideModal: React.FC<UserGuideModalProps> = ({ isOpen, onClose }) => {
  if (!isOpen) return null;

  return (
    <UserGuideWebextension
      isOpen={isOpen}
      onClose={onClose}
    />
  );
};
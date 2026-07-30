import React from "react";
import { isDesktop } from "../utils/desktop";
import { UserGuideDesktop } from "./UserGuideDesktop";

import React from "react";
import { UserGuideWebextension } from "./UserGuideWebextension";

interface UserGuideModalProps {
  isOpen: boolean;
  onClose: () => void;
}

export const UserGuideModal: React.FC<UserGuideModalProps> = ({ isOpen, onClose }) => {
  if (!isOpen) return null;

  return isDesktop()
    ? <UserGuideDesktop {...props} /> : <UserGuideWebextension isOpen={isOpen} onClose={onClose} />;
};

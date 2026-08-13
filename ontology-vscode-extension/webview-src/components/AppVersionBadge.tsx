import React, { useEffect, useState } from "react";
import { getAppVersion, getWebAppVersion } from "../utils/appVersion";
import { isDesktop } from "../utils/desktop";

type Variant = "light" | "dark" | "muted" | "header";

type AppVersionBadgeProps = {

  editionLabel?: string;
  variant?: Variant;
  className?: string;
};

const variantClasses: Record<Variant, string> = {
  light: "text-gray-500 bg-gray-100 border-gray-200",
  dark: "text-white/70 bg-white/10 border-white/20",
  muted: "text-gray-400 bg-transparent border-transparent",
  header: "text-gray-500 bg-gray-50 border-gray-200",
};

export const AppVersionBadge: React.FC<AppVersionBadgeProps> = ({
  editionLabel,
  variant = "muted",
  className = "",
}) => {
  const [version, setVersion] = useState(() => getWebAppVersion());

  useEffect(() => {
    getAppVersion().then(setVersion).catch(() => {});
  }, []);

  if (!version) return null;

  const edition = editionLabel ?? (isDesktop() ? "Desktop" : "Web Beta");
  const styles = variantClasses[variant];

  return (
    <span
      className={`inline-flex items-center px-2 py-0.5 rounded-full border text-[11px] font-medium tabular-nums ${styles} ${className}`.trim()}
      title={`${edition} v${version}`}
    >
      {edition} v{version}
    </span>
  );
};

export default AppVersionBadge;

import React from "react";

type OntoCodeLogoProps = {
  size?: number;
  className?: string;
  rounded?: boolean;
};

const logoSrc = new URL("../assets/ontocode-logo.png", import.meta.url).href;

export const OntoCodeLogo: React.FC<OntoCodeLogoProps> = ({
  size = 32,
  className = "",
  rounded = true,
}) => (
  <img
    src={logoSrc}
    alt="OntoCode Studio"
    width={size}
    height={size}
    className={`${rounded ? "rounded-lg" : ""} object-contain ${className}`.trim()}
    draggable={false}
  />
);

export default OntoCodeLogo;

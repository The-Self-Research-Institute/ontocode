import React from 'react';
import { isDesktop } from '../utils/desktop';

type DeploymentType = 'self-hosted' | 'cloud';

interface DeploymentModePillProps {
  deploymentType: DeploymentType;
  onSwitch: (type: DeploymentType) => void;
}

export const DeploymentModePill: React.FC<DeploymentModePillProps> = ({ deploymentType, onSwitch }) => {
  if (isDesktop()) return null;

  const other: DeploymentType = deploymentType === 'cloud' ? 'self-hosted' : 'cloud';
  const label = deploymentType === 'cloud' ? 'Cloud' : 'Self-Hosted';
  const switchLabel = other === 'cloud' ? 'Switch to Cloud' : 'Switch to Self-Hosted';

  return (
    <div className="flex items-center justify-center gap-2 mb-3">
      <span className="inline-flex items-center px-2 py-0.5 rounded-full border text-[11px] font-medium text-white/70 bg-white/10 border-white/20">
        {label}
      </span>
      <button
        type="button"
        onClick={() => onSwitch(other)}
        className="text-[11px] font-medium text-purple-400 hover:text-purple-300 underline underline-offset-2"
      >
        {switchLabel}
      </button>
    </div>
  );
};

export default DeploymentModePill;

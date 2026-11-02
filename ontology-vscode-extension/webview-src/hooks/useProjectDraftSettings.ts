import { useCallback, useEffect, useRef, useState } from "react";
import { draftTrackingService } from "../services/draftTrackingService";

export interface ProjectDraftSettings {
  requireDraftForMembers: boolean;
  isOwner: boolean;
}

export function useProjectDraftSettings(projectId: string | null | undefined) {
  const [requireDraftForMembers, setRequireDraftForMembers] = useState(false);
  const [isProjectOwner, setIsProjectOwner] = useState(false);
  const projectIdRef = useRef(projectId);
  const requestTokenRef = useRef(0);
  projectIdRef.current = projectId;

  const reset = useCallback(() => {
    requestTokenRef.current += 1;
    setRequireDraftForMembers(false);
    setIsProjectOwner(false);
  }, []);

  useEffect(() => {
    reset();
  }, [projectId, reset]);

  const load = useCallback(
    async (targetProjectId: string, userId: string): Promise<ProjectDraftSettings | null> => {
      requestTokenRef.current += 1;
      const token = requestTokenRef.current;
      setRequireDraftForMembers(false);
      setIsProjectOwner(false);
      const isCurrent = () =>
        requestTokenRef.current === token && projectIdRef.current === targetProjectId;
      try {
        const settings = await draftTrackingService.getDraftSettings(targetProjectId, userId);
        if (!isCurrent()) return null;
        setRequireDraftForMembers(settings.requireDraftForMembers);
        setIsProjectOwner(settings.isOwner === true);
        return settings;
      } catch {
        if (isCurrent()) {
          setRequireDraftForMembers(false);
          setIsProjectOwner(false);
        }
        return null;
      }
    },
    [],
  );

  return {
    requireDraftForMembers,
    isProjectOwner,
    setRequireDraftForMembers,
    loadDraftSettings: load,
    resetDraftSettings: reset,
  };
}

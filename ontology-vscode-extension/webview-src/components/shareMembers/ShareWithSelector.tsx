import React, { useEffect, useMemo } from "react";
import { Users, Search } from "lucide-react";
import { useShareableMembers } from "../../hooks/useShareableMembers";
import {
  MEMBER_SEARCH_THRESHOLD,
  filterVisibleMembers,
  isPrivilegedMember,
  normalizeEmail,
  type ProjectShareRole,
  type ShareSelection,
  type ShareWith,
} from "../../utils/projectShare";
import ShareMemberRow from "./ShareMemberRow";

interface ShareWithSelectorProps {
  workspaceId?: string;
  excludeEmails?: string[];
  autoIncludePrivileged?: boolean;
  selection: ShareSelection;
  onSelectionChange: (selection: ShareSelection) => void;
  refreshKey?: number | string;
  noMembersHint?: string;
  noneDescription?: string;
  allDescriptionSuffix?: string;
  currentUser?: { userId?: string | null; email?: string | null } | null;
}

const ShareWithSelector: React.FC<ShareWithSelectorProps> = ({
  workspaceId,
  excludeEmails = [],
  autoIncludePrivileged = false,
  selection,
  onSelectionChange,
  refreshKey,
  noMembersHint = "Invite another member to the workspace first, or use All Workspace Members.",
  noneDescription = "No one else will be added",
  allDescriptionSuffix = "",
  currentUser,
}) => {
  const {
    workspaceMembers,
    loadingMembers,
    membersLoadError,
    memberSearch,
    setMemberSearch,
    loadWorkspaceMembers,
  } = useShareableMembers(workspaceId, excludeEmails, refreshKey, currentUser);

  useEffect(() => {
    if (!autoIncludePrivileged || selection.shareWith !== "specific") return;
    const privileged = workspaceMembers.filter(isPrivilegedMember);
    if (privileged.length === 0) return;

    const existingEmails = new Set(selection.members.map((m) => normalizeEmail(m.email)));
    const missing = privileged.filter((p) => !existingEmails.has(normalizeEmail(p.email)));
    if (missing.length === 0) return;

    onSelectionChange({
      shareWith: selection.shareWith,
      members: [
        ...missing.map((m) => ({ email: m.email.trim(), role: "EDITOR" as ProjectShareRole })),
        ...selection.members,
      ],
    });
  }, [autoIncludePrivileged, workspaceMembers, selection.shareWith]);

  const setMode = (mode: ShareWith) => {
    onSelectionChange({ shareWith: mode, members: mode === "specific" ? selection.members : [] });
  };

  const toggleMember = (email: string, privileged = false) => {
    if (privileged) return;
    const normalizedEmail = normalizeEmail(email);
    const isSelected = selection.members.some((m) => normalizeEmail(m.email) === normalizedEmail);

    if (isSelected) {
      onSelectionChange({
        ...selection,
        members: selection.members.filter((m) => normalizeEmail(m.email) !== normalizedEmail),
      });
    } else {
      onSelectionChange({
        ...selection,
        members: [...selection.members, { email: email.trim(), role: "VIEWER" }],
      });
    }
  };

  const getMemberRole = (email: string): ProjectShareRole =>
    selection.members.find((m) => normalizeEmail(m.email) === normalizeEmail(email))?.role || "VIEWER";

  const setMemberRole = (email: string, role: ProjectShareRole) => {
    const normalizedEmail = normalizeEmail(email);
    onSelectionChange({
      ...selection,
      members: selection.members.map((m) => (normalizeEmail(m.email) === normalizedEmail ? { ...m, role } : m)),
    });
  };

  const hasShareableMembers = workspaceMembers.length > 0;
  const shareableMemberCount = workspaceMembers.length;

  const visibleMembers = useMemo(
    () => filterVisibleMembers(workspaceMembers, memberSearch),
    [workspaceMembers, memberSearch],
  );

  return (
    <div className="space-y-3">
      <label className="flex items-start gap-3 p-3 border border-gray-200 rounded-lg cursor-pointer hover:bg-gray-50">
        <input
          type="radio"
          name="shareWith"
          value="none"
          checked={selection.shareWith === "none"}
          onChange={() => setMode("none")}
          className="mt-0.5"
        />
        <div>
          <div className="font-medium text-gray-900">Only me</div>
          <div className="text-sm text-gray-500">{noneDescription}</div>
        </div>
      </label>

      <label className="flex items-start gap-3 p-3 border border-gray-200 rounded-lg cursor-pointer hover:bg-gray-50">
        <input
          type="radio"
          name="shareWith"
          value="all"
          checked={selection.shareWith === "all"}
          onChange={() => setMode("all")}
          className="mt-0.5"
        />
        <div>
          <div className="font-medium text-gray-900 flex items-center gap-2">
            <Users size={16} />
            All Workspace Members
          </div>
          <div className="text-sm text-gray-500">
            {loadingMembers ? (
              "Checking active workspace members..."
            ) : shareableMemberCount === 0 ? (
              "No other active members to add yet."
            ) : (
              <>
                {shareableMemberCount} active workspace member{shareableMemberCount === 1 ? "" : "s"} will be added as{" "}
                <strong>Viewer</strong>
                {allDescriptionSuffix}
              </>
            )}
          </div>
          {autoIncludePrivileged && (selection.shareWith === "all" || selection.shareWith === "specific") && (
            <p className="text-xs text-gray-500 mt-1 leading-snug">
              The workspace owner and workspace admins are always given at least <strong>Editor</strong> on shared
              projects. Only the workspace owner can remove them from the project later.
            </p>
          )}
        </div>
      </label>

      <label
        className={`flex items-start gap-3 p-3 border border-gray-200 rounded-lg ${
          hasShareableMembers ? "cursor-pointer hover:bg-gray-50" : "opacity-60 cursor-not-allowed"
        }`}
      >
        <input
          type="radio"
          name="shareWith"
          value="specific"
          checked={selection.shareWith === "specific"}
          disabled={!hasShareableMembers && !loadingMembers}
          onChange={() => setMode("specific")}
          className="mt-0.5"
        />
        <div className="flex-1">
          <div className="font-medium text-gray-900 flex items-center gap-2">
            Specific Members
            {selection.shareWith === "specific" && selection.members.length > 0 && (
              <span className="px-2 py-0.5 bg-purple-100 text-purple-700 text-xs rounded-full">
                {selection.members.length} selected
              </span>
            )}
          </div>
          <div className="text-sm text-gray-500">Choose active workspace members and their project role</div>
          {autoIncludePrivileged && selection.shareWith === "specific" && workspaceMembers.some(isPrivilegedMember) && (
            <p className="text-xs text-purple-600 mt-1">Owner and admins are always included with Editor access.</p>
          )}
          {!hasShareableMembers && !loadingMembers && <p className="text-xs text-gray-500 mt-1">{noMembersHint}</p>}
        </div>
      </label>

      {selection.shareWith === "specific" && (
        <div className="ml-8 space-y-3 border border-gray-200 rounded-lg p-3 bg-gray-50">
          {workspaceMembers.length >= MEMBER_SEARCH_THRESHOLD && !loadingMembers && !membersLoadError && (
            <div className="relative">
              <Search size={16} className="absolute left-3 top-1/2 -translate-y-1/2 text-gray-400" />
              <input
                type="search"
                value={memberSearch}
                onChange={(e) => setMemberSearch(e.target.value)}
                placeholder="Search by name or email"
                className="w-full pl-9 pr-3 py-2 text-sm border border-gray-200 rounded-lg bg-white focus:ring-2 focus:ring-purple-500 focus:border-transparent"
              />
            </div>
          )}

          <div className="space-y-2 max-h-48 overflow-y-auto">
            {loadingMembers ? (
              <div className="text-center text-sm text-gray-500 py-4">Loading members...</div>
            ) : membersLoadError ? (
              <div className="space-y-3 py-2">
                <p className="text-sm text-red-600">{membersLoadError}</p>
                <button
                  type="button"
                  onClick={() => loadWorkspaceMembers()}
                  className="text-sm font-medium text-purple-600 hover:text-purple-700"
                >
                  Retry
                </button>
              </div>
            ) : workspaceMembers.length === 0 ? (
              <p className="text-center text-sm text-gray-500 py-4">
                No other active members are available in this workspace.
              </p>
            ) : visibleMembers.length === 0 ? (
              <p className="text-center text-sm text-gray-500 py-4">No members match your search.</p>
            ) : (
              visibleMembers.map((member) => {
                const privileged = autoIncludePrivileged && isPrivilegedMember(member);
                const isSelected =
                  privileged ||
                  selection.members.some((m) => normalizeEmail(m.email) === normalizeEmail(member.email));
                return (
                  <ShareMemberRow
                    key={member.userId || member.email}
                    member={member}
                    privileged={privileged}
                    isSelected={isSelected}
                    role={getMemberRole(member.email)}
                    onToggle={() => toggleMember(member.email, privileged)}
                    onRoleChange={(role) => setMemberRole(member.email, role)}
                  />
                );
              })
            )}
          </div>
        </div>
      )}
    </div>
  );
};

export default ShareWithSelector;

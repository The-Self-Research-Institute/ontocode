import React, { useState } from "react";
import {
  X,
  BookOpen,
  ChevronDown,
  ChevronRight,
  FolderPlus,
  Building2,
  UserPlus,
  Mail,
  Check,
  FileText,
  AlertCircle,
  CheckCircle,
  XCircle,
  Users,
} from "lucide-react";

interface UserGuideModalProps {
  isOpen: boolean;
  onClose: () => void;
}

export const UserGuideModal: React.FC<UserGuideModalProps> = ({ isOpen, onClose }) => {
  const [expandedSections, setExpandedSections] = useState<Record<string, boolean>>({
    workspace: true,
    inviteMembers: false,
    acceptInvitation: false,
    project: false,
    assignMembers: false,
    createFile: false,
  });

  if (!isOpen) return null;

  const toggleSection = (section: string) => {
    setExpandedSections((prev) => ({
      ...prev,
      [section]: !prev[section],
    }));
  };

  const allSections = () => {
    const currentlyAllExpanded = Object.values(expandedSections).every((v) => v);
    const newValue = !currentlyAllExpanded;
    setExpandedSections({
      workspace: newValue,
      inviteMembers: newValue,
      acceptInvitation: newValue,
      project: newValue,
      assignMembers: newValue,
      createFile: newValue,
    });
  };

  const scrollToSection = (sectionId: string) => {
    // Map section IDs to state keys
    const sectionMap: Record<string, string> = {
      "section-workspace": "workspace",
      "section-invite": "inviteMembers",
      "section-accept": "acceptInvitation",
      "section-project": "project",
      "section-assign": "assignMembers",
      "section-file": "createFile",
    };

    // Collapse all sections and expand only the clicked one
    const sectionKey = sectionMap[sectionId];
    if (sectionKey) {
      setExpandedSections({
        workspace: false,
        inviteMembers: false,
        acceptInvitation: false,
        project: false,
        assignMembers: false,
        createFile: false,
        [sectionKey]: true,
      });
    }

    // Scroll to the section
    const element = document.getElementById(sectionId);
    if (element) {
      // Add a small delay to allow the section to expand before scrolling
      setTimeout(() => {
        element.scrollIntoView({ behavior: "smooth", block: "start" });
      }, 100);
    }
  };

  return (
    <div style={styles.overlay}>
      <div style={styles.modal}>
        {/* Header */}
        <div style={styles.header}>
          <div style={styles.headerTitle}>
            <BookOpen size={24} style={{ color: "#3b82f6" }} />
            <h2 style={styles.title}>OntoCode User Guide</h2>
          </div>
          <div style={styles.headerActions}>
            <button onClick={allSections} style={styles.expandAllBtn}>
              {Object.values(expandedSections).every((v) => v) ? "Collapse All" : "Expand All"}
            </button>
            <button onClick={onClose} style={styles.closeButton}>
              <X size={20} />
            </button>
          </div>
        </div>

        {/* Content */}
        <div style={styles.content}>
          <p style={styles.intro}>
            Welcome to OntoCode! This guide follows the logical workflow for setting up and using OntoCode. Start with
            creating a workspace, then invite team members, and progress through creating projects and files. Each
            section includes positive scenarios (what to do) and negative scenarios (what to avoid) to help you work
            efficiently.
          </p>

          {/* Table of Contents Navigation */}
          <div style={styles.tableOfContents}>
            <h3 style={styles.tocTitle}>Quick Navigation</h3>
            <div style={styles.tocGrid}>
              <div style={styles.tocItem} onClick={() => scrollToSection("section-workspace")}>
                <Building2 size={18} style={{ color: "#f59e0b" }} />
                <span>1. Create a Workspace</span>
              </div>
              <div style={styles.tocItem} onClick={() => scrollToSection("section-invite")}>
                <UserPlus size={18} style={{ color: "#8b5cf6" }} />
                <span>2. Invite Members</span>
              </div>
              <div style={styles.tocItem} onClick={() => scrollToSection("section-accept")}>
                <Mail size={18} style={{ color: "#06b6d4" }} />
                <span>3. Accept Invitation</span>
              </div>
              <div style={styles.tocItem} onClick={() => scrollToSection("section-project")}>
                <FolderPlus size={18} style={{ color: "#10b981" }} />
                <span>4. Create a Project</span>
              </div>
              <div style={styles.tocItem} onClick={() => scrollToSection("section-assign")}>
                <Users size={18} style={{ color: "#ec4899" }} />
                <span>5. Assign Members</span>
              </div>
              <div style={styles.tocItem} onClick={() => scrollToSection("section-file")}>
                <FileText size={18} style={{ color: "#3b82f6" }} />
                <span>6. Create a File</span>
              </div>
            </div>
          </div>

          {/* Section 1: How to Create a Workspace */}
          <div id="section-workspace" style={styles.section}>
            <button onClick={() => toggleSection("workspace")} style={styles.sectionHeader}>
              {expandedSections.workspace ? <ChevronDown size={20} /> : <ChevronRight size={20} />}
              <Building2 size={20} style={{ color: "#f59e0b" }} />
              <span style={styles.sectionTitle}>1. How to Create a Workspace</span>
            </button>

            {expandedSections.workspace && (
              <div style={styles.sectionContent}>
                <div style={styles.subsection}>
                  <h4 style={styles.subsectionTitle}>
                    <CheckCircle size={16} style={{ color: "#10b981" }} />
                    Positive Cases (Recommended)
                  </h4>
                  <ul style={styles.list}>
                    <li>
                      <strong>During Signup:</strong> First workspace is created automatically when you register
                    </li>
                    <li>
                      <strong>Additional Workspaces:</strong> Click "Create Workspace" from workspace selector dropdown
                    </li>
                    <li>
                      <strong>Naming Convention:</strong> Use organization/team names (e.g., "Research Lab",
                      "Engineering Team")
                    </li>
                    <li>
                      <strong>Purpose Definition:</strong> Each workspace should represent a distinct organization or
                      team
                    </li>
                    <li>
                      <strong>Subscription Plan:</strong> Select appropriate plan based on team size and needs
                      (Free/Pro/Enterprise)
                    </li>
                    <li>
                      <strong>Ownership:</strong> Creator automatically becomes workspace owner with full permissions
                    </li>
                  </ul>

                  <div style={styles.example}>
                    <strong>✅ Example:</strong>
                    <div style={styles.exampleContent}>
                      Workspace Name: "BioinformaticsLab - Stanford"
                      <br />
                      Plan: Pro (for 10 team members)
                      <br />
                      Purpose: Academic research in genomics ontologies
                    </div>
                  </div>
                </div>

                <div style={styles.subsection}>
                  <h4 style={styles.subsectionTitle}>
                    <XCircle size={16} style={{ color: "#ef4444" }} />
                    Negative Cases (Common Pitfalls)
                  </h4>
                  <ul style={styles.list}>
                    <li>
                      <strong>Empty Name:</strong> ❌ Workspace name is required
                    </li>
                    <li>
                      <strong>Personal + Team Mix:</strong> ❌ Don't mix personal and team projects in same workspace
                    </li>
                    <li>
                      <strong>Wrong Plan Selection:</strong> ❌ Free plan has limitations - choose plan matching team
                      size
                    </li>
                    <li>
                      <strong>Duplicate Registration:</strong> ❌ Each email can only create one account - use workspace
                      invitations instead
                    </li>
                    <li>
                      <strong>No Workspace Management:</strong> ❌ Neglecting to archive or delete unused workspaces
                    </li>
                  </ul>
                </div>

                <div style={styles.subsection}>
                  <h4 style={styles.subsectionTitle}>
                    <Users size={16} style={{ color: "#8b5cf6" }} />
                    Possibilities & Features
                  </h4>
                  <ul style={styles.list}>
                    <li>🔹 Switch between multiple workspaces instantly</li>
                    <li>🔹 Each workspace has independent subscription and billing</li>
                    <li>🔹 Invite unlimited members (based on subscription)</li>
                    <li>🔹 Workspace data is isolated and secure</li>
                  </ul>
                </div>
              </div>
            )}
          </div>

          {/* Section 2: How to Invite Members */}
          <div id="section-invite" style={styles.section}>
            <button onClick={() => toggleSection("inviteMembers")} style={styles.sectionHeader}>
              {expandedSections.inviteMembers ? <ChevronDown size={20} /> : <ChevronRight size={20} />}
              <UserPlus size={20} style={{ color: "#ec4899" }} />
              <span style={styles.sectionTitle}>2. How to Invite Members & Who Can Invite</span>
            </button>

            {expandedSections.inviteMembers && (
              <div style={styles.sectionContent}>
                <div style={styles.subsection}>
                  <h4 style={styles.subsectionTitle}>
                    <CheckCircle size={16} style={{ color: "#10b981" }} />
                    Positive Cases (Recommended)
                  </h4>
                  <ul style={styles.list}>
                    <li>
                      <strong>From Dashboard:</strong> Click "Invite Member" button (UserPlus icon) on Project Dashboard
                    </li>
                    <li>
                      <strong>Required Info:</strong> Provide: Email address, Username, and Role (Owner/Editor/Viewer)
                    </li>
                    <li>
                      <strong>Email Validation:</strong> Ensure email is valid and belongs to intended recipient
                    </li>
                    <li>
                      <strong>Role Selection:</strong> Start with Viewer for new members, upgrade as needed
                    </li>
                    <li></li>
                    <li>
                      <strong>Follow-up:</strong> Confirm invitation was received and accepted
                    </li>
                  </ul>

                  <div style={styles.infoBox}>
                    <strong>Who Can Invite Members:</strong>
                    <ul style={styles.roleList}>
                      <li>
                        <strong>✅ Workspace Owner:</strong> Can invite anyone to workspace
                      </li>
                      <li>
                        <strong>✅ Workspace Admins:</strong> Can invite members (if enabled)
                      </li>
                      <li>
                        <strong>❌ Regular Members:</strong> Cannot invite unless promoted to admin
                      </li>
                      <li>
                        <strong>❌ Project-only Members:</strong> Cannot invite to workspace
                      </li>
                    </ul>
                  </div>
                </div>

                <div style={styles.subsection}>
                  <h4 style={styles.subsectionTitle}>
                    <XCircle size={16} style={{ color: "#ef4444" }} />
                    Negative Cases (Common Pitfalls)
                  </h4>
                  <ul style={styles.list}>
                    <li>
                      <strong>Invalid Email:</strong> ❌ Typo in email means invitation won't reach user
                    </li>
                    <li>
                      <strong>Already a Member:</strong> ❌ Cannot invite existing workspace members - assign to project
                      instead
                    </li>
                    <li>
                      <strong>No Permission:</strong> ❌ Regular members see no invite button - only owners/admins can
                      invite
                    </li>
                    <li>
                      <strong>Plan Limits:</strong> ❌ Free plan has member limits - upgrade subscription first
                    </li>
                    <li>
                      <strong>Spam Risk:</strong> ❌ Sending multiple invites to same email creates confusion
                    </li>
                    <li>
                      <strong>Wrong Workspace:</strong> ❌ Inviting to wrong workspace requires cancellation and
                      re-invite
                    </li>
                    <li>
                      <strong>Pending Invitations:</strong> ❌ Not managing/canceling expired invitations
                    </li>
                  </ul>

                  <div style={styles.warning}>
                    <AlertCircle size={16} />
                    <span>
                      <strong>Privacy:</strong> Invited users can see all workspace projects unless restricted by
                      permissions.
                    </span>
                  </div>
                </div>

                <div style={styles.subsection}>
                  <h4 style={styles.subsectionTitle}>
                    <Users size={16} style={{ color: "#8b5cf6" }} />
                    Possibilities & Features
                  </h4>
                  <ul style={styles.list}>
                    <li>🔹 Invitation links sent to email automatically</li>
                    <li>🔹 Track invitation status (Pending/Accepted/Expired)</li>
                    <li>🔹 Cancel pending invitations anytime</li>
                    <li>🔹 7days is the invitation expiry time (security feature)</li>
                    <li>🔹 Bulk invite multiple users via CSV upload (Enterprise)</li>
                  </ul>
                </div>
              </div>
            )}
          </div>

          {/* Section 3: How to Accept Invitation */}
          <div id="section-accept" style={styles.section}>
            <button onClick={() => toggleSection("acceptInvitation")} style={styles.sectionHeader}>
              {expandedSections.acceptInvitation ? <ChevronDown size={20} /> : <ChevronRight size={20} />}
              <Mail size={20} style={{ color: "#14b8a6" }} />
              <span style={styles.sectionTitle}>3. How to Accept an Invitation</span>
            </button>

            {expandedSections.acceptInvitation && (
              <div style={styles.sectionContent}>
                <div style={styles.subsection}>
                  <h4 style={styles.subsectionTitle}>
                    <CheckCircle size={16} style={{ color: "#10b981" }} />
                    Positive Cases (Recommended)
                  </h4>
                  <ul style={styles.list}>
                    <li>
                      <strong>Check Email:</strong> Look for invitation email from OntoCode (check spam/junk folder if
                      needed)
                    </li>
                    <li>
                      <strong>Verify Sender:</strong> Ensure the invitation is from a legitimate source you recognize
                    </li>
                    <li>
                      <strong>Click Invitation Link:</strong> Click the "Accept Invitation" button in the email
                    </li>
                    <li>
                      <strong>Review Details:</strong> Check workspace name, your assigned role, and inviter details
                    </li>
                    <li>
                      <strong>Create Account or Login:</strong> If new user, create account; if existing user, login
                      with your credentials
                    </li>
                    <li>
                      <strong>Accept & Join:</strong> Click "Accept & Join Workspace" to complete the process
                    </li>
                    <li>
                      <strong>Explore Workspace:</strong> Once accepted, you'll be redirected to the workspace dashboard
                    </li>
                  </ul>

                  <div style={styles.example}>
                    <strong>✅ Acceptance Flow:</strong>
                    <div style={styles.exampleContent}>
                      1. Email notification → 2. Click link → 3. Review workspace info
                      <br />
                      4. Login/Signup → 5. Accept invitation → 6. Access workspace
                    </div>
                  </div>
                </div>

                <div style={styles.subsection}>
                  <h4 style={styles.subsectionTitle}>
                    <XCircle size={16} style={{ color: "#ef4444" }} />
                    Negative Cases (Common Pitfalls)
                  </h4>
                  <ul style={styles.list}>
                    <li>
                      <strong>Expired Link:</strong> ❌ Invitations expire after 7 days - request a new invitation from
                      the workspace owner
                    </li>
                    <li>
                      <strong>Wrong Email Account:</strong> ❌ Must accept using the same email address invitation was
                      sent to
                    </li>
                    <li>
                      <strong>Email in Spam:</strong> ❌ Check spam/junk folder if you don't see the invitation
                    </li>
                    <li>
                      <strong>Already a Member:</strong> ❌ If already in workspace, link won't work - you're already
                      added
                    </li>
                    <li>
                      <strong>Account Mismatch:</strong> ❌ Logging in with different email than invited email causes
                      rejection
                    </li>
                    <li>
                      <strong>Delayed Response:</strong> ❌ Waiting too long may result in expired invitation or revoked
                      access
                    </li>
                    <li>
                      <strong>Multiple Clicks:</strong> ❌ Clicking accept multiple times may cause errors - click once
                      and wait
                    </li>
                  </ul>

                  <div style={styles.warning}>
                    <AlertCircle size={16} />
                    <span>
                      <strong>Security Tip:</strong> Never share your invitation link with others. Each link is unique
                      and personal.
                    </span>
                  </div>
                </div>

                <div style={styles.subsection}>
                  <h4 style={styles.subsectionTitle}>
                    <Users size={16} style={{ color: "#8b5cf6" }} />
                    Possibilities & Features
                  </h4>
                  <ul style={styles.list}>
                    <li>🔹 Preview workspace information before accepting</li>
                    <li>🔹 Request new invitation link if expired</li>
                    <li>🔹 Accept multiple workspace invitations with same account</li>
                    <li>🔹 Immediately access workspace upon acceptance</li>
                  </ul>
                </div>
              </div>
            )}
          </div>

          {/* Section 4: How to Create a Project */}
          <div id="section-project" style={styles.section}>
            <button onClick={() => toggleSection("project")} style={styles.sectionHeader}>
              {expandedSections.project ? <ChevronDown size={20} /> : <ChevronRight size={20} />}
              <FolderPlus size={20} style={{ color: "#10b981" }} />
              <span style={styles.sectionTitle}>4. How to Create a Project</span>
            </button>

            {expandedSections.project && (
              <div style={styles.sectionContent}>
                <div style={styles.subsection}>
                  <h4 style={styles.subsectionTitle}>
                    <CheckCircle size={16} style={{ color: "#10b981" }} />
                    Positive Cases (Recommended)
                  </h4>
                  <ul style={styles.list}>
                    <li>
                      <strong>From Project Dashboard:</strong> Click the "Create New Project" button (+ icon) in the top
                      right corner
                    </li>
                    <li>
                      <strong>Provide Clear Name:</strong> Use descriptive names (e.g., "Medical Ontology v2" instead of
                      "Project1")
                    </li>
                    <li>
                      <strong>Add Description:</strong> Include purpose, scope, and relevant keywords for easy
                      identification
                    </li>
                    <li>
                      <strong>Workspace Selection:</strong> Ensure you're creating the project in the correct workspace
                    </li>
                    <li>
                      <strong>Initial Members:</strong> You can add team members immediately or later
                    </li>
                    <li>
                      <strong>Subscription Check:</strong> Verify your workspace plan allows creating more projects
                    </li>
                  </ul>

                  <div style={styles.example}>
                    <strong>✅ Example:</strong>
                    <div style={styles.exampleContent}>
                      Name: "Healthcare Domain Ontology"
                      <br />
                      Description: "Comprehensive ontology for medical terminology and patient care workflows"
                      <br />
                      Tags: healthcare, medical, patient-care
                    </div>
                  </div>
                </div>

                <div style={styles.subsection}>
                  <h4 style={styles.subsectionTitle}>
                    <XCircle size={16} style={{ color: "#ef4444" }} />
                    Negative Cases (Common Pitfalls)
                  </h4>
                  <ul style={styles.list}>
                    <li>
                      <strong>Empty Name:</strong> ❌ Project name cannot be empty - validation will prevent creation
                    </li>
                    <li>
                      <strong>Duplicate Names:</strong> ⚠️ While allowed, avoid identical names to prevent confusion
                    </li>
                    <li>
                      <strong>Generic Names:</strong> ❌ Avoid "Test", "New Project", "Untitled" - use descriptive names
                    </li>
                    <li>
                      <strong>Wrong Workspace:</strong> ❌ Creating in wrong workspace requires deletion and recreation
                    </li>
                    <li>
                      <strong>Missing Access:</strong> ❌ Only workspace members can create projects
                    </li>
                  </ul>

                  <div style={styles.warning}>
                    <AlertCircle size={16} />
                    <span>
                      <strong>Important:</strong> Projects cannot be moved between workspaces after creation.
                      Double-check workspace selection!
                    </span>
                  </div>
                </div>

                <div style={styles.subsection}>
                  <h4 style={styles.subsectionTitle}>
                    <Users size={16} style={{ color: "#8b5cf6" }} />
                    Possibilities & Features
                  </h4>
                  <ul style={styles.list}>
                    <li>🔹 Create multiple projects within workspace limits</li>
                    <li>🔹 Set project visibility and access permissions</li>
                    <li>🔹 Delete projects (only owners can delete)</li>
                  </ul>
                </div>
              </div>
            )}
          </div>

          {/* Section 5: How to Assign Project Members */}
          <div id="section-assign" style={styles.section}>
            <button onClick={() => toggleSection("assignMembers")} style={styles.sectionHeader}>
              {expandedSections.assignMembers ? <ChevronDown size={20} /> : <ChevronRight size={20} />}
              <Users size={20} style={{ color: "#3b82f6" }} />
              <span style={styles.sectionTitle}>5. How to Assign Project Members</span>
            </button>

            {expandedSections.assignMembers && (
              <div style={styles.sectionContent}>
                <div style={styles.subsection}>
                  <h4 style={styles.subsectionTitle}>
                    <CheckCircle size={16} style={{ color: "#10b981" }} />
                    Positive Cases (Recommended)
                  </h4>
                  <ul style={styles.list}>
                    <li>
                      <strong>Access Project Settings:</strong> Click the menu (⋮) on project card → "Project Settings"
                    </li>
                    <li>
                      <strong>Members Tab:</strong> Navigate to "Members" tab in project settings
                    </li>
                    <li>
                      <strong>Add Existing Members:</strong> Select from workspace members dropdown
                    </li>
                    <li>
                      <strong>Role Assignment:</strong> Choose appropriate role (Owner/Editor/Viewer)
                    </li>
                    <li>
                      <strong>Bulk Assignment:</strong> Add multiple members at once for efficiency
                    </li>
                    <li>
                      <strong>Role Clarity:</strong> Ensure roles match actual responsibilities
                    </li>
                  </ul>

                  <div style={styles.infoBox}>
                    <strong>Role Permissions:</strong>
                    <ul style={styles.roleList}>
                      <li>
                        <strong>Owner:</strong> Full control - edit, delete, manage members
                      </li>
                      <li>
                        <strong>Editor:</strong> Can edit ontology and create files
                      </li>
                      <li>
                        <strong>Viewer:</strong> Read-only access to project
                      </li>
                    </ul>
                  </div>
                </div>

                <div style={styles.subsection}>
                  <h4 style={styles.subsectionTitle}>
                    <XCircle size={16} style={{ color: "#ef4444" }} />
                    Negative Cases (Common Pitfalls)
                  </h4>
                  <ul style={styles.list}>
                    <li>
                      <strong>Not in Workspace:</strong> ❌ Cannot assign users who aren't workspace members - invite
                      them first
                    </li>
                    <li>
                      <strong>Insufficient Permissions:</strong> ❌ Only project owners can manage members
                    </li>
                    <li>
                      <strong>Wrong Role:</strong> ❌ Giving editor access to viewers creates security risks
                    </li>
                    <li>
                      <strong>Over-permissioning:</strong> ❌ Making everyone owner reduces accountability
                    </li>
                    <li>
                      <strong>No Removal Path:</strong> ❌ Forgetting to remove members when they leave team
                    </li>
                    <li>
                      <strong>Duplicate Assignment:</strong> ❌ Member already in project cannot be added again
                    </li>
                  </ul>

                  <div style={styles.warning}>
                    <AlertCircle size={16} />
                    <span>
                      <strong>Security:</strong> Regularly audit project members and remove inactive users.
                    </span>
                  </div>
                </div>

                <div style={styles.subsection}>
                  <h4 style={styles.subsectionTitle}>
                    <Users size={16} style={{ color: "#8b5cf6" }} />
                    Possibilities & Features
                  </h4>
                  <ul style={styles.list}>
                    <li>🔹 Change member roles anytime (by owner)</li>
                    <li>🔹 Remove members from specific projects</li>
                    <li>🔹 Collaborative editing with live cursors</li>
                  </ul>
                </div>
              </div>
            )}
          </div>

          {/* Section 6: How to Create a New File */}
          <div id="section-file" style={styles.section}>
            <button onClick={() => toggleSection("createFile")} style={styles.sectionHeader}>
              {expandedSections.createFile ? <ChevronDown size={20} /> : <ChevronRight size={20} />}
              <FileText size={20} style={{ color: "#8b5cf6" }} />
              <span style={styles.sectionTitle}>6. How to Create a New File</span>
            </button>

            {expandedSections.createFile && (
              <div style={styles.sectionContent}>
                <div style={styles.subsection}>
                  <h4 style={styles.subsectionTitle}>
                    <CheckCircle size={16} style={{ color: "#10b981" }} />
                    Positive Cases (Recommended) - Multiple Ways to Create Files
                  </h4>

                  <p style={{ marginBottom: "16px", fontSize: "14px", color: "#6b7280" }}>
                    OntoCode provides three different methods to create new files depending on your workflow:
                  </p>

                  <div style={{ marginBottom: "20px", paddingLeft: "12px", borderLeft: "3px solid #3b82f6" }}>
                    <h5 style={{ fontSize: "14px", fontWeight: "600", color: "#1f2937", marginBottom: "8px" }}>
                      📁 Method 1: From Project Library
                    </h5>
                    <ul style={styles.list}>
                      <li>
                        <strong>Navigate:</strong> Go to Project Dashboard → Select your project
                      </li>
                      <li>
                        <strong>Project Library View:</strong> You'll see the project files
                      </li>
                      <li>
                        <strong>Create Button:</strong> Click the "+" (New File) button at the top of the library panel
                      </li>
                      <li>
                        <strong>File Details:</strong> Enter filename with extension (e.g., "domain-model.owl")
                      </li>
                      <li>
                        <strong>Auto-Open:</strong> File is created and automatically opens in the editor
                      </li>
                    </ul>
                    <div style={styles.example}>
                      <strong>✅ Best For:</strong> Quick file creation while browsing project files
                    </div>
                  </div>

                  <div style={{ marginBottom: "20px", paddingLeft: "12px", borderLeft: "3px solid #10b981" }}>
                    <h5 style={{ fontSize: "14px", fontWeight: "600", color: "#1f2937", marginBottom: "8px" }}>
                      ✏️ Method 2: From Project Dashboard
                    </h5>
                    <ul style={styles.list}>
                      <li>
                        <strong>Open Editor:</strong> Click "Editor" button from project dashboard
                      </li>
                      <li>
                        <strong>Open File Dialog:</strong> In the editor, click "File" → "Open" or use the "Open File"
                        button
                      </li>
                      <li>
                        <strong>Browse View:</strong> The file browser dialog appears showing existing files
                      </li>
                      <li>
                        <strong>Create New File:</strong> At the bottom of the dialog, click "Create New File" button
                      </li>
                      <li>
                        <strong>Enter Details:</strong> Enter filename and click create - file is added to current
                        folder
                      </li>
                      <li>
                        <strong>Immediate Access:</strong> File opens automatically after creation
                      </li>
                    </ul>
                    <div style={styles.example}>
                      <strong>✅ Best For:</strong> Creating files after opening the editor from dashboard
                    </div>
                  </div>

                  <div style={{ marginBottom: "20px", paddingLeft: "12px", borderLeft: "3px solid #f59e0b" }}>
                    <h5 style={{ fontSize: "14px", fontWeight: "600", color: "#1f2937", marginBottom: "8px" }}>
                      �️ Method 3: From Open File Menu
                    </h5>
                    <ul style={styles.list}>
                      <li>
                        <strong>Open File Dialog:</strong> While already in the editor, click "File" → "Open" or use the
                        "Open File" button
                      </li>
                      <li>
                        <strong>Browse View:</strong> The file browser dialog appears showing existing files
                      </li>
                      <li>
                        <strong>New File Option:</strong> At the bottom of the dialog, click "Create New File Instead"
                        button
                      </li>
                      <li>
                        <strong>Quick Create:</strong> Enter filename and click create - file is added to current folder
                      </li>
                      <li>
                        <strong>Immediate Access:</strong> File opens automatically after creation
                      </li>
                    </ul>
                    <div style={styles.example}>
                      <strong>✅ Best For:</strong> Quick creation when you're already working in the editor and
                      browsing files
                    </div>
                  </div>

                  <div style={{ marginTop: "16px", padding: "12px", backgroundColor: "#f0f9ff", borderRadius: "6px" }}>
                    <p style={{ fontSize: "13px", color: "#075985", margin: 0 }}>
                      <strong>💡 Pro Tip:</strong> All methods create files in the currently active project. Always
                      verify you're in the correct project before creating files.
                    </p>
                  </div>
                </div>

                <div style={styles.subsection}>
                  <h4 style={styles.subsectionTitle}>
                    <XCircle size={16} style={{ color: "#ef4444" }} />
                    Negative Cases (Common Pitfalls Across All Methods)
                  </h4>
                  <ul style={styles.list}>
                    <li>
                      <strong>Wrong Project Context:</strong> ❌ Creating file in wrong project - always check active
                      project name before creating
                    </li>
                    <li>
                      <strong>No File Extension:</strong> ⚠️ Always use proper extension (.owl, .rdf, .ttl, .omn, etc.)
                      or file may not be recognized
                    </li>
                    <li>
                      <strong>Duplicate Names:</strong> ❌ Creating file with same name in library causes overwrite
                      warning - choose unique names
                    </li>
                    <li>
                      <strong>Invalid Characters:</strong> ❌ Avoid special characters in filenames (/, \, :, *, ?, ",
                      &lt;, &gt;, |) - use hyphens or underscores
                    </li>
                    <li>
                      <strong>Unsaved After Creation:</strong> ❌ Creating file but not saving immediately risks data
                      loss
                    </li>
                    <li>
                      <strong>Wrong Format Selection:</strong> ❌ Choosing incompatible format for your use case (e.g.,
                      Manchester syntax when collaborators need RDF/XML)
                    </li>
                  </ul>

                  <div style={styles.warning}>
                    <AlertCircle size={16} />
                    <span>
                      <strong>Auto-save:</strong> OntoCode auto-saves drafts every 30 seconds, but explicitly save via
                      the "File" menu click "Save" important changes to prevent loss during network issues.
                    </span>
                  </div>
                </div>

                <div style={styles.subsection}>
                  <h4 style={styles.subsectionTitle}>
                    <Users size={16} style={{ color: "#8b5cf6" }} />
                    Possibilities & Features for File Creation
                  </h4>
                  <ul style={styles.list}>
                    <li>🔹 Create unlimited files per project (based on storage limits)</li>
                    <li>🔹 Support for multiple file formats: .owl, .rdf, .ttl, .omn, .json (JSON-LD)</li>
                    <li>🔹 Three convenient creation methods - library, editor, and open dialog</li>
                    <li>🔹 Create files in specific folders within project structure</li>
                    <li>🔹 Filename uniqueness validation prevents overwrites</li>
                  </ul>

                  <div
                    style={{
                      marginTop: "16px",
                      padding: "12px",
                      backgroundColor: "#ecfdf5",
                      borderRadius: "6px",
                      borderLeft: "4px solid #10b981",
                    }}
                  >
                    <p style={{ fontSize: "13px", color: "#065f46", margin: 0 }}>
                      <strong>🎯 Recommendation:</strong> Use <strong>Project Library</strong> for quick file creation
                      while browsing, <strong>Editor File button</strong> for convenience while working in the editor,
                      and <strong>Open File dialog</strong> when already browsing for files.
                    </p>
                  </div>
                </div>
              </div>
            )}
          </div>

          {/* Quick Reference */}
          <div style={styles.quickReference}>
            <h3 style={styles.quickRefTitle}>Quick Reference</h3>
            <div style={styles.quickRefGrid}>
              <div style={styles.quickRefCard}>
                <strong>📧 Need Help?</strong>
                <p style={styles.quickRefText}>Help → Report Issue</p>
              </div>
              <div style={styles.quickRefCard}>
                <strong>🚀 Best Practice</strong>
                <p style={styles.quickRefText}>Save frequently, use descriptive names</p>
              </div>
              <div style={styles.quickRefCard}>
                <strong>👥 Collaboration</strong>
                <p style={styles.quickRefText}>Real-time editing, live cursors enabled</p>
              </div>
            </div>
          </div>
        </div>
      </div>
    </div>
  );
};

// Styles
const styles: Record<string, React.CSSProperties> = {
  overlay: {
    position: "fixed",
    top: 0,
    left: 0,
    right: 0,
    bottom: 0,
    backgroundColor: "rgba(0, 0, 0, 0.6)",
    display: "flex",
    alignItems: "center",
    justifyContent: "center",
    zIndex: 10000,
    padding: "20px",
  },
  modal: {
    backgroundColor: "#ffffff",
    borderRadius: "12px",
    width: "100%",
    maxWidth: "900px",
    maxHeight: "90vh",
    display: "flex",
    flexDirection: "column",
    boxShadow: "0 20px 60px rgba(0, 0, 0, 0.3)",
  },
  header: {
    padding: "24px",
    borderBottom: "1px solid #e5e7eb",
    display: "flex",
    justifyContent: "space-between",
    alignItems: "center",
  },
  headerTitle: {
    display: "flex",
    alignItems: "center",
    gap: "12px",
  },
  headerActions: {
    display: "flex",
    alignItems: "center",
    gap: "12px",
  },
  title: {
    margin: 0,
    fontSize: "24px",
    fontWeight: "600",
    color: "#111827",
  },
  expandAllBtn: {
    padding: "8px 16px",
    fontSize: "14px",
    color: "#3b82f6",
    backgroundColor: "#eff6ff",
    border: "1px solid #3b82f6",
    borderRadius: "6px",
    cursor: "pointer",
    transition: "all 0.2s",
  },
  closeButton: {
    background: "transparent",
    border: "none",
    cursor: "pointer",
    padding: "8px",
    display: "flex",
    alignItems: "center",
    justifyContent: "center",
    borderRadius: "6px",
    color: "#6b7280",
    transition: "all 0.2s",
  },
  content: {
    padding: "24px",
    overflowY: "auto",
    flex: 1,
  },
  intro: {
    fontSize: "15px",
    color: "#4b5563",
    lineHeight: "1.6",
    marginBottom: "24px",
    backgroundColor: "#f9fafb",
    padding: "16px",
    borderRadius: "8px",
    borderLeft: "4px solid #3b82f6",
  },
  section: {
    marginBottom: "16px",
    border: "1px solid #e5e7eb",
    borderRadius: "8px",
    overflow: "hidden",
  },
  sectionHeader: {
    width: "100%",
    padding: "16px",
    backgroundColor: "#f9fafb",
    border: "none",
    cursor: "pointer",
    display: "flex",
    alignItems: "center",
    gap: "12px",
    fontSize: "16px",
    fontWeight: "600",
    color: "#111827",
    transition: "background-color 0.2s",
    textAlign: "left",
  },
  sectionTitle: {
    flex: 1,
  },
  sectionContent: {
    padding: "20px",
    backgroundColor: "#ffffff",
  },
  subsection: {
    marginBottom: "24px",
  },
  subsectionTitle: {
    display: "flex",
    alignItems: "center",
    gap: "8px",
    fontSize: "15px",
    fontWeight: "600",
    color: "#111827",
    marginBottom: "12px",
  },
  list: {
    marginLeft: "20px",
    lineHeight: "1.8",
    color: "#374151",
    fontSize: "14px",
  },
  example: {
    marginTop: "16px",
    padding: "16px",
    backgroundColor: "#ecfdf5",
    borderRadius: "8px",
    borderLeft: "4px solid #10b981",
  },
  exampleContent: {
    marginTop: "8px",
    fontSize: "13px",
    color: "#065f46",
    lineHeight: "1.8",
    fontFamily: "monospace",
  },
  warning: {
    marginTop: "16px",
    padding: "12px 16px",
    backgroundColor: "#fef2f2",
    borderRadius: "8px",
    borderLeft: "4px solid #ef4444",
    display: "flex",
    alignItems: "flex-start",
    gap: "12px",
    fontSize: "14px",
    color: "#991b1b",
  },
  infoBox: {
    marginTop: "16px",
    padding: "16px",
    backgroundColor: "#eff6ff",
    borderRadius: "8px",
    borderLeft: "4px solid #3b82f6",
    fontSize: "14px",
  },
  roleList: {
    marginLeft: "20px",
    marginTop: "8px",
    lineHeight: "1.8",
    color: "#1e40af",
  },
  quickReference: {
    marginTop: "32px",
    padding: "24px",
    backgroundColor: "#f9fafb",
    borderRadius: "8px",
    border: "1px solid #e5e7eb",
  },
  quickRefTitle: {
    fontSize: "18px",
    fontWeight: "600",
    color: "#111827",
    marginBottom: "16px",
  },
  quickRefGrid: {
    display: "grid",
    gridTemplateColumns: "repeat(auto-fit, minmax(200px, 1fr))",
    gap: "16px",
  },
  quickRefCard: {
    padding: "16px",
    backgroundColor: "#ffffff",
    borderRadius: "8px",
    border: "1px solid #e5e7eb",
  },
  quickRefText: {
    fontSize: "13px",
    color: "#6b7280",
    marginTop: "8px",
  },
  tableOfContents: {
    marginBottom: "24px",
    padding: "20px",
    backgroundColor: "#f9fafb",
    borderRadius: "8px",
    border: "1px solid #e5e7eb",
  },
  tocTitle: {
    margin: "0 0 16px 0",
    fontSize: "16px",
    fontWeight: "600",
    color: "#111827",
    display: "flex",
    alignItems: "center",
    gap: "8px",
  },
  tocGrid: {
    display: "grid",
    gridTemplateColumns: "repeat(auto-fit, minmax(250px, 1fr))",
    gap: "12px",
  },
  tocItem: {
    display: "flex",
    alignItems: "center",
    gap: "10px",
    padding: "12px 16px",
    backgroundColor: "#ffffff",
    borderRadius: "6px",
    border: "1px solid #e5e7eb",
    cursor: "pointer",
    transition: "all 0.2s",
    fontSize: "14px",
    fontWeight: "500",
    color: "#374151",
  },
  footer: {
    padding: "16px 24px",
    borderTop: "1px solid #e5e7eb",
    backgroundColor: "#f9fafb",
  },
  footerText: {
    fontSize: "13px",
    color: "#6b7280",
    textAlign: "center",
    margin: 0,
  },
};

import React, { useState } from "react";
import { isDesktop } from "../utils/desktop";
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
  Share2,
  Bug,
  Code,
  CreditCard,
} from "lucide-react";

interface UserGuideModalProps {
  isOpen: boolean;
  onClose: () => void;
}

export const UserGuideModal: React.FC<UserGuideModalProps> = ({ isOpen, onClose }) => {
  const [expandedSections, setExpandedSections] = useState<Record<string, boolean>>({
    workspace: true,
    billing: false,
    inviteMembers: false,
    acceptInvitation: false,
    project: false,
    assignMembers: false,
    createFile: false,
    collaboration: false,
    reportIssue: false,
    codeView: false,
  });

  if (!isOpen) return null;

  const desktop = isDesktop();

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
      billing: newValue,
      inviteMembers: newValue,
      acceptInvitation: newValue,
      project: newValue,
      assignMembers: newValue,
      createFile: newValue,
      collaboration: newValue,
      reportIssue: newValue,
      codeView: newValue,
    });
  };

  const scrollToSection = (sectionId: string) => {
    // Map section IDs to state keys
    const sectionMap: Record<string, string> = {
      "section-workspace": "workspace",
      "section-billing": "billing",
      "section-invite": "inviteMembers",
      "section-accept": "acceptInvitation",
      "section-project": "project",
      "section-assign": "assignMembers",
      "section-file": "createFile",
      "section-collaboration": "collaboration",
      "section-report": "reportIssue",
      "section-codeview": "codeView",
    };

    // Collapse all sections and expand only the clicked one
    const sectionKey = sectionMap[sectionId];
    if (sectionKey) {
      setExpandedSections({
        workspace: false,
        billing: false,
        inviteMembers: false,
        acceptInvitation: false,
        project: false,
        assignMembers: false,
        createFile: false,
        collaboration: false,
        reportIssue: false,
        codeView: false,
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
            creating a workspace, then invite workspace members, and progress through creating projects and files. Each
            section includes positive scenarios (what to do) and negative scenarios (what to avoid) to help you work
            efficiently.
          </p>

          {/* Table of Contents Navigation */}
          <div style={styles.tableOfContents}>
            <h3 style={styles.tocTitle}>Quick Navigation</h3>
            <div style={styles.tocGrid}>
              {!desktop && (
                <div style={styles.tocItem} onClick={() => scrollToSection("section-workspace")}>
                  <Building2 size={18} style={{ color: "#f59e0b" }} />
                  <span>1. Create a Workspace</span>
                </div>
              )}
              {!desktop && (
                <div style={styles.tocItem} onClick={() => scrollToSection("section-billing")}>
                  <CreditCard size={18} style={{ color: "#10b981" }} />
                  <span>2. Subscription &amp; Billing</span>
                </div>
              )}
              {!desktop && (
                <div style={styles.tocItem} onClick={() => scrollToSection("section-invite")}>
                  <UserPlus size={18} style={{ color: "#8b5cf6" }} />
                  <span>3. Invite Members</span>
                </div>
              )}
              {!desktop && (
                <div style={styles.tocItem} onClick={() => scrollToSection("section-accept")}>
                  <Mail size={18} style={{ color: "#06b6d4" }} />
                  <span>4. Accept Invitation</span>
                </div>
              )}
              <div style={styles.tocItem} onClick={() => scrollToSection("section-project")}>
                <FolderPlus size={18} style={{ color: "#10b981" }} />
                <span>5. Create a Project</span>
              </div>
              {!desktop && (
                <div style={styles.tocItem} onClick={() => scrollToSection("section-assign")}>
                  <Users size={18} style={{ color: "#ec4899" }} />
                  <span>6. Assign Project Members</span>
                </div>
              )}
              <div style={styles.tocItem} onClick={() => scrollToSection("section-file")}>
                <FileText size={18} style={{ color: "#3b82f6" }} />
                <span>7. Create a New File</span>
              </div>
              <div style={styles.tocItem} onClick={() => scrollToSection("section-collaboration")}>
                <Share2 size={18} style={{ color: "#8b5cf6" }} />
                <span>8. Collaboration</span>
              </div>
              <div style={styles.tocItem} onClick={() => scrollToSection("section-report")}>
                <Bug size={18} style={{ color: "#ef4444" }} />
                <span>8. Report an Issue</span>
              </div>
              <div style={styles.tocItem} onClick={() => scrollToSection("section-codeview")}>
                <Code size={18} style={{ color: "#06b6d4" }} />
                <span>10. Code View</span>
              </div>
            </div>
          </div>

          {!desktop && (
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
                      <strong>After signup:</strong> Completing registration does not create a workspace by itself. After
                      you verify your email and sign in, use <strong>Create New Workspace</strong> on the workspace
                      selector to add your first workspace.
                    </li>
                    <li>
                      <strong>Additional Workspaces:</strong> Click "Create New Workspace" from workspace selector dropdown
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
                      <strong>Workspace Details:</strong> Enter a workspace name and optional description
                    </li>
                    <li>
                      <strong>Account Subscription:</strong> Upgrade your account plan later from Billing when you need higher member or project limits
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
                      Description: Academic research in genomics ontologies
                      <br />
                      Subscription: Upgrade from Billing if the team outgrows Free limits
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
                      <strong>Duplicate Registration:</strong> ❌ Each email can only create one account - use workspace
                      invitations instead
                    </li>
                    <li>
                      <strong>No Workspace Management:</strong> ❌ Neglecting to delete unused workspaces
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
                    <li>🔹 Workspaces you own share your account subscription and billing</li>
                    <li>🔹 Invite members up to your plan limit</li>
                    <li>🔹 Workspace data is isolated and secure</li>
                  </ul>
                </div>
              </div>
            )}
          </div>
          )}

          {!desktop && (
          <div id="section-billing" style={styles.section}>
            <button onClick={() => toggleSection("billing")} style={styles.sectionHeader}>
              {expandedSections.billing ? <ChevronDown size={20} /> : <ChevronRight size={20} />}
              <CreditCard size={20} style={{ color: "#10b981" }} />
              <span style={styles.sectionTitle}>2. Subscription &amp; Billing</span>
            </button>

            {expandedSections.billing && (
              <div style={styles.sectionContent}>
                <div style={styles.subsection}>
                  <h4 style={styles.subsectionTitle}>
                    <CheckCircle size={16} style={{ color: "#10b981" }} />
                    Plans &amp; Pricing
                  </h4>
                  <div style={styles.infoBox}>
                    <strong>Available Plans:</strong>
                    <ul style={styles.roleList}>
                      <li><strong>FREE:</strong> Limited members &amp; projects — ideal for exploring OntoCode</li>
                      <li><strong>PRO:</strong> Increased member limits, priority support, monthly or annual billing — annual discount available</li>
                      <li><strong>ENTERPRISE:</strong> Unlimited members, custom limits, annual discount available</li>
                    </ul>
                  </div>
                  <ul style={styles.list}>
                    <li>
                      <strong>14-Day Free Trial:</strong> New subscribers on PRO get a 14-day trial — no
                      charge until the trial ends. The trial is a one-time offer per account.
                    </li>
                    <li>
                      <strong>Annual Billing:</strong> Choose yearly billing during checkout to receive a discount
                      compared to the equivalent monthly total
                    </li>
                    <li>
                      <strong>Account-level subscription:</strong> Billing is tied to the workspace owner&apos;s
                      account. All workspaces that person owns inherit that plan; if the account subscription lapses,
                      paid access to those workspaces is suspended until billing is current again.
                    </li>
                  </ul>
                </div>

                <div style={styles.subsection}>
                  <h4 style={styles.subsectionTitle}>
                    <CheckCircle size={16} style={{ color: "#10b981" }} />
                    Positive Cases (Recommended)
                  </h4>

                  <div style={{ marginBottom: "20px", paddingLeft: "12px", borderLeft: "3px solid #10b981" }}>
                    <h5 style={{ fontSize: "14px", fontWeight: "600", color: "#1f2937", marginBottom: "8px" }}>
                      Subscribing to a Plan
                    </h5>
                    <ul style={styles.list}>
                      <li>
                        <strong>Access Billing:</strong> Go to Workspace Settings → Billing tab
                      </li>
                      <li>
                        <strong>Choose Plan:</strong> Select PRO or ENTERPRISE and your billing interval
                        (Monthly / Yearly)
                      </li>
                      <li>
                        <strong>Enter Card Details:</strong> Payment is handled securely by Stripe — your card data
                        never touches OntoCode servers
                      </li>
                      <li>
                        <strong>Confirm Subscription:</strong> After payment, your workspace is immediately upgraded and
                        all members gain access to the new plan features
                      </li>
                    </ul>
                  </div>

                  <div style={{ marginBottom: "20px", paddingLeft: "12px", borderLeft: "3px solid #3b82f6" }}>
                    <h5 style={{ fontSize: "14px", fontWeight: "600", color: "#1f2937", marginBottom: "8px" }}>
                      Managing Your Subscription
                    </h5>
                    <ul style={styles.list}>
                      <li>
                        <strong>Update Payment Method:</strong> Go to Billing → Update Card to add a new payment method
                        via Stripe
                      </li>
                      <li>
                        <strong>Billing Portal:</strong> Click "Manage Billing" to open the Stripe customer portal —
                        view invoices, download receipts, and update payment details
                      </li>
                      <li>
                        <strong>Auto-Renewal:</strong> Enabled by default. You can disable auto-renewal at any time —
                        your subscription stays active until the end of the current billing period
                      </li>
                      <li>
                        <strong>Re-enable Auto-Renewal:</strong> If you disabled auto-renewal, you can turn it back on
                        before the period ends to continue uninterrupted
                      </li>
                    </ul>
                  </div>

                  <div style={styles.example}>
                    <strong>Recommended Flow:</strong>
                    <div style={styles.exampleContent}>
                      Start FREE → Explore features → Upgrade to PRO with annual billing (save ~20%)
                      <br />
                      Use 14-day trial → Confirm subscription → Access full plan features immediately
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
                      <strong>Trial Used:</strong> The 14-day free trial is a one-time offer per account — it cannot
                      be restarted or applied to a second subscription
                    </li>
                    <li>
                      <strong>Only Owner Can Cancel:</strong> Only the workspace owner can cancel or modify the
                      workspace subscription — members and editors cannot
                    </li>
                    <li>
                      <strong>Cancel Plan:</strong> Billing → Cancel Plan turns off auto-renewal. Your paid access
                      continues until the end of the current billing period
                    </li>
                    <li>
                      <strong>Payment Failure:</strong> If a payment fails, workspace access may be suspended — update
                      your payment method promptly via the Billing portal
                    </li>
                    <li>
                      <strong>No Downgrades:</strong> OntoCode does not support moving to a lower paid plan. You can
                      keep your current plan, upgrade, or cancel renewal at period end
                    </li>
                  </ul>

                  <div style={styles.warning}>
                    <AlertCircle size={16} />
                    <span>
                      <strong>Important:</strong> The workspace owner's subscription status affects all workspace
                      members. If the owner's plan expires, all members lose access to that workspace.
                    </span>
                  </div>
                </div>

                <div style={styles.subsection}>
                  <h4 style={styles.subsectionTitle}>
                    <Users size={16} style={{ color: "#8b5cf6" }} />
                    Possibilities &amp; Features
                  </h4>
                  <ul style={styles.list}>
                    <li>Switch between monthly and yearly billing by resubscribing</li>
                    <li>Stripe billing portal provides full invoice history and PDF receipts</li>
                    <li>Auto-renewal can be toggled without canceling your current subscription</li>
                    <li>Subscription status is visible on the Billing page (Active / Trialing / Expired)</li>
                  </ul>
                </div>
              </div>
            )}
          </div>
          )}

          {!desktop && (
          <div id="section-invite" style={styles.section}>
            <button onClick={() => toggleSection("inviteMembers")} style={styles.sectionHeader}>
              {expandedSections.inviteMembers ? <ChevronDown size={20} /> : <ChevronRight size={20} />}
              <UserPlus size={20} style={{ color: "#ec4899" }} />
              <span style={styles.sectionTitle}>3. How to Invite Members & Who Can Invite</span>
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
                      <strong>Required Info:</strong> Provide the invitee email address and workspace role (Admin, Member, or Viewer)
                    </li>
                    <li>
                      <strong>Email Validation:</strong> Ensure email is valid and belongs to intended recipient
                    </li>
                    <li>
                      <strong>Role Selection:</strong> Start with Viewer for new workspace members and promote to Admin or Member as needed
                    </li>
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
                      <strong>Privacy:</strong> Invited users join the workspace. Project access still depends on each project's sharing settings.
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
                  </ul>
                </div>
              </div>
            )}
          </div>
          )}

          {!desktop && (
          <div id="section-accept" style={styles.section}>
            <button onClick={() => toggleSection("acceptInvitation")} style={styles.sectionHeader}>
              {expandedSections.acceptInvitation ? <ChevronDown size={20} /> : <ChevronRight size={20} />}
              <Mail size={20} style={{ color: "#14b8a6" }} />
              <span style={styles.sectionTitle}>4. How to Accept an Invitation</span>
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
                      <strong>Click Invitation Link:</strong> Click the invitation link in the email — it will open in
                      your browser or webview
                    </li>
                    <li>
                      <strong>Login or Sign Up:</strong> If you already have an account, log in; if you're new, create
                      an account using the same email address the invitation was sent to
                    </li>
                    <li>
                      <strong>Review Details:</strong> Check workspace name, your assigned role, and inviter details
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
                      1. Email notification → 2. Click link (opens in browser/webview)
                      <br />
                      3. Login or Sign Up → 4. Review workspace info → 5. Accept invitation → 6. Access workspace
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
          )}

          {/* Section 4: How to Create a Project */}
          <div id="section-project" style={styles.section}>
            <button onClick={() => toggleSection("project")} style={styles.sectionHeader}>
              {expandedSections.project ? <ChevronDown size={20} /> : <ChevronRight size={20} />}
              <FolderPlus size={20} style={{ color: "#10b981" }} />
              <span style={styles.sectionTitle}>5. How to Create a Project</span>
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
                      <strong>Share with:</strong> Choose Only me, All Workspace Members, or Specific Members when creating the project
                    </li>
                    <li>
                      <strong>Specific Members:</strong> Select workspace members and set each person's project role (Viewer or Editor) before you create the project
                    </li>
                    <li>
                      <strong>Initial Members:</strong> You can also add or change project members later in Project Settings
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
                      <strong>Missing Access:</strong> ❌ Workspace viewers cannot create projects; on Free plan only the workspace owner can create projects
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
                    <li>🔹 Delete projects (owners and workspace admins can delete)</li>
                  </ul>
                </div>
              </div>
            )}
          </div>

          {!desktop && (
          <div id="section-assign" style={styles.section}>
            <button onClick={() => toggleSection("assignMembers")} style={styles.sectionHeader}>
              {expandedSections.assignMembers ? <ChevronDown size={20} /> : <ChevronRight size={20} />}
              <Users size={20} style={{ color: "#3b82f6" }} />
              <span style={styles.sectionTitle}>6. How to Assign Project Members</span>
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
                      <strong>Role Assignment:</strong> Choose Viewer or Editor when adding a member. The project creator is Owner.
                    </li>
                    <li>
                      <strong>Per-Member Roles:</strong> Each project member keeps their own role; changing one member does not change others
                    </li>
                    <li>
                      <strong>Role Clarity:</strong> Ensure roles match actual responsibilities
                    </li>
                  </ul>

                  <div style={styles.infoBox}>
                    <strong>Role Permissions:</strong>
                    <ul style={styles.roleList}>
                      <li>
                        <strong>Owner:</strong> Project creator with full control over the project
                      </li>
                      <li>
                        <strong>Admin:</strong> Can manage project settings and members
                      </li>
                      <li>
                        <strong>Editor:</strong> Can edit ontology content in the project
                      </li>
                      <li>
                        <strong>Viewer:</strong> Read-only access to the project
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
                      <strong>Insufficient Permissions:</strong> ❌ Only the project owner/admin or workspace owner/admin can manage project members
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
          )}

          {/* Section 6: How to Create a New File */}
          <div id="section-file" style={styles.section}>
            <button onClick={() => toggleSection("createFile")} style={styles.sectionHeader}>
              {expandedSections.createFile ? <ChevronDown size={20} /> : <ChevronRight size={20} />}
              <FileText size={20} style={{ color: "#8b5cf6" }} />
              <span style={styles.sectionTitle}>7. How to Create a New File</span>
            </button>

            {expandedSections.createFile && (
              <div style={styles.sectionContent}>
                <div style={styles.subsection}>
                  <h4 style={styles.subsectionTitle}>
                    <CheckCircle size={16} style={{ color: "#10b981" }} />
                    Positive Cases (Recommended) - Multiple Ways to Create Files
                  </h4>

                  <div style={{ marginBottom: "20px", paddingLeft: "12px", borderLeft: "3px solid #3b82f6" }}>
                    <h5 style={{ fontSize: "14px", fontWeight: "600", color: "#1f2937", marginBottom: "8px" }}>
                      📁 From Project Library
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
                  </div>

                  <div style={{ marginBottom: "20px", paddingLeft: "12px", borderLeft: "3px solid #10b981" }}>
                    <h5 style={{ fontSize: "14px", fontWeight: "600", color: "#1f2937", marginBottom: "8px" }}>
                      ✏️ From Within the Editor
                    </h5>
                    <ul style={styles.list}>
                      <li>
                        <strong>Open a File:</strong> Click "File" → "Open" — the dialog lists all files in your
                        current project. Select a file to open it in the editor
                      </li>
                      <li>
                        <strong>Upload a File:</strong> Use the upload option in the file dialog to upload a file from
                        your computer — it is added directly to the current project
                      </li>
                      <li>
                        <strong>Create New File:</strong> Click "File" → "New File" to create a new file — it is added
                        to the current project and opens automatically in the editor
                      </li>
                    </ul>
                  </div>

                  <div style={{ marginTop: "16px", padding: "12px", backgroundColor: "#f0f9ff", borderRadius: "6px" }}>
                    <p style={{ fontSize: "13px", color: "#075985", margin: 0 }}>
                      <strong>💡 Pro Tip:</strong> Files are created in the currently active project. Always verify
                      you're in the correct project before creating files.
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
                      while browsing projects. Once in the editor, use <strong>File → Open</strong> to open existing
                      files or <strong>File → New File</strong> to create new ones.
                    </p>
                  </div>
                </div>
              </div>
            )}
          </div>

          {/* Section 7: Collaboration */}
          <div id="section-collaboration" style={styles.section}>
            <button onClick={() => toggleSection("collaboration")} style={styles.sectionHeader}>
              {expandedSections.collaboration ? <ChevronDown size={20} /> : <ChevronRight size={20} />}
              <Share2 size={20} style={{ color: "#8b5cf6" }} />
              <span style={styles.sectionTitle}>8. Collaboration</span>
            </button>

            {expandedSections.collaboration && (
              <div style={styles.sectionContent}>
                <div style={styles.subsection}>
                  <h4 style={styles.subsectionTitle}>
                    <CheckCircle size={16} style={{ color: "#10b981" }} />
                    Positive Cases (Recommended)
                  </h4>

                  <div style={{ marginBottom: "20px", paddingLeft: "12px", borderLeft: "3px solid #8b5cf6" }}>
                    <h5 style={{ fontSize: "14px", fontWeight: "600", color: "#1f2937", marginBottom: "8px" }}>
                      👥 Real-Time Presence & Cursors
                    </h5>
                    <ul style={styles.list}>
                      <li>
                        <strong>See Who's Online:</strong> The Collaboration panel (bottom-right corner) shows all
                        active users in your project with colored avatars and their last activity time
                      </li>
                      <li>
                        <strong>Live Cursors:</strong> See collaborators' mouse positions on the canvas in real time —
                        each user gets a unique colored cursor with their name label
                      </li>
                      <li>
                        <strong>Connection Status:</strong> A green "Live" badge confirms you're connected to the
                        collaboration server; gray "Offline" means you're disconnected
                      </li>
                      <li>
                        <strong>Cursor Position Tracking:</strong> The Active Users list shows which ontology node each
                        collaborator is currently viewing
                      </li>
                    </ul>
                  </div>

                  <div style={{ marginBottom: "20px", paddingLeft: "12px", borderLeft: "3px solid #06b6d4" }}>
                    <h5 style={{ fontSize: "14px", fontWeight: "600", color: "#1f2937", marginBottom: "8px" }}>
                      ✏️ Collaborative Editing
                    </h5>
                    <ul style={styles.list}>
                      <li>
                        <strong>Real-Time Sync:</strong> All ontology changes (classes, properties, annotations,
                        individuals, axioms, SWRL rules) are propagated to all collaborators instantly
                      </li>
                    </ul>
                  </div>

                  <div style={{ marginBottom: "20px", paddingLeft: "12px", borderLeft: "3px solid #10b981" }}>
                    <h5 style={{ fontSize: "14px", fontWeight: "600", color: "#1f2937", marginBottom: "8px" }}>
                      📋 Change Tracking & Activity Feed
                    </h5>
                    <ul style={styles.list}>
                      <li>
                        <strong>Activity Feed:</strong> The Collaboration panel displays a scrollable list of recent
                        changes with color-coded indicators — green for additions, red for deletions, blue for edits
                      </li>
                      <li>
                        <strong>Diff View:</strong> Edit entries show old → new value differences so you can see exactly
                        what changed
                      </li>
                      <li>
                        <strong>Author & Timestamp:</strong> Every change entry shows who made it and when
                      </li>
                      <li>
                        <strong>Auto-Refresh:</strong> The activity feed refreshes automatically every 30 seconds and
                        can be manually refreshed
                      </li>
                    </ul>
                  </div>

                  <div style={{ paddingLeft: "12px", borderLeft: "3px solid #f59e0b" }}>
                    <h5 style={{ fontSize: "14px", fontWeight: "600", color: "#1f2937", marginBottom: "8px" }}>
                      🔧 Collaboration Panel
                    </h5>
                    <ul style={styles.list}>
                      <li>
                        <strong>Draggable Panel:</strong> The Collaboration panel can be dragged to any position on
                        screen — your preferred position is saved across sessions
                      </li>
                      <li>
                        <strong>Expand/Collapse:</strong> Click or double-click the panel header to toggle between
                        compact and expanded views
                      </li>
                    </ul>
                  </div>
                </div>

                <div style={styles.subsection}>
                  <h4 style={styles.subsectionTitle}>
                    <XCircle size={16} style={{ color: "#ef4444" }} />
                    Negative Cases (What to Avoid)
                  </h4>
                  <ul style={styles.list}>
                    <li>
                      <strong>Don't edit simultaneously without checking presence:</strong> Always check who's online
                      before making major changes to avoid unintended overwrites
                    </li>
                    <li>
                      <strong>Don't ignore the Offline indicator:</strong> If the connection badge shows "Offline", your
                      changes won't be visible to others until reconnected
                    </li>
                  </ul>
                </div>

                <div style={styles.subsection}>
                  <h4 style={styles.subsectionTitle}>
                    <AlertCircle size={16} style={{ color: "#f59e0b" }} />
                    Possibilities
                  </h4>
                  <ul style={styles.list}>
                    <li>
                      <strong>Auto-Reconnect:</strong> If your connection drops, OntoCode automatically reconnects and
                      re-syncs your collaboration session
                    </li>
                    <li>
                      <strong>Multi-User Scaling:</strong> Multiple workspace members can work on the same ontology project
                      simultaneously with real-time visibility
                    </li>
                    <li>
                      <strong>Import Progress:</strong> When a collaborator imports an ontology, you'll see real-time
                      progress updates
                    </li>
                    <li>
                      <strong>Cross-Platform:</strong> Collaboration works both in VS Code (via the extension) and in
                      the browser-based editor
                    </li>
                  </ul>
                </div>

                <div
                  style={{
                    padding: "12px",
                    backgroundColor: "#f5f3ff",
                    borderRadius: "6px",
                    borderLeft: "4px solid #8b5cf6",
                  }}
                >
                  <p style={{ fontSize: "13px", color: "#4c1d95", margin: 0 }}>
                    <strong>🎯 Recommendation:</strong> Keep the Collaboration panel visible while working in a team.
                    Check the <strong>Active Users</strong> list before making large-scale changes, and use the{" "}
                    <strong>Activity Feed</strong> to stay informed of recent modifications by your collaborators.
                  </p>
                </div>
              </div>
            )}
          </div>

          {/* Section 8: Report an Issue */}
          <div id="section-report" style={styles.section}>
            <button onClick={() => toggleSection("reportIssue")} style={styles.sectionHeader}>
              {expandedSections.reportIssue ? <ChevronDown size={20} /> : <ChevronRight size={20} />}
              <Bug size={20} style={{ color: "#ef4444" }} />
              <span style={styles.sectionTitle}>9. How to Report an Issue</span>
            </button>

            {expandedSections.reportIssue && (
              <div style={styles.sectionContent}>
                <div style={styles.subsection}>
                  <h4 style={styles.subsectionTitle}>
                    <CheckCircle size={16} style={{ color: "#10b981" }} />
                    Positive Cases (Recommended)
                  </h4>

                  <div style={{ marginBottom: "20px", paddingLeft: "12px", borderLeft: "3px solid #ef4444" }}>
                    <h5 style={{ fontSize: "14px", fontWeight: "600", color: "#1f2937", marginBottom: "8px" }}>
                      🐛 Opening the Report Issue Dialog
                    </h5>
                    <ul style={styles.list}>
                      <li>
                        <strong>From Help Menu:</strong> Click <strong>Help</strong> in the top menu bar, then select{" "}
                        <strong>Report Issue</strong> to open the issue reporting form
                      </li>
                      <li>
                        <strong>Project Context:</strong> The dialog automatically captures your current project name,
                        project ID, and active file path to provide context with your report
                      </li>
                      <li>
                        <strong>System Info:</strong> Your OS, VS Code version, and extension version are automatically
                        collected — no need to enter them manually
                      </li>
                    </ul>
                  </div>

                  <div style={{ marginBottom: "20px", paddingLeft: "12px", borderLeft: "3px solid #8b5cf6" }}>
                    <h5 style={{ fontSize: "14px", fontWeight: "600", color: "#1f2937", marginBottom: "8px" }}>
                      📝 Filling Out the Form
                    </h5>
                    <ul style={styles.list}>
                      <li>
                        <strong>Issue Type:</strong> Choose <strong>Bug</strong> for something broken or{" "}
                        <strong>Feature Request</strong> for a new feature or improvement
                      </li>
                      <li>
                        <strong>Priority:</strong> Select from Highest, High, Medium, Low, or Lowest to indicate urgency
                      </li>
                      <li>
                        <strong>Title:</strong> Provide a clear, concise summary of the issue (up to 200 characters)
                      </li>
                      <li>
                        <strong>Description:</strong> Describe the issue in detail — include what you expected vs. what
                        actually happened
                      </li>
                      <li>
                        <strong>Steps to Reproduce:</strong> (Optional) List the exact steps to recreate the issue, e.g.
                        "1. Go to... 2. Click on... 3. See error..."
                      </li>
                    </ul>
                  </div>

                  <div style={{ marginBottom: "20px", paddingLeft: "12px", borderLeft: "3px solid #10b981" }}>
                    <h5 style={{ fontSize: "14px", fontWeight: "600", color: "#1f2937", marginBottom: "8px" }}>
                      📎 Attaching Files
                    </h5>
                    <ul style={styles.list}>
                      <li>
                        <strong>Drag & Drop:</strong> Drag files directly onto the attachment area to upload them
                      </li>
                      <li>
                        <strong>File Browser:</strong> Click "Choose files to upload" to select files from your computer
                      </li>
                      <li>
                        <strong>Supported Formats:</strong> Images (JPG, PNG, GIF, SVG, WebP), documents (PDF, DOC,
                        DOCX, TXT), logs (.log), and ontology files (.owl, .ttl, .rdf)
                      </li>
                      <li>
                        <strong>Preview:</strong> Attached images and text files show a preview thumbnail — you can
                        remove any attachment by hovering and clicking the remove button
                      </li>
                    </ul>
                  </div>

                  <div style={{ paddingLeft: "12px", borderLeft: "3px solid #f59e0b" }}>
                    <h5 style={{ fontSize: "14px", fontWeight: "600", color: "#1f2937", marginBottom: "8px" }}>
                      ✅ Submitting the Report
                    </h5>
                    <ul style={styles.list}>
                      <li>
                        <strong>Submit:</strong> Click <strong>Submit Issue Report</strong> — the button is disabled
                        until both Title and Description are filled in
                      </li>
                      <li>
                        <strong>Confirmation:</strong> A success message appears with the issue type and priority
                        badges. The dialog closes automatically after 3 seconds
                      </li>
                      <li>
                        <strong>Jira Integration:</strong> If enabled, your report is automatically created as a Jira
                        ticket with all attachments uploaded
                      </li>
                    </ul>
                  </div>
                </div>

                <div style={styles.subsection}>
                  <h4 style={styles.subsectionTitle}>
                    <XCircle size={16} style={{ color: "#ef4444" }} />
                    Negative Cases (What to Avoid)
                  </h4>
                  <ul style={styles.list}>
                    <li>
                      <strong>Don't leave Title or Description empty:</strong> Both are required fields — the submit
                      button won't be enabled without them
                    </li>
                    <li>
                      <strong>Don't use vague titles:</strong> "It's broken" is unhelpful — instead, describe what
                      specifically failed (e.g., "Class creation fails when name contains special characters")
                    </li>
                    <li>
                      <strong>Don't attach unsupported file types:</strong> Only images, documents, text/log files, and
                      ontology files are accepted
                    </li>
                    <li>
                      <strong>Don't skip steps to reproduce:</strong> Even though optional, including reproduction steps
                      greatly helps the team diagnose and fix the issue faster
                    </li>
                  </ul>
                </div>

                <div style={styles.subsection}>
                  <h4 style={styles.subsectionTitle}>
                    <AlertCircle size={16} style={{ color: "#f59e0b" }} />
                    Possibilities
                  </h4>
                  <ul style={styles.list}>
                    <li>
                      <strong>Offline Fallback:</strong> If the Jira connection is unavailable, your report is saved
                      locally and the team is notified
                    </li>
                    <li>
                      <strong>Multiple Attachments:</strong> You can attach several files at once — screenshots, log
                      files, and ontology files can all be included in a single report
                    </li>
                    <li>
                      <strong>Priority Auto-Detection:</strong> If you don't set a priority, the system can
                      automatically determine one based on the issue content
                    </li>
                    <li>
                      <strong>Cross-Platform:</strong> Report issues from both VS Code and the browser-based editor —
                      system information is collected automatically in both environments
                    </li>
                  </ul>
                </div>

                <div
                  style={{
                    padding: "12px",
                    backgroundColor: "#fef2f2",
                    borderRadius: "6px",
                    borderLeft: "4px solid #ef4444",
                  }}
                >
                  <p style={{ fontSize: "13px", color: "#991b1b", margin: 0 }}>
                    <strong>🎯 Recommendation:</strong> Always include <strong>Steps to Reproduce</strong> and attach a{" "}
                    <strong>screenshot</strong> when reporting bugs. The more detail you provide, the faster the issue
                    can be resolved.
                  </p>
                </div>
              </div>
            )}
          </div>

          {/* Section 9: Code View */}
          <div id="section-codeview" style={styles.section}>
            <button onClick={() => toggleSection("codeView")} style={styles.sectionHeader}>
              {expandedSections.codeView ? <ChevronDown size={20} /> : <ChevronRight size={20} />}
              <Code size={20} style={{ color: "#06b6d4" }} />
              <span style={styles.sectionTitle}>10. Code View</span>
            </button>

            {expandedSections.codeView && (
              <div style={styles.sectionContent}>
                <div style={styles.subsection}>
                  <h4 style={styles.subsectionTitle}>
                    <CheckCircle size={16} style={{ color: "#10b981" }} />
                    Positive Cases (Recommended)
                  </h4>

                  <div style={{ marginBottom: "20px", paddingLeft: "12px", borderLeft: "3px solid #06b6d4" }}>
                    <h5 style={{ fontSize: "14px", fontWeight: "600", color: "#1f2937", marginBottom: "8px" }}>
                      📄 Accessing & Switching Formats
                    </h5>
                    <ul style={styles.list}>
                      <li>
                        <strong>Open Code View:</strong> Click the <strong>CodeView</strong> tab in the main navigation
                        to view your ontology as serialized code
                      </li>
                      <li>
                        <strong>Six Formats Available:</strong> Switch between <strong>Turtle</strong> (.ttl),{" "}
                        <strong>RDF/XML</strong> (.rdf), <strong>N-Triples</strong> (.nt), <strong>OWL/XML</strong>{" "}
                        (.owl), <strong>Manchester</strong> (.omn), and <strong>Functional</strong> (.ofn) using the
                        format buttons in the toolbar
                      </li>
                      <li>
                        <strong>Active Format:</strong> The currently selected format button is highlighted in purple —
                        click any other format to switch instantly
                      </li>
                      <li>
                        <strong>Syntax Highlighting:</strong> Each format has specialized color-coding — keywords in
                        blue, URIs in teal, strings in tan, comments in green, and XML tags in their respective colors
                      </li>
                    </ul>
                  </div>

                  <div style={{ marginBottom: "20px", paddingLeft: "12px", borderLeft: "3px solid #8b5cf6" }}>
                    <h5 style={{ fontSize: "14px", fontWeight: "600", color: "#1f2937", marginBottom: "8px" }}>
                      ✏️ View Mode vs Edit Mode
                    </h5>
                    <ul style={styles.list}>
                      <li>
                        <strong>View Mode (Default):</strong> Read-only display with line numbers and syntax
                        highlighting — ideal for examining your ontology structure
                      </li>
                      <li>
                        <strong>Edit Mode:</strong> Click the <strong>Edit</strong> button to enable direct code editing
                        with a textarea editor, line numbers gutter, and fold indicators
                      </li>
                      <li>
                        <strong>Cursor Position:</strong> In edit mode, a status indicator at the bottom-right shows
                        your current line and column (e.g., "Ln 42, Col 15")
                      </li>
                      <li>
                        <strong>Auto-Indentation:</strong> Press Tab to insert 4 spaces for consistent code formatting
                      </li>
                      <li>
                        <strong>Switch Back:</strong> Click <strong>View</strong> to return to read-only mode
                      </li>
                    </ul>
                  </div>

                  <div style={{ marginBottom: "20px", paddingLeft: "12px", borderLeft: "3px solid #10b981" }}>
                    <h5 style={{ fontSize: "14px", fontWeight: "600", color: "#1f2937", marginBottom: "8px" }}>
                      🔍 Search & Navigation
                    </h5>
                    <ul style={styles.list}>
                      <li>
                        <strong>Search Bar:</strong> Type text to search through the code — matches are highlighted in
                        amber with a match counter (e.g., "3 of 12")
                      </li>
                      <li>
                        <strong>Navigate Matches:</strong> Use the Previous/Next arrows (or Enter / Shift+Enter) to jump
                        between search results
                      </li>
                      <li>
                        <strong>Case Sensitivity:</strong> Toggle the "Aa" button to enable or disable case-sensitive
                        searching
                      </li>
                      <li>
                        <strong>Search Results Panel:</strong> Expand the results panel to see all matches with line
                        numbers and code previews — click any result to jump directly to that line
                      </li>
                      <li>
                        <strong>Go to Line:</strong> Enter a line number in the "Go to:" field and press Go or Enter to
                        jump to a specific line
                      </li>
                    </ul>
                  </div>

                  <div style={{ marginBottom: "20px", paddingLeft: "12px", borderLeft: "3px solid #f59e0b" }}>
                    <h5 style={{ fontSize: "14px", fontWeight: "600", color: "#1f2937", marginBottom: "8px" }}>
                      📁 Code Folding
                    </h5>
                    <ul style={styles.list}>
                      <li>
                        <strong>Fold Indicators:</strong> Foldable regions (bracket pairs, XML tags) show arrow
                        indicators (▶/▼) in the gutter — click to collapse or expand
                      </li>
                      <li>
                        <strong>Collapsed Summary:</strong> Folded regions display "⋯ N lines" to show how many lines
                        are hidden
                      </li>
                      <li>
                        <strong>Works in Both Modes:</strong> Code folding is available in view mode and edit mode —
                        entering edit mode automatically expands all folds
                      </li>
                    </ul>
                  </div>

                  <div style={{ marginBottom: "20px", paddingLeft: "12px", borderLeft: "3px solid #ec4899" }}>
                    <h5 style={{ fontSize: "14px", fontWeight: "600", color: "#1f2937", marginBottom: "8px" }}>
                      📚 Citation Management (Zotero Integration)
                    </h5>
                    <ul style={styles.list}>
                      <li>
                        <strong>Insert from Zotero:</strong> Click <strong>📚 Zotero Citation</strong> in the toolbar to
                        browse your Zotero library and select a citation — then click on a code line to insert it at
                        that location
                      </li>
                      <li>
                        <strong>Manual Citation:</strong> Click <strong>✏️ Manual Citation</strong> to enter citation
                        metadata manually (Title, Authors, Year, DOI, etc.) and insert it into the code
                      </li>
                      <li>
                        <strong>Insertion Mode:</strong> A blue banner indicates insertion mode is active — use the
                        search to find the right location, then click a line number to place the citation
                      </li>
                      <li>
                        <strong>Cross-Format Sync:</strong> Citations are automatically inserted across all six formats
                        simultaneously
                      </li>
                      <li>
                        <strong>Remove Citations:</strong> Click <strong>🗑️ Remove Citation</strong> — citation blocks
                        are highlighted in red. Click any highlighted line to remove the entire citation block from all
                        formats
                      </li>
                      <li>
                        <strong>DOI Links:</strong> DOI values in citations are automatically rendered as clickable
                        hyperlinks
                      </li>
                    </ul>
                  </div>

                  <div style={{ paddingLeft: "12px", borderLeft: "3px solid #3b82f6" }}>
                    <h5 style={{ fontSize: "14px", fontWeight: "600", color: "#1f2937", marginBottom: "8px" }}>
                      💾 Copy, Download & Word Wrap
                    </h5>
                    <ul style={styles.list}>
                      <li>
                        <strong>Copy All:</strong> Click the copy button to copy the entire ontology code in the current
                        format to your clipboard
                      </li>
                      <li>
                        <strong>Download:</strong> Click the download button to save the ontology as a file in the
                        current format (e.g., ontology.ttl, ontology.rdf)
                      </li>
                      <li>
                        <strong>Word Wrap:</strong> Toggle word wrap on/off to control whether long lines wrap to the
                        visible width or scroll horizontally
                      </li>
                    </ul>
                  </div>
                </div>

                <div style={styles.subsection}>
                  <h4 style={styles.subsectionTitle}>
                    <XCircle size={16} style={{ color: "#ef4444" }} />
                    Negative Cases (What to Avoid)
                  </h4>
                  <ul style={styles.list}>
                    <li>
                      <strong>Don't edit while in citation mode:</strong> Edit mode is disabled when citation insertion
                      or removal mode is active — cancel citation mode first
                    </li>
                    <li>
                      <strong>Don't switch formats with unsaved edits:</strong> Switching formats discards any
                      uncommitted edit-mode changes — save your work before switching
                    </li>
                    <li>
                      <strong>Don't remove citations by deleting lines manually:</strong> Use the Remove Citation mode
                      instead, which ensures the citation is properly removed from all formats
                    </li>
                    <li>
                      <strong>Don't search extremely large ontologies without patience:</strong> Searching files with
                      10,000+ lines may take a moment — a progress bar will show the search status
                    </li>
                  </ul>
                </div>

                <div style={styles.subsection}>
                  <h4 style={styles.subsectionTitle}>
                    <AlertCircle size={16} style={{ color: "#f59e0b" }} />
                    Possibilities
                  </h4>
                  <ul style={styles.list}>
                    <li>
                      <strong>Progressive Loading:</strong> Large ontologies load the first 500 lines initially — click
                      "Load More" at the bottom to load additional content in chunks
                    </li>
                    <li>
                      <strong>Format Comparison:</strong> Quickly switch between formats to compare how the same
                      ontology is represented in different serializations
                    </li>
                    <li>
                      <strong>Citation Highlighting:</strong> Lines containing DOI citations are highlighted with a
                      green background tint for easy identification
                    </li>
                    <li>
                      <strong>Clickable Line Numbers:</strong> In citation modes, line numbers become clickable targets
                      — green for DOI lines, red for citation blocks in removal mode
                    </li>
                  </ul>
                </div>

                <div
                  style={{
                    padding: "12px",
                    backgroundColor: "#ecfeff",
                    borderRadius: "6px",
                    borderLeft: "4px solid #06b6d4",
                  }}
                >
                  <p style={{ fontSize: "13px", color: "#164e63", margin: 0 }}>
                    <strong>🎯 Recommendation:</strong> Use <strong>Turtle</strong> format for the most human-readable
                    view of your ontology. Use the <strong>Search</strong> feature to quickly locate entities, and
                    leverage <strong>Code Folding</strong> to collapse large sections when navigating complex
                    ontologies.
                  </p>
                </div>
              </div>
            )}
          </div>

          {/* Quick Reference */}
          <div style={styles.quickReference}>
            <h3 style={styles.quickRefTitle}>Quick Reference</h3>
            <div style={styles.quickRefGrid}>
              <div style={styles.quickRefCard}>
                <strong>Billing</strong>
                <p style={styles.quickRefText}>Workspace Settings → Billing — manage plan, card &amp; invoices</p>
              </div>
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

<div align="center">

# **OntoCode Platform**
## **Workspace & Non-Workspace Flow**
### **Technical Specification Document**

---

**Document Version:** 1.0.0  
**Release Date:** January 19, 2026  
**Classification:** Internal - Technical Documentation  
**Author:** OntoCode Development Team  
**Status:** Final

---

</div>

<div style="page-break-after: always;"></div>

## **Document Control**

| **Version** | **Date** | **Author** | **Changes** |
|-------------|----------|------------|-------------|
| 1.0.0 | January 19, 2026 | OntoCode Team | Initial release |

## **Distribution List**

- Development Team
- Product Management
- Quality Assurance
- Technical Support
- System Administrators

## **Document Purpose**

This document provides comprehensive technical specifications for OntoCode's dual operational modes: Workspace Mode (team collaboration) and Non-Workspace Mode (individual usage). It describes database models, API endpoints, authentication flows, and user workflows for both modes.

## **Intended Audience**

- Backend Developers
- Frontend Developers
- System Architects
- QA Engineers
- Technical Documentation Writers
- Integration Partners

<div style="page-break-after: always;"></div>

## **Table of Contents**

1. [Executive Summary](#1-executive-summary)
2. [System Overview](#2-system-overview)
3. [User Roles & Permissions](#3-user-roles--permissions)
4. [Workspace Flow (Team Collaboration)](#4-workspace-flow-team-collaboration)
5. [Non-Workspace Flow (Individual Usage)](#5-non-workspace-flow-individual-usage)
6. [Database Schema Specification](#6-database-schema-specification)
7. [API Reference](#7-api-reference)
8. [Authentication & Authorization](#8-authentication--authorization)
9. [Use Cases & Scenarios](#9-use-cases--scenarios)
10. [Feature Comparison Matrix](#10-feature-comparison-matrix)
11. [Migration Path](#11-migration-path)
12. [Technical Architecture](#12-technical-architecture)
13. [Security Specifications](#13-security-specifications)
14. [Performance Optimization](#14-performance-optimization)
15. [Appendices](#15-appendices)

<div style="page-break-after: always;"></div>

## **1. Executive Summary**

### **1.1 Document Overview**

OntoCode is an advanced ontology management platform that supports two distinct operational modes, designed to accommodate diverse user requirements ranging from individual researchers to large collaborative teams. This document specifies the technical implementation, database architecture, API design, and operational workflows for both modes.

### **1.2 System Capabilities**

**Workspace Mode (Collaborative Environment)**
- Multi-user collaboration with role-based access control
- Centralized project management and governance
- Real-time synchronization using Yjs CRDT
- GraphDB integration for semantic queries
- Comprehensive audit trails and version control

**Non-Workspace Mode (Individual Environment)**
- Personal file management without organizational overhead
- Selective file sharing capabilities
- Simplified user interface and onboarding
- Seamless upgrade path to workspace mode
- Maintains privacy and data isolation

### **1.3 Key Benefits**

| **Benefit** | **Description** |
|-------------|-----------------|
| **Flexibility** | Users choose between individual or collaborative workflows |
| **Scalability** | Supports single users to enterprise teams (1000+ members) |
| **Interoperability** | GraphDB integration enables SPARQL queries across ontologies |
| **Security** | JWT authentication, RBAC, encrypted data storage |
| **Performance** | MongoDB indexing, Redis caching, WebSocket real-time updates |

<div style="page-break-after: always;"></div>

---

## **2. System Overview**

### **2.1 Operational Modes**

#### **2.1.1 Workspace Mode (Team Collaboration)**

Workspace Mode is engineered for organizations and teams requiring structured collaboration on ontology projects. It provides enterprise-grade features including:

- **Centralized Management:** Single workspace owner controls all organizational resources
- **Role-Based Access Control:** Granular permissions at workspace, project, and file levels
- **Project Organization:** Hierarchical structure (Workspace → Projects → Files)
- **Real-Time Collaboration:** Concurrent editing with conflict-free replicated data types (CRDTs)
- **Integration:** MongoDB for metadata, GraphDB for ontology triples

#### **2.1.2 Non-Workspace Mode (Individual Usage)**

Non-Workspace Mode is optimized for individual users requiring lightweight personal file management without organizational complexity:

- **Personal File Management:** Direct file upload without workspace creation
- **Privacy:** Files remain private unless explicitly shared
- **Selective Sharing:** Fine-grained sharing with specific users
- **Simplified Interface:** Streamlined dashboard with minimal configuration
- **Upgrade Path:** Seamless migration to workspace mode when needed

### **2.2 System Architecture Diagram**

```
┌─────────────────────────────────────────────────────────────┐
│                     OntoCode Platform                       │
├─────────────────────────────────────────────────────────────┤
│                                                             │
│  ┌──────────────────┐         ┌──────────────────┐        │
│  │  Workspace Mode  │         │ Non-Workspace    │        │
│  │  (Team Collab)   │         │ (Individual)     │        │
│  └────────┬─────────┘         └────────┬─────────┘        │
│           │                            │                   │
│           └────────────┬───────────────┘                   │
│                        │                                   │
│           ┌────────────▼───────────────┐                   │
│           │   API Gateway (Nginx)      │                   │
│           └────────────┬───────────────┘                   │
│                        │                                   │
│     ┌──────────────────┼──────────────────┐               │
│     │                  │                  │               │
│ ┌───▼────┐      ┌──────▼──────┐    ┌─────▼─────┐         │
│ │ Auth   │      │   Editor    │    │  Plugin   │         │
│ │Service │      │   Service   │    │  Service  │         │
│ └───┬────┘      └──────┬──────┘    └─────┬─────┘         │
│     │                  │                  │               │
│     └──────────────────┼──────────────────┘               │
│                        │                                   │
│           ┌────────────▼───────────────┐                   │
│           │     Data Layer             │                   │
│           │  ┌──────────┐ ┌─────────┐ │                   │
│           │  │ MongoDB  │ │ GraphDB │ │                   │
│           │  │(Metadata)│ │(Triples)│ │                   │
│           │  └──────────┘ └─────────┘ │                   │
│           └────────────────────────────┘                   │
│                                                             │
└─────────────────────────────────────────────────────────────┘
```

<div style="page-break-after: always;"></div>

---

## **3. User Roles & Permissions**

### **3.1 Role Hierarchy Specification**

The OntoCode platform implements a hierarchical role-based access control (RBAC) system with clearly defined permissions at each level. The following table specifies all system roles and their capabilities:

```
SYSTEM_ADMIN (Super Admin)
    ├── Full system access
    ├── Can manage all workspaces
    ├── Can view all users
    └── System configuration

WORKSPACE_OWNER (Admin)
    ├── Create and delete workspaces
    ├── Manage workspace members
    ├── Create and manage all projects in workspace
    ├── Assign roles to members
    └── Workspace-level settings

WORKSPACE_ADMIN
    ├── Create and manage projects
    ├── Invite members to workspace
    ├── Manage project permissions
    └── Cannot delete workspace

PROJECT_OWNER
    ├── Full control over their projects
    ├── Invite members to projects
    ├── Assign project-level roles
    └── Delete their own projects

PROJECT_ADMIN
    ├── Manage project files
    ├── Invite project members
    ├── Cannot delete project
    └── Full edit permissions

PROJECT_MEMBER
    ├── Edit files in project
    ├── Upload new files
    ├── Comment and collaborate
    └── Cannot invite others

PROJECT_VIEWER
    ├── Read-only access
    ├── View files and comments
    ├── Cannot edit or upload
    └── Cannot invite others

REGULAR_USER (Non-Workspace)
    ├── Manage own files only
    ├── Upload and edit personal files
    ├── Share files with specific users
    └── Join workspaces when invited
```

### **3.2 Role Permission Matrix**

| **Role** | **Create Workspace** | **Delete Workspace** | **Invite Members** | **Create Projects** | **Delete Projects** | **Edit Files** | **View Files** |
|----------|---------------------|---------------------|-------------------|-------------------|-------------------|---------------|---------------|
| SYSTEM_ADMIN | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ |
| WORKSPACE_OWNER | ✅ | ✅ (Own only) | ✅ | ✅ | ✅ | ✅ | ✅ |
| WORKSPACE_ADMIN | ❌ | ❌ | ✅ | ✅ | ✅ (Own only) | ✅ | ✅ |
| PROJECT_OWNER | ❌ | ❌ | ⚠️ (Project only) | ✅ | ✅ (Own only) | ✅ | ✅ |
| PROJECT_ADMIN | ❌ | ❌ | ⚠️ (Project only) | ❌ | ❌ | ✅ | ✅ |
| PROJECT_MEMBER | ❌ | ❌ | ❌ | ❌ | ❌ | ✅ | ✅ |
| PROJECT_VIEWER | ❌ | ❌ | ❌ | ❌ | ❌ | ❌ | ✅ |
| REGULAR_USER | ✅ | ❌ | ❌ | ⚠️ (Personal) | ⚠️ (Personal) | ⚠️ (Own files) | ⚠️ (Own files) |

**Legend:** ✅ Allowed | ❌ Not Allowed | ⚠️ Limited Access

<div style="page-break-after: always;"></div>

---

## **4. Workspace Flow (Team Collaboration)**

### **4.1 Workspace Lifecycle**

#### **4.1.1 Workspace Creation**

**Admin Journey:**
```
1. Admin creates account (via signup)
   ├── POST /api/auth/signup
   ├── Receives JWT token
   └── Automatically becomes WORKSPACE_OWNER

2. Admin logs into extension
   ├── POST /api/auth/login
   ├── Extension receives JWT token
   └── Dashboard opens

3. Admin creates workspace
   ├── GET /api/workspaces (checks existing workspaces)
   ├── POST /api/workspaces/create
   │   Body: {
   │     "name": "Research Lab Ontologies",
   │     "description": "Collaborative ontology workspace",
   │     "subscriptionPlan": "FREE|PRO|ENTERPRISE"
   │   }
   └── Workspace stored in MongoDB
       - workspaceId: UUID
       - ownerId: admin user ID
       - members: [{ userId, role: "OWNER" }]
       - subscriptionPlan: Determines features
       - createdAt: timestamp
```

**Database State After Creation:**
```json
{
  "workspaceId": "ws-12345",
  "name": "Research Lab Ontologies",
  "description": "Collaborative ontology workspace",
  "ownerId": "user-admin-001",
  "members": [
    {
      "userId": "user-admin-001",
      "role": "OWNER",
      "joinedAt": "2026-01-19T10:00:00Z"
    }
  ],
  "subscriptionPlan": "PRO",
  "createdAt": "2026-01-19T10:00:00Z",
  "isDeleted": false
}
```

#### **4.1.2 Team Member Invitation**

**Admin invites team members:**
```
1. Admin clicks "Invite Member" in dashboard
   ├── Opens invite modal
   └── Enters member email and role

2. Backend creates invitation
   ├── POST /api/workspaces/{workspaceId}/invite
   │   Body: {
   │     "email": "developer@example.com",
   │     "role": "ADMIN|MEMBER|VIEWER"
   │   }
   └── Invitation stored in MongoDB

3. Invited user receives notification
   ├── Email notification (optional)
   └── In-app invitation visible on login

4. User accepts invitation
   ├── POST /api/workspaces/{workspaceId}/invitations/{invitationId}/accept
   └── User added to workspace members list
```

**MongoDB Invitation Document:**
```json
{
  "invitationId": "inv-67890",
  "workspaceId": "ws-12345",
  "invitedBy": "user-admin-001",
  "invitedEmail": "developer@example.com",
  "role": "MEMBER",
  "status": "PENDING|ACCEPTED|REJECTED",
  "createdAt": "2026-01-19T11:00:00Z",
  "expiresAt": "2026-01-26T11:00:00Z"
}
```

#### **4.1.3 Project Creation & Management**

**Admin creates project in workspace:**
```
1. Admin selects workspace in dashboard
   ├── GET /api/projects/workspace/{workspaceId}
   └── Shows all projects in workspace

2. Admin creates new project
   ├── POST /api/projects
   │   Body: {
   │     "name": "Medical Ontology Project",
   │     "description": "Healthcare domain ontology",
   │     "workspaceId": "ws-12345",
   │     "tags": ["healthcare", "medical", "owl"]
   │   }
   └── Project stored in MongoDB
       - projectId: UUID
       - workspaceId: linked to workspace
       - ownerId: admin user ID
       - status: "ACTIVE"
```

**MongoDB Project Document:**
```json
{
  "projectId": "proj-11111",
  "name": "Medical Ontology Project",
  "description": "Healthcare domain ontology",
  "workspaceId": "ws-12345",
  "ownerId": "user-admin-001",
  "members": [
    {
      "userId": "user-admin-001",
      "role": "OWNER",
      "permission": "EDIT"
    }
  ],
  "files": [],
  "tags": ["healthcare", "medical", "owl"],
  "status": "ACTIVE",
  "createdAt": "2026-01-19T12:00:00Z",
  "isDeleted": false
}
```

#### **4.1.4 File Upload & Collaboration Workflow**

**Admin/Member uploads OWL file to project:**
```
1. Right-click .owl file in VS Code Explorer
   ├── Select "Process Large OWL File"
   └── Extension opens with pending file

2. Extension shows project selection
   ├── GET /api/projects/workspace/{workspaceId}
   ├── User selects target project
   └── Purple banner shows: "File pending upload to [Project Name]"

3. File uploads to backend
   ├── POST /api/projects/{projectId}/files
   │   Body: {
   │     "fileName": "medical-ontology.owl",
   │     "fileData": "base64_encoded_content",
   │     "fileSize": 2048576,
   │     "fileType": "application/rdf+xml"
   │   }
   └── File stored in MongoDB with metadata

4. File synced to GraphDB
   ├── Backend pushes ontology to GraphDB
   ├── Enables SPARQL queries and reasoning
   └── Stores triples for collaborative editing

5. Real-time collaboration starts
   ├── Yjs WebSocket connection established
   ├── All team members see file in project
   └── Concurrent editing with CRDT synchronization
```

**MongoDB FileMetadata Document:**
```json
{
  "fileId": "file-22222",
  "fileName": "medical-ontology.owl",
  "projectId": "proj-11111",
  "workspaceId": "ws-12345",
  "uploadedBy": "user-admin-001",
  "uploaderEmail": "admin@example.com",
  "uploaderUsername": "admin",
  "fileSize": 2048576,
  "fileType": "application/rdf+xml",
  "extension": "owl",
  "base64Data": "[base64_content]",
  "uploadedAt": "2026-01-19T13:00:00Z",
  "status": "ACTIVE",
  "isDeleted": false
}
```

### **4.2 Admin Dashboard Specification**

#### **4.2.1 Dashboard Layout**

**Full Admin Dashboard View:**
```
┌─────────────────────────────────────────────────────────┐
│  OntoCode - Admin Dashboard                            │
├─────────────────────────────────────────────────────────┤
│                                                         │
│  📊 Workspace: Research Lab Ontologies                │
│      Plan: PRO ($29/mo) | Members: 8 | Projects: 12   │
│                                                         │
│  ⚙️  Workspace Actions:                                │
│      [Create New Workspace]  [Invite Members]          │
│      [Manage Permissions]    [Workspace Settings]      │
│                                                         │
├─────────────────────────────────────────────────────────┤
│  📁 All Projects (Admin View)                          │
├─────────────────────────────────────────────────────────┤
│  ✅ Medical Ontology Project                           │
│     Owner: John Doe | Members: 4 | Files: 23          │
│     [Open] [Manage] [Settings] [Delete]                │
│                                                         │
│  ✅ Biology Knowledge Base                             │
│     Owner: Jane Smith | Members: 3 | Files: 15        │
│     [Open] [Manage] [Settings] [Delete]                │
│                                                         │
│  ✅ Chemistry Ontology                                 │
│     Owner: Admin (You) | Members: 2 | Files: 8        │
│     [Open] [Manage] [Settings] [Delete]                │
│                                                         │
│  [+ Create New Project]                                │
│                                                         │
├─────────────────────────────────────────────────────────┤
│  👥 Recent Activity (Admin View)                       │
├─────────────────────────────────────────────────────────┤
│  • Jane uploaded "protein-ontology.owl" (2 mins ago)   │
│  • John edited "medical-terms.owl" (15 mins ago)       │
│  • New member "bob@example.com" joined (1 hour ago)    │
│  • Admin created "Chemistry Ontology" (3 hours ago)    │
│                                                         │
└─────────────────────────────────────────────────────────┘
```

#### **4.2.2 Administrative Capabilities Summary**

The following table summarizes administrative capabilities available in workspace mode:

| **Capability** | **Description** | **Required Role** |
|---------------|-----------------|-------------------|
| View all projects | Access to all workspace projects regardless of membership | OWNER, ADMIN |
| Create projects | Initiate new ontology projects within workspace | OWNER, ADMIN |
| Delete projects | Permanently remove projects (soft delete) | OWNER |
| Manage members | Invite, remove, and modify member roles | OWNER, ADMIN |
| Assign permissions | Grant/revoke project-level access | OWNER |
| Upload files | Add files to any project in workspace | OWNER, ADMIN |
| Access analytics | View workspace-level statistics and metrics | OWNER |
| Configure settings | Modify workspace configuration and policies | OWNER |
| Manage trash | View and restore soft-deleted entities | OWNER, ADMIN |

<div style="page-break-after: always;"></div>

---

## **5. Non-Workspace Flow (Individual Usage)**

### **5.1 Individual User Lifecycle**

#### **5.1.1 User Registration & Onboarding**

**Individual User Journey:**
```
1. User creates account
   ├── POST /api/auth/signup
   │   Body: {
   │     "username": "john_researcher",
   │     "email": "john@researcher.com",
   │     "password": "SecurePass123"
   │   }
   └── User stored in MongoDB
       - userId: UUID
       - role: "USER" (default)
       - workspaces: [] (empty initially)

2. User logs into extension
   ├── POST /api/auth/login
   ├── Extension receives JWT token
   └── Dashboard opens (Non-Workspace Mode)

3. No workspace selection required
   ├── User sees "My Files" dashboard immediately
   └── Simpler, focused interface
```

**MongoDB User Document (Non-Workspace):**
```json
{
  "userId": "user-33333",
  "username": "john_researcher",
  "email": "john@researcher.com",
  "passwordHash": "[bcrypt_hash]",
  "role": "USER",
  "workspaces": [],
  "createdAt": "2026-01-19T14:00:00Z",
  "lastLogin": "2026-01-19T14:00:00Z"
}
```

#### **5.1.2 Personal File Management**

**User uploads personal file:**
```
1. User opens extension (non-workspace mode)
   ├── No workspace required
   └── Direct access to file upload

2. Right-click .owl file OR click upload button
   ├── File upload modal opens
   └── File directly uploaded to user's space

3. File stored with user ownership
   ├── POST /api/projects (creates implicit personal project)
   │   OR
   │   POST /api/files/upload (direct file upload)
   │   Body: {
   │     "fileName": "my-research.owl",
   │     "fileData": "base64_content",
   │     "visibility": "PRIVATE|SHARED"
   │   }
   └── File stored in MongoDB
       - ownerId: user ID
       - workspaceId: null (non-workspace)
       - sharedWith: [] (initially private)
```

**MongoDB Personal File Document:**
```json
{
  "fileId": "file-44444",
  "fileName": "my-research.owl",
  "ownerId": "user-33333",
  "workspaceId": null,
  "projectId": null,
  "uploadedBy": "user-33333",
  "uploaderEmail": "john@researcher.com",
  "fileSize": 1024000,
  "fileType": "application/rdf+xml",
  "visibility": "PRIVATE",
  "sharedWith": [],
  "uploadedAt": "2026-01-19T15:00:00Z",
  "status": "ACTIVE",
  "isDeleted": false
}
```

#### **5.1.3 File Sharing Mechanism (Optional)**

**User shares file with another user:**
```
1. User selects file in "My Files"
   ├── Clicks "Share" button
   └── Opens sharing modal

2. User enters recipient email
   ├── POST /api/files/{fileId}/share
   │   Body: {
   │     "shareWithEmail": "colleague@example.com",
   │     "permission": "VIEW|EDIT"
   │   }
   └── Share record created in MongoDB

3. Recipient sees file in "Shared Files"
   ├── GET /api/files/shared
   └── File appears with "Shared by [Owner]" label
```

**MongoDB File Share Document:**
```json
{
  "shareId": "share-55555",
  "fileId": "file-44444",
  "ownerId": "user-33333",
  "sharedWithUserId": "user-66666",
  "sharedWithEmail": "colleague@example.com",
  "permission": "EDIT",
  "sharedAt": "2026-01-19T16:00:00Z",
  "status": "ACTIVE"
}
```

### **5.2 Regular User Dashboard Specification**

#### **5.2.1 Dashboard Layout**

**Non-Workspace Dashboard View:**
```
┌─────────────────────────────────────────────────────────┐
│  OntoCode - My Dashboard                               │
├─────────────────────────────────────────────────────────┤
│                                                         │
│  📄 My Files (3)                                       │
├─────────────────────────────────────────────────────────┤
│  📝 my-research.owl                                    │
│     Size: 1.2 MB | Modified: 2 hours ago               │
│     [Open] [Share] [Download] [Delete]                 │
│                                                         │
│  📝 experiment-data.owl                                │
│     Size: 850 KB | Modified: Yesterday                 │
│     [Open] [Share] [Download] [Delete]                 │
│                                                         │
│  📝 thesis-ontology.owl                                │
│     Size: 2.4 MB | Modified: 3 days ago                │
│     [Open] [Share] [Download] [Delete]                 │
│                                                         │
│  [+ Upload New File]                                   │
│                                                         │
├─────────────────────────────────────────────────────────┤
│  🔗 Shared with Me (2)                                 │
├─────────────────────────────────────────────────────────┤
│  📝 team-ontology.owl                                  │
│     Shared by: jane@example.com | Permission: Edit     │
│     [Open] [Download]                                  │
│                                                         │
│  📝 reference-model.owl                                │
│     Shared by: prof@university.edu | Permission: View  │
│     [Open] [Download]                                  │
│                                                         │
├─────────────────────────────────────────────────────────┤
│  💼 Workspace Invitations (1)                          │
├─────────────────────────────────────────────────────────┤
│  📧 Invitation to "Research Lab Ontologies"            │
│     From: admin@lab.com | Role: MEMBER                 │
│     [Accept] [Decline]                                 │
│                                                         │
└─────────────────────────────────────────────────────────┘
```

#### **5.2.2 User Capabilities Summary**

The following table summarizes capabilities available in non-workspace mode:

| **Capability** | **Status** | **Notes** |
|---------------|-----------|-----------|
| Upload personal files | ✅ Available | Direct upload without project creation |
| Edit own files | ✅ Available | Full editing capabilities locally |
| Share files | ✅ Available | Selective sharing with specific users |
| Access shared files | ✅ Available | View/edit based on granted permissions |
| Accept invitations | ✅ Available | Join workspaces when invited |
| Switch modes | ✅ Available | Toggle between personal and workspace views |
| View others' files | ❌ Restricted | Only if explicitly shared |
| Create workspaces | ⚠️ Upgrade | Requires workspace creation (becomes owner) |
| Team management | ❌ Not Available | No organizational features |
| Analytics | ❌ Not Available | No workspace-level statistics |

#### **5.1.4 Workspace Invitation Acceptance**

**User accepts workspace invitation:**
```
1. User sees invitation in dashboard
   ├── GET /api/workspaces/invitations
   └── Displays pending invitations

2. User accepts invitation
   ├── POST /api/workspaces/{workspaceId}/invitations/{invitationId}/accept
   └── User added to workspace members

3. User gains workspace access
   ├── GET /api/workspaces
   ├── User can now switch to workspace mode
   └── Sees workspace projects based on role

4. Dual-mode operation
   ├── User maintains personal files
   ├── Can switch between personal and workspace views
   └── Dashboard shows both sections
```

#### **5.2.3 Hybrid Mode Dashboard**

After accepting a workspace invitation, users operate in hybrid mode with access to both personal and workspace resources:
```
┌─────────────────────────────────────────────────────────┐
│  OntoCode - Dashboard                                  │
├─────────────────────────────────────────────────────────┤
│  🔄 Switch Mode: [Personal Files ▼] [Workspace: Lab ▼]│
├─────────────────────────────────────────────────────────┤
│  Currently Viewing: Research Lab Ontologies (Member)   │
│                                                         │
│  📁 My Projects in Workspace (2)                       │
│     • Medical Ontology (EDIT permission)               │
│     • Biology KB (VIEW permission)                     │
│                                                         │
│  📁 Shared Projects (3)                                │
│     • Chemistry Ontology (VIEW only)                   │
│     • Physics Models (EDIT permission)                 │
│     • Math Foundations (VIEW only)                     │
│                                                         │
│  [Switch to Personal Files]                            │
└─────────────────────────────────────────────────────────┘
```

<div style="page-break-after: always;"></div>

---

## **6. Database Schema Specification**

### **6.1 MongoDB Collections Overview**

The OntoCode platform utilizes MongoDB as the primary data store for user information, workspace metadata, project data, and file metadata. The following sections define the schema for each collection.

### **6.2 User Collection Schema**
**Collection Name:** `users`

**Schema Definition:**

```javascript
{
  userId: String (UUID),           // Primary key, unique identifier
  username: String (unique),       // Unique username for authentication
  email: String (unique),          // Unique email address
  passwordHash: String (bcrypt),   // Bcrypt hashed password (cost: 12)
  role: String (enum),             // "USER", "ADMIN", "SYSTEM_ADMIN"
  workspaces: [String],            // Array of workspace IDs user belongs to
  createdAt: Date,                 // Account creation timestamp
  lastLogin: Date,                 // Last successful login timestamp
  preferences: {                   // User preferences object
    theme: String,                 // UI theme preference
    language: String               // Interface language
  }
}
```

**Indexes:**
```javascript
db.users.createIndex({ "email": 1 }, { unique: true });
db.users.createIndex({ "username": 1 }, { unique: true });
db.users.createIndex({ "userId": 1 }, { unique: true });
```

### **6.3 Workspace Collection Schema**

**Collection Name:** `workspaces`

**Schema Definition:**
```javascript
{
  workspaceId: String (UUID),          // Primary key, unique identifier
  name: String,                        // Workspace display name
  description: String,                 // Workspace description
  ownerId: String (userId),            // Reference to workspace owner
  members: [{                          // Array of workspace members
    userId: String,                    // Reference to user
    role: String (enum),               // "OWNER", "ADMIN", "MEMBER", "VIEWER"
    joinedAt: Date,                    // Membership start timestamp
    invitedBy: String (userId)         // Reference to inviting user
  }],
  subscriptionPlan: String (enum),     // "FREE", "PRO", "ENTERPRISE"
  subscriptionStatus: String,          // Active subscription status
  createdAt: Date,                     // Workspace creation timestamp
  updatedAt: Date,                     // Last modification timestamp
  isDeleted: Boolean (default: false), // Soft delete flag
  deletedAt: Date,                     // Deletion timestamp (if deleted)
  deletedBy: String                    // Reference to deleting user
}
```

**Indexes:**
```javascript
db.workspaces.createIndex({ "workspaceId": 1 }, { unique: true });
db.workspaces.createIndex({ "ownerId": 1 });
db.workspaces.createIndex({ "members.userId": 1 });
db.workspaces.createIndex({ "isDeleted": 1 });
```

### **6.4 Project Collection Schema**

**Collection Name:** `projects`

**Schema Definition:**
```javascript
{
  projectId: String (UUID),            // Primary key, unique identifier
  name: String,                        // Project display name
  description: String,                 // Project description
  workspaceId: String (nullable),      // Reference to workspace (null for personal)
  ownerId: String (userId),            // Reference to project owner
  members: [{                          // Array of project members
    userId: String,                    // Reference to user
    role: String (enum),               // "OWNER", "ADMIN", "MEMBER", "VIEWER"
    permission: String (enum),         // "EDIT", "VIEW"
    joinedAt: Date                     // Membership start timestamp
  }],
  files: [{                            // Array of file references
    fileId: String,                    // Reference to file metadata
    fileName: String,                  // File display name
    fileSize: Number,                  // File size in bytes
    fileType: String,                  // MIME type
    extension: String,                 // File extension
    uploadedBy: String,                // Reference to uploader
    uploaderUsername: String,          // Uploader's username
    uploaderEmail: String,             // Uploader's email
    uploadedAt: Date,                  // Upload timestamp
    status: String                     // File status
  }],
  tags: [String],                      // Array of project tags
  status: String (enum),               // "ACTIVE", "ARCHIVED", "DELETED"
  createdAt: Date,                     // Project creation timestamp
  updatedAt: Date,                     // Last modification timestamp
  isDeleted: Boolean (default: false), // Soft delete flag
  deletedAt: Date,                     // Deletion timestamp
  deletedBy: String                    // Reference to deleting user
}
```

**Indexes:**
```javascript
db.projects.createIndex({ "projectId": 1 }, { unique: true });
db.projects.createIndex({ "workspaceId": 1, "isDeleted": 1 });
db.projects.createIndex({ "ownerId": 1 });
db.projects.createIndex({ "members.userId": 1 });
```

### **6.5 FileMetadata Collection Schema**

**Collection Name:** `fileMetadata`

**Schema Definition:**
```javascript
{
  fileId: String (UUID),               // Primary key, unique identifier
  fileName: String,                    // File display name
  projectId: String (nullable),        // Reference to project (null for personal)
  workspaceId: String (nullable),      // Reference to workspace (null for personal)
  ownerId: String (for non-workspace), // Reference to file owner
  uploadedBy: String (userId),         // Reference to uploader
  uploaderEmail: String,               // Uploader's email
  uploaderUsername: String,            // Uploader's username
  fileSize: Number,                    // File size in bytes
  fileType: String,                    // MIME type (e.g., "application/rdf+xml")
  extension: String,                   // File extension (e.g., "owl")
  base64Data: String,                  // Base64 encoded file content
  visibility: String (enum),           // "PRIVATE", "SHARED", "PUBLIC"
  sharedWith: [{                       // Array of users with access
    userId: String,                    // Reference to user
    permission: String,                // "EDIT", "VIEW"
    sharedAt: Date                     // Sharing timestamp
  }],
  uploadedAt: Date,                    // Upload timestamp
  modifiedAt: Date,                    // Last modification timestamp
  status: String (enum),               // "ACTIVE", "ARCHIVED", "DELETED"
  isDeleted: Boolean (default: false), // Soft delete flag
  deletedAt: Date,                     // Deletion timestamp
  deletedBy: String                    // Reference to deleting user
}
```

**Indexes:**
```javascript
db.fileMetadata.createIndex({ "fileId": 1 }, { unique: true });
db.fileMetadata.createIndex({ "projectId": 1, "isDeleted": 1 });
db.fileMetadata.createIndex({ "workspaceId": 1 });
db.fileMetadata.createIndex({ "ownerId": 1 });
db.fileMetadata.createIndex({ "uploadedBy": 1 });
```

### **6.6 Invitation Collection Schema**

**Collection Name:** `invitations`

**Schema Definition:**
```javascript
{
  invitationId: String (UUID),              // Primary key, unique identifier
  workspaceId: String,                      // Reference to workspace
  invitedBy: String (userId),               // Reference to inviting user
  invitedEmail: String,                     // Email address of invitee
  invitedUserId: String (nullable),         // Reference to user (set on accept)
  role: String (enum),                      // "ADMIN", "MEMBER", "VIEWER"
  message: String,                          // Optional invitation message
  status: String (enum),                    // "PENDING", "ACCEPTED", "REJECTED", "EXPIRED"
  token: String (unique),                   // Unique invitation token
  createdAt: Date,                          // Invitation creation timestamp
  expiresAt: Date,                          // Expiration timestamp (7 days default)
  respondedAt: Date                         // Response timestamp
}
```

**Indexes:**
```javascript
db.invitations.createIndex({ "invitationId": 1 }, { unique: true });
db.invitations.createIndex({ "token": 1 }, { unique: true });
db.invitations.createIndex({ "workspaceId": 1, "status": 1 });
db.invitations.createIndex({ "invitedEmail": 1 });
db.invitations.createIndex({ "invitedUserId": 1 });
```

<div style="page-break-after: always;"></div>

---

## **7. API Reference**

### **7.1 Authentication Endpoints**
| **Method** | **Endpoint** | **Description** | **Auth Required** |
|------------|--------------|-----------------|-------------------|
| POST | `/api/auth/signup` | Create new user account | No |
| POST | `/api/auth/login` | Login and receive JWT token | No |
| POST | `/api/auth/logout` | Logout current user | Yes |
| GET | `/api/auth/me` | Get current user information | Yes |

### **7.2 Workspace Endpoints (Admin)**
| **Method** | **Endpoint** | **Description** | **Required Role** |
|------------|--------------|-----------------|-------------------|
| GET | `/api/workspaces` | Get all user workspaces | Any authenticated |
| POST | `/api/workspaces/create` | Create new workspace | Any authenticated |
| GET | `/api/workspaces/{workspaceId}` | Get workspace details | Member |
| PUT | `/api/workspaces/{workspaceId}` | Update workspace | OWNER, ADMIN |
| DELETE | `/api/workspaces/{workspaceId}` | Soft delete workspace | OWNER |
| POST | `/api/workspaces/{workspaceId}/restore` | Restore deleted workspace | OWNER |
| GET | `/api/workspaces/deleted` | Get deleted workspaces | OWNER |
| POST | `/api/workspaces/{workspaceId}/invite` | Invite member | OWNER, ADMIN |
| GET | `/api/workspaces/invitations` | Get pending invitations | Any authenticated |
| POST | `/api/workspaces/{workspaceId}/invitations/{invId}/accept` | Accept invitation | Invitee |
| POST | `/api/workspaces/{workspaceId}/invitations/{invId}/reject` | Reject invitation | Invitee |

### **7.3 Project Endpoints**
| **Method** | **Endpoint** | **Description** | **Required Permission** |
|------------|--------------|-----------------|-------------------------|
| GET | `/api/projects` | Get user's projects | Any authenticated |
| POST | `/api/projects` | Create new project | Workspace ADMIN or above |
| GET | `/api/projects/{projectId}` | Get project details | Project member |
| PUT | `/api/projects/{projectId}` | Update project | Project OWNER or ADMIN |
| DELETE | `/api/projects/{projectId}` | Soft delete project | Project OWNER |
| POST | `/api/projects/{projectId}/restore` | Restore deleted project | Project OWNER |
| GET | `/api/projects/workspace/{workspaceId}` | Get all projects in workspace | Workspace member |
| GET | `/api/projects/workspace/{workspaceId}/deleted` | Get deleted projects | Workspace OWNER/ADMIN |
| POST | `/api/projects/{projectId}/invite` | Invite member to project | Project OWNER/ADMIN |

### **7.4 File Endpoints**
| **Method** | **Endpoint** | **Description** | **Required Permission** |
|------------|--------------|-----------------|-------------------------|
| POST | `/api/projects/{projectId}/files` | Upload file to project | Project EDIT permission |
| GET | `/api/projects/{projectId}/files` | Get all files in project | Project member |
| GET | `/api/projects/{projectId}/files/{fileId}` | Get file details | Project member |
| DELETE | `/api/projects/{projectId}/files/{fileId}` | Soft delete file | File uploader or Project OWNER |
| POST | `/api/projects/{projectId}/files/{fileId}/restore` | Restore file | File uploader or Project OWNER |
| GET | `/api/files/my` | Get user's personal files | File owner |
| POST | `/api/files/upload` | Upload personal file | Any authenticated |
| GET | `/api/files/shared` | Get files shared with user | Any authenticated |
| POST | `/api/files/{fileId}/share` | Share file with user | File owner |
| DELETE | `/api/files/{fileId}/share/{shareId}` | Remove file share | File owner |

<div style="page-break-after: always;"></div>

---

## **8. Authentication & Authorization**

### **8.1 JWT Token Specification**
**Token Format:** JSON Web Token (JWT)  
**Algorithm:** HS256 (HMAC with SHA-256)  
**Expiration:** 24 hours  
**Header:** Authorization: Bearer <token>

**Payload Structure:**

```json
{
  "userId": "user-12345",
  "username": "john_doe",
  "email": "john@example.com",
  "role": "ADMIN",
  "workspaceId": "ws-67890",
  "iat": 1737295200,
  "exp": 1737381600
}
```

### **8.2 Authorization Rules Matrix**

#### **8.2.1 Workspace-Level Authorization**

| **Operation** | **OWNER** | **ADMIN** | **MEMBER** | **VIEWER** |
|---------------|-----------|-----------|------------|------------|
| Create Workspace | ✅ | ✅ | ✅ | ✅ |
| Delete Workspace | ✅ | ❌ | ❌ | ❌ |
| Invite Members | ✅ | ✅ | ❌ | ❌ |
| Remove Members | ✅ | ✅ | ❌ | ❌ |
| Change Roles | ✅ | ❌ | ❌ | ❌ |

#### **8.2.2 Project-Level Authorization**

| **Operation** | **OWNER** | **ADMIN** | **MEMBER** | **VIEWER** |
|---------------|-----------|-----------|------------|------------|
| Create Project | ✅ | ✅ | ❌ | ❌ |
| Delete Project | ✅ | ❌ | ❌ | ❌ |
| Edit Project | ✅ | ✅ | ✅ | ❌ |
| View Project | ✅ | ✅ | ✅ | ✅ |
| Invite Members | ✅ | ✅ | ❌ | ❌ |

#### **8.2.3 File-Level Authorization**

| **Operation** | **File Owner** | **EDIT Permission** | **VIEW Permission** |
|---------------|----------------|---------------------|---------------------|
| Upload File | N/A | ✅ | ❌ |
| Edit File | ✅ | ✅ | ❌ |
| Delete File | ✅ | ❌ | ❌ |
| View File | ✅ | ✅ | ✅ |
| Share File | ✅ | ❌ | ❌ |

### **8.3 Security Implementation Examples**

#### **8.3.1 Spring Security Annotations**

**Java Spring Security:**
```java
// Check if user is workspace admin
@PreAuthorize("hasRole('ADMIN') or @workspaceSecurityService.isWorkspaceAdmin(#workspaceId, authentication.principal.userId)")
public ResponseEntity<?> createProject(String workspaceId, CreateProjectRequest request) {
    // Create project logic
}

// Check if user has edit permission on project
@PreAuthorize("@projectSecurityService.hasEditPermission(#projectId, authentication.principal.userId)")
public ResponseEntity<?> uploadFile(String projectId, MultipartFile file) {
    // Upload file logic
}

// Check if user owns file (non-workspace)
@PreAuthorize("#userId == authentication.principal.userId")
public ResponseEntity<?> sharePersonalFile(String userId, String fileId, ShareRequest request) {
    // Share file logic
}
```

<div style="page-break-after: always;"></div>

---

## **9. Use Cases & Scenarios**

### **9.1 Scenario 1: Research Lab Setup (Workspace Mode)**

**Scenario Description:**  
A university bioinformatics research lab requires a centralized platform for managing ontology projects across multiple research teams.

**Actors:**  
- Dr. Smith (Lab Director) - Workspace Owner
- 3 PhD Students - Project Members
- 2 Postdocs - Project Admins

**Implementation Flow:**
1. Dr. Smith creates account → Becomes admin
2. Dr. Smith creates workspace "Bioinformatics Lab"
3. Dr. Smith invites 5 members (students and postdocs)
   - PhD students get MEMBER role (EDIT permission)
   - Postdocs get ADMIN role (can manage projects)
4. Dr. Smith creates project "Protein Ontology"
5. Postdoc creates project "Gene Ontology Extension"
6. PhD student uploads protein-ontology.owl to project
7. All team members collaborate in real-time using Yjs
8. GraphDB enables SPARQL queries across all ontologies
9. Version history tracked in MongoDB

**Benefits Realized:**
- ✅ Centralized knowledge management across research teams
- ✅ Role-based access control ensures data governance
- ✅ Real-time collaboration eliminates version conflicts
- ✅ Comprehensive audit trail for research compliance
- ✅ GraphDB integration enables cross-ontology SPARQL queries

### **9.2 Scenario 2: Individual Researcher (Non-Workspace Mode)**

**Scenario Description:**  
A graduate student needs personal ontology management without organizational complexity during thesis research.

**Actor:**  
- Jane (Graduate Student) - Regular User

**Implementation Flow:**
1. Jane creates account → Regular user
2. Jane uploads personal files:
   - thesis-ontology.owl
   - literature-review.owl
   - experiment-data.owl
3. Jane shares thesis-ontology.owl with advisor (VIEW permission)
4. Jane shares experiment-data.owl with lab mate (EDIT permission)
5. Lab mate edits experiment-data.owl
6. Jane later receives workspace invitation from lab director
7. Jane accepts invitation → Gains access to lab workspace
8. Jane can now switch between personal files and workspace projects

**Benefits Realized:**
- ✅ Simple, focused interface without organizational overhead
- ✅ No forced organizational structure
- ✅ Selective file sharing with granular permissions
- ✅ Seamless upgrade path to workspace mode when lab invitation received
- ✅ Privacy maintained for personal research

### **9.3 Scenario 3: Hybrid Usage (Both Modes)**

**Scenario Description:**  
A university professor manages both personal research and collaborative team projects requiring dual-mode operation.

**Actor:**  
- Prof. Johnson - Workspace Owner + Regular User

**Implementation Flow:**
1. Prof. Johnson starts as regular user with personal files
2. Creates workspace "Advanced AI Research" → Becomes admin
3. Invites team members to workspace
4. Still maintains personal research files separate from workspace
5. Dashboard shows both:
   - Personal Files section (3 files)
   - Workspace Projects section (12 projects)
6. Can switch between modes based on context
7. Personal files remain private
8. Workspace files shared with team

**Benefits Realized:**
- ✅ Flexibility to work individually or collaboratively based on context
- ✅ Clear separation between personal and team work
- ✅ No forced organizational structure for personal research
- ✅ Smooth transition between modes within single dashboard

<div style="page-break-after: always;"></div>

---

## **10. Feature Comparison Matrix**

### **10.1 Comprehensive Feature Matrix**

The following table provides a detailed comparison of features available in each operational mode:

| **Feature** | **Workspace Mode** | **Non-Workspace Mode** |
|---------|---------------|-------------------|
| **User Management** | ✅ Full team management | ❌ Individual only |
| **Role-Based Access** | ✅ Multiple roles (8 levels) | ❌ Owner only |
| **Project Organization** | ✅ Multiple projects per workspace | ⚠️ Personal files only |
| **Real-Time Collaboration** | ✅ Yjs + WebSocket CRDT | ❌ No collaboration |
| **File Sharing** | ✅ Automatic within workspace | ⚠️ Manual per-user sharing |
| **GraphDB Integration** | ✅ Full integration with triples | ⚠️ Limited (personal files) |
| **SPARQL Queries** | ✅ Across all workspace data | ⚠️ Personal files only |
| **Version Control** | ✅ Full history with audit trail | ⚠️ Basic tracking |
| **Audit Trail** | ✅ Comprehensive logs | ⚠️ Limited logs |
| **Subscription Plans** | ✅ FREE/PRO/ENTERPRISE | ✅ FREE (individual) |
| **Storage Limits** | 50GB-Unlimited (plan-based) | 5GB free |
| **Member Invitations** | ✅ Unlimited invites | ❌ No invitations |
| **Dashboard Complexity** | ⚠️ More features, steeper learning | ✅ Simple, focused |
| **Onboarding Time** | ⚠️ 10-15 minutes | ✅ 2-3 minutes |
| **Monthly Cost** | $0-$99/month | $0 |
| **Migration Path** | N/A | ✅ Easy upgrade to workspace |
| **Concurrent Editing** | ✅ Multiple users simultaneously | ❌ Single user only |
| **Conflict Resolution** | ✅ Automatic CRDT-based | ❌ Not applicable |
| **API Rate Limits** | Higher (plan-based) | Standard |
| **Technical Support** | Priority (PRO/ENTERPRISE) | Community |

**Legend:**  
✅ Fully Available | ⚠️ Limited/Partial | ❌ Not Available

<div style="page-break-after: always;"></div>

---

## **11. Migration Path**

### **11.1 Upgrade Process: Non-Workspace → Workspace Mode**

#### **11.1.1 Migration Workflow**
```
1. User clicks "Create Workspace" in dashboard
   └── Promotion modal appears

2. User creates workspace
   ├── Existing personal files remain separate
   └── User becomes workspace OWNER

3. Optional: Import personal files to workspace
   ├── User selects files to import
   ├── POST /api/files/import-to-workspace
   └── Files copied (not moved) to workspace project

4. Dual operation
   ├── Personal files still accessible
   ├── Workspace projects separate
   └── Dashboard shows both sections
```

**Migration ensures:**
- ✅ Zero data loss during transition
- ✅ Personal files remain private and accessible
- ✅ Gradual transition without forced migration
- ✅ Ability to revert if needed
- ✅ Maintains file ownership and permissions

#### **11.1.2 Migration API Sequence**

```javascript
// Step 1: Create workspace
POST /api/workspaces/create
Body: {
  "name": "My Research Workspace",
  "description": "Transitioning from personal to team mode"
}
Response: { "workspaceId": "ws-new-12345" }

// Step 2: (Optional) Import personal files
POST /api/files/import-to-workspace
Body: {
  "workspaceId": "ws-new-12345",
  "fileIds": ["file-44444", "file-55555"]
}
Response: { "imported": 2, "failed": 0 }

// Step 3: User now has dual-mode access
GET /api/workspaces
Response: [
  { "workspaceId": "ws-new-12345", "role": "OWNER" }
]
```

<div style="page-break-after: always;"></div>

---

## **12. Technical Architecture**

### **12.1 Technology Stack Overview**

**Backend:**
- Spring Boot 3.x (Java)
- MongoDB (User data, projects, files metadata)
- GraphDB (Ontology triples, SPARQL)
- JWT Authentication
- WebSocket (Real-time collaboration)

**Frontend:**
- VS Code Extension API
- TypeScript
- React (Webviews)
- Yjs (CRDT synchronization)

**Infrastructure:**
- Docker & Docker Compose
- Kubernetes (k8s)
- Nginx (API Gateway)

### Data Flow Diagrams

See attached diagrams:
- [WORKSPACE_FLOW_DIAGRAM.drawio](WORKSPACE_FLOW_DIAGRAM.drawio) - Complete workspace flow
- [DESKTOP_SYNC_DIAGRAM.drawio](DESKTOP_SYNC_DIAGRAM.drawio) - P2P sync architecture

---

## Security Considerations

### Authentication
- JWT tokens with 24-hour expiration
- Refresh tokens for extended sessions
- Password hashing with bcrypt (cost factor: 12)
- Rate limiting on login attempts

### Authorization
- Role-based access control (RBAC)
- Row-level security in MongoDB queries
- Project-level permissions
- File-level permissions

### Data Protection
- HTTPS/TLS 1.3 for all communications
- Encrypted data at rest (MongoDB encryption)
- Encrypted WebSocket connections
- End-to-end encryption for file contents (optional)

### Audit Trail
- All actions logged to MongoDB
- User actions, timestamps, IP addresses
- Soft delete with audit (who, when, why)
- Compliance with GDPR, HIPAA (configurable)

---

## Performance Optimization

### MongoDB Indexes
```javascript
// Users
db.users.createIndex({ "email": 1 }, { unique: true });
db.users.createIndex({ "username": 1 }, { unique: true });

// Workspaces
db.workspaces.createIndex({ "workspaceId": 1 }, { unique: true });
db.workspaces.createIndex({ "ownerId": 1 });
db.workspaces.createIndex({ "members.userId": 1 });
db.workspaces.createIndex({ "isDeleted": 1 });

// Projects
db.projects.createIndex({ "projectId": 1 }, { unique: true });
db.projects.createIndex({ "workspaceId": 1, "isDeleted": 1 });
db.projects.createIndex({ "ownerId": 1 });
db.projects.createIndex({ "members.userId": 1 });

// Files
db.fileMetadata.createIndex({ "fileId": 1 }, { unique: true });
db.fileMetadata.createIndex({ "projectId": 1, "isDeleted": 1 });
db.fileMetadata.createIndex({ "workspaceId": 1 });
db.fileMetadata.createIndex({ "ownerId": 1 });
db.fileMetadata.createIndex({ "uploadedBy": 1 });
```

### Caching Strategy
- Redis cache for frequently accessed data
- User sessions cached (15 min TTL)
- Project metadata cached (5 min TTL)
- File metadata cached (1 min TTL)

---

## Future Enhancements

### Planned Features
1. **Advanced Analytics Dashboard** (Workspace admins)
   - File access statistics
   - Collaboration metrics
   - Storage usage trends

2. **Bulk Operations**
   - Bulk file upload
   - Bulk member invitation
   - Bulk permission changes

3. **Integration with External Tools**
   - Protégé plugin
   - TopBraid integration
   - GitHub ontology versioning

4. **Advanced Collaboration**
   - Comments and annotations
   - Task assignments
   - Review workflows

5. **Mobile App**
   - iOS and Android apps
   - View-only mode
   - Push notifications

---

## Support & Documentation

### Resources
- API Documentation: `/api/docs` (Swagger UI)
- User Guide: [README.md](README.md)
- Architecture Diagram: [WORKSPACE_FLOW_DIAGRAM.drawio](WORKSPACE_FLOW_DIAGRAM.drawio)
- Sync Architecture: [DESKTOP_SYNC_DIAGRAM.drawio](DESKTOP_SYNC_DIAGRAM.drawio)
- Soft Delete Guide: [SOFT_DELETE_IMPLEMENTATION.md](SOFT_DELETE_IMPLEMENTATION.md)

### Contact
- Technical Support: support@ontocode.dev
- Bug Reports: GitHub Issues
- Feature Requests: GitHub Discussions

---

**Document Version:** 1.0  
**Last Updated:** January 19, 2026  
**Maintained By:** OntoCode Development Team

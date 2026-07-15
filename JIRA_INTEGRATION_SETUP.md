# Jira Integration - Issue Reporting Setup Guide

## Overview

The OntoCode application now supports automatic Jira bug card creation when users report issues through the **Help → Report Issue** menu. When configured, issues are automatically created in your Jira Cloud instance under a specified epic.

---

## Prerequisites

Before enabling Jira integration, ensure you have:

1. **Jira Cloud Account** (not Jira Server/Data Center - requires Jira Cloud)
2. **Jira Project** with Bug issue type enabled
3. **Epic** (optional but recommended) where bugs will be grouped
4. **Admin/Project permissions** to create API tokens and issues

---

## Configuration Steps

### 1. Create Jira API Token

1. Log in to your Jira Cloud account
2. Go to **Account Settings** → **Security** → **API Tokens**
3. Click **Create API token**
4. Give it a name (e.g., "OntoCode Issue Reporter")
5. **Copy the token immediately** (you won't be able to see it again)

### 2. Get Your Jira Project Details

You'll need:
- **Jira Cloud URL**: e.g., `https://yourcompany.atlassian.net`
- **Project Key**: Found in your project settings (e.g., `ONTO`)
- **Epic Key**: (Optional) The issue key of your epic (e.g., `ONTO-123`)

To find your Epic Key:
1. Navigate to your Jira project
2. Open the epic you want to use
3. Copy the issue key from the URL or top of the page

### 3. Configure Environment Variables

Update your `.env` file in the workspace root:

```bash
# Jira Integration (Optional - for issue reporting)
JIRA_ENABLED=true
JIRA_CLOUD_URL=https://yourcompany.atlassian.net
JIRA_USER_EMAIL=your-email@company.com
JIRA_API_TOKEN=your_api_token_here
JIRA_PROJECT_KEY=ONTO
JIRA_EPIC_KEY=ONTO-123
```

**Important**:
- Use the email address associated with your Jira account for `JIRA_USER_EMAIL`
- Keep your API token secure - never commit it to version control
- Set `JIRA_ENABLED=false` to disable integration without removing configuration

### 4. Restart Backend Service

After updating `.env`, restart the `ontology-editor` service:

```bash
# If using Docker Compose
docker-compose restart ontology-editor

# Or rebuild if needed
docker-compose up -d --build ontology-editor
```

### 5. Verify Connection

Test the Jira connection:

```bash
curl -X GET http://localhost:8083/api/v1/issues/jira/validate
```

Expected success response:
```json
{
  "success": true,
  "message": "Successfully connected to Jira project: Your Project Name",
  "projectName": "Your Project Name"
}
```

---

## Usage

### For Users

1. **Open VS Code Extension**
2. Click **Help** → **Report Issue** in the top menu
3. Fill out the issue form:
   - **Issue Type**: Bug, Task, Story, etc.
   - **Priority**: Highest, High, Medium, Low, Lowest
   - **Title** (required): Brief summary
   - **Description** (required): Detailed explanation
   - **Steps to Reproduce**: How to trigger the issue
   - **Attachments**: Upload files (images, PDFs, Word documents, text files, logs, ontology files)

4. Click **Submit Issue**

5. If successful, you'll see:
   - Success message with link to Jira ticket
   - Jira ticket key (e.g., `ONTO-456`)
   - Direct link to view the issue in Jira

### Automatic Data Captured

The system automatically includes:
- **Project Context**: Current project name and ontology file path
- **System Information**: OS, VS Code version, extension version
- **Timestamp**: When the issue was reported
- **User Email**: If provided

---

## Issue Workflow

### When Jira is Enabled

1. User submits issue → System creates Jira bug ticket
2. Ticket is automatically linked to the configured epic
3. Priority is set based on keywords:
   - **Highest**: Contains "crash", "data loss", or "critical"
   - **High**: Contains "error", "broken", or "failure"  
   - **Medium**: Contains "slow" or "performance"
   - **Medium**: Default for all other issues

4. Labels added: `ontocode`, `auto-reported`, `{project-name}`
5. Issue is saved locally in MongoDB for audit trail
6. User receives Jira URL to track progress

### When Jira is Disabled/Unavailable

- Issues are saved locally only in MongoDB
- User sees message: "Issue logged locally. Please contact support@ontocode.com for assistance."
- Admin can manually triage and create Jira tickets later

---

## Jira Ticket Format

### Bug Ticket Structure

**Summary**: `{User-provided title}`

**Description** (auto-formatted):
```
{User-provided description}

*Steps to Reproduce:*
{User-provided steps}

*Context:*
Project: {project name}
File: {ontology file path}

*System Information:*
OS: {operating system}
VS Code: {version}
Extension: {version}

*Reporter:* {user email}

*Error Logs:*
{code}
{recent error logs if included}
{code}
```

**Issue Type**: Bug

**Parent**: {Configured Epic}

**Priority**: Auto-determined (see above)

**Labels**: `ontocode`, `auto-reported`, `{project-name}`

---

## Troubleshooting

### Connection Failed

**Symptom**: Validation endpoint returns `success: false`

**Solutions**:
1. Verify API token is correct and not expired
2. Check email matches your Jira account
3. Ensure Jira Cloud URL is correct (no trailing slash)
4. Verify your account has permission to create issues

### Authentication Error (401)

**Cause**: Invalid API token or email

**Fix**:
1. Generate a new API token
2. Update `JIRA_API_TOKEN` in `.env`
3. Restart backend service

### Epic Not Found (404)

**Cause**: Epic key is incorrect or not accessible

**Fix**:
1. Verify epic exists in your project
2. Check you have permission to view/edit the epic
3. Update `JIRA_EPIC_KEY` or leave empty to create bugs without parent epic

### Issues Not Creating

**Check**:
1. Backend logs: `docker logs ontology-editor`
2. Look for errors mentioning "Jira"
3. Verify MongoDB connection (issues should always save locally)

**Common issues**:
- Required fields in Jira project not configured properly
- Custom fields in Jira that aren't being populated
- Network/firewall blocking Jira Cloud access

### Custom Required Fields

If your Jira project has custom required fields:

1. Update `JiraService.java` method `buildIssuePayload()`
2. Add custom fields to the JSON payload:

```java
// Add custom field
ObjectNode customField = objectMapper.createObjectNode();
customField.put("value", "yourValue");
fields.set("customfield_12345", customField);
```

3. Rebuild and restart the service

---

## Security Notes

1. **API Token Storage**:
   - Stored in environment variables (not in code)
   - Never logged or exposed in responses
   - Should be rotated periodically

2. **User Data**:
   - Reported by info (username/email) automatically extracted from JWT token
   - No sensitive data is sent to Jira by default
   - File attachments are uploaded directly (scanning for malware not implemented yet, consider adding)

3. **Rate Limiting**:
   - Jira Cloud limits: 10 requests/second per user
   - Automatic retry with exponential backoff implemented
   - If limit exceeded, issues are saved locally for manual creation

---

## Disabling Jira Integration

To disable without removing configuration:

```bash
JIRA_ENABLED=false
```

Or completely remove from `.env`:

```bash
# Remove or comment out all JIRA_* variables
```

Issues will be saved locally only, and users will see fallback message directing them to email support.

---

## MongoDB Audit Trail

All issue reports are stored in the `issue_reports` collection:

```javascript
{
  "_id": "...",
  "title": "Bug title",
  "description": "...",
  "jiraIssueKey": "ONTO-456",  // null if Jira disabled
  "jiraIssueUrl": "https://...",
  "status": "SUBMITTED",  // PENDING, SUBMITTED, FAILED, LOCAL_ONLY
  "createdAt": ISODate("..."),
  "userEmail": "user@example.com",
  "projectId": "project-123",
  "systemInfo": { ... },
  "failureReason": null  // populated if Jira creation failed
}
```

Query examples:

```javascript
// Find failed Jira submissions
db.issue_reports.find({ status: "FAILED" })

// Find all issues for a project
db.issue_reports.find({ projectId: "my-ontology" })

// Find issues by user
db.issue_reports.find({ userEmail: "user@example.com" })
```

---

## API Reference

### Validate Jira Connection

```http
GET /api/v1/issues/jira/validate
```

**Response**:
```json
{
  "success": true,
  "message": "Successfully connected to Jira project: OntoCode",
  "projectName": "OntoCode"
}
```

### Submit Issue Report

```http
POST /api/v1/issues/report
Content-Type: multipart/form-data
```

**Parameters**:
- `title` (required): Issue title
- `description` (required): Issue description
- `issueType` (required): Bug, Task, Story, etc.
- `priority` (required): Highest, High, Medium, Low, Lowest
- `stepsToReproduce` (optional): Steps to reproduce
- `projectId` (optional): Auto-populated
- `projectName` (optional): Auto-populated
- `ontologyFilePath` (optional): Auto-populated
- `osName`, `osVersion`, `vsCodeVersion`, `extensionVersion`: Auto-populated
- `attachments` (optional): Unlimited files - images (.jpg, .png, .gif, etc.), PDFs, Word documents (.doc, .docx), text files (.txt, .log), ontology files (.owl, .ttl, .rdf)

**Response**:
```json
{
  "success": true,
  "message": "Issue reported successfully",
  "issueReportId": "67abc...",
  "jiraIssueKey": "ONTO-456",
  "jiraIssueUrl": "https://yourcompany.atlassian.net/browse/ONTO-456"
}
```

### Get User's Issue Reports

```http
GET /api/v1/issues/user/{email}
```

**Response**: Array of issue reports

---

## Support

For issues with Jira integration:

1. Check logs: `docker logs ontology-editor | grep -i jira`
2. Validate configuration: Call `/api/v1/issues/jira/validate`
3. Review MongoDB `issue_reports` collection for error details
4. Contact: support@ontocode.com

---

## Future Enhancements

Potential improvements:

- [ ] Bi-directional sync (update status from Jira back to app)
- [ ] Support for Jira Server/Data Center
- [ ] Attachment virus scanning before upload
- [ ] Custom field mapping UI in admin panel
- [ ] Issue templates for different bug categories
- [ ] Auto-assignment based on project component
- [ ] Duplicate detection before creating ticket

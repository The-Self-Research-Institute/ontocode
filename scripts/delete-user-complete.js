#!/usr/bin/env node

const nodeCrypto = require("crypto");

if (!globalThis.crypto && nodeCrypto.webcrypto) {
  globalThis.crypto = nodeCrypto.webcrypto;
}

const { MongoClient, ObjectId } = require("mongodb");
const http = require("http");
const https = require("https");
const readline = require("readline/promises");
const fs = require("fs/promises");
const path = require("path");

const DEFAULT_MONGODB_URI =
  "mongodb://admin:changeme123@localhost:27017/ontocode?authSource=admin";

const PROJECT_SCOPED_COLLECTIONS = [
  "ontology_annotation_properties",
  "ontology_axioms",
  "ontology_classes",
  "ontology_datatypes",
  "ontology_individuals",
  "ontology_properties",
  "ontology_search_index",
  "ontologies",
  "sparql_queries",
  "swrl_rules",
  "datatype_definitions",
];

function printUsage() {
  console.log(`
Usage:
  node scripts/delete-user-complete.js <email|username|userId> [options]

Options:
  --execute              Apply deletes. Without this, the script only prints a plan.
  --yes                  Skip interactive confirmation when using --execute.
  --skip-fuseki          Do not clear Fuseki project graphs.
  --delete-project-dirs  Delete DATA_DIR/projects/<projectId> directories for deleted projects.
  --mongodb-uri=<uri>    Override MONGODB_URI for this run.
  --mongo-db=<name>      Override Mongo database name for this run.
  --fuseki-url=<url>     Override FUSEKI_URL for this run.
  --fuseki-dataset=<name> Override FUSEKI_DATASET for this run.
  --help                 Show this help.

Dry-run is the default. Use --execute --yes only after the plan looks correct.
`);
}

function parseArgs(argv) {
  const options = {
    execute: false,
    yes: false,
    skipFuseki: false,
    deleteProjectDirs: false,
    mongodbUri: process.env.MONGODB_URI || DEFAULT_MONGODB_URI,
    mongoDb: process.env.MONGODB_DATABASE || "",
    fusekiUrl: process.env.FUSEKI_URL || "http://localhost:3030",
    fusekiDataset: process.env.FUSEKI_DATASET || "ontocode",
    dataDir: process.env.DATA_DIR || "./data",
  };

  let identifier = null;
  for (const arg of argv) {
    if (arg === "--help" || arg === "-h") {
      options.help = true;
    } else if (arg === "--execute") {
      options.execute = true;
    } else if (arg === "--yes" || arg === "-y") {
      options.yes = true;
    } else if (arg === "--skip-fuseki" || arg === "--skip-graphdb") {
      options.skipFuseki = true;
    } else if (arg === "--delete-project-dirs") {
      options.deleteProjectDirs = true;
    } else if (arg.startsWith("--mongodb-uri=")) {
      options.mongodbUri = arg.slice("--mongodb-uri=".length);
    } else if (arg.startsWith("--mongo-db=")) {
      options.mongoDb = arg.slice("--mongo-db=".length);
    } else if (arg.startsWith("--fuseki-url=")) {
      options.fusekiUrl = arg.slice("--fuseki-url=".length).replace(/\/+$/, "");
    } else if (arg.startsWith("--fuseki-dataset=")) {
      options.fusekiDataset = arg.slice("--fuseki-dataset=".length);
    } else if (!identifier) {
      identifier = arg;
    } else {
      throw new Error(`Unknown extra argument: ${arg}`);
    }
  }

  options.identifier = identifier;
  options.mongoDb = options.mongoDb || databaseNameFromUri(options.mongodbUri) || "ontocode";
  options.fusekiUrl = options.fusekiUrl.replace(/\/+$/, "");
  return options;
}

function databaseNameFromUri(uri) {
  try {
    const parsed = new URL(uri);
    const db = parsed.pathname.replace(/^\/+/, "");
    return db || "";
  } catch {
    return "";
  }
}

function escapeRegExp(value) {
  return String(value).replace(/[.*+?^${}()|[\]\\]/g, "\\$&");
}

function exactRegex(value) {
  return new RegExp(`^${escapeRegExp(value)}$`, "i");
}

function unique(values) {
  const seen = new Set();
  const out = [];
  for (const value of values) {
    if (value === undefined || value === null) continue;
    const normalized = String(value).trim();
    if (!normalized || seen.has(normalized)) continue;
    seen.add(normalized);
    out.push(normalized);
  }
  return out;
}

function objectIdIfValid(value) {
  if (typeof value !== "string" || !ObjectId.isValid(value)) {
    return null;
  }
  try {
    return new ObjectId(value);
  } catch {
    return null;
  }
}

function mongoIdValues(value) {
  const values = [];
  if (value !== undefined && value !== null) {
    values.push(value);
    const asString = String(value);
    if (asString !== value) {
      values.push(asString);
    }
    const objectId = objectIdIfValid(asString);
    if (objectId) {
      values.push(objectId);
    }
  }
  return values;
}

function docIdToString(value) {
  if (value === undefined || value === null) return "";
  if (typeof value === "string") return value;
  if (value instanceof ObjectId) return value.toHexString();
  return String(value);
}

function getProjectIdsFromDoc(doc) {
  const ids = [doc.projectId];
  if (!doc.projectId && typeof doc._id === "string") {
    ids.push(doc._id);
  }
  return unique(ids);
}

function getWorkspaceIdsFromDoc(doc) {
  const ids = [doc.workspaceId];
  if (!doc.workspaceId && typeof doc._id === "string") {
    ids.push(doc._id);
  }
  return unique(ids);
}

function orQuery(clauses) {
  const filtered = clauses.filter(Boolean);
  if (filtered.length === 0) return null;
  return filtered.length === 1 ? filtered[0] : { $or: filtered };
}

function inClause(field, values) {
  return values.length > 0 ? { [field]: { $in: values } } : null;
}

function notInClause(field, values) {
  return values.length > 0 ? { [field]: { $nin: values } } : {};
}

async function collectionExists(db, name) {
  const matches = await db.listCollections({ name }).toArray();
  return matches.length > 0;
}

async function findUsers(db, identifier) {
  const userQuery = {
    $or: [
      { email: exactRegex(identifier) },
      { username: identifier },
      { _id: identifier },
    ],
  };

  const objectId = objectIdIfValid(identifier);
  if (objectId) {
    userQuery.$or.push({ _id: objectId });
  }

  return db.collection("users").find(userQuery).toArray();
}

async function buildUserPlan(db, user) {
  const userIdString = docIdToString(user._id || user.id);
  const userRefIds = unique([userIdString, user.id]);
  const userMongoIds = mongoIdValues(user._id || userIdString);
  const email = user.email || "";
  const emailRegex = email ? exactRegex(email) : /^$/i;

  const workspaces = db.collection("workspaces");
  const projects = db.collection("projects");
  const invitations = db.collection("invitations");
  const files = db.collection("file_metadata");
  const projectShares = db.collection("project_shares");

  const ownedWorkspaceQuery = inClause("ownerId", userRefIds) || { _id: "__never__" };
  const ownedWorkspaces = await workspaces.find(ownedWorkspaceQuery).toArray();
  const ownedWorkspaceMongoIds = ownedWorkspaces.map((w) => w._id);
  const workspaceIdsToDelete = unique(ownedWorkspaces.flatMap(getWorkspaceIdsFromDoc));

  const otherWorkspaceMemberQuery = {
    ...notInClause("ownerId", userRefIds),
    ...(orQuery([
      inClause("members.userId", userRefIds),
      email ? { "members.email": emailRegex } : null,
    ]) || { _id: "__never__" }),
  };
  const otherWorkspaceMemberships = await workspaces.find(otherWorkspaceMemberQuery).toArray();

  const projectDeleteQuery = orQuery([
    inClause("ownerId", userRefIds),
    inClause("workspaceId", workspaceIdsToDelete),
    email ? { ownerEmail: emailRegex } : null,
  ]) || { _id: "__never__" };
  let projectsToDelete = await projects.find(projectDeleteQuery).toArray();
  let projectMongoIdsToDelete = projectsToDelete.map((p) => p._id);
  let projectIdsToDelete = unique(projectsToDelete.flatMap(getProjectIdsFromDoc));

  if (projectIdsToDelete.length > 0) {
    const relatedProjectDocs = await projects.find(orQuery([
      inClause("projectId", projectIdsToDelete),
      { _id: { $in: projectIdsToDelete } },
    ])).toArray();
    const byId = new Map();
    for (const doc of [...projectsToDelete, ...relatedProjectDocs]) {
      byId.set(docIdToString(doc._id), doc);
    }
    projectsToDelete = [...byId.values()];
    projectMongoIdsToDelete = projectsToDelete.map((p) => p._id);
    projectIdsToDelete = unique(projectsToDelete.flatMap(getProjectIdsFromDoc));
  }

  const otherProjectMemberQuery = {
    ...notInClause("_id", projectMongoIdsToDelete),
    ...(orQuery([
      inClause("members.userId", userRefIds),
      email ? { "members.email": emailRegex } : null,
    ]) || { _id: "__never__" }),
  };
  const otherProjectMemberships = await projects.find(otherProjectMemberQuery).toArray();

  const invitationDeleteQuery = orQuery([
    email ? { inviteeEmail: emailRegex } : null,
    inClause("invitedBy", userRefIds),
    email ? { invitedByEmail: emailRegex } : null,
    inClause("workspaceId", workspaceIdsToDelete),
  ]) || { _id: "__never__" };

  const fileDeleteQuery = orQuery([
    inClause("projectId", projectIdsToDelete),
    inClause("workspaceId", workspaceIdsToDelete),
  ]) || { _id: "__never__" };
  const fileMetadataToDelete = await files.find(fileDeleteQuery).toArray();
  const gridFsIdsFromMetadata = unique(fileMetadataToDelete.map((f) => f.gridfsId));

  const gridFsFileObjectIds = gridFsIdsFromMetadata
    .map(objectIdIfValid)
    .filter(Boolean);

  const gridFsFileQuery = orQuery([
    gridFsFileObjectIds.length ? { _id: { $in: gridFsFileObjectIds } } : null,
    inClause("metadata.projectId", projectIdsToDelete),
  ]) || { _id: "__never__" };

  const gridFsFilesToDelete = await db.collection("fs.files")
    .find(gridFsFileQuery, { projection: { _id: 1, filename: 1 } })
    .toArray()
    .catch(() => []);

  const projectShareDeleteQuery = orQuery([
    inClause("projectId", projectIdsToDelete),
    email ? { ownerEmail: emailRegex } : null,
  ]) || { _id: "__never__" };
  const projectSharePullQuery = {
    ...notInClause("projectId", projectIdsToDelete),
    ...(email ? { sharedWithEmails: emailRegex } : { _id: "__never__" }),
  };

  const issueReportDeleteQuery = orQuery([
    inClause("projectId", projectIdsToDelete),
    email ? { userEmail: emailRegex } : null,
    email ? { reporterEmail: emailRegex } : null,
  ]) || { _id: "__never__" };

  const userChangeQuery = orQuery([
    inClause("projectId", projectIdsToDelete),
    inClause("userId", userRefIds),
    inClause("approvedBy", userRefIds),
    inClause("rejectedBy", userRefIds),
    inClause("resolvedBy", userRefIds),
    inClause("revertedBy", userRefIds),
  ]) || { _id: "__never__" };

  const counts = {
    invitations: await invitations.countDocuments(invitationDeleteQuery),
    projectSharesDelete: await projectShares.countDocuments(projectShareDeleteQuery).catch(() => 0),
    projectSharesPull: await projectShares.countDocuments(projectSharePullQuery).catch(() => 0),
    issueReports: await db.collection("issue_reports").countDocuments(issueReportDeleteQuery).catch(() => 0),
    fileMetadata: fileMetadataToDelete.length,
    gridFsFiles: gridFsFilesToDelete.length,
  };

  for (const name of PROJECT_SCOPED_COLLECTIONS) {
    counts[name] = projectIdsToDelete.length
      ? await db.collection(name).countDocuments({ projectId: { $in: projectIdsToDelete } }).catch(() => 0)
      : 0;
  }

  for (const name of ["ontology_changes", "history_changes", "draft_changes", "draft_sessions"]) {
    counts[name] = await db.collection(name).countDocuments(userChangeQuery).catch(() => 0);
  }

  const draftPRDeleteQuery = orQuery([
    inClause("projectId", projectIdsToDelete),
    inClause("authorId", userRefIds),
  ]) || { _id: "__never__" };
  counts["draft_pull_requests"] = await db.collection("draft_pull_requests")
    .countDocuments(draftPRDeleteQuery).catch(() => 0);

  return {
    user,
    userIdString,
    userRefIds,
    userMongoIds,
    email,
    emailRegex,
    username: user.username || "",
    ownedWorkspaces,
    ownedWorkspaceMongoIds,
    workspaceIdsToDelete,
    otherWorkspaceMemberships,
    projectsToDelete,
    projectMongoIdsToDelete,
    projectIdsToDelete,
    otherProjectMemberships,
    invitationDeleteQuery,
    fileDeleteQuery,
    gridFsFilesToDelete,
    gridFsFileQuery,
    projectShareDeleteQuery,
    projectSharePullQuery,
    issueReportDeleteQuery,
    userChangeQuery,
    draftPRDeleteQuery,
    counts,
  };
}

function printPlan(plan, options) {
  console.log("\nUser cleanup plan");
  console.log("-----------------");
  console.log(`Mode: ${options.execute ? "EXECUTE" : "DRY RUN"}`);
  console.log(`MongoDB database: ${options.mongoDb}`);
  console.log(`User: ${plan.email || "(no email)"} (${plan.username || "no username"})`);
  console.log(`User ID: ${plan.userIdString}`);
  console.log("");
  console.log(`Owned workspaces to delete: ${plan.ownedWorkspaces.length}`);
  for (const workspace of plan.ownedWorkspaces) {
    console.log(`  - ${workspace.workspaceId || docIdToString(workspace._id)} ${workspace.name ? `(${workspace.name})` : ""}`);
  }
  console.log(`Other workspaces to remove this user from: ${plan.otherWorkspaceMemberships.length}`);
  console.log(`Projects to delete: ${plan.projectsToDelete.length}`);
  for (const project of plan.projectsToDelete) {
    const ids = getProjectIdsFromDoc(project).join(", ") || docIdToString(project._id);
    console.log(`  - ${ids} ${project.name ? `(${project.name})` : ""}`);
  }
  console.log(`Other projects to remove this user from: ${plan.otherProjectMemberships.length}`);
  console.log(`Invitations to delete: ${plan.counts.invitations}`);
  console.log(`File metadata records to delete: ${plan.counts.fileMetadata}`);
  console.log(`GridFS files to delete: ${plan.counts.gridFsFiles}`);
  console.log(`Project shares to delete: ${plan.counts.projectSharesDelete}`);
  console.log(`Project shares to update: ${plan.counts.projectSharesPull}`);
  console.log(`Issue reports to delete: ${plan.counts.issueReports}`);
  console.log(`Fuseki project graphs to clear: ${options.skipFuseki ? "skipped" : plan.projectIdsToDelete.length}`);
  console.log(`Project directories to delete: ${options.deleteProjectDirs ? plan.projectIdsToDelete.length : "skipped"}`);

  const scopedTotal = PROJECT_SCOPED_COLLECTIONS
    .map((name) => plan.counts[name] || 0)
    .reduce((sum, count) => sum + count, 0);
  const changeTotal =
    (plan.counts.ontology_changes || 0) +
    (plan.counts.history_changes || 0) +
    (plan.counts.draft_changes || 0) +
    (plan.counts.draft_sessions || 0) +
    (plan.counts.draft_pull_requests || 0);
  console.log(`Project-scoped editor/index docs to delete: ${scopedTotal}`);
  console.log(`Change/draft/session/PR docs to delete: ${changeTotal}`);
}

async function confirmExecution(plan) {
  const rl = readline.createInterface({
    input: process.stdin,
    output: process.stdout,
  });
  try {
    const answer = await rl.question(
      `\nType DELETE ${plan.email || plan.userIdString} to permanently delete this user: `
    );
    return answer === `DELETE ${plan.email || plan.userIdString}`;
  } finally {
    rl.close();
  }
}

async function deleteMany(db, name, query, label) {
  const result = await db.collection(name).deleteMany(query);
  console.log(`  ${label}: deleted ${result.deletedCount}`);
  return result.deletedCount;
}

async function updateMany(db, name, filter, update, label) {
  const result = await db.collection(name).updateMany(filter, update);
  console.log(`  ${label}: matched ${result.matchedCount}, modified ${result.modifiedCount}`);
  return result.modifiedCount;
}

async function applyMongoCleanup(db, plan) {
  console.log("\nApplying MongoDB cleanup...");

  await deleteMany(db, "invitations", plan.invitationDeleteQuery, "invitations");

  if (plan.email) {
    await updateMany(
      db,
      "workspaces",
      notInClause("_id", plan.ownedWorkspaceMongoIds),
      { $pull: { members: { email: plan.emailRegex } } },
      "workspace member email removals"
    );
  }
  await updateMany(
    db,
    "workspaces",
    notInClause("_id", plan.ownedWorkspaceMongoIds),
    { $pull: { members: { userId: { $in: plan.userRefIds } } } },
    "workspace member userId removals"
  );

  if (plan.email) {
    await updateMany(
      db,
      "projects",
      notInClause("_id", plan.projectMongoIdsToDelete),
      { $pull: { members: { email: plan.emailRegex } } },
      "project member email removals"
    );
  }
  await updateMany(
    db,
    "projects",
    notInClause("_id", plan.projectMongoIdsToDelete),
    { $pull: { members: { userId: { $in: plan.userRefIds } } } },
    "project member userId removals"
  );

  await deleteMany(db, "project_shares", plan.projectShareDeleteQuery, "project shares");
  if (plan.email) {
    await updateMany(
      db,
      "project_shares",
      plan.projectSharePullQuery,
      { $pull: { sharedWithEmails: plan.emailRegex } },
      "project share recipient removals"
    );
  }

  await deleteMany(db, "issue_reports", plan.issueReportDeleteQuery, "issue reports");

  for (const name of ["ontology_changes", "history_changes", "draft_changes", "draft_sessions"]) {
    await deleteMany(db, name, plan.userChangeQuery, name);
  }
  await deleteMany(db, "draft_pull_requests", plan.draftPRDeleteQuery, "draft_pull_requests");

  if (plan.projectIdsToDelete.length > 0) {
    for (const name of PROJECT_SCOPED_COLLECTIONS) {
      await deleteMany(
        db,
        name,
        { projectId: { $in: plan.projectIdsToDelete } },
        name
      );
    }
  }

  const gridFileIds = plan.gridFsFilesToDelete.map((file) => file._id);
  if (gridFileIds.length > 0) {
    await deleteMany(db, "fs.chunks", { files_id: { $in: gridFileIds } }, "GridFS chunks");
    await deleteMany(db, "fs.files", { _id: { $in: gridFileIds } }, "GridFS files");
  }

  await deleteMany(db, "file_metadata", plan.fileDeleteQuery, "file metadata");

  if (plan.projectMongoIdsToDelete.length > 0 || plan.projectIdsToDelete.length > 0) {
    await deleteMany(
      db,
      "projects",
      orQuery([
        plan.projectMongoIdsToDelete.length ? { _id: { $in: plan.projectMongoIdsToDelete } } : null,
        inClause("projectId", plan.projectIdsToDelete),
        plan.projectIdsToDelete.length ? { _id: { $in: plan.projectIdsToDelete } } : null,
      ]),
      "owned/workspace projects"
    );
  }

  if (plan.ownedWorkspaceMongoIds.length > 0) {
    await deleteMany(
      db,
      "workspaces",
      { _id: { $in: plan.ownedWorkspaceMongoIds } },
      "owned workspaces"
    );
  }

  await deleteMany(db, "users", { _id: { $in: plan.userMongoIds } }, "user account");
}

function javaUrlEncode(value) {
  return encodeURIComponent(String(value))
    .replace(/[!'()]/g, (char) => `%${char.charCodeAt(0).toString(16).toUpperCase()}`)
    .replace(/\*/g, "%2A");
}

function sparqlString(value) {
  return JSON.stringify(String(value));
}

function projectGraphUpdate(projectId) {
  const encoded = javaUrlEncode(projectId);
  const projectGraph = `http://ontocode.org/project/${encoded}`;
  const oldGraph = `http://ontocode.org/${encoded}`;
  const metadataUri = `http://ontocode.org/metadata/${encoded}`;

  return `
DELETE { GRAPH ?g { ?s ?p ?o } }
WHERE {
  GRAPH ?g { ?s ?p ?o }
  FILTER(STR(?g) = ${sparqlString(projectGraph)} || STRSTARTS(STR(?g), ${sparqlString(`${projectGraph}/`)}))
};
DELETE { GRAPH ?g { ?s ?p ?o } }
WHERE {
  GRAPH ?g { ?s ?p ?o }
  FILTER(STR(?g) = ${sparqlString(oldGraph)} || STRSTARTS(STR(?g), ${sparqlString(`${oldGraph}_v`)}))
};
DELETE WHERE { <${metadataUri}> ?p ?o };
`;
}

function httpRequest(method, targetUrl, headers, body, timeoutMs = 120000) {
  return new Promise((resolve, reject) => {
    const url = new URL(targetUrl);
    const transport = url.protocol === "https:" ? https : http;
    const req = transport.request(
      {
        method,
        hostname: url.hostname,
        port: url.port || (url.protocol === "https:" ? 443 : 80),
        path: `${url.pathname}${url.search}`,
        headers,
        timeout: timeoutMs,
      },
      (res) => {
        const chunks = [];
        res.on("data", (chunk) => chunks.push(chunk));
        res.on("end", () => {
          const responseBody = Buffer.concat(chunks).toString("utf8");
          if (res.statusCode >= 200 && res.statusCode < 300) {
            resolve({ status: res.statusCode, body: responseBody });
          } else {
            reject(new Error(`HTTP ${res.statusCode}: ${responseBody}`));
          }
        });
      }
    );
    req.on("timeout", () => req.destroy(new Error(`Request timed out after ${timeoutMs}ms`)));
    req.on("error", reject);
    if (body) req.write(body);
    req.end();
  });
}

async function applyFusekiCleanup(plan, options) {
  if (options.skipFuseki) {
    console.log("\nSkipping Fuseki cleanup.");
    return;
  }
  if (plan.projectIdsToDelete.length === 0) {
    console.log("\nNo project IDs found for Fuseki cleanup.");
    return;
  }

  console.log("\nClearing Fuseki project graphs...");
  const endpoint = `${options.fusekiUrl}/${encodeURIComponent(options.fusekiDataset)}/update`;
  for (const projectId of plan.projectIdsToDelete) {
    const update = projectGraphUpdate(projectId);
    const body = `update=${encodeURIComponent(update)}`;
    await httpRequest(
      "POST",
      endpoint,
      {
        "Content-Type": "application/x-www-form-urlencoded",
        Accept: "application/json,*/*",
      },
      body
    );
    console.log(`  cleared Fuseki graphs for project ${projectId}`);
  }
}

async function removeProjectDirectories(plan, options) {
  if (!options.deleteProjectDirs || plan.projectIdsToDelete.length === 0) {
    return;
  }

  console.log("\nDeleting project directories...");
  const projectsRoot = path.resolve(options.dataDir, "projects");
  for (const projectId of plan.projectIdsToDelete) {
    const target = path.resolve(projectsRoot, projectId);
    if (target !== projectsRoot && target.startsWith(`${projectsRoot}${path.sep}`)) {
      await fs.rm(target, { recursive: true, force: true });
      console.log(`  deleted ${target}`);
    } else {
      throw new Error(`Refusing to delete unsafe project path: ${target}`);
    }
  }
}

async function main() {
  const options = parseArgs(process.argv.slice(2));
  if (options.help) {
    printUsage();
    return;
  }
  if (!options.identifier) {
    printUsage();
    process.exitCode = 1;
    return;
  }

  const client = new MongoClient(options.mongodbUri);
  await client.connect();
  try {
    const db = client.db(options.mongoDb);
    if (!(await collectionExists(db, "users"))) {
      throw new Error(`MongoDB collection not found: ${options.mongoDb}.users`);
    }

    const users = await findUsers(db, options.identifier);
    if (users.length === 0) {
      throw new Error(`No user found for identifier: ${options.identifier}`);
    }
    if (users.length > 1) {
      console.log("Multiple users matched. Use a unique email, username, or user ID.");
      for (const user of users) {
        console.log(`  - ${user.email || "(no email)"} ${user.username || ""} ${docIdToString(user._id)}`);
      }
      process.exitCode = 1;
      return;
    }

    const plan = await buildUserPlan(db, users[0]);
    printPlan(plan, options);

    if (!options.execute) {
      console.log("\nDry run only. Re-run with --execute after reviewing the plan.");
      return;
    }

    if (!options.yes) {
      const confirmed = await confirmExecution(plan);
      if (!confirmed) {
        console.log("Confirmation did not match. Aborting.");
        process.exitCode = 1;
        return;
      }
    }

    await applyMongoCleanup(db, plan);
    await applyFusekiCleanup(plan, options);
    await removeProjectDirectories(plan, options);

    console.log("\nUser cleanup complete.");
  } finally {
    await client.close();
  }
}

main().catch((error) => {
  console.error(`\nCleanup failed: ${error.message}`);
  if (process.env.DEBUG) {
    console.error(error.stack);
  }
  process.exit(1);
});

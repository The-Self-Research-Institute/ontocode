#!/usr/bin/env node

const http = require("http");
const https = require("https");
const readline = require("readline/promises");
const fs = require("fs/promises");
const path = require("path");
const { newPool, blobsRoot, blobPath } = require("./lib/db");

const PROJECT_SCOPED_TABLES = [
  "ontology_file",
  "project_share",
  "user_project_preference",
  "project_public_graph_version",
  "project_write_lease",
  "project_recovery_lock",
  "draft_change",
  "draft_change_parked",
  "draft_session",
  "draft_pull_request",
  "draft_pr_change",
  "swrl_rule",
  "reasoning_job",
  "reasoning_snapshot",
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
  --fuseki-url=<url>     Override FUSEKI_URL for this run.
  --fuseki-dataset=<name> Override FUSEKI_DATASET for this run.
  --help                 Show this help.

The database connection comes from ONTOCODE_DB_URL, ONTOCODE_DB_USER and ONTOCODE_DB_PASSWORD.
Stored files are removed from ONTOCODE_BLOBS_DIR.
Dry-run is the default. Use --execute --yes only after the plan looks correct.
`);
}

function parseArgs(argv) {
  const options = {
    execute: false,
    yes: false,
    skipFuseki: false,
    deleteProjectDirs: false,
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
  options.fusekiUrl = options.fusekiUrl.replace(/\/+$/, "");
  return options;
}

function unique(values) {
  return [...new Set(values.filter((v) => v !== undefined && v !== null && v !== ""))];
}

async function findUsers(db, identifier) {
  const result = await db.query(
    "select id, username, email from app_user where lower(email) = lower($1) or username = $1 or id = $1",
    [identifier]
  );
  return result.rows;
}

async function buildUserPlan(db, user) {
  const userId = user.id;
  const email = user.email || "";

  const ownedWorkspaces = (await db.query(
    "select id, workspace_id, name from workspace where owner_id = $1", [userId]
  )).rows;
  const workspaceIdsToDelete = unique(ownedWorkspaces.flatMap((w) => [w.workspace_id, w.id]));
  const ownedWorkspaceRowIds = ownedWorkspaces.map((w) => w.id);

  const otherWorkspaceMemberships = (await db.query(
    "select count(*)::int as n from workspace_member m join workspace w on w.id = m.workspace_row_id " +
    "where w.owner_id <> $1 and (m.user_id = $1 or lower(m.email) = lower($2))",
    [userId, email]
  )).rows[0].n;

  const projectsToDelete = (await db.query(
    "select id, project_id, name from project where owner_id = $1 or workspace_id = any($2::text[])",
    [userId, workspaceIdsToDelete]
  )).rows;
  const projectRowIdsToDelete = projectsToDelete.map((p) => p.id);
  const projectIdsToDelete = unique(projectsToDelete.flatMap((p) => [p.project_id]));

  const otherProjectMemberships = (await db.query(
    "select count(*)::int as n from project_member where not (project_row_id = any($3::text[])) " +
    "and (user_id = $1 or lower(email) = lower($2))",
    [userId, email, projectRowIdsToDelete]
  )).rows[0].n;

  const invitationCount = (await db.query(
    "select count(*)::int as n from invitation where lower(invitee_email) = lower($2) or invited_by = $1 " +
    "or lower(invited_by_email) = lower($2) or workspace_id = any($3::text[])",
    [userId, email, workspaceIdsToDelete]
  )).rows[0].n;

  const fileRows = (await db.query(
    "select id, blob_id from file_metadata where project_id = any($1::text[]) or workspace_id = any($2::text[])",
    [projectIdsToDelete, workspaceIdsToDelete]
  )).rows;
  const blobIdsFromMetadata = unique(fileRows.map((f) => f.blob_id));

  const storedFilesToDelete = (await db.query(
    "select id, bucket, storage from stored_file where id = any($1::text[]) or metadata ->> 'projectId' = any($2::text[])",
    [blobIdsFromMetadata, projectIdsToDelete]
  )).rows;

  const shareDelete = (await db.query(
    "select count(*)::int as n from project_share where project_id = any($1::text[]) or lower(owner_email) = lower($2)",
    [projectIdsToDelete, email]
  )).rows[0].n;
  const sharePull = (await db.query(
    "select count(*)::int as n from project_share where not (project_id = any($1::text[])) " +
    "and exists (select 1 from unnest(shared_with_emails) e where lower(e) = lower($2))",
    [projectIdsToDelete, email]
  )).rows[0].n;

  const counts = {
    invitations: invitationCount,
    projectSharesDelete: shareDelete,
    projectSharesPull: sharePull,
    fileMetadata: fileRows.length,
    storedFiles: storedFilesToDelete.length,
  };

  for (const table of PROJECT_SCOPED_TABLES) {
    counts[table] = projectIdsToDelete.length
      ? (await db.query(`select count(*)::int as n from ${table} where project_id = any($1::text[])`, [projectIdsToDelete])).rows[0].n
      : 0;
  }
  counts.draftChangesByUser = (await db.query(
    "select (select count(*) from draft_change where user_id = $1) + (select count(*) from draft_change_parked where user_id = $1) " +
    "+ (select count(*) from draft_session where user_id = $1) + (select count(*) from draft_pull_request where author_id = $1) as n",
    [userId]
  )).rows[0].n;

  return {
    user,
    userId,
    email,
    username: user.username || "",
    ownedWorkspaces,
    ownedWorkspaceRowIds,
    workspaceIdsToDelete,
    otherWorkspaceMemberships,
    projectsToDelete,
    projectRowIdsToDelete,
    projectIdsToDelete,
    otherProjectMemberships,
    storedFilesToDelete,
    counts,
  };
}

function printPlan(plan, options) {
  console.log("\nUser cleanup plan");
  console.log("-----------------");
  console.log(`Mode: ${options.execute ? "EXECUTE" : "DRY RUN"}`);
  console.log(`User: ${plan.email || "(no email)"} (${plan.username || "no username"})`);
  console.log(`User ID: ${plan.userId}`);
  console.log("");
  console.log(`Owned workspaces to delete: ${plan.ownedWorkspaces.length}`);
  for (const workspace of plan.ownedWorkspaces) {
    console.log(`  - ${workspace.workspace_id || workspace.id} ${workspace.name ? `(${workspace.name})` : ""}`);
  }
  console.log(`Other workspaces to remove this user from: ${plan.otherWorkspaceMemberships}`);
  console.log(`Projects to delete: ${plan.projectsToDelete.length}`);
  for (const project of plan.projectsToDelete) {
    console.log(`  - ${project.project_id} ${project.name ? `(${project.name})` : ""}`);
  }
  console.log(`Other projects to remove this user from: ${plan.otherProjectMemberships}`);
  console.log(`Invitations to delete: ${plan.counts.invitations}`);
  console.log(`File metadata records to delete: ${plan.counts.fileMetadata}`);
  console.log(`Stored files to delete: ${plan.counts.storedFiles}`);
  console.log(`Project shares to delete: ${plan.counts.projectSharesDelete}`);
  console.log(`Project shares to update: ${plan.counts.projectSharesPull}`);
  console.log(`Fuseki project graphs to clear: ${options.skipFuseki ? "skipped" : plan.projectIdsToDelete.length}`);
  console.log(`Project directories to delete: ${options.deleteProjectDirs ? plan.projectIdsToDelete.length : "skipped"}`);

  const scopedTotal = PROJECT_SCOPED_TABLES
    .map((name) => plan.counts[name] || 0)
    .reduce((sum, count) => sum + count, 0);
  console.log(`Project-scoped rows to delete: ${scopedTotal}`);
  console.log(`Draft rows owned by the user to delete: ${plan.counts.draftChangesByUser}`);
}

async function confirmExecution(plan) {
  const rl = readline.createInterface({
    input: process.stdin,
    output: process.stdout,
  });
  try {
    const answer = await rl.question(
      `\nType DELETE ${plan.email || plan.userId} to permanently delete this user: `
    );
    return answer === `DELETE ${plan.email || plan.userId}`;
  } finally {
    rl.close();
  }
}

async function applyDatabaseCleanup(pool, plan) {
  console.log("\nApplying PostgreSQL cleanup...");
  const client = await pool.connect();
  const report = (label, result) => console.log(`  ${label}: ${result.rowCount}`);
  try {
    await client.query("begin");
    const { userId, email, projectIdsToDelete, workspaceIdsToDelete, projectRowIdsToDelete, ownedWorkspaceRowIds } = plan;

    report("invitations", await client.query(
      "delete from invitation where lower(invitee_email) = lower($2) or invited_by = $1 " +
      "or lower(invited_by_email) = lower($2) or workspace_id = any($3::text[])",
      [userId, email, workspaceIdsToDelete]
    ));
    report("workspace memberships", await client.query(
      "delete from workspace_member where not (workspace_row_id = any($3::text[])) and (user_id = $1 or lower(email) = lower($2))",
      [userId, email, ownedWorkspaceRowIds]
    ));
    report("project memberships", await client.query(
      "delete from project_member where not (project_row_id = any($3::text[])) and (user_id = $1 or lower(email) = lower($2))",
      [userId, email, projectRowIdsToDelete]
    ));
    report("project shares", await client.query(
      "delete from project_share where project_id = any($1::text[]) or lower(owner_email) = lower($2)",
      [projectIdsToDelete, email]
    ));
    report("project share recipients", await client.query(
      "update project_share set shared_with_emails = array(select e from unnest(shared_with_emails) e where lower(e) <> lower($1)) " +
      "where exists (select 1 from unnest(shared_with_emails) e where lower(e) = lower($1))",
      [email]
    ));
    report("draft changes", await client.query("delete from draft_change where user_id = $1", [userId]));
    report("parked draft changes", await client.query("delete from draft_change_parked where user_id = $1", [userId]));
    report("draft sessions", await client.query("delete from draft_session where user_id = $1", [userId]));
    report("draft pull request changes", await client.query(
      "delete from draft_pr_change where pr_id in (select id from draft_pull_request where author_id = $1)", [userId]
    ));
    report("draft pull requests", await client.query("delete from draft_pull_request where author_id = $1", [userId]));
    report("project preferences", await client.query(
      "delete from user_project_preference where lower(user_email) = lower($1)", [email]
    ));

    if (projectIdsToDelete.length > 0) {
      for (const table of PROJECT_SCOPED_TABLES) {
        report(table, await client.query(`delete from ${table} where project_id = any($1::text[])`, [projectIdsToDelete]));
      }
    }

    const storedIds = plan.storedFilesToDelete.map((f) => f.id);
    if (storedIds.length > 0) {
      report("stored files", await client.query("delete from stored_file where id = any($1::text[])", [storedIds]));
    }
    report("file metadata", await client.query(
      "delete from file_metadata where project_id = any($1::text[]) or workspace_id = any($2::text[])",
      [projectIdsToDelete, workspaceIdsToDelete]
    ));
    if (projectRowIdsToDelete.length > 0) {
      report("projects", await client.query("delete from project where id = any($1::text[])", [projectRowIdsToDelete]));
    }
    if (ownedWorkspaceRowIds.length > 0) {
      report("owned workspaces", await client.query("delete from workspace where id = any($1::text[])", [ownedWorkspaceRowIds]));
    }
    report("user account", await client.query("delete from app_user where id = $1", [userId]));
    await client.query("commit");
  } catch (error) {
    await client.query("rollback");
    throw error;
  } finally {
    client.release();
  }

  const root = blobsRoot();
  let removed = 0;
  for (const file of plan.storedFilesToDelete) {
    if (file.storage === "disk") {
      await fs.rm(blobPath(root, file.bucket, file.id), { force: true });
      removed++;
    }
  }
  console.log(`  files removed from disk: ${removed}`);
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

  const pool = newPool();
  try {
    const users = await findUsers(pool, options.identifier);
    if (users.length === 0) {
      throw new Error(`No user found for identifier: ${options.identifier}`);
    }
    if (users.length > 1) {
      console.log("Multiple users matched. Use a unique email, username, or user ID.");
      for (const user of users) {
        console.log(`  - ${user.email || "(no email)"} ${user.username || ""} ${user.id}`);
      }
      process.exitCode = 1;
      return;
    }

    const plan = await buildUserPlan(pool, users[0]);
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

    await applyDatabaseCleanup(pool, plan);
    await applyFusekiCleanup(plan, options);
    await removeProjectDirectories(plan, options);

    console.log("\nUser cleanup complete.");
  } finally {
    await pool.end();
  }
}

main().catch((error) => {
  console.error(`\nCleanup failed: ${error.message}`);
  if (process.env.DEBUG) {
    console.error(error.stack);
  }
  process.exit(1);
});

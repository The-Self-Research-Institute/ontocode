# Contributing to OntoCode

OntoCode is a **public** repository: anyone can read the code, fork it, and open a pull request. That does not mean write access is open.

**Public visibility ≠ write access.** You cannot fully block forks/PRs on a public repo. You *can* block unsolicited pushes/merges and require approval before someone becomes a real contributor.

## Request to contribute

Before large work (or if you need push access):

1. Open an issue titled `Request to contribute` (or email maintainers if Issues are disabled).
2. Say what you want to work on and whether you need collaborator (write) access.
3. Wait for maintainer approval.
4. Then **fork → branch → PR** (usual path), or accept a collaborator invite if granted for ongoing work.

Small fixes (typos, obvious bugs) via fork + PR are fine without a formal request. Large unsolicited PRs may be deferred until approved.

## Pull request process

1. Fork (or use your collaborator clone if invited).
2. Branch from `main`.
3. Keep changes focused; match existing style.
4. Open a PR against `main`.
5. Address review comments. Only maintainers merge.

## Access model

| Action | Public (no invite) | Collaborator | Maintainer |
|--------|--------------------|--------------|------------|
| Read / fork | Yes | Yes | Yes |
| Open a PR from a fork | Yes | Yes | Yes |
| Push to this repo | No | Yes | Yes |
| Merge to `main` | No | No | Yes |

## Reporting issues

Use GitHub Issues for bugs/features, or in-app **Help → Report Issue** where available. For security-sensitive reports, contact maintainers privately.

---

## How maintainers protect this repo (correct setup)

Do these in order on [github.com/The-Self-Research-Institute/ontocode](https://github.com/The-Self-Research-Institute/ontocode).

### 1. Org team for ownership (stable — usernames can change)

Individual `@username` entries in CODEOWNERS break if that person renames. Use an **org team** instead.

1. Org → **Teams** → New team: `ontocode-maintainers` (visibility: Visible or Secret).
2. Add trusted maintainers to the team (e.g. your account).
3. Give the team **Maintain** (or Admin) on this repo: Repo → **Settings** → **Collaborators and teams** → add team with role **Maintain**.
4. Keep [`.github/CODEOWNERS`](.github/CODEOWNERS) as:

   ```
   * @The-Self-Research-Institute/ontocode-maintainers
   ```

### 2. Do not grant random write access

- Leave the repo **public** if you want visibility.
- **Collaborators**: invite **Write** only after an approved contribution request (ongoing contributors). Prefer fork + PR for one-offs.
- Never rely on “security through obscurity”; rely on rulesets + who has Write/Maintain.

### 3. Protect `main` with a ruleset

Repo → **Settings** → **Rules** → **Rulesets** → **New branch ruleset**:

| Setting | Value |
|---------|--------|
| Ruleset name | `Protect main` |
| Enforcement | Active |
| Target branches | `main` (include by name, or `~DEFAULT_BRANCH`) |
| Restrict deletions | On |
| Block force pushes | On |
| Require a pull request before merging | On |
| Required approvals | `1` (or more) |
| Dismiss stale pull request approvals when new commits are pushed | On |
| Require conversation resolution before merging | On |
| Require review from Code Owners | On |
| Require status checks to pass | On (once CI is reliable; add your required job names) |
| Require branches to be up to date before merging | Recommended with status checks |
| Restrict who can push / bypass | Only org admins / the maintainers team; avoid broad bypass |

Save the ruleset.

Legacy path (same idea): **Settings** → **Branches** → branch protection rule for `main`. Prefer **Rulesets** on orgs.

### 4. Optional hardening

- **Restrict who can create matching branches** / push to matching branches so only the maintainers team can push branch names you care about.
- Disable or limit **Allow force pushes** / **Allow deletions** everywhere for protected refs.
- If you do not want public tickets: disable Issues; use Discussions or email for contribution requests.
- For a **hard** lock (no public PRs on the real codebase): make the working repo **private**, or keep a public read-only mirror and develop in private.

### 5. What this does *not* stop

On a public repo, strangers can still **fork and open PRs**. That is expected. Protection means: they cannot push to your repo, cannot merge to `main`, and cannot become collaborators without your invite.

### Checklist

- [ ] Team `ontocode-maintainers` exists; maintainers are members
- [ ] Team has Maintain on this repo
- [ ] `.github/CODEOWNERS` points at that team
- [ ] Ruleset on `main`: PR required, 1+ approvals, conversation resolution, no force-push/delete, Code Owners required
- [ ] Status checks required (when CI is green and stable)
- [ ] No casual Write invites; request-to-contribute process documented above

## Developing the VS Code extension

Build, run and debug the extension from source. All paths are inside `ontology-vscode-extension/`.

### Overview

The Ontology VS Code Extension allows users to:
- Edit and visualize OWL ontologies directly in VS Code.
- Use a custom web-based editor (built with React).
- Interact with backend ontology services (Auth, Gateway, OWL Editor, SWRL).

It provides an integrated development environment for ontology engineers and knowledge graph developers.

---

### 📁 Folder Structure

```
ontology-vscode-extension/
├── src/
│   ├── extension.ts          # Entry point for the extension (desktop)
│   ├── extension.web.ts      # Entry point for the web extension (vscode.dev / vscode-test-web)
│   ├── collaboration/        # Real-time collaboration (CRDT sync, presence)
│   ├── features/             # Citation insertion and other feature modules
│   ├── services/             # Citation lookup, issue reporting, sci2Code, etc.
│   ├── config/                # Deployment/environment configuration
│   ├── resources/             # Static resources bundled with the extension
│   └── utils/                 # Helper utilities
├── webview-src/               # React-based webview UI (Vite)
│   ├── App.tsx
│   ├── components/
│   ├── contexts/
│   ├── services/
│   ├── hooks/
│   ├── assets/
│   ├── dist/                  # Build output consumed by the extension (git-ignored, built by build:vscode)
│   ├── package.json
│   └── vite.config.ts
├── images/
│   ├── icon.png                # Extension marketplace icon
│   ├── screenshots/            # Static feature screenshots used in this README
│   └── gifs/                   # Feature walkthrough GIFs used in this README
├── package.json               # Extension metadata, contributes, and build scripts
├── tsconfig.json               # TypeScript configuration
└── README.md                  # This file
```

---

### ⚙️ Setup

#### Prerequisites
- **Node.js 18+**
- **npm**
- **VS Code**
- **Ontology Platform backend services** running (optional for UI testing)

---

#### Install Dependencies

From the `ontology-vscode-extension` directory:

```bash
npm install
```

---

### 🏗️ Build

#### 1. Build the Webview (React App)

From the root of `ontology-vscode-extension`:

```bash
npm run build-webview
```

This runs `vite build --base ./` inside `webview-src` and compiles the React webview into `webview-src/dist/`, used by the extension.

> ⚠️ Do **not** run `cd webview-src && npm run build` directly for the VS Code extension. That script builds with an absolute `/` base path intended for the standalone cloud webapp deployment (`Dockerfile.webapp`) and will break asset loading (e.g. the logo will fail to render) inside the VS Code webview. Use `npm run build-webview` (or `webview-src`'s own `npm run build:vscode`) instead. `npm run build:electron` is the equivalent for the Electron desktop app.

---

#### 2. Build the Extension (TypeScript → JavaScript)

From the root of `ontology-vscode-extension`:

```bash
npm run compile
```

---

### 🚀 Run and Debug

#### Launch the Extension in VS Code

1. Open the `ontology-vscode-extension` directory in **VS Code**.
2. Open `extension.ts`.
3. Press **F5** or go to **Run → Start Debugging**.

A new **Extension Development Host** window will open.

---

### 🧰 Development Notes

- The webview communicates with the backend via REST APIs through the **Gateway service**.
- During local development, you can configure the backend URL via `webview-src/.env` (see `.env.production`, `.env.selfhosted-test`, `.env.cloud-test` for other targets).
- Make sure to rebuild (`npm run build-webview`) the React webview after making UI changes, then reload the Extension Development Host (`Ctrl+R` / `Cmd+R` in the host window) or use **Developer: Reload Window**.
- `npm run dev-webview` starts the Vite dev server (`http://localhost:3001`) with HMR for faster UI iteration.

---

### 🧱 Common Commands

```bash
# Build the React webview for the VS Code extension (relative asset paths)
npm run build-webview

# Build + package everything (webview, extension host bundle, web bundle)
npm run bundle:all

# Compile the extension host TypeScript
npm run compile

# Package a .vsix
npm run package

# Run in debug mode
code .

# Lint and check
npm run lint
```

---

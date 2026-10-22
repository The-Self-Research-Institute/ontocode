# 🧩 OntoCode Studio

**Edit, query, reason over, and visualize OWL/RDF ontologies — collaboratively, right inside VS Code.**

A **VS Code extension** for ontology development, providing a full **visual ontology editor** through a React-based webview: SPARQL/DL querying, an AI-assisted change review workflow, an OWL reasoner, D3-powered graph visualization, real-time team collaboration, and a plugin marketplace.
Part of the [OntoCode Studio platform](https://github.com/The-Self-Research-Institute/ontocode). Free plan, no credit card needed.

[![Report a bug](https://img.shields.io/badge/Report%20a%20bug-d73a4a)](https://ontocode.selfresearch.org/report-bug) [![Request a feature](https://img.shields.io/badge/Request%20a%20feature-0e8a16)](https://ontocode.selfresearch.org/request-feature) [![Changelog](https://img.shields.io/badge/Changelog-what's%20new-blue)](./CHANGELOG.md) [![Sponsor](https://img.shields.io/badge/Sponsor-%E2%9D%A4-red)](https://github.com/sponsors/The-Self-Research-Institute) [![Donate](https://img.shields.io/badge/Donate-selfresearch.org-blueviolet)](https://selfresearch.org/donate)

![OntoCode ontology graph visualization with AI insights](https://raw.githubusercontent.com/The-Self-Research-Institute/links/main/ontocode/gifs/graph-visualization.gif)

---

## ⚡ Get started in 60 seconds

1. Install the extension, then click the **OntoCode Studio** icon in the Activity Bar (the vertical strip of icons on the left edge of VS Code).
2. Click **Open OntoCode Editor** and create an account. The Free plan needs no credit card.
3. Create a project or open an ontology file (`.owl`, `.ttl`, `.rdf`) and start editing.

Want to try it without an account or without VS Code? Use the [desktop app](#-download-the-desktop-app), which works offline with no sign-up, or open [ontocode.selfresearch.org](https://ontocode.selfresearch.org/login) in your browser.

---


## 🚀 Features

- **AI Code View assistant** — ask questions about your ontology, or let the AI propose edits in Code View (Turtle, RDF/XML, OWL/XML, Manchester, Functional). Review each change before it is applied. Works with your own OpenAI, Gemini or Claude key, or your organization's provider.
- **Undo and Redo** — `Ctrl+Z` / `Ctrl+Shift+Z` reverse creating, deleting and renaming entities, axiom edits and more. In shared projects, Undo only reverses your own changes.
- **Ontology editing across formats** — the Code View editor reads *and writes* the same ontology as Turtle, RDF/XML, N-Triples, OWL/XML, Manchester, Functional, or JSON-LD. Switch formats and keep editing — it's not a one-way export.
- **SPARQL / DL Query Workbench** — run SPARQL queries against your active ontology and browse results as a table or JSON.
- **Change Assistant** — every edit is tracked as a reviewable change (drafts, saved, conflicts, timeline, rollback) with per-author attribution.
- **Built-in reasoner** — run HermiT (or other supported reasoners) for consistency checking, satisfiability, and inferred class hierarchies.
- **Graph visualization** — explore your ontology as an interactive D3 graph (tree, network, and VOWL notation) with AI-generated topic/cluster insights.
- **Real-time collaboration** — create workspaces, invite members with role-based access, and see collaborator presence live.
- **Citation insertion** — search your reference library and insert items as inline citations directly in `.owl`/`.ttl`/`.rdf`/`.n3` files from Code View — works the same in VS Code, the web app, and the desktop app.
- **Sci2Code editor integration** — the `Ctrl+Shift+C` / **Insert Citation** editor command is powered by [Sci2Code](https://github.com/The-Self-Research-Institute/Sci2Code-extension-for-vscode), inserting a citation at your cursor in any open file (VS Code only).
- **Plugin Marketplace** — install community and first-party plugins (SWRL Rule Editor, Fuzzy Ontology, additional graph visualizations, and more).
- **Desktop app** — an Electron-based desktop build for working outside VS Code.

---

## 🧭 New to VS Code?

- **Install:** open the Extensions view (`Ctrl+Shift+X`, or `Cmd+Shift+X` on a Mac), search for **OntoCode Studio** and click **Install**.
- **Find the icon:** it sits in the Activity Bar on the left. If the bar is hidden, choose **View → Appearance → Activity Bar**.
- **Command Palette:** press `Ctrl+Shift+P` (`Cmd+Shift+P` on a Mac) and type `OntoCode` to see every command.
- **Open an ontology file:** choose **File → Open File**, pick your `.owl`, `.ttl` or `.rdf` file, right-click inside it and choose **OntoCode: Edit Ontology File**. The editor opens as a new tab next to your file.
- **Undo and redo:** `Ctrl+Z` and `Ctrl+Shift+Z` (`Cmd+Z` and `Cmd+Shift+Z` on a Mac) work inside the OntoCode tab.
- **Sign out:** run **OntoCode: Logout** from the Command Palette.

---


## 💻 Download the desktop app

Prefer not to use VS Code? The desktop app is the same editor with its own built-in local backend. No VS Code and no setup needed. Downloads are several hundred MB. All options are on the [download page](https://ontocode.selfresearch.org/download).

| Your computer | Download | How to install |
|---------------|----------|----------------|
| Windows 10 or later (64-bit) | [Windows installer (.exe)](https://ontocodeapi.selfresearch.org/api/downloads/windows-x64) | Run the installer. |
| Linux, any distribution | [AppImage](https://ontocodeapi.selfresearch.org/api/downloads/linux-x64) | `chmod +x OntoCode*.AppImage && ./OntoCode*.AppImage` |
| Debian or Ubuntu | [.deb package](https://ontocodeapi.selfresearch.org/api/downloads/linux-deb) | `sudo apt install ./ontocode-desktop_*_amd64.deb` |
| Linux on ARM64 | [AppImage](https://ontocodeapi.selfresearch.org/api/downloads/linux-arm64) or [.deb](https://ontocodeapi.selfresearch.org/api/downloads/linux-deb-arm64) | Same as above. |
| Flatpak | [Flatpak package](https://ontocodeapi.selfresearch.org/api/downloads/linux-flatpak) | Open the downloaded file with your software center, or install it with `flatpak`. |

Or open it in your browser: [ontocode.selfresearch.org/login](https://ontocode.selfresearch.org/login). A macOS build is on the way.

---

## 🖼 Screenshots

### 1. Workspace & team collaboration
Create a project, choose who can access it, and invite workspace members by email with role-based permissions.

![Create a project and invite workspace members](https://raw.githubusercontent.com/The-Self-Research-Institute/links/main/ontocode/gifs/collaboration.gif)

### 2. Ontology editing across formats (Code View)
Read and edit the same ontology as Turtle, RDF/XML, N-Triples, OWL/XML, Manchester, Functional, or JSON-LD syntax — switch formats and keep editing, it's not a one-way export.

![Code View editing an ontology across Turtle, RDF/XML, N-Triples, OWL/XML, Manchester, Functional, and JSON-LD](https://raw.githubusercontent.com/The-Self-Research-Institute/links/main/ontocode/gifs/code-view-multi-format.gif)

### 3. SPARQL Query Workbench
Write and run SPARQL queries against the active ontology, with results as a sortable table or raw JSON.

![SPARQL Query Editor with results](https://raw.githubusercontent.com/The-Self-Research-Institute/links/main/ontocode/gifs/query-workbench.gif)

### 4. Change Assistant
Every mutation is tracked as a reviewable, attributable change — with drafts, conflicts, a timeline, and one-click rollback.

![Change Assistant reviewing a saved change](https://raw.githubusercontent.com/The-Self-Research-Institute/links/main/ontocode/gifs/change-assistant.gif)

### 5. Built-in OWL reasoner
Run the HermiT reasoner to check consistency, satisfiability, and view the inferred class hierarchy.

![Running the HermiT reasoner](https://raw.githubusercontent.com/The-Self-Research-Institute/links/main/ontocode/gifs/reasoner.gif)

### 6. Citation insertion
Search your reference library and insert a citation directly into the ontology, in whichever format the Code View tab is currently showing. Works identically in VS Code, the web app, and the desktop app.

![Searching and inserting a citation from Code View](https://raw.githubusercontent.com/The-Self-Research-Institute/links/main/ontocode/gifs/sci2code-integration.gif)

### 7. Sci2Code editor integration
A separate, VS Code-only path: run **Insert Citation** (`Ctrl+Shift+C` / `Cmd+Shift+C`) from any open file and it's powered by [Sci2Code](https://github.com/The-Self-Research-Institute/Sci2Code-extension-for-vscode), OntoCode's companion extension. Pick a reference from your library via the command palette and it's inserted at your cursor, formatted to match the file — OntoCode also detects and offers to fix any RDF namespace declarations the citation needs.

![Inserting a citation via Sci2Code's Ctrl+Shift+C command, then fixing missing RDF namespaces](https://raw.githubusercontent.com/The-Self-Research-Institute/links/main/ontocode/gifs/sci2code-editor.gif)

### 8. Plugin Marketplace
Browse, install, and manage plugins — SWRL rule editing, fuzzy ontology support, alternate graph visualizations, and more.

![Plugin Marketplace with installable plugins](https://raw.githubusercontent.com/The-Self-Research-Institute/links/main/ontocode/gifs/plugin-marketplace.gif)

### 9. Graph visualization + AI insights
Explore the ontology as an interactive graph (tree, network, or VOWL notation), with AI-generated topic clusters and trend summaries.

![Ontology graph visualization](https://raw.githubusercontent.com/The-Self-Research-Institute/links/main/ontocode/screenshots/graph-visualization.png)

> Screenshots and GIFs are generated from a real product walkthrough recording — see `images/screenshots/` and `images/gifs/` for the full set.

---

## 🧩 Usage

**Starting from scratch (no ontology file open yet):**

1. Click the OntoCode icon in the Activity Bar.
2. Click **Open OntoCode Editor** at the top of the sidebar.
3. Sign in (or create an account) — the editor opens straight into your projects.

**Starting from an existing `.owl` file:**

1. In the new window, open an `.owl` ontology file.
2. Right-click the file (or open the **Command Palette** with `Ctrl+Shift+P` / `Cmd+Shift+P`) and run **Edit with OntoCode** (`ontocode.edit`) — or **OntoCode: Edit Ontology File** (`ontocode.editLargeFile`) for large files.
3. The **Ontology Web Editor UI** (React webview) will open in a new tab.
4. You can now edit and visualize ontology content.

---

## 💡 Commands

| Command | ID | Description |
|----------|-----|-------------|
| Edit with OntoCode | `ontocode.edit` | Opens the ontology web editor view for an `.owl` file. |
| OntoCode: Edit Ontology File | `ontocode.editLargeFile` | Opens the editor for large ontology files (streamed/buffered load). |
| OntoCode: Logout | `ontocode.logout` | Signs out of the current OntoCode session. |
| Insert Citation | `ontocode.insertCitation` | Inserts a citation into an `.owl`/`.ttl`/`.rdf`/`.n3` file (`Ctrl+Shift+C` / `Cmd+Shift+C`). |
| OntoCode: Show Collaboration Status | `ontocode.showCollaborationStatus` | Shows real-time collaboration/presence status. |
| OntoCode: Configure Citation Library | `ontocode.configureZotero` | Sets up the citation library API key/user ID for citation lookup. |
| OntoCode: Test Citation Library Connection | `ontocode.testZoteroConnection` | Verifies the configured citation library credentials. |
| OntoCode: Test Invitation Flow | `ontocode.testInvitationFlow` | Debug command for testing collaboration invite links. |
| Open OntoCode Editor | `ontocode.openWebview` | Opens the OntoCode webview panel directly. |

See `contributes.commands` in [package.json](./package.json) for the authoritative list.

---

## ⚙️ Settings

Open **Settings** and search `ontocode`:

| Setting | Default | Description |
|---------|---------|-------------|
| `ontocode.zotero.apiKey` | *(empty)* | Your citation library API key. |
| `ontocode.zotero.userId` | *(empty)* | Your citation library user ID. |
| `ontocode.zotero.libraryType` | `user` | Type of citation library to read from: `user` or `group`. |
| `ontocode.zotero.groupId` | *(empty)* | Group ID, required when `libraryType` is `group`. |

See `contributes.configuration` in [package.json](./package.json) for the authoritative list.

---

## 🤝 Contributing

To build and run the extension yourself, see [Developing the VS Code extension](https://github.com/The-Self-Research-Institute/ontocode/blob/main/CONTRIBUTING.md#developing-the-vs-code-extension). Contributions are welcome — see the [GitHub repository](https://github.com/The-Self-Research-Institute/ontocode) for source, and use [Report a bug](https://ontocode.selfresearch.org/report-bug) or [Request a feature](https://ontocode.selfresearch.org/request-feature) to tell us what to fix or add.

---

## 📄 License

[AGPL-3.0](./LICENSE)

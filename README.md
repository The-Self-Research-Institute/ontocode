# OntoCode — Ontology Editing Platform

A **microservices-based ontology editing platform** with a **VS Code extension** and **web editor** for ontology authoring, visualization, reasoning, and collaboration.

---

## Table of Contents

- [Overview](#overview)
- [Features](#features)
  - [Multi-Format Code Editor](#multi-format-code-editor)
  - [Reasoner Engine](#reasoner-engine)
  - [Plugin System](#plugin-system)
  - [Collaboration](#collaboration)
  - [Project Management](#project-management)
  - [Import and Export](#import-and-export)
  - [Citation Management](#citation-management)
  - [Queue Management](#queue-management)
  - [Jira Integration](#jira-integration)
  - [Built-in User Guide](#built-in-user-guide)
- [Architecture](#architecture)
- [Quick Start](#quick-start)
  - [Docker-Only Mode](#docker-only-mode)
  - [Hybrid Mode](#hybrid-mode)
  - [Desktop App](#desktop-app)
- [Local Development Setup](#local-development-setup)
  - [Backend Services](#1-backend-services)
  - [Web Editor (VS Code Extension)](#2-web-editor-vs-code-extension)
- [Project Structure](#project-structure)
- [Build Commands](#build-commands)
- [Dependencies](#dependencies)
- [Contributing](#contributing)
- [Citation](#citation)
- [License](#license)

---

## Overview

OntoCode provides a modular environment for **building, editing, managing, and collaborating** on OWL ontologies. It consists of multiple Java microservices, a rule management service for SWRL, a plugin system with graph visualization, and a React-based web editor that runs inside VS Code or as a standalone web application.

---

## Features

### Multi-Format Code Editor

Edit ontologies in **6 serialization formats** with real-time synchronization:

| Format                | Description                               |
| --------------------- | ----------------------------------------- |
| **Turtle**            | Compact, human-readable RDF syntax        |
| **RDF/XML**           | Standard XML-based RDF serialization      |
| **N-Triples**         | Line-based, plain-text RDF format         |
| **OWL/XML**           | XML syntax for OWL ontologies             |
| **Manchester Syntax** | User-friendly OWL class expression syntax |
| **Functional Syntax** | Compact OWL functional-style syntax       |

- **Cross-format sync**: Edit in one format, save, and all other formats reflect the changes automatically
- **Syntax highlighting**: Format-specific color coding for prefixes, URIs, literals, and keywords
- **View/Edit toggle**: Switch between read-only and editable modes
- **Clickable DOI hyperlinks**: Embedded citations with clickable links across all formats

### Reasoner Engine

Run OWL reasoning directly from the editor with support for **5 reasoners**:

- **HermiT** (default) — Full OWL 2 DL reasoning
- **ELK** — Optimized for OWL 2 EL ontologies
- **Pellet** — OWL 2 DL with SWRL rule support
- **Openllet** — Open-source Pellet fork
- **Structural Reasoner** — Lightweight structural checks

**Capabilities:**

- Classification with inferred hierarchy
- Consistency checking
- Unsatisfiable class detection
- Equivalent class grouping
- Auto-sync mode (re-runs reasoning 2 seconds after edits)
- Statistics dashboard: classes, properties, data properties, individuals

### Plugin System

Extensible architecture with **3 built-in plugins**:

1. **Graph View Plugin** — D3.js force-directed visualization with VOWL notation
   - Handles 100k+ nodes with viewport virtualization and level-of-detail rendering
   - Hierarchical, radial, and circular layouts
   - Smart search with path visualization
   - Export to SVG, PNG, OWL, RDF, JSON-LD, GraphML, Cypher
   - Minimap and fish-eye lens zoom
   - 60 FPS with viewport culling on large graphs

2. **SWRL Editor Plugin** — Visual rule editor with syntax validation and execution
   - Dynamic template generation from ontology schema
   - Classification rules, string matching, property chains, math operations
   - SQWRL query support (SELECT, aggregate functions)

3. **Fuzzy Ontology Plugin** — Fuzzy membership functions and degree-based inference

- Dynamic install/uninstall without restart
- Plugin isolation: each maintains independent state and storage

### Collaboration

- **Workspace model**: Role-based access control (Owner, Admin, Member, Viewer)
- **Member invitations**: Email-based invitations with 7-day expiry
- **Project sharing**: Share with all workspace members or specific individuals
- **Real-time sync**: STOMP WebSocket-based live updates
- **Change approval workflow**: Approve, reject, or comment on edits
- **Conflict resolution**: Built-in detection and manual resolution UI

### Project Management

- **Project Dashboard**: Create, list, and manage projects with descriptions
- **Project Library**: Grid/list views of project files with search and filtering
- **File management**: Upload, download, and delete files with GridFS storage
- **Access control**: Per-project roles (Owner, Admin, Member, Viewer)

### Import and Export

- **Import**: OWL Functional, Manchester, RDF/XML, Turtle, N-Triples, OWL/XML
  - Automatic format conversion for GraphDB compatibility
  - Streaming import for large files (100MB+)
  - Batch optimization: 10k triples per transaction
- **Export**: One-click download in any of the 6 supported formats
- **Queue system**: FIFO processing with position tracking and estimated wait times

### Citation Management

Two methods to manage citations:

1. **Zotero Integration** — Pull citations directly from your Zotero library
2. **Manual Entry** — Add citations with title, author, year, and optional DOI/URL

OntoCode automatically formats and inserts citations into the ontology file and appends metadata to `CITATION.cff`, `CITATIONS.md`, and `references.bib`.

### Queue Management

- FIFO fair processing with position tracking
- Estimated wait time calculation
- Real-time WebSocket notifications for queue status
- Max 1 concurrent import to prevent GraphDB conflicts

### Jira Integration

- **Bug reporting**: Help > Report Issue creates Jira cards automatically
- **API token authentication**: Secure connection via Jira API tokens
- **Connection testing**: Built-in validation endpoint

### Built-in User Guide

Interactive guide accessible from the editor covering 9 sections: workspace creation, member invitations, accepting invitations, project creation, member assignment, file creation, collaboration, issue reporting, and code view usage. Each section includes positive cases, negative cases, and recommendations.

---

## Architecture

| Service               | Description                              | Port    |
| --------------------- | ---------------------------------------- | ------- |
| **Gateway**           | API Gateway for routing requests         | `80`    |
| **Auth**              | Authentication and user management       | `8086`  |
| **OWL Editor**        | Core ontology editing and operations     | `8083`  |
| **SWRL Service**      | SWRL rules engine                        | `8084`  |
| **Plugin Service**    | Plugin management and execution          | `8087`  |
| **GraphDB**           | RDF triple store (Ontotext GraphDB)      | `7200`  |
| **MongoDB**           | Metadata, version control, collaboration | `27017` |
| **Web App**           | Browser-based editor UI                  | `3000`  |
| **VS Code Extension** | Desktop editor client                    | —       |

**Data flow:** GraphDB stores RDF triples (primary). MongoDB stores metadata, collaboration data, and change history. Changes sync automatically between the two.

---

## Quick Start

### Docker-Only Mode

Everything runs in Docker. **Only Docker is required.**

**Windows:**

```cmd
docker-install.bat
```

**PowerShell:**

```powershell
.\docker-install.ps1
```

**Linux/Mac:**

```bash
chmod +x docker-install.sh
./docker-install.sh
```

Access the editor at **http://localhost:3000**

---

### Hybrid Mode

Backend in Docker, VS Code extension runs locally with hot-reload. Requires Node.js.

**Windows:**

```cmd
install-and-run.bat
```

**PowerShell:**

```powershell
.\install-and-run.ps1
```

**Linux/Mac:**

```bash
chmod +x install-and-run.sh
./install-and-run.sh
```

Scripts auto-detect whether Node.js is available and choose the best mode.

---

### Desktop App

Native desktop launchers are available:

- **Windows**: `OntoCodeLauncher.exe` — auto-detects Docker, pulls images, creates desktop shortcuts
- **macOS**: `OntoCode.app` bundle with native launcher script

See [LAUNCHER_README.md](LAUNCHER_README.md) for details.

---

### Docker Compose (Manual)

```bash
docker compose up -d
```

See [INSTALLATION_GUIDE.md](INSTALLATION_GUIDE.md) for detailed comparison of all installation modes.

---

## Local Development Setup

### 1. Backend Services

#### Prerequisites

- **JDK 21**
- **Maven 3.8+**
- **MongoDB 6.0+**
- **GraphDB 10.0+** with a repository named `ontocode`

#### Setup GraphDB

```bash
# Using Docker
docker run -d -p 7200:7200 --name graphdb ontotext/graphdb:10.7.0-free
```

Then create the `ontocode` repository:

1. Open http://localhost:7200
2. Navigate to **Setup > Repositories > Create new repository**
3. Set Repository ID: `ontocode`, Ruleset: `OWL2-RL (Optimized)`
4. Click **Create**

See [GRAPHDB_SETUP.md](GRAPHDB_SETUP.md) for complete instructions.

#### Build and Run

```bash
mvn clean install
./scripts/start-services.sh
```

---

### 2. Web Editor (VS Code Extension)

1. **Install dependencies**

   ```bash
   cd ontology-vscode-extension
   npm i
   cd webview-src
   npm i
   ```

2. **Build the webview**

   ```bash
   npm run build
   ```

3. **Launch in VS Code**

   ```bash
   cd ..
   code .
   ```

   Open `extension.ts`, press **F5** to start debugging.

4. **Test**: A new Extension Development Host window opens. Open an `.owl` file and run the **OntoCode: Edit** command.

---

## Project Structure

```
ontocode/
├── ontology-gateway/              # API Gateway
├── ontology-auth/                 # Authentication service
├── ontology-editor/               # OWL editing service
├── ontology-swrl-service/         # SWRL rules engine
├── ontology-plugin-service/       # Plugin management service
├── shared/                        # Shared libraries
│   ├── common-models/
│   └── common-utils/
├── ontology-vscode-extension/     # VS Code extension
│   └── webview-src/               # React-based web editor UI
├── docker-compose.yml             # Docker orchestration
├── Dockerfile.*                   # Service-specific Dockerfiles
└── *.bat / *.sh / *.ps1           # Installation and build scripts
```

---

## Build Commands

```bash
# Build all backend services
mvn clean install

# Build a specific service
cd ontology-editor
mvn clean package

# Build webview UI
cd ontology-vscode-extension/webview-src
npm run build

# Bundle VS Code extension
cd ontology-vscode-extension
npm run bundle:extension
```

---

## Dependencies

- **Java 21+**
- **Maven 3.8+**
- **Node.js 18+**
- **MongoDB 6.0+**
- **GraphDB 10.0+**
- **Docker** (for containerized deployment)
- **VS Code** (for extension development)

---

## Contributing

Contributions are welcome. Fork the repository, create a feature branch, and submit a pull request.

---

## Citation

If you use this software in your research, please cite it:

```bibtex
@software{ontocode_2025,
  author = {{OntoCode Team}},
  title = {OntoCode Ontology Platform},
  version = {1.0.0},
  date = {2025-12-23},
  url = {https://github.com/ontocode/ontocode}
}
```

See also [CITATION.cff](CITATION.cff), [CITATIONS.md](CITATIONS.md), and [references.bib](references.bib).

---

## License

[GPL v3 License](LICENSE)

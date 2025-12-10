# 🧩 Ontology Platform

A **microservices-based ontology editing platform** with a **VS Code extension** for ontology authoring and visualization.

---

## 📚 Table of Contents
- [Overview](#overview)
- [Services](#services)
- [Quick Start](#quick-start)
  - [Using Docker](#using-docker)
  - [Using Scripts](#using-scripts)
- [Local Development Setup](#local-development-setup)
  - [1. Backend Services](#1-backend-services)
  - [2. How to Run the Web Editor (VS Code Extension)](#2-how-to-run-the-web-editor-vs-code-extension)
- [Project Structure](#project-structure)
- [Build Commands](#build-commands)
- [Features](#features)
- [Dependencies](#dependencies)
- [Contributing](#contributing)
- [License](#license)

---

## 🧠 Overview

The Ontology Platform provides a modular environment for **building, editing, and managing OWL ontologies**.  
It consists of multiple Java microservices, a rule management service for **SWRL**, and a **VS Code extension** with an integrated React-based web editor.

---

## ⚙️ Services

| Service | Description | Port |
|----------|--------------|------|
| **Gateway** | Main API Gateway for routing requests | `8082` |
| **Auth** | Handles authentication and user management | `8083` |
| **OWL Editor** | Core ontology editing and operations | `8084` |
| **SWRL Service** | Manages the SWRL rules engine | `8085` |
| **VS Code Extension** | A desktop ontology editor client for VS Code | — |

---

## 🚀 Quick Start

### Using Docker
To spin up all backend services with a single command:

```bash
docker-compose up
```

---

### Using Scripts
You can also use the provided helper scripts.

```bash
# Setup (run once)
./scripts/setup.sh

# Start all backend services
./scripts/start-services.sh
```

---

## 💻 Local Development Setup

Follow these steps to run services manually for local development.

---

### 1. Backend Services

These include **Gateway**, **Auth**, **OWL Editor**, and **SWRL Service**.

#### Prerequisites
- **JDK 21** (Java Development Kit)
- **Maven 3.8+**
- **MongoDB 5.0+** (for metadata and version control)
- **GraphDB 10.0+** (Ontotext GraphDB or GraphDB Free Edition)
  - **⚠️ REQUIRED**: GraphDB must be running with a repository named `ontocode`
  - See [GRAPHDB_SETUP.md](GRAPHDB_SETUP.md) for detailed setup instructions

#### Setup GraphDB (Required)

Before running the application, you must set up GraphDB:

```bash
# Option 1: Download and run GraphDB Free Edition
# Visit: https://www.ontotext.com/products/graphdb/download/

# Option 2: Using Docker
docker run -d -p 7200:7200 --name graphdb ontotext/graphdb:10.7.0-free
```

Then create the `ontocode` repository:
1. Open GraphDB Workbench: http://localhost:7200
2. Navigate to **Setup → Repositories**
3. Click **Create new repository**
4. Set Repository ID: `ontocode`
5. Set Ruleset: `OWL2-RL (Optimized)`
6. Click **Create**

For complete instructions, see [GRAPHDB_SETUP.md](GRAPHDB_SETUP.md)

#### Build All Services

From the project root:

```bash
mvn clean install
```

#### Run Services

```bash
./scripts/start-services.sh
```

---

### 2. How to Run the Web Editor (VS Code Extension)

The web editor is a **React-based webview** that runs inside the **VS Code extension** (`ontology-vscode-extension`).

#### Steps

1. **Navigate to the extension directory**
   ```bash
   cd ontology-vscode-extension
   ```

2. **Install extension dependencies**
   ```bash
   npm i
   ```

3. **Build the webview (React UI)**
   ```bash
   cd webview-src
   npm i
   npm run build
   ```

4. **Launch the VS Code Extension**
   ```bash
   cd ..
   code .
   ```

   - Open `extension.ts`
   - Press **F5** or select **Run → Start Debugging**

5. **Test**
   - A new **Extension Development Host** window will open.
   - Open an `.owl` file.
   - Run the **OntoCode: Edit** command.
   - The **web editor UI** will appear in a new tab.

---

## 🧱 Project Structure

```
ontology-platform/
├── ontology-gateway/           # API Gateway
├── ontology-auth/              # Authentication service
├── ontology-editor/            # OWL editing service
├── ontology-swrl-service/      # SWRL rules engine
├── shared/                     # Shared libraries
│   ├── common-models/
│   └── common-utils/
└── ontology-vscode-extension/  # VS Code extension (with webview React UI)
```

---

## 🏗️ Build Commands

```bash
# Build all services
mvn clean install

# Build a specific service
cd ontology-gateway
mvn clean package
```

---

## ✨ Features

- Modular microservices architecture  
- Centralized API Gateway  
- Secure user authentication  
- Ontology editing via OWL  
- Integrated SWRL rule management  
- VS Code extension with built-in web UI  

---

## 📦 Dependencies

- **Java 17+**
- **Maven 3.8+**
- **Node.js 18+**
- **MongoDB**
- **VS Code** (for the extension)

---

## 🤝 Contributing

Contributions are welcome!  
Please fork the repository, create a feature branch, and submit a pull request.

---

## 📄 License

[MIT License](LICENSE)

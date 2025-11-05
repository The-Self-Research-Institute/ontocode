# 🧩 Ontology VS Code Extension

A **VS Code extension** for ontology development, providing a **visual ontology editor** through a React-based webview.  
This extension is part of the larger [Ontology Platform](../README.md) but can be built and run independently.

---

## 📚 Table of Contents
- [Overview](#overview)
- [Folder Structure](#folder-structure)
- [Setup](#setup)
- [Build](#build)
- [Run and Debug](#run-and-debug)
- [Usage](#usage)
- [Commands](#commands)
- [Development Notes](#development-notes)
- [License](#license)

---

## 🧠 Overview

The Ontology VS Code Extension allows users to:
- Edit and visualize OWL ontologies directly in VS Code.
- Use a custom web-based editor (built with React).
- Interact with backend ontology services (Auth, Gateway, OWL Editor, SWRL).

It provides an integrated development environment for ontology engineers and knowledge graph developers.

---

## 📁 Folder Structure

```
ontology-vscode-extension/
├── src/
│   ├── extension.ts          # Entry point for the extension
│   ├── commands/             # VS Code command definitions
│   └── utils/                # Helper utilities
├── webview-src/              # React-based webview UI
│   ├── src/
│   ├── public/
│   ├── package.json
│   └── tsconfig.json
├── package.json              # Extension metadata and dependencies
├── tsconfig.json             # TypeScript configuration
└── README.md                 # This file
```

---

## ⚙️ Setup

### Prerequisites
- **Node.js 18+**
- **npm**
- **VS Code**
- **Ontology Platform backend services** running (optional for UI testing)

---

### Install Dependencies

From the `ontology-vscode-extension` directory:

```bash
npm install
```

---

## 🏗️ Build

### 1. Build the Webview (React App)

Navigate to the webview source folder:

```bash
cd webview-src
npm install
npm run build
```

This compiles the React webview into a `dist/` folder used by the extension.

---

### 2. Build the Extension (TypeScript → JavaScript)

From the root of `ontology-vscode-extension`:

```bash
npm run compile
```

---

## 🚀 Run and Debug

### Launch the Extension in VS Code

1. Open the `ontology-vscode-extension` directory in **VS Code**.
2. Open `extension.ts`.
3. Press **F5** or go to **Run → Start Debugging**.

A new **Extension Development Host** window will open.

---

## 🧩 Usage

1. In the new window, open an `.owl` ontology file.
2. Open the **Command Palette** (`Ctrl+Shift+P` / `Cmd+Shift+P`).
3. Run the command:

   ```
   OntoCode: Edit
   ```

4. The **Ontology Web Editor UI** (React webview) will open in a new tab.
5. You can now edit and visualize ontology content.

---

## 💡 Commands

| Command | Description |
|----------|-------------|
| `OntoCode: Edit` | Opens the ontology web editor view for an `.owl` file. |
| `OntoCode: Reload Webview` | Rebuilds and refreshes the webview content. |

---

## 🧰 Development Notes

- The webview communicates with the backend via REST APIs through the **Gateway service**.
- During local development, you can configure the backend URL in the webview’s `.env` file.
- Make sure to rebuild (`npm run build`) the React webview after making UI changes.

---

## 🧱 Common Commands

```bash
# Build the React webview
cd webview-src && npm run build

# Compile the extension
npm run compile

# Run in debug mode
code .

# Lint and check
npm run lint
```

---

## 📄 License

[MIT License](../LICENSE)

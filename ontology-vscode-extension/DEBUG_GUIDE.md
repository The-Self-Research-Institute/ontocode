# Running the OntoCode VS Code Extension

## Quick Start

1. **Build the webview** (do this first):
   ```bash
   cd webview-src
   npm install
   npm run build
   ```

2. **Compile the extension**:
   ```bash
   npm install
   npm run compile
   ```

3. **Press F5** to launch the Extension Development Host

4. **In the new VS Code window**, run the command:
   - Press `Ctrl+Shift+P`
   - Type: `OntoCode: Edit File`
   - Select an OWL file or it will prompt you

## Development

### Watch Mode (Auto-compile on changes)
```bash
npm run watch
```

### Build Webview
```bash
cd webview-src
npm run build
```

### Debug
- Set breakpoints in `src/extension.ts`
- Press F5 to start debugging
- Check Debug Console for logs

## Troubleshooting

### "Cannot find module" errors
```bash
npm install
cd webview-src && npm install
```

### Webview not loading
```bash
cd webview-src
npm run build
```

### Extension not activating
1. Check that services are running:
   - Auth Service: http://localhost:8086
   - Gateway: http://localhost:8082
   - OWL Editor: http://localhost:8083
   - GraphDB: http://localhost:7200

2. Check the Output panel: "OntoCode Extension" for logs

### "Command not found"
Make sure the extension is compiled:
```bash
npm run compile
```

## Architecture

- **Extension Host**: `src/extension.ts` - VS Code extension logic
- **Webview**: `webview-src/` - React app for the editor UI
- **Collaboration**: `src/collaboration/` - WebSocket collaboration manager
- **Services**: Backend Spring Boot services (separate processes)

# OntoCode Environment Configuration

This extension uses environment variables to configure deployment URLs. This allows you to easily switch between different environments without modifying code.

## Configuration Files

- **`.env`**: Your local environment configuration (git-ignored for security)
- **`.env.example`**: Template showing all available configuration options

## Setup

1. Copy `.env.example` to `.env`:
   ```bash
   cp .env.example .env
   ```

2. Edit `.env` with your deployment URLs:
   ```bash
   # Self-hosted Deployment URLs
   SELF_HOSTED_GATEWAY_URL=http://localhost:80
   SELF_HOSTED_EDITOR_URL=http://localhost:80
   SELF_HOSTED_PLUGIN_URL=http://localhost:8087

   # Cloud Deployment URLs
   CLOUD_GATEWAY_URL=https://ontocodeapi.selfresearch.org
   CLOUD_EDITOR_URL=https://ontocodeapi.selfresearch.org
   CLOUD_PLUGIN_URL=https://ontocodeapi.selfresearch.org:8087

   # Default Deployment Type (self-hosted or cloud)
   DEFAULT_DEPLOYMENT_TYPE=cloud
   ```

3. Rebuild the extension:
   ```bash
   npm install
   npm run compile
   ```

## Available Environment Variables

| Variable | Description | Default |
|----------|-------------|---------|
| `SELF_HOSTED_GATEWAY_URL` | Gateway URL for self-hosted deployment | `http://localhost:80` |
| `SELF_HOSTED_EDITOR_URL` | Editor URL for self-hosted deployment | `http://localhost:80` |
| `SELF_HOSTED_PLUGIN_URL` | Plugin service URL for self-hosted deployment | `http://localhost:8087` |
| `CLOUD_GATEWAY_URL` | Gateway URL for cloud deployment | `https://ontocodeapi.selfresearch.org` |
| `CLOUD_EDITOR_URL` | Editor URL for cloud deployment | `https://ontocodeapi.selfresearch.org` |
| `CLOUD_PLUGIN_URL` | Plugin service URL for cloud deployment | `https://ontocodeapi.selfresearch.org:8087` |
| `DEFAULT_DEPLOYMENT_TYPE` | Default deployment selection | `cloud` |

## How It Works

1. **Extension loads**: The extension reads environment variables from `.env` using dotenv
2. **Webview injection**: Environment config is injected into the webview as `window.__ONTOCODE_CONFIG__`
3. **User selection**: When users select a deployment type (self-hosted or cloud), the appropriate URLs are used
4. **Dynamic switching**: Users can switch between deployments at runtime through the deployment selector UI

## Notes

- The `.env` file is git-ignored to prevent accidentally committing sensitive URLs
- Always use `.env.example` as a reference for required variables
- Changes to `.env` require rebuilding the extension (`npm run compile`)

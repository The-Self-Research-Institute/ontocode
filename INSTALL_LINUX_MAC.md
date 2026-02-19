# OntoCode - Linux & macOS Installation

## Requirements
- **macOS:** Docker Desktop (10.13+)
- **Linux:** Docker Engine + Docker Compose
- 8 GB RAM, 20 GB disk space

## Installation Steps

1. Download and extract the OntoCode ZIP file
2. Install Docker (macOS: https://www.docker.com/products/docker-desktop, Linux: https://docs.docker.com/engine/install/)
3. Open Terminal in OntoCode folder
4. Right-click inside folder → Select "Open in Terminal"
5. Run: `chmod +x OntoCodeLauncher.sh`
6. Run: `./OntoCodeLauncher.sh`
7. Wait for setup (3-5 minutes first time)
8. Browser opens to http://localhost:3000
9. Click **File** → **Open File** to open OWL file
10. Right-click file → **OntoCode: Process Large OWL File**

**Desktop shortcut created automatically**  
**Stop:** `docker compose down`

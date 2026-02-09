using System;
using System.Diagnostics;
using System.IO;
using System.Threading;

namespace OntoCodeLauncher
{
    class Program
    {
        static string REGISTRY = "sindhujacoretopia";
        static string VERSION = "latest";
        
        // Direct configuration - no .env needed
        static string MONGO_INITDB_ROOT_USERNAME = "admin";
        static string MONGO_INITDB_ROOT_PASSWORD = "changeme123";
        static string MONGO_INITDB_DATABASE = "ontocode";
        static string JWT_SECRET = "AVjDXKlnmDh4K26HTaEwHvNBN3IWXpbcGxt+2sveqGA=";
        static string GRAPHDB_ADMIN_PASSWORD = "admin";

        static void Main(string[] args)
        {
            Console.Title = "OntoCode Launcher";
            
            Console.ForegroundColor = ConsoleColor.Cyan;
            Console.WriteLine();
            Console.WriteLine("========================================");
            Console.WriteLine("   OntoCode One-Click Installation");
            Console.WriteLine("   Registry: " + REGISTRY);
            Console.WriteLine("========================================");
            Console.ResetColor();
            Console.WriteLine();

            try
            {
                // Step 1: Check Docker
                Console.WriteLine("[1/6] Checking Docker...");
                if (!CheckDocker())
                {
                    Console.ForegroundColor = ConsoleColor.Red;
                    Console.WriteLine("[ERROR] Docker is not running. Please start Docker Desktop.");
                    Console.ResetColor();
                    Console.WriteLine("Install from: https://www.docker.com/products/docker-desktop");
                    Console.WriteLine("\nPress any key to exit...");
                    Console.ReadKey();
                    return;
                }
                Console.ForegroundColor = ConsoleColor.Green;
                Console.WriteLine("[OK] Docker is running");
                Console.ResetColor();

                // Step 2: Prepare workspace
                Console.WriteLine();
                Console.WriteLine("[2/6] Preparing workspace...");
                PrepareWorkspace();
                Console.ForegroundColor = ConsoleColor.Green;
                Console.WriteLine("[OK] Workspace ready");
                Console.ResetColor();

                // Step 3: Check/Pull images
                Console.WriteLine();
                Console.WriteLine("[3/6] Checking images...");
                CheckAndPullImages();
                Console.ForegroundColor = ConsoleColor.Green;
                Console.WriteLine("[OK] Images ready");
                Console.ResetColor();

                // Step 4: Start services
                Console.WriteLine();
                Console.WriteLine("[4/6] Checking and starting services...");
                StartServices();
                Console.ForegroundColor = ConsoleColor.Green;
                Console.WriteLine("[OK] All services active");
                Console.ResetColor();

                // Step 5: Create desktop shortcut
                Console.WriteLine();
                Console.WriteLine("[5/6] Creating desktop shortcut...");
                CreateDesktopShortcut();

                // Step 6: Wait and open
                Console.WriteLine();
                Console.WriteLine("[6/6] Waiting for services to be ready...");
                int waitTime = IsServiceRunning() ? 5 : 40;
                Thread.Sleep(waitTime * 1000);
                Console.ForegroundColor = ConsoleColor.Green;
                Console.WriteLine("[OK] Services initialized");
                Console.ResetColor();

                // Display info
                Console.WriteLine();
                Console.ForegroundColor = ConsoleColor.Cyan;
                Console.WriteLine("========================================");
                Console.WriteLine("   OntoCode is running!");
                Console.WriteLine("========================================");
                Console.ResetColor();
                Console.WriteLine();
                Console.WriteLine("   VS Code Web Editor:  http://localhost:3000");
                Console.WriteLine("   API Gateway:         http://localhost:80");
                Console.WriteLine("   GraphDB:             http://localhost:7200");
                Console.WriteLine("   MongoDB:             mongodb://localhost:27017");
                Console.WriteLine();
                Console.WriteLine("   Stop:  docker compose down");
                Console.WriteLine("   Logs:  docker compose logs -f");
                Console.WriteLine();
                Console.ForegroundColor = ConsoleColor.Cyan;
                Console.WriteLine("========================================");
                Console.WriteLine("Opening VS Code Web Editor...");
                Console.ResetColor();

                Thread.Sleep(3000);
                Process.Start(new ProcessStartInfo("http://localhost:3000") { UseShellExecute = true });

                Console.WriteLine();
                Console.WriteLine("Press any key to exit...");
                Console.ReadKey();
            }
            catch (Exception ex)
            {
                Console.ForegroundColor = ConsoleColor.Red;
                Console.WriteLine("\n[ERROR] " + ex.Message);
                Console.ResetColor();
                Console.WriteLine("\nPress any key to exit...");
                Console.ReadKey();
            }
        }

        static bool CheckDocker()
        {
            return RunCommand("docker", "ps", true) == 0;
        }

        static void PrepareWorkspace()
        {
            string dataPath = Path.Combine(Directory.GetCurrentDirectory(), "data", "projects");
            Directory.CreateDirectory(dataPath);

            // Create .env file with direct configuration
            string envFile = Path.Combine(Directory.GetCurrentDirectory(), ".env");
            Console.WriteLine("[INFO] Creating configuration...");
            
            string envContent = 
                "# MongoDB Configuration\n" +
                "MONGO_INITDB_ROOT_USERNAME=" + MONGO_INITDB_ROOT_USERNAME + "\n" +
                "MONGO_INITDB_ROOT_PASSWORD=" + MONGO_INITDB_ROOT_PASSWORD + "\n" +
                "MONGO_INITDB_DATABASE=" + MONGO_INITDB_DATABASE + "\n" +
                "MONGO_URI=mongodb://admin:" + MONGO_INITDB_ROOT_PASSWORD + "@mongodb:27017/" + MONGO_INITDB_DATABASE + "?authSource=admin\n" +
                "\n" +
                "# JWT Configuration\n" +
                "JWT_SECRET=" + JWT_SECRET + "\n" +
                "JWT_EXPIRATION=86400\n" +
                "\n" +
                "# GraphDB Configuration\n" +
                "GRAPHDB_URL=http://graphdb:7200\n" +
                "GRAPHDB_REPOSITORY=ontocode\n" +
                "GRAPHDB_ADMIN_PASSWORD=" + GRAPHDB_ADMIN_PASSWORD + "\n" +
                "\n" +
                "# Service URLs\n" +
                "AUTH_SERVICE_URL=http://ontocode-auth:8081\n" +
                "EDITOR_SERVICE_URL=http://ontocode-editor:8082\n" +
                "PLUGIN_SERVICE_URL=http://ontocode-plugin:8084\n" +
                "SWRL_SERVICE_URL=http://ontocode-swrl:8085\n" +
                "\n" +
                "# Docker Registry\n" +
                "DOCKER_REGISTRY=" + REGISTRY + "\n";
            
            File.WriteAllText(envFile, envContent);
        }

        static void CheckAndPullImages()
        {
            // Check if main image exists
            string checkCmd = "images " + REGISTRY + "/ontocode-gateway:" + VERSION + " --format \"{{.Repository}}\"";
            var output = RunCommandWithOutput("docker", checkCmd);
            
            if (output.Contains("ontocode-gateway"))
            {
                Console.WriteLine("[INFO] Images already available");
            }
            else
            {
                Console.WriteLine("[INFO] Pulling pre-built images from " + REGISTRY + "...");
                Console.WriteLine("This may take a few minutes on first run...");
                Console.WriteLine();

                string[] images = {
                    "ontocode-graphdb", "ontocode-auth", "ontocode-gateway",
                    "ontocode-editor", "ontocode-swrl", "ontocode-plugin",
                    "ontocode-plugin-init", "ontocode-vscode-web"
                };

                foreach (var image in images)
                {
                    Console.Write("   Pulling " + REGISTRY + "/" + image + ":" + VERSION + "...");
                    int result = RunCommand("docker", "pull " + REGISTRY + "/" + image + ":" + VERSION, true);
                    if (result == 0)
                    {
                        Console.ForegroundColor = ConsoleColor.Green;
                        Console.WriteLine(" [OK]");
                        Console.ResetColor();
                    }
                    else
                    {
                        Console.ForegroundColor = ConsoleColor.Yellow;
                        Console.WriteLine(" [WARN] Failed - will build locally");
                        Console.ResetColor();
                    }
                }
                Console.WriteLine();
            }
        }

        static void StartServices()
        {
            // Check if docker-compose.yml exists
            string composeFile = Path.Combine(Directory.GetCurrentDirectory(), "docker-compose.yml");
            if (!File.Exists(composeFile))
            {
                Console.ForegroundColor = ConsoleColor.Red;
                Console.WriteLine("[ERROR] docker-compose.yml not found!");
                Console.ResetColor();
                Console.WriteLine();
                Console.WriteLine("The OntoCode executable must be placed in the OntoCode project directory.");
                Console.WriteLine("Expected location: " + composeFile);
                Console.WriteLine();
                Console.WriteLine("Please:");
                Console.WriteLine("  1. Download the complete OntoCode project");
                Console.WriteLine("  2. Place this executable in the project root folder");
                Console.WriteLine("  3. Run it again");
                throw new Exception("docker-compose.yml not found in current directory");
            }
            
            // Check if services are already running
            var output = RunCommandWithOutput("docker", "compose ps --services --filter \"status=running\"");
            
            if (output.Contains("ontocode-gateway"))
            {
                Console.WriteLine("[INFO] Services are already running");
            }
            else
            {
                Console.WriteLine("[INFO] Starting services...");
                RunCommand("docker", "compose down", true);
                
                var psi = new ProcessStartInfo
                {
                    FileName = "docker",
                    Arguments = "compose up -d",
                    UseShellExecute = false,
                    RedirectStandardOutput = true,
                    RedirectStandardError = true,
                    CreateNoWindow = false,
                    WorkingDirectory = Directory.GetCurrentDirectory()
                };
                psi.EnvironmentVariables["DOCKER_REGISTRY"] = REGISTRY;
                
                var process = Process.Start(psi);
                string errorOutput = process.StandardError.ReadToEnd();
                string standardOutput = process.StandardOutput.ReadToEnd();
                process.WaitForExit();
                
                if (process.ExitCode != 0)
                {
                    Console.ForegroundColor = ConsoleColor.Red;
                    Console.WriteLine("[ERROR] Failed to start services.");
                    Console.ResetColor();
                    Console.WriteLine();
                    Console.WriteLine("Error details:");
                    if (!string.IsNullOrEmpty(errorOutput))
                    {
                        Console.WriteLine(errorOutput);
                    }
                    if (!string.IsNullOrEmpty(standardOutput))
                    {
                        Console.WriteLine(standardOutput);
                    }
                    Console.WriteLine();
                    Console.WriteLine("Common issues:");
                    Console.WriteLine("  1. Docker is not running - Start Docker Desktop");
                    Console.WriteLine("  2. Ports already in use - Stop conflicting services");
                    Console.WriteLine("  3. Missing images - Run 'docker compose pull' manually");
                    Console.WriteLine("  4. Insufficient resources - Check Docker Desktop settings");
                    throw new Exception("Failed to start services. See error details above.");
                }
                Console.WriteLine("[OK] All services started");
            }
        }

        static void CreateDesktopShortcut()
        {
            try
            {
                string desktopPath = Environment.GetFolderPath(Environment.SpecialFolder.Desktop);
                string shortcutPath = Path.Combine(desktopPath, "OntoCode.lnk");
                string targetPath = Process.GetCurrentProcess().MainModule.FileName;
                string workingDir = Directory.GetCurrentDirectory();

                string script = 
                    "$WshShell = New-Object -comObject WScript.Shell; " +
                    "$Shortcut = $WshShell.CreateShortcut('" + shortcutPath + "'); " +
                    "$Shortcut.TargetPath = '" + targetPath + "'; " +
                    "$Shortcut.WorkingDirectory = '" + workingDir + "'; " +
                    "$Shortcut.Description = 'One-click launcher for OntoCode'; " +
                    "$Shortcut.Save()";

                var psi = new ProcessStartInfo
                {
                    FileName = "powershell",
                    Arguments = "-Command \"" + script + "\"",
                    UseShellExecute = false,
                    RedirectStandardOutput = true,
                    RedirectStandardError = true,
                    CreateNoWindow = true
                };

                var process = Process.Start(psi);
                process.WaitForExit();

                if (File.Exists(shortcutPath))
                {
                    Console.ForegroundColor = ConsoleColor.Green;
                    Console.WriteLine("[OK] Desktop shortcut created");
                    Console.ResetColor();
                }
                else
                {
                    Console.ForegroundColor = ConsoleColor.Yellow;
                    Console.WriteLine("[WARN] Could not create desktop shortcut");
                    Console.ResetColor();
                }
            }
            catch
            {
                Console.ForegroundColor = ConsoleColor.Yellow;
                Console.WriteLine("[WARN] Could not create desktop shortcut");
                Console.ResetColor();
            }
        }

        static bool IsServiceRunning()
        {
            var output = RunCommandWithOutput("docker", "compose ps --services --filter \"status=running\"");
            return output.Contains("ontocode-gateway");
        }

        static int RunCommand(string fileName, string arguments, bool silent)
        {
            try
            {
                var psi = new ProcessStartInfo
                {
                    FileName = fileName,
                    Arguments = arguments,
                    UseShellExecute = false,
                    RedirectStandardOutput = true,
                    RedirectStandardError = true,
                    CreateNoWindow = true,
                    WorkingDirectory = Directory.GetCurrentDirectory()
                };

                var process = Process.Start(psi);
                if (!silent)
                {
                    Console.WriteLine(process.StandardOutput.ReadToEnd());
                }
                process.WaitForExit();
                return process.ExitCode;
            }
            catch
            {
                return 1;
            }
        }

        static string RunCommandWithOutput(string fileName, string arguments)
        {
            try
            {
                var psi = new ProcessStartInfo
                {
                    FileName = fileName,
                    Arguments = arguments,
                    UseShellExecute = false,
                    RedirectStandardOutput = true,
                    RedirectStandardError = true,
                    CreateNoWindow = true,
                    WorkingDirectory = Directory.GetCurrentDirectory()
                };

                var process = Process.Start(psi);
                string output = process.StandardOutput.ReadToEnd();
                process.WaitForExit();
                return output;
            }
            catch
            {
                return "";
            }
        }
    }
}

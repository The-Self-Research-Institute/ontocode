package self.research.ontology.auth.controller;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;

/**
 * Controller to handle web-based invitation redirects
 * Provides an HTML landing page that redirects to VS Code
 */
@Controller
public class WebInvitationController {

    private static final Logger log = LoggerFactory.getLogger(WebInvitationController.class);

    /**
     * Handle web invitation links from emails
     * Serves an HTML page that auto-redirects to vscode:// URI
     */
    @GetMapping(value = "/invite", produces = MediaType.TEXT_HTML_VALUE)
    @ResponseBody
    public String handleInviteRedirect(@RequestParam("token") String token) {
        log.info("📧 Web invitation accessed for token: {}...", token.substring(0, Math.min(8, token.length())));
        
        String vscodeLink = "vscode://self.ontocode-extension/invite?token=" + token;
        
        return "<!DOCTYPE html>" +
            "<html lang=\"en\">" +
            "<head>" +
            "    <meta charset=\"UTF-8\">" +
            "    <meta name=\"viewport\" content=\"width=device-width, initial-scale=1.0\">" +
            "    <title>OntoCode Invitation</title>" +
            "    <style>" +
            "        * { margin: 0; padding: 0; box-sizing: border-box; }" +
            "        body {" +
            "            font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, Arial, sans-serif;" +
            "            background: linear-gradient(135deg, #667eea 0%, #764ba2 100%);" +
            "            min-height: 100vh;" +
            "            display: flex;" +
            "            align-items: center;" +
            "            justify-content: center;" +
            "            padding: 20px;" +
            "        }" +
            "        .container {" +
            "            background: white;" +
            "            border-radius: 16px;" +
            "            box-shadow: 0 20px 60px rgba(0, 0, 0, 0.3);" +
            "            padding: 48px;" +
            "            max-width: 600px;" +
            "            text-align: center;" +
            "            animation: slideUp 0.5s ease-out;" +
            "        }" +
            "        @keyframes slideUp {" +
            "            from { opacity: 0; transform: translateY(30px); }" +
            "            to { opacity: 1; transform: translateY(0); }" +
            "        }" +
            "        .logo {" +
            "            width: 80px;" +
            "            height: 80px;" +
            "            background: linear-gradient(135deg, #667eea 0%, #764ba2 100%);" +
            "            border-radius: 20px;" +
            "            display: flex;" +
            "            align-items: center;" +
            "            justify-content: center;" +
            "            margin: 0 auto 24px;" +
            "            color: white;" +
            "            font-size: 40px;" +
            "        }" +
            "        h1 { color: #1a202c; font-size: 32px; font-weight: 700; margin-bottom: 16px; }" +
            "        p { color: #4a5568; font-size: 16px; line-height: 1.6; margin-bottom: 32px; }" +
            "        .spinner {" +
            "            width: 40px;" +
            "            height: 40px;" +
            "            border: 4px solid #e2e8f0;" +
            "            border-top-color: #667eea;" +
            "            border-radius: 50%;" +
            "            animation: spin 0.8s linear infinite;" +
            "            margin: 24px auto;" +
            "        }" +
            "        @keyframes spin { to { transform: rotate(360deg); } }" +
            "        .button {" +
            "            display: inline-block;" +
            "            background: linear-gradient(135deg, #667eea 0%, #764ba2 100%);" +
            "            color: white;" +
            "            padding: 14px 32px;" +
            "            text-decoration: none;" +
            "            border-radius: 8px;" +
            "            font-weight: 600;" +
            "            font-size: 16px;" +
            "            transition: transform 0.2s, box-shadow 0.2s;" +
            "            margin-top: 16px;" +
            "            border: none;" +
            "            cursor: pointer;" +
            "        }" +
            "        .button:hover {" +
            "            transform: translateY(-2px);" +
            "            box-shadow: 0 8px 20px rgba(102, 126, 234, 0.4);" +
            "        }" +
            "        .manual-link {" +
            "            margin-top: 24px;" +
            "            padding-top: 24px;" +
            "            border-top: 1px solid #e2e8f0;" +
            "        }" +
            "        .manual-link p { font-size: 13px; color: #718096; margin-bottom: 12px; }" +
            "        .link-box {" +
            "            background: #f7fafc;" +
            "            padding: 12px;" +
            "            border-radius: 8px;" +
            "            word-break: break-all;" +
            "            font-family: 'Courier New', monospace;" +
            "            font-size: 12px;" +
            "            color: #2d3748;" +
            "            max-height: 80px;" +
            "            overflow-y: auto;" +
            "        }" +
            "        .status { margin-top: 16px; font-size: 14px; color: #718096; }" +
            "    </style>" +
            "</head>" +
            "<body>" +
            "    <div class=\"container\">" +
            "        <div class=\"logo\">🎯</div>" +
            "        <h1>OntoCode Invitation</h1>" +
            "        <p>You've been invited to join a workspace on OntoCode!</p>" +
            "        <div id=\"status\">" +
            "            <p class=\"status\">Click below to open your invitation in VS Code:</p>" +
            "        </div>" +
            "        <button id=\"openVSCode\" class=\"button\" onclick=\"openVSCode()\">" +
            "            🚀 Open in VS Code" +
            "        </button>" +
            "        <div class=\"manual-link\" id=\"manualLink\">" +
            "            <p><strong>Didn't work?</strong> Copy and use this link:</p>" +
            "            <div class=\"link-box\">" + vscodeLink + "</div>" +
            "            <p style=\"margin-top: 12px; font-size: 12px;\">" +
            "                Paste this link in your browser's address bar and press Enter" +
            "            </p>" +
            "        </div>" +
            "    </div>" +
            "    <script>" +
            "        const vscodeLink = '" + vscodeLink + "';" +
            "        const inviteToken = '" + token + "';" +
            "        " +
            "        console.log('Invitation page ready - will open in VS Code extension webview');" +
            "        " +
            "        // Open in VS Code extension (which will display invitation in its webview)" +
            "        function openVSCode() {" +
            "            console.log('Opening invitation in VS Code:', vscodeLink);" +
            "            try {" +
            "                window.location.href = vscodeLink;" +
            "                document.getElementById('status').innerHTML = " +
            "                    '<p class=\\'status\\' style=\\'color: #10b981;\\'>✓ Opening VS Code...</p>';" +
            "            } catch (e) {" +
            "                console.log('Failed to open VS Code:', e);" +
            "                document.getElementById('status').innerHTML = " +
            "                    '<p class=\\'status\\' style=\\'color: #ef4444;\\'>Failed to open VS Code. Try the manual link below.</p>';" +
            "            }" +
            "        }" +
            "    </script>" +
            "</body>" +
            "</html>";
    }
}

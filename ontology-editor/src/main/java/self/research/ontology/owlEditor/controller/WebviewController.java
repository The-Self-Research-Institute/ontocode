package self.research.ontology.owlEditor.controller;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

@Controller
public class WebviewController {

    private static final Logger log = LoggerFactory.getLogger(WebviewController.class);

    @GetMapping("/assets/{filename}")
    public ResponseEntity<Resource> serveAsset(@PathVariable String filename) {
        try {

            Path distPath = Paths.get("../ontology-vscode-extension/webview-src/dist/assets").normalize();
            File distFile = distPath.toFile();

            Resource resource = null;

            if (distFile.exists()) {
                Path assetPath = distPath.resolve(filename);
                if (Files.exists(assetPath)) {
                    resource = new FileSystemResource(assetPath);
                    log.debug("Serving asset from filesystem: {}", assetPath);
                }
            }

            if (resource == null || !resource.exists()) {
                resource = new ClassPathResource("static/assets/" + filename);
                log.debug("Serving asset from classpath: static/assets/{}", filename);
            }

            if (!resource.exists()) {
                log.warn("Asset not found: /assets/{}", filename);
                return ResponseEntity.notFound().build();
            }

            String contentType = determineContentType(filename);

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.parseMediaType(contentType));
            headers.setCacheControl("public, max-age=3600");

            return new ResponseEntity<>(resource, headers, HttpStatus.OK);

        } catch (Exception e) {
            log.error("Error serving asset: {}", filename, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }

    @GetMapping("/projects/**")
    public ResponseEntity<Resource> serveWebview() {
        try {

            Path distPath = Paths.get("../ontology-vscode-extension/webview-src/dist/index.html").normalize();
            File distFile = distPath.toFile();

            Resource resource;
            if (distFile.exists()) {
                resource = new FileSystemResource(distFile);
                log.debug("Serving webview from filesystem: {}", distPath);
            } else {

                resource = new ClassPathResource("static/index.html");
                log.debug("Serving webview from classpath");
            }

            if (!resource.exists()) {
                log.error("Webview index.html not found!");
                return ResponseEntity.notFound().build();
            }

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.TEXT_HTML);
            headers.setCacheControl("no-cache, no-store, must-revalidate");

            return new ResponseEntity<>(resource, headers, HttpStatus.OK);

        } catch (Exception e) {
            log.error("Error serving webview", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }

    private String determineContentType(String filename) {
        String lower = filename.toLowerCase();

        if (lower.endsWith(".js")) {
            return "application/javascript";
        } else if (lower.endsWith(".css")) {
            return "text/css";
        } else if (lower.endsWith(".json")) {
            return "application/json";
        } else if (lower.endsWith(".png")) {
            return "image/png";
        } else if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) {
            return "image/jpeg";
        } else if (lower.endsWith(".svg")) {
            return "image/svg+xml";
        } else if (lower.endsWith(".woff") || lower.endsWith(".woff2")) {
            return "font/woff2";
        } else if (lower.endsWith(".ttf")) {
            return "font/ttf";
        }

        return "application/octet-stream";
    }
}

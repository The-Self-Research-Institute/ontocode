package self.research.ontology.owlEditor.controller;

import org.eclipse.rdf4j.rio.RDFFormat;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import self.research.ontology.owlEditor.model.ImportOptions;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

final class UploadFileSupport {

    private static final Logger log = LoggerFactory.getLogger(UploadFileSupport.class);

    private UploadFileSupport() {
    }

    static String sha256Hex(byte[] data) {
        try {
            java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(data);
            StringBuilder sb = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    static boolean isOntologyPackage(String filename, String contentType) {
        String lowerName = filename != null ? filename.toLowerCase(Locale.ROOT) : "";
        String lowerContentType = contentType != null ? contentType.toLowerCase(Locale.ROOT) : "";
        return lowerName.endsWith(".zip")
                || lowerContentType.contains("zip")
                || lowerContentType.contains("x-zip-compressed");
    }

    static void extractOntologyPackage(Path packageZip, Path targetDir) throws IOException {
        Path normalizedTarget = targetDir.toAbsolutePath().normalize();
        try (ZipInputStream zip = new ZipInputStream(Files.newInputStream(packageZip))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                Path destination = normalizedTarget.resolve(entry.getName()).normalize();
                if (!destination.startsWith(normalizedTarget)) {
                    throw new IOException("Unsafe ZIP entry outside target directory: " + entry.getName());
                }
                if (entry.isDirectory()) {
                    Files.createDirectories(destination);
                } else {
                    Files.createDirectories(destination.getParent());
                    Files.copy(zip, destination, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                }
                zip.closeEntry();
            }
        }
    }

    static Optional<Path> selectPackageRootOntology(Path libraryDir, String packageFilename) throws IOException {
        String packageBaseName = packageFilename != null ? packageFilename : "";
        int dot = packageBaseName.lastIndexOf('.');
        if (dot > 0) {
            packageBaseName = packageBaseName.substring(0, dot);
        }
        final String normalizedPackageBase = packageBaseName.toLowerCase(Locale.ROOT);

        List<Path> candidates = new ArrayList<>();
        try (java.util.stream.Stream<Path> stream = Files.walk(libraryDir, 8)) {
            stream
                    .filter(Files::isRegularFile)
                    .filter(UploadFileSupport::isOntologyDocumentFile)
                    .forEach(candidates::add);
        }
        if (candidates.isEmpty()) {
            return Optional.empty();
        }

        candidates.sort(Comparator
                .comparingInt((Path path) -> scoreRootCandidate(libraryDir, path, normalizedPackageBase))
                .thenComparing(path -> libraryDir.relativize(path).toString()));
        return Optional.of(candidates.get(0));
    }

    static int scoreRootCandidate(Path libraryDir, Path path, String normalizedPackageBase) {
        Path relative = libraryDir.relativize(path);
        String fileName = path.getFileName() != null ? path.getFileName().toString().toLowerCase(Locale.ROOT) : "";
        String base = fileName;
        int dot = base.lastIndexOf('.');
        if (dot > 0) {
            base = base.substring(0, dot);
        }

        if (!normalizedPackageBase.isBlank() && base.equals(normalizedPackageBase)) {
            return 0;
        }
        if (relative.getNameCount() == 1 && (fileName.equals("root.owl") || fileName.equals("ontology.owl"))) {
            return 1;
        }
        if (relative.getNameCount() == 1) {
            return 2;
        }
        if (fileName.equals("root.owl") || fileName.equals("ontology.owl")) {
            return 3;
        }
        return 4;
    }

    static boolean isOntologyDocumentFile(Path path) {
        String name = path.getFileName() != null ? path.getFileName().toString().toLowerCase(Locale.ROOT) : "";
        if (name.equals("catalog-v001.xml")) {
            return false;
        }
        return name.endsWith(".owl")
                || name.endsWith(".rdf")
                || name.endsWith(".xml")
                || name.endsWith(".ttl")
                || name.endsWith(".n3")
                || name.endsWith(".nt")
                || name.endsWith(".jsonld")
                || name.endsWith(".owlxml");
    }

    static void deleteRecursively(Path path) throws IOException {
        if (path == null || !Files.exists(path)) {
            return;
        }
        try (java.util.stream.Stream<Path> stream = Files.walk(path)) {
            List<Path> paths = stream.sorted(Comparator.reverseOrder()).toList();
            for (Path p : paths) {
                Files.deleteIfExists(p);
            }
        }
    }

    static ImportOptions resolveImportOptions(String importMode, String partition) {
        ImportOptions.ImportMode mode = ImportOptions.ImportMode.FULL;
        if (importMode != null) {
            switch (importMode.toLowerCase(Locale.ROOT)) {
                case "incremental" -> mode = ImportOptions.ImportMode.INCREMENTAL;
                case "diff" -> mode = ImportOptions.ImportMode.DIFF;
                default -> mode = ImportOptions.ImportMode.FULL;
            }
        }

        ImportOptions.PartitionStrategy strategy = ImportOptions.PartitionStrategy.NONE;
        if (partition != null && partition.equalsIgnoreCase("namespace")) {
            strategy = ImportOptions.PartitionStrategy.NAMESPACE;
        }

        return ImportOptions.builder()
                .mode(mode)
                .partitionStrategy(strategy)
                .build();
    }

    static RDFFormat detectFormat(Path file) {
        String fileName = file.getFileName().toString().toLowerCase(Locale.ROOT);

        if (fileName.endsWith(".ttl") || fileName.endsWith(".turtle")) {
            return RDFFormat.TURTLE;
        } else if (fileName.endsWith(".nt") || fileName.endsWith(".ntriples")) {
            return RDFFormat.NTRIPLES;
        } else if (fileName.endsWith(".jsonld")) {
            return RDFFormat.JSONLD;
        } else if (fileName.endsWith(".n3")) {
            return RDFFormat.N3;
        }

        if (fileName.endsWith(".owl") || fileName.endsWith(".rdf")) {
            RDFFormat detectedFormat = detectFormatByContent(file);
            if (detectedFormat != null) {
                log.info("Detected format by content for {}: {}", fileName, detectedFormat);
                return detectedFormat;
            }
        }

        return RDFFormat.RDFXML;
    }

    static RDFFormat detectFormatByContent(Path file) {
        try {
            byte[] header = java.nio.file.Files.readAllBytes(file);
            int readLength = Math.min(2048, header.length);

            int offset = 0;
            if (header.length >= 3 && header[0] == (byte) 0xEF && 
                header[1] == (byte) 0xBB && header[2] == (byte) 0xBF) {
                offset = 3;
            }

            while (offset < readLength && (header[offset] == ' ' || header[offset] == '\t' || 
                   header[offset] == '\n' || header[offset] == '\r')) {
                offset++;
            }

            String content = new String(header, offset, Math.min(readLength - offset, 1024), 
                                       java.nio.charset.StandardCharsets.UTF_8);
            String contentLower = content.toLowerCase(Locale.ROOT);

            if (contentLower.startsWith("<?xml") || contentLower.contains("<rdf:rdf") || 
                contentLower.contains("<owl:ontology") || contentLower.contains("<ontology")) {
                log.info("Detected RDF/XML format (found XML markers)");
                return RDFFormat.RDFXML;
            }

            if (contentLower.startsWith("@prefix") || contentLower.startsWith("@base") ||
                contentLower.contains("@prefix ") || contentLower.contains("@base ")) {
                log.info("Detected Turtle format (found @prefix or @base directive)");
                return RDFFormat.TURTLE;
            }

            if (content.matches("(?s)^\\s*<[^>]+>\\s+<[^>]+>\\s+.*")) {
                log.info("Detected N-Triples format");
                return RDFFormat.NTRIPLES;
            }

            if (contentLower.trim().startsWith("{") && contentLower.contains("@context")) {
                log.info("Detected JSON-LD format");
                return RDFFormat.JSONLD;
            }

            log.warn("Unable to detect format by content, will use default");
            return null;

        } catch (Exception e) {
            log.warn("Failed to detect format by content: {}", e.getMessage());
            return null;
        }
    }
}

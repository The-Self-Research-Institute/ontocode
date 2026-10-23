package self.research.ontology.plugins.controller;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import self.research.ontology.plugins.service.PluginOwnershipException;

import java.util.Map;

@RestControllerAdvice
public class PluginOwnershipAdvice {

    @ExceptionHandler(PluginOwnershipException.class)
    public ResponseEntity<Map<String, String>> handleOwnership(PluginOwnershipException ex) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("message", ex.getMessage()));
    }
}

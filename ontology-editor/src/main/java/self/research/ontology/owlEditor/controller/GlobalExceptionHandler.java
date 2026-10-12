package self.research.ontology.owlEditor.controller;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.context.request.async.AsyncRequestNotUsableException;
import self.research.ontology.owlEditor.service.DraftNotReadyException;
import self.research.ontology.owlEditor.service.ProjectWriteLeaseUnavailableException;

import java.util.Map;
import java.util.concurrent.TimeoutException;

@RestControllerAdvice("owlEditorGlobalExceptionHandler")
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(AsyncRequestNotUsableException.class)
    public void handleClientAbort(AsyncRequestNotUsableException ex) {
        log.debug("Client disconnected before response was fully sent (ignored): {}", ex.getMessage());

    }

    @ExceptionHandler(DraftNotReadyException.class)
    public ResponseEntity<Map<String, Object>> handleDraftNotReady(DraftNotReadyException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of(
                        "success", false,
                        "draftCopyNotReady", true,
                        "error", ex.getMessage()
                ));
    }

    @ExceptionHandler(ProjectWriteLeaseUnavailableException.class)
    public ResponseEntity<Map<String, Object>> handleWriteLeaseUnavailable(ProjectWriteLeaseUnavailableException ex) {
        log.warn("Write lease unavailable: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of(
                        "success", false,
                        "error", "Another request is still writing to this project — please try again in a moment."
                ));
    }

    @ExceptionHandler(org.springframework.dao.DataAccessException.class)
    public ResponseEntity<Map<String, Object>> handleDataAccess(org.springframework.dao.DataAccessException ex) {
        log.error("Database error: {}", ex.getMessage(), ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of(
                        "success", false,
                        "error", "Couldn't save this — the request may be too large. Try a smaller batch of changes."
                ));
    }

    @ExceptionHandler({TimeoutException.class, ResourceAccessException.class})
    public ResponseEntity<Map<String, Object>> handleTimeout(Exception ex) {
        log.warn("Request timed out: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.GATEWAY_TIMEOUT)
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of(
                        "success", false,
                        "error", "This took too long to complete. Try again, or with a smaller change."
                ));
    }

    @ExceptionHandler(HttpStatusCodeException.class)
    public ResponseEntity<Map<String, Object>> handleDownstreamServiceError(HttpStatusCodeException ex) {
        log.error("Downstream service error: {} {}", ex.getStatusCode(), ex.getMessage());
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of(
                        "success", false,
                        "error", "The reasoner service couldn't process this request. Try again in a moment."
                ));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> handleIllegalArgument(IllegalArgumentException ex) {
        log.warn("Bad request: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of(
                        "success", false,
                        "error", ex.getMessage() != null ? ex.getMessage() : "Bad request"
                ));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> handleException(Exception ex) {
        log.error("Unhandled exception in controller: {}", ex.getMessage(), ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of(
                        "success", false,
                        "error", ex.getMessage() != null ? ex.getMessage() : "Internal server error"
                ));
    }
}

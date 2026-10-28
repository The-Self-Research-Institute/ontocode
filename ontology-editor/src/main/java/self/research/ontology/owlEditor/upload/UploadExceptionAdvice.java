package self.research.ontology.owlEditor.upload;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MultipartException;
import self.research.ontology.owlEditor.service.ImportQueueFullException;

import java.util.Map;

@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
public class UploadExceptionAdvice {

    @ExceptionHandler(UploadLimitExceededException.class)
    public ResponseEntity<Map<String, Object>> tooLarge(UploadLimitExceededException ex) {
        return body(ex);
    }

    @ExceptionHandler(MultipartException.class)
    public ResponseEntity<Map<String, Object>> multipart(MultipartException ex) throws MultipartException {
        UploadLimitExceededException u = find(ex);
        if (u != null) {
            return body(u);
        }
        throw ex;
    }

    @ExceptionHandler(ImportQueueFullException.class)
    public ResponseEntity<Map<String, Object>> queueFull(ImportQueueFullException ex) {
        return busy(ex);
    }

    public static ResponseEntity<Map<String, Object>> busy(ImportQueueFullException ex) {
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .header("Retry-After", String.valueOf(ex.getRetryAfterSeconds()))
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("success", false, "busy", true, "reason", ex.getReason(),
                        "retryAfterSeconds", ex.getRetryAfterSeconds(), "message", ex.getMessage()));
    }

    public static UploadLimitExceededException find(Throwable t) {
        for (int i = 0; t != null && i < 12; i++, t = t.getCause()) {
            if (t instanceof UploadLimitExceededException u) {
                return u;
            }
        }
        return null;
    }

    public static ResponseEntity<Map<String, Object>> body(UploadLimitExceededException ex) {
        return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE)
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("success", false, "error", ex.getMessage(), "limitBytes", ex.getLimitBytes()));
    }
}

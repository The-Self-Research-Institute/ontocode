package self.research.ontology.owlEditor.controller;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import self.research.ontology.common.disk.InsufficientDiskSpaceException;

import java.util.LinkedHashMap;
import java.util.Map;

@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
public class DiskSpaceExceptionAdvice {

    @ExceptionHandler(InsufficientDiskSpaceException.class)
    public ResponseEntity<Map<String, Object>> handleDiskFull(InsufficientDiskSpaceException full) {
        return response(full);
    }

    public static ResponseEntity<Map<String, Object>> response(InsufficientDiskSpaceException full) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("success", false);
        body.put("diskFull", true);
        body.put("error", full.getMessage());
        body.put("message", full.getMessage());
        return ResponseEntity.status(HttpStatus.INSUFFICIENT_STORAGE)
                .contentType(MediaType.APPLICATION_JSON)
                .body(body);
    }
}

package self.research.ontology.owlEditor.controller;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import self.research.ontology.owlEditor.service.BusyException;

import java.util.LinkedHashMap;
import java.util.Map;

@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
public class BusyExceptionAdvice {

    @ExceptionHandler(BusyException.class)
    public ResponseEntity<Map<String, Object>> handleBusy(BusyException busy) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("busy", true);
        body.put("reason", busy.reason() == null ? null : busy.reason().name());
        body.put("retryAfterSeconds", busy.retryAfterSeconds());
        body.put("message", busy.getMessage());
        ResponseEntity.BodyBuilder response = ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .contentType(MediaType.APPLICATION_JSON);
        if (busy.retryAfterSeconds() > 0) {
            response.header(HttpHeaders.RETRY_AFTER, Long.toString(busy.retryAfterSeconds()));
        }
        return response.body(body);
    }
}

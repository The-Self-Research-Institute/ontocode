package self.research.ontology.owlEditor.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.reactive.function.client.ClientRequest;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.ExchangeFunction;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

class OpenProjectServiceTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private AtomicReference<Function<ClientRequest, Mono<ClientResponse>>> responder;

    @BeforeEach
    void setUp() {
        responder = new AtomicReference<>();
    }

    private OpenProjectService newService(boolean enabled) {
        ExchangeFunction exchangeFunction = request -> responder.get().apply(request);
        WebClient.Builder builder = WebClient.builder().exchangeFunction(exchangeFunction);
        OpenProjectService service = new OpenProjectService(builder, objectMapper);
        ReflectionTestUtils.setField(service, "enabled", enabled);
        ReflectionTestUtils.setField(service, "host", "https://openproject.test");
        ReflectionTestUtils.setField(service, "apiKey", "test-key");
        ReflectionTestUtils.setField(service, "projectId", "DEMO");
        ReflectionTestUtils.setField(service, "configuredParentId", "");
        ReflectionTestUtils.setField(service, "configuredStatus", "");
        ReflectionTestUtils.setField(service, "assigneeId", "");
        ReflectionTestUtils.setField(service, "defaultType", "Task");
        ReflectionTestUtils.setField(service, "lookupTimeoutSeconds", 5L);
        ReflectionTestUtils.setField(service, "createTimeoutSeconds", 5L);
        ReflectionTestUtils.setField(service, "attachmentTimeoutSeconds", 5L);
        ReflectionTestUtils.setField(service, "retryMaxAttempts", 0L);
        return service;
    }

    private static ClientResponse jsonResponse(HttpStatus status, String body) {
        return ClientResponse.create(status)
                .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .body(body)
                .build();
    }

    @Test
    void isEnabledRequiresAllOfHostApiKeyAndProjectId() {
        OpenProjectService service = newService(true);
        assertTrue(service.isEnabled());

        ReflectionTestUtils.setField(service, "apiKey", "");
        assertFalse(service.isEnabled());
    }

    @Test
    void isEnabledFalseWhenDisabledEvenIfConfigured() {
        OpenProjectService service = newService(false);
        assertFalse(service.isEnabled());
    }

    @Test
    void validateConnectionReturnsFailureMessageWhenDisabled() {
        OpenProjectService service = newService(false);
        OpenProjectService.OpenProjectValidationResult result = service.validateConnection();
        assertFalse(result.isSuccess());
        assertEquals("OpenProject integration is disabled or not configured", result.getMessage());
    }

    @Test
    void validateConnectionSucceedsOnValidProjectResponse() {
        OpenProjectService service = newService(true);
        responder.set(req -> Mono.just(jsonResponse(HttpStatus.OK, "{\"name\":\"Demo Project\"}")));

        OpenProjectService.OpenProjectValidationResult result = service.validateConnection();

        assertTrue(result.isSuccess());
        assertEquals("Demo Project", result.getProjectName());
        assertTrue(result.getMessage().contains("Demo Project"));
    }

    @Test
    void validateConnectionMaps404ToMembershipHint() {
        OpenProjectService service = newService(true);
        responder.set(req -> Mono.error(WebClientResponseException.create(
                404, "Not Found", HttpHeaders.EMPTY, new byte[0], StandardCharsets.UTF_8)));

        OpenProjectService.OpenProjectValidationResult result = service.validateConnection();

        assertFalse(result.isSuccess());
        assertTrue(result.getMessage().contains("not a member of project"));
    }

    @Test
    void describeErrorMaps401And403ToTokenHint() {
        OpenProjectService service = newService(true);
        String message401 = service.describeError(WebClientResponseException.create(
                401, "Unauthorized", HttpHeaders.EMPTY, new byte[0], StandardCharsets.UTF_8));
        String message403 = service.describeError(WebClientResponseException.create(
                403, "Forbidden", HttpHeaders.EMPTY, new byte[0], StandardCharsets.UTF_8));

        assertTrue(message401.contains("rejected the API token"));
        assertTrue(message403.contains("rejected the API token"));
    }

    @Test
    void describeErrorExtractsOpenProjectMessageAndErrorIdentifier() {
        OpenProjectService service = newService(true);
        String body = "{\"_type\":\"Error\",\"errorIdentifier\":\"urn:openproject-org:api:v3:errors:PropertyConstraintViolation\",\"message\":\"Subject can't be blank\"}";
        String message = service.describeError(WebClientResponseException.create(
                422, "Unprocessable Entity", HttpHeaders.EMPTY, body.getBytes(StandardCharsets.UTF_8), StandardCharsets.UTF_8));

        assertTrue(message.contains("Subject can't be blank"));
        assertTrue(message.contains("PropertyConstraintViolation"));
    }

    @Test
    void createIssueBuildsRequestAndReturnsWorkPackageIdAndUrl() {
        OpenProjectService service = newService(true);
        responder.set(req -> {
            String path = req.url().getPath();
            if (path.endsWith("/types")) {
                return Mono.just(jsonResponse(HttpStatus.OK,
                        "{\"_embedded\":{\"elements\":[{\"name\":\"Task\",\"_links\":{\"self\":{\"href\":\"/api/v3/types/1\"}}}]}}"));
            }
            if (path.endsWith("/work_packages")) {
                return Mono.just(jsonResponse(HttpStatus.CREATED, "{\"id\":42}"));
            }
            return Mono.error(new AssertionError("Unexpected request: " + req.url()));
        });

        OpenProjectService.OpenProjectIssueResult result = service.createIssue(
                "Something broke", "It broke badly", null, "Task");

        assertTrue(result.isSuccess());
        assertEquals("42", result.getIssueKey());
        assertEquals("https://openproject.test/work_packages/42", result.getIssueUrl());
    }

    @Test
    void createIssueReturnsFailureResultWhenTypeNotFound() {
        OpenProjectService service = newService(true);
        responder.set(req -> Mono.just(jsonResponse(HttpStatus.OK,
                "{\"_embedded\":{\"elements\":[{\"name\":\"Bug\",\"_links\":{\"self\":{\"href\":\"/api/v3/types/2\"}}}]}}")));

        OpenProjectService.OpenProjectIssueResult result = service.createIssue(
                "Something broke", "It broke badly", null, "NonexistentType");

        assertFalse(result.isSuccess());
        assertTrue(result.getErrorMessage().contains("NonexistentType"));
    }

    @Test
    void createIssueThrowsWhenNotEnabled() {
        OpenProjectService service = newService(false);
        assertThrows(IllegalStateException.class, () ->
                service.createIssue("Title", "Description", null, "Task"));
    }

    @Test
    void createIssueTimesOutWhenOpenProjectNeverResponds() {
        OpenProjectService service = newService(true);
        ReflectionTestUtils.setField(service, "createTimeoutSeconds", 1L);
        responder.set(req -> {
            String path = req.url().getPath();
            if (path.endsWith("/types")) {
                return Mono.just(jsonResponse(HttpStatus.OK,
                        "{\"_embedded\":{\"elements\":[{\"name\":\"Task\",\"_links\":{\"self\":{\"href\":\"/api/v3/types/1\"}}}]}}"));
            }
            return Mono.never();
        });

        OpenProjectService.OpenProjectIssueResult result = service.createIssue(
                "Title", "Description", null, "Task");

        assertFalse(result.isSuccess());
    }

    @Test
    void uploadAttachmentReturnsFalseWhenNotEnabled() {
        OpenProjectService service = newService(false);
        assertFalse(service.uploadAttachment("42", "log.txt", "content".getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void uploadAttachmentReturnsTrueOnSuccess() {
        OpenProjectService service = newService(true);
        responder.set(req -> Mono.just(jsonResponse(HttpStatus.OK, "{}")));

        assertTrue(service.uploadAttachment("42", "log.txt", "content".getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void determinePriorityMapsKeywordsToUrgencyLevels() {
        assertEquals("Highest", OpenProjectService.determinePriority("App crash", "data loss occurred"));
        assertEquals("High", OpenProjectService.determinePriority("Error on save", "broken feature"));
        assertEquals("Medium", OpenProjectService.determinePriority("Slow load", "performance issue"));
        assertEquals("Medium", OpenProjectService.determinePriority("Feature request", "please add this"));
    }
}

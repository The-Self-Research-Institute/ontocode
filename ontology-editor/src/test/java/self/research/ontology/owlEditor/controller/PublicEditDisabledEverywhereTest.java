package self.research.ontology.owlEditor.controller;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.multipart.MultipartFile;
import self.research.ontology.owlEditor.model.HistoryChange;
import self.research.ontology.owlEditor.service.ProjectMetadataService;
import self.research.ontology.owlEditor.service.PublicEditGate;
import self.research.ontology.owlEditor.service.PublicEditGuard;
import self.research.ontology.owlEditor.service.WorkspaceOwnershipService;
import self.research.ontology.owlEditor.util.JwtIdentityExtractor;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Parameter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PublicEditDisabledEverywhereTest {

    private static final String OWNER = "owner@x.com";
    private static final String MEMBER = "member@x.com";

    private static final List<String[]> WRITES = List.of(
            new String[]{"EntityController", "renameEntity"},
            new String[]{"OntologyIriController", "updateOntologyIRIs"},
            new String[]{"OntologyMetadataController", "addAnnotation"},
            new String[]{"OntologyMetadataController", "updateAnnotation"},
            new String[]{"OntologyMetadataController", "deleteAnnotation"},
            new String[]{"OntologyMetadataController", "addImport"},
            new String[]{"OntologyMetadataController", "deleteImport"},
            new String[]{"OntologyMetadataController", "addGCI"},
            new String[]{"OntologyMetadataController", "updateGCI"},
            new String[]{"OntologyMetadataController", "deleteGCI"},
            new String[]{"OntologyMetadataController", "updatePrefix"},
            new String[]{"OntologyMetadataController", "deletePrefix"},
            new String[]{"DLQueryController", "addDLQueryToOntology"},
            new String[]{"SpreadsheetImportController", "commit"},
            new String[]{"RuleController", "create"},
            new String[]{"RuleController", "update"},
            new String[]{"RuleController", "delete"},
            new String[]{"RuleImportController", "importRules"},
            new String[]{"AxiomAnnotationController", "addAnnotation"},
            new String[]{"AxiomAnnotationController", "deleteAnnotation"},
            new String[]{"DatatypeDefinitionController", "createDefinition"},
            new String[]{"DatatypeDefinitionController", "updateDefinition"},
            new String[]{"DatatypeDefinitionController", "deleteDefinition"},
            new String[]{"ExpressionParseController", "addPropertyExpressionAxiom"},
            new String[]{"ExpressionParseController", "deletePropertyExpressionAxiom"},
            new String[]{"ExpressionParseController", "addGeneralClassAxiom"},
            new String[]{"ExpressionParseController", "addClassExpressionAxiom"},
            new String[]{"OntologyMergeController", "executeMerge"},
            new String[]{"OntologyMergeController", "executeMergeWithResolutions"},
            new String[]{"OntologyCrudController", "makeSiblingsDisjoint"},
            new String[]{"SparqlQueryController", "update"},
            new String[]{"CitationController", "insertCitation"},
            new String[]{"CitationController", "deleteCitation"},
            new String[]{"ProjectLoadController", "saveCodeViewAndSync"});

    static Stream<Arguments> writes() {
        return WRITES.stream().map(w -> Arguments.of(w[0], w[1]));
    }

    @BeforeEach
    void unsigned() {
        JwtIdentityExtractor.requireSignature(null);
    }

    @AfterEach
    void reset() {
        JwtIdentityExtractor.requireSignature(null);
        RequestContextHolder.resetRequestAttributes();
    }

    private static ProjectMetadataService metadata(boolean requireDraft) {
        ProjectMetadataService metadata = mock(ProjectMetadataService.class);
        when(metadata.isRequireDraftForMembers("p1")).thenReturn(requireDraft);
        when(metadata.getOwnerEmail("p1")).thenReturn(Optional.of(OWNER));
        return metadata;
    }

    private static String token(String email) {
        String payload = "{\"email\":\"" + email + "\",\"userId\":\"id-" + email + "\"}";
        Base64.Encoder encoder = Base64.getUrlEncoder().withoutPadding();
        return "Bearer " + encoder.encodeToString("{\"alg\":\"none\"}".getBytes(StandardCharsets.UTF_8)) + "."
                + encoder.encodeToString(payload.getBytes(StandardCharsets.UTF_8)) + ".x";
    }

    private static MockHttpServletRequest requestBy(String email) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Authorization", token(email));
        return request;
    }

    private static Object controller(String simpleName, PublicEditGate gate) throws Exception {
        Class<?> type = Class.forName("self.research.ontology.owlEditor.controller." + simpleName);
        var constructor = type.getDeclaredConstructors()[0];
        constructor.setAccessible(true);
        Class<?>[] types = constructor.getParameterTypes();
        Object[] args = new Object[types.length];
        for (int i = 0; i < args.length; i++) {
            args[i] = types[i].isPrimitive() ? primitive(types[i]) : mock(types[i]);
        }
        Object controller = constructor.newInstance(args);
        ReflectionTestUtils.setField(controller, "publicEditGate", gate);
        return controller;
    }

    private static Object primitive(Class<?> type) {
        if (type == boolean.class) {
            return false;
        }
        if (type == int.class) {
            return 0;
        }
        if (type == long.class) {
            return 0L;
        }
        if (type == double.class) {
            return 0d;
        }
        return null;
    }

    private static Method method(Object controller, String name) {
        for (Method candidate : controller.getClass().getDeclaredMethods()) {
            if (candidate.getName().equals(name) && Modifier.isPublic(candidate.getModifiers())) {
                return candidate;
            }
        }
        throw new AssertionError("No public method " + name);
    }

    private static Object[] arguments(Method method, MockHttpServletRequest request, boolean draft) {
        Parameter[] parameters = method.getParameters();
        Object[] args = new Object[parameters.length];
        boolean projectIdSet = false;
        for (int i = 0; i < parameters.length; i++) {
            Class<?> type = parameters[i].getType();
            String name = parameters[i].getName();
            if (type == String.class) {
                if (!projectIdSet) {
                    args[i] = "p1";
                    projectIdSet = true;
                } else if (name.equals("draft")) {
                    args[i] = String.valueOf(draft);
                }
            } else if (type == boolean.class) {
                args[i] = name.equals("draft") && draft;
            } else if (type == Boolean.class) {
                args[i] = draft;
            } else if (Map.class.isAssignableFrom(type)) {
                Map<String, Object> body = new HashMap<>();
                body.put("draft", String.valueOf(draft));
                args[i] = body;
            } else if (jakarta.servlet.http.HttpServletRequest.class.isAssignableFrom(type)) {
                args[i] = request;
            } else if (List.class.isAssignableFrom(type)) {
                args[i] = new ArrayList<>();
            } else if (type.isPrimitive()) {
                args[i] = primitive(type);
            } else if (type == MultipartFile.class || !Modifier.isFinal(type.getModifiers()) || type.isRecord()) {
                args[i] = mock(type);
            }
        }
        return args;
    }

    private static ResponseEntity<?> call(String controllerName, String methodName, PublicEditGate gate,
                                          MockHttpServletRequest request, boolean draft) throws Exception {
        Object controller = controller(controllerName, gate);
        Method method = method(controller, methodName);
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
        try {
            Object result = method.invoke(controller, arguments(method, request, draft));
            return result instanceof ResponseEntity<?> response ? response : null;
        } catch (InvocationTargetException e) {
            return null;
        }
    }

    private static boolean refusedAsPublicEditOff(ResponseEntity<?> response) {
        return response != null && response.getStatusCode().value() == 403 && response.getBody() instanceof Map<?, ?> body
                && PublicEditGuard.CODE.equals(body.get("code"));
    }

    @ParameterizedTest(name = "{0}.{1}")
    @MethodSource("writes")
    void memberSavingPublicWhilePublicEditingIsOffIsRefused(String controller, String method) throws Exception {
        ResponseEntity<?> response = call(controller, method, new PublicEditGate(metadata(true), false), requestBy(MEMBER), false);

        assertNotNull(response);
        assertEquals(403, response.getStatusCode().value());
        Map<?, ?> body = (Map<?, ?>) response.getBody();
        assertEquals(PublicEditGuard.CODE, body.get("code"));
        assertEquals(true, body.get("requireDraftForMembers"));
        assertEquals(true, body.get("draftAllowed"));
    }

    @ParameterizedTest(name = "{0}.{1}")
    @MethodSource("writes")
    void ownerIsNotRefused(String controller, String method) throws Exception {
        assertFalse(refusedAsPublicEditOff(call(controller, method, new PublicEditGate(metadata(true), false), requestBy(OWNER), false)));
    }

    @ParameterizedTest(name = "{0}.{1}")
    @MethodSource("writes")
    void memberIsNotRefusedWhenTheSettingIsOff(String controller, String method) throws Exception {
        assertFalse(refusedAsPublicEditOff(call(controller, method, new PublicEditGate(metadata(false), false), requestBy(MEMBER), false)));
    }

    @ParameterizedTest(name = "{0}.{1}")
    @MethodSource("writes")
    void desktopIsNotRefused(String controller, String method) throws Exception {
        assertFalse(refusedAsPublicEditOff(call(controller, method, new PublicEditGate(metadata(true), true), requestBy(MEMBER), false)));
    }

    @ParameterizedTest(name = "{0}.{1}")
    @MethodSource("writes")
    void draftSaveIsNotRefused(String controller, String method) throws Exception {
        Method probe = method(controller(controller, null), method);
        boolean hasDraftChoice = Stream.of(probe.getParameters()).anyMatch(p -> p.getName().startsWith("draft"))
                || Stream.of(probe.getParameters()).anyMatch(p -> Map.class.isAssignableFrom(p.getType()));
        if (!hasDraftChoice) {
            return;
        }
        assertFalse(refusedAsPublicEditOff(call(controller, method, new PublicEditGate(metadata(true), false), requestBy(MEMBER), true)));
    }

    @Test
    void controllersBuiltWithoutTheGateKeepWorking() throws Exception {
        assertFalse(refusedAsPublicEditOff(call("EntityController", "renameEntity", null, requestBy(MEMBER), false)));
    }

    @Test
    void spreadsheetImportOnlyRefusesAnExplicitPublicRequest() throws Exception {
        Object controller = controller("SpreadsheetImportController", new PublicEditGate(metadata(true), false));
        Method commit = method(controller, "commit");
        MockHttpServletRequest request = requestBy(MEMBER);
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
        Object[] args = arguments(commit, request, false);

        assertEquals(403, ((ResponseEntity<?>) commit.invoke(controller, args)).getStatusCode().value());

        Parameter[] parameters = commit.getParameters();
        for (int i = 0; i < parameters.length; i++) {
            if (parameters[i].getType() == Boolean.class) {
                args[i] = null;
            }
        }
        Object omitted = null;
        try {
            omitted = commit.invoke(controller, args);
        } catch (InvocationTargetException ignored) {
        }
        assertFalse(refusedAsPublicEditOff(omitted instanceof ResponseEntity<?> response ? response : null));
    }

    private static HistoryChange change(boolean draft, String userId) {
        HistoryChange change = mock(HistoryChange.class);
        when(change.isDraft()).thenReturn(draft);
        when(change.getUserId()).thenReturn(userId);
        return change;
    }

    private static RollbackRequestSupport support(boolean requireDraft, boolean desktop) {
        RollbackRequestSupport support = new RollbackRequestSupport(mock(WorkspaceOwnershipService.class));
        ReflectionTestUtils.setField(support, "publicEditGate", new PublicEditGate(metadata(requireDraft), desktop));
        return support;
    }

    @Test
    void undoAndRedoOfPublicChangesByAMemberAreRefused() {
        ResponseEntity<Map<String, Object>> denied = support(true, false)
                .denyIfNotAllowed(List.of(change(false, "id-" + MEMBER)), "p1", requestBy(MEMBER));

        assertNotNull(denied);
        assertEquals(403, denied.getStatusCode().value());
        assertEquals(PublicEditGuard.CODE, denied.getBody().get("code"));
    }

    @Test
    void undoOfAMembersOwnDraftChangesIsAllowed() {
        assertNull(support(true, false).denyIfNotAllowed(List.of(change(true, "id-" + MEMBER)), "p1", requestBy(MEMBER)));
    }

    @Test
    void ownerUndoOfPublicChangesIsAllowed() {
        assertNull(support(true, false).denyIfNotAllowed(List.of(change(false, "id-" + OWNER)), "p1", requestBy(OWNER)));
    }

    @Test
    void desktopUndoOfPublicChangesIsAllowed() {
        assertNull(support(true, true).denyIfNotAllowed(List.of(change(false, "id-" + MEMBER)), "p1", requestBy(MEMBER)));
    }

    @Test
    void settingOffLetsMembersUndoPublicChanges() {
        assertNull(support(false, false).denyIfNotAllowed(List.of(change(false, "id-" + MEMBER)), "p1", requestBy(MEMBER)));
    }

    @Test
    void previewOfAPublicUndoIsNotRefused() {
        MockHttpServletRequest request = requestBy(MEMBER);
        request.setParameter("dryRun", "true");
        assertNull(support(true, false).denyIfNotAllowed(List.of(change(false, "id-" + MEMBER)), "p1", request));
    }
}

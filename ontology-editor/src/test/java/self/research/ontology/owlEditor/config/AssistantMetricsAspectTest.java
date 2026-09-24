package self.research.ontology.owlEditor.config;

import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.config.MeterFilter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.mockito.stubbing.Answer;
import org.springframework.aop.support.AopUtils;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.EnableAspectJAutoProxy;
import self.research.ontology.owlEditor.document.AssistantSessionDocument;
import self.research.ontology.owlEditor.service.AssistantContextToolService;
import self.research.ontology.owlEditor.service.AssistantEditApplyService;
import self.research.ontology.owlEditor.service.AssistantEditProposalService;
import self.research.ontology.owlEditor.service.AssistantProviderRateLimiter;
import self.research.ontology.owlEditor.service.AssistantSessionService;
import self.research.ontology.owlEditor.service.AssistantSparqlToolService;
import self.research.ontology.owlEditor.service.CodeViewReimportPipeline;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AssistantMetricsAspectTest {

    @Configuration
    @EnableAspectJAutoProxy(proxyTargetClass = true)
    static class AopConfig {
    }

    private AnnotationConfigApplicationContext context;
    private SimpleMeterRegistry registry;
    private final AtomicReference<Answer<Object>> behaviour = new AtomicReference<>();

    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
        context = new AnnotationConfigApplicationContext();
        context.register(AopConfig.class);
        context.registerBean(MeterRegistry.class, () -> registry);
        context.registerBean(AssistantMetricsAspect.class);
        registerMock(AssistantContextToolService.class, "readContext");
        registerMock(AssistantSparqlToolService.class, "runSparql");
        registerMock(AssistantEditProposalService.class, "propose");
        registerMock(AssistantEditApplyService.class, "applyGroup");
        registerMock(AssistantSessionService.class, "createSession");
        registerMock(CodeViewReimportPipeline.class, "reimport");
        context.refresh();
    }

    @AfterEach
    void tearDown() {
        context.close();
    }

    private <T> void registerMock(Class<T> type, String methodName) {
        T mock = Mockito.mock(type, invocation -> invocation.getMethod().getName().equals(methodName)
                ? behaviour.get().answer(invocation)
                : Mockito.RETURNS_DEFAULTS.answer(invocation));
        context.registerBean(type, () -> mock);
    }

    private Object invoke(Class<?> type, String methodName) throws Throwable {
        Object bean = context.getBean(type);
        Method method = Arrays.stream(bean.getClass().getMethods())
                .filter(m -> m.getName().equals(methodName))
                .findFirst()
                .orElseThrow();
        Object[] args = Arrays.stream(method.getParameterTypes()).map(AssistantMetricsAspectTest::defaultValue).toArray();
        try {
            return method.invoke(bean, args);
        } catch (InvocationTargetException e) {
            throw e.getCause();
        }
    }

    private static Object defaultValue(Class<?> type) {
        if (!type.isPrimitive()) {
            return null;
        }
        if (type == boolean.class) {
            return false;
        }
        if (type == long.class) {
            return 0L;
        }
        if (type == int.class) {
            return 0;
        }
        if (type == double.class) {
            return 0d;
        }
        return null;
    }

    private Timer timer(String op, String outcome) {
        return registry.find(AssistantMetricsAspect.TIMER_NAME).tags("op", op, "outcome", outcome).timer();
    }

    @Test
    void assistantBeansAreProxied() {
        assertTrue(AopUtils.isAopProxy(context.getBean(AssistantEditApplyService.class)));
        assertTrue(AopUtils.isAopProxy(context.getBean(CodeViewReimportPipeline.class)));
        assertTrue(AopUtils.isAopProxy(context.getBean(AssistantSessionService.class)));
    }

    @Test
    void okResultIsTimedWithOkOutcome() throws Throwable {
        AssistantContextToolService.ContextToolResult ok = AssistantContextToolService.ContextToolResult.builder()
                .ok(true).build();
        behaviour.set(invocation -> ok);

        Object result = invoke(AssistantContextToolService.class, "readContext");

        assertSame(ok, result);
        assertEquals(1, timer("read_context", "ok").count());
    }

    @Test
    void failedResultIsTaggedWithItsErrorCode() throws Throwable {
        behaviour.set(invocation -> AssistantSparqlToolService.SparqlToolResult.builder()
                .ok(false).errorCode("REVISION_STALE").message("moved").build());
        invoke(AssistantSparqlToolService.class, "runSparql");

        behaviour.set(invocation -> AssistantEditApplyService.ApplyResult.builder()
                .ok(false).errorCode("RECOVERY_REQUIRED").build());
        invoke(AssistantEditApplyService.class, "applyGroup");

        behaviour.set(invocation -> AssistantEditProposalService.ProposeEditResult.builder()
                .ok(false).errorCode("VALIDATION_FAILED").build());
        invoke(AssistantEditProposalService.class, "propose");

        assertEquals(1, timer("run_sparql", "REVISION_STALE").count());
        assertEquals(1, timer("apply", "RECOVERY_REQUIRED").count());
        assertEquals(1, timer("propose", "VALIDATION_FAILED").count());
    }

    @Test
    void resultsWithoutOkFlagCountAsOk() throws Throwable {
        behaviour.set(invocation -> AssistantSessionDocument.builder().id("s").build());
        invoke(AssistantSessionService.class, "createSession");

        behaviour.set(invocation -> new CodeViewReimportPipeline.ReimportResult("turtle", null, 3L));
        invoke(CodeViewReimportPipeline.class, "reimport");

        assertEquals(1, timer("create_session", "ok").count());
        assertEquals(1, timer("reimport", "ok").count());
    }

    @Test
    void thrownExceptionIsRecordedAndRethrown() {
        behaviour.set(invocation -> {
            throw new IOException("disk full");
        });

        IOException thrown = assertThrows(IOException.class, () -> invoke(CodeViewReimportPipeline.class, "reimport"));

        assertEquals("disk full", thrown.getMessage());
        assertEquals(1, timer("reimport", "exception").count());
        assertNull(timer("reimport", "ok"));
    }

    @Test
    void uncheckedExceptionPropagatesAsTheSameInstance() {
        IllegalStateException failure = new IllegalStateException("lock lost");
        behaviour.set(invocation -> {
            throw failure;
        });

        IllegalStateException thrown = assertThrows(IllegalStateException.class,
                () -> invoke(AssistantEditApplyService.class, "applyGroup"));

        assertSame(failure, thrown);
        assertEquals(1, timer("apply", "exception").count());
    }

    @Test
    void errorPropagatesAndIsRecordedAsException() {
        StackOverflowError failure = new StackOverflowError("deep");
        behaviour.set(invocation -> {
            throw failure;
        });

        StackOverflowError thrown = assertThrows(StackOverflowError.class,
                () -> invoke(AssistantContextToolService.class, "readContext"));

        assertSame(failure, thrown);
        assertEquals(1, timer("read_context", "exception").count());
    }

    @Test
    void aBrokenMeterRegistryNeverChangesTheResultOrTheException() throws Throwable {
        registry.config().meterFilter(new MeterFilter() {
            @Override
            public Meter.Id map(Meter.Id id) {
                throw new IllegalStateException("registry broken");
            }
        });
        AssistantEditApplyService.ApplyResult ok = AssistantEditApplyService.ApplyResult.builder().ok(true).build();
        behaviour.set(invocation -> ok);

        assertSame(ok, invoke(AssistantEditApplyService.class, "applyGroup"));

        IOException failure = new IOException("disk full");
        behaviour.set(invocation -> {
            throw failure;
        });
        assertSame(failure, assertThrows(IOException.class, () -> invoke(CodeViewReimportPipeline.class, "reimport")));
    }

    @Test
    void resultWhoseAccessorsThrowIsReturnedUnchangedAndTaggedError() throws Throwable {
        AssistantEditApplyService.ApplyResult odd = Mockito.mock(AssistantEditApplyService.ApplyResult.class);
        Mockito.when(odd.isOk()).thenThrow(new IllegalStateException("broken getter"));
        behaviour.set(invocation -> odd);

        assertSame(odd, invoke(AssistantEditApplyService.class, "applyGroup"));
        assertEquals(1, timer("apply", "error").count());
    }

    @Test
    void durationIsMeasured() throws Throwable {
        behaviour.set(invocation -> {
            Thread.sleep(30);
            return AssistantEditApplyService.ApplyResult.builder().ok(true).build();
        });

        invoke(AssistantEditApplyService.class, "applyGroup");

        Timer timer = timer("apply", "ok");
        assertNotNull(timer);
        assertTrue(timer.totalTime(java.util.concurrent.TimeUnit.MILLISECONDS) >= 25);
    }

    @Test
    void unrelatedMethodsAreNotTimed() {
        context.getBean(AssistantSessionService.class).getMaxRetrievalAttempts();

        assertTrue(registry.find(AssistantMetricsAspect.TIMER_NAME).timers().isEmpty());
    }

    @Test
    void outcomeSanitizesUnexpectedErrorCodes() {
        AssistantMetricsAspect aspect = new AssistantMetricsAspect(new SimpleMeterRegistry());

        assertEquals("error", aspect.outcomeOf(AssistantEditApplyService.ApplyResult.builder()
                .ok(false).errorCode("weird code with spaces").build()));
        assertEquals("error", aspect.outcomeOf(AssistantEditApplyService.ApplyResult.builder().ok(false).build()));
        assertEquals("CONFLICT", aspect.outcomeOf(AssistantEditApplyService.ApplyResult.builder()
                .ok(false).errorCode("conflict").build()));
        assertEquals("ok", aspect.outcomeOf("plain string"));
        assertEquals("ok", aspect.outcomeOf(null));
        assertEquals("ok", aspect.outcomeOf(new AssistantProviderRateLimiter.Decision(false, 3)));
    }
}

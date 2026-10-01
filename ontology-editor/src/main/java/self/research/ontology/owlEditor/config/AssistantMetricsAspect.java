package self.research.ontology.owlEditor.config;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.stereotype.Component;

import java.lang.reflect.Method;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

@Slf4j
@Aspect
@Component
public class AssistantMetricsAspect {

    public static final String TIMER_NAME = "assistant.operation";
    static final String OUTCOME_OK = "ok";
    static final String OUTCOME_ERROR = "error";
    static final String OUTCOME_EXCEPTION = "exception";

    private static final Pattern ERROR_CODE_PATTERN = Pattern.compile("^[A-Z][A-Z0-9_]{0,47}$");

    private record ResultAccessors(Method isOk, Method getErrorCode) {
    }

    private final MeterRegistry registry;
    private final ConcurrentHashMap<Class<?>, Optional<ResultAccessors>> accessorCache = new ConcurrentHashMap<>();

    public AssistantMetricsAspect(MeterRegistry registry) {
        this.registry = registry;
    }

    @Around("execution(* self.research.ontology.owlEditor.service.AssistantContextToolService.readContext(..))")
    public Object timeReadContext(ProceedingJoinPoint joinPoint) throws Throwable {
        return time(joinPoint, "read_context");
    }

    @Around("execution(* self.research.ontology.owlEditor.service.AssistantSparqlToolService.runSparql(..))")
    public Object timeRunSparql(ProceedingJoinPoint joinPoint) throws Throwable {
        return time(joinPoint, "run_sparql");
    }

    @Around("execution(* self.research.ontology.owlEditor.service.AssistantEditProposalService.propose(..))")
    public Object timePropose(ProceedingJoinPoint joinPoint) throws Throwable {
        return time(joinPoint, "propose");
    }

    @Around("execution(* self.research.ontology.owlEditor.service.AssistantEditApplyService.applyGroup(..))")
    public Object timeApply(ProceedingJoinPoint joinPoint) throws Throwable {
        return time(joinPoint, "apply");
    }

    @Around("execution(* self.research.ontology.owlEditor.service.AssistantSessionService.createSession(..))")
    public Object timeCreateSession(ProceedingJoinPoint joinPoint) throws Throwable {
        return time(joinPoint, "create_session");
    }

    @Around("execution(* self.research.ontology.owlEditor.service.CodeViewReimportPipeline.reimport(..))")
    public Object timeReimport(ProceedingJoinPoint joinPoint) throws Throwable {
        return time(joinPoint, "reimport");
    }

    Object time(ProceedingJoinPoint joinPoint, String op) throws Throwable {
        long start = System.nanoTime();
        String outcome = OUTCOME_EXCEPTION;
        try {
            Object result = joinPoint.proceed();
            outcome = outcomeOf(result);
            return result;
        } finally {
            record(op, outcome, System.nanoTime() - start);
        }
    }

    private void record(String op, String outcome, long elapsedNanos) {
        try {
            Timer.builder(TIMER_NAME)
                    .description("Duration of Code View assistant backend operations")
                    .tag("op", op)
                    .tag("outcome", outcome)
                    .register(registry)
                    .record(elapsedNanos, TimeUnit.NANOSECONDS);
        } catch (RuntimeException e) {
            log.debug("[Assistant] Failed to record metric for {}: {}", op, e.getClass().getSimpleName());
        }
    }

    String outcomeOf(Object result) {
        if (result == null) {
            return OUTCOME_OK;
        }
        Optional<ResultAccessors> accessors = accessorCache.computeIfAbsent(result.getClass(),
                AssistantMetricsAspect::findAccessors);
        if (accessors.isEmpty()) {
            return OUTCOME_OK;
        }
        try {
            Object ok = accessors.get().isOk().invoke(result);
            if (Boolean.TRUE.equals(ok)) {
                return OUTCOME_OK;
            }
            Object code = accessors.get().getErrorCode().invoke(result);
            return sanitizeErrorCode(code);
        } catch (ReflectiveOperationException | RuntimeException e) {
            return OUTCOME_ERROR;
        }
    }

    static String sanitizeErrorCode(Object code) {
        if (code == null) {
            return OUTCOME_ERROR;
        }
        String value = code.toString().trim().toUpperCase(Locale.ROOT);
        return ERROR_CODE_PATTERN.matcher(value).matches() ? value : OUTCOME_ERROR;
    }

    private static Optional<ResultAccessors> findAccessors(Class<?> type) {
        try {
            Method isOk = type.getMethod("isOk");
            Method getErrorCode = type.getMethod("getErrorCode");
            if (isOk.getReturnType() != boolean.class && isOk.getReturnType() != Boolean.class) {
                return Optional.empty();
            }
            return Optional.of(new ResultAccessors(isOk, getErrorCode));
        } catch (NoSuchMethodException | RuntimeException e) {
            return Optional.empty();
        }
    }
}

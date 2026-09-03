package com.example.catalog;

import java.util.Arrays;
import java.util.stream.Collectors;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;

@Aspect
@Component
public class LoggingAspect {
    private static final Logger log = LoggerFactory.getLogger(LoggingAspect.class);
    @Around("within(@org.springframework.web.bind.annotation.RestController *)")
    public Object logController(ProceedingJoinPoint point) throws Throwable {
        long start = System.nanoTime();
        String operation = point.getSignature().toShortString();
        String id = MDC.get("traceId");
        id = id == null ? "-" : id;
        log.info("AOP_START operation={} traceId={} args={}", operation, id, safeArgs(point.getArgs()));
        try {
            Object result = point.proceed();
            log.info("AOP_SUCCESS operation={} traceId={} durationMs={} resultType={}",
                    operation, id, elapsedMs(start), result == null ? "null" : result.getClass().getSimpleName());
            return result;
        } catch (Throwable ex) {
            log.error("AOP_FAILURE operation={} traceId={} durationMs={} exception={}",
                    operation, id, elapsedMs(start), ex.getClass().getSimpleName(), ex);
            throw ex;
        }
    }
    private static long elapsedMs(long start) { return (System.nanoTime() - start) / 1_000_000; }
    private static String safeArgs(Object[] args) {
        if (args == null || args.length == 0) return "[]";
        return Arrays.stream(args).map(arg -> arg == null ? "null" : "<redacted:" + arg.getClass().getSimpleName() + ">")
                .collect(Collectors.joining(",", "[", "]"));
    }
}

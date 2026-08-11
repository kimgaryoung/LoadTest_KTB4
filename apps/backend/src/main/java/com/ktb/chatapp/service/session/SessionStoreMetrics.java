package com.ktb.chatapp.service.session;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import org.springframework.stereotype.Component;

@Component
public class SessionStoreMetrics {

    private final MeterRegistry meterRegistry;
    private final ConcurrentHashMap<OperationKey, Counter> operationCounters = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<OperationKey, Timer> operationTimers = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<ValidationKey, Counter> validationCounters = new ConcurrentHashMap<>();

    public SessionStoreMetrics(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
    }

    public <T> T record(String store, String operation, Supplier<T> operationCall) {
        Timer.Sample sample = Timer.start(meterRegistry);
        String outcome = "success";
        try {
            return operationCall.get();
        } catch (RuntimeException exception) {
            outcome = "error";
            throw exception;
        } finally {
            OperationKey key = new OperationKey(store, operation, outcome);
            operationCounters.computeIfAbsent(key, this::createOperationCounter).increment();
            sample.stop(operationTimers.computeIfAbsent(key, this::createOperationTimer));
        }
    }

    public void record(String store, String operation, Runnable operationCall) {
        record(store, operation, () -> {
            operationCall.run();
            return null;
        });
    }

    public void recordValidation(String store, SessionTouchResult.Status status) {
        ValidationKey key = new ValidationKey(store, status.name().toLowerCase());
        validationCounters.computeIfAbsent(key, this::createValidationCounter).increment();
    }

    private Counter createOperationCounter(OperationKey key) {
        return Counter.builder("session.store.operations")
                .tag("store", key.store())
                .tag("operation", key.operation())
                .tag("outcome", key.outcome())
                .register(meterRegistry);
    }

    private Timer createOperationTimer(OperationKey key) {
        return Timer.builder("session.store.operation.duration")
                .tag("store", key.store())
                .tag("operation", key.operation())
                .tag("outcome", key.outcome())
                .publishPercentileHistogram()
                .register(meterRegistry);
    }

    private Counter createValidationCounter(ValidationKey key) {
        return Counter.builder("session.store.validations")
                .tag("store", key.store())
                .tag("result", key.result())
                .register(meterRegistry);
    }

    private record OperationKey(String store, String operation, String outcome) {}

    private record ValidationKey(String store, String result) {}
}

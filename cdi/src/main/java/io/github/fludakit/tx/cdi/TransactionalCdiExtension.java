package io.github.fludakit.tx.cdi;

import io.github.fludakit.tx.support.TransactionContext;
import io.github.fludakit.tx.support.TransactionContextHolder;

import java.lang.annotation.Annotation;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import jakarta.enterprise.event.Observes;
import jakarta.enterprise.event.Reception;
import jakarta.enterprise.event.TransactionPhase;
import jakarta.enterprise.inject.spi.AfterBeanDiscovery;
import jakarta.enterprise.inject.spi.AnnotatedMethod;
import jakarta.enterprise.inject.spi.AnnotatedType;
import jakarta.enterprise.inject.spi.Extension;
import jakarta.enterprise.inject.spi.ObserverMethod;
import jakarta.enterprise.inject.spi.ProcessAnnotatedType;
import jakarta.enterprise.inject.spi.ProcessObserverMethod;
import jakarta.inject.Singleton;
import jakarta.interceptor.Interceptor;
import jakarta.transaction.Transactional;

/**
 * Portable extension that wires {@code jakarta.transaction.Transactional} to the CDI container:
 *
 * <ul>
 *   <li>Adds the internal {@link TransactionalBinding} to every {@code @Transactional} type, so the
 *       single {@link TransactionalInterceptor} matches all six {@code TxType} values.</li>
 *   <li>Vetoes every {@code @Observes(during != IN_PROGRESS)} observer and replays it at the matching
 *       phase through a {@link TransactionalObserverRegistry}.</li>
 * </ul>
 */
public class TransactionalCdiExtension implements Extension {

    private final List<CollectedObserver> transactionalObservers = new ArrayList<>();

    private static <T> boolean hasTransactional(AnnotatedType<T> annotatedType) {
        if (annotatedType.isAnnotationPresent(Transactional.class)) {
            return true;
        }
        for (AnnotatedMethod<? super T> method : annotatedType.getMethods()) {
            if (method.isAnnotationPresent(Transactional.class)) {
                return true;
            }
        }
        return false;
    }

    public <T> void processAnnotatedType(@Observes ProcessAnnotatedType<T> event) {
        AnnotatedType<T> annotatedType = event.getAnnotatedType();
        if (hasTransactional(annotatedType)) {
            event.configureAnnotatedType().add(TransactionalBinding.Literal.INSTANCE);
        }
    }

    public <T, X> void processObserverMethod(@Observes ProcessObserverMethod<T, X> event) {
        ObserverMethod<T> observer = event.getObserverMethod();
        if (observer.getTransactionPhase() != TransactionPhase.IN_PROGRESS) {
            event.veto();
            transactionalObservers.add(new CollectedObserver(observer.getTransactionPhase(), observer));
        }
    }

    public void afterBeanDiscovery(@Observes AfterBeanDiscovery event) {
        TransactionalObserverRegistry registry = new TransactionalObserverRegistry();
        for (CollectedObserver collected : transactionalObservers) {
            registry.register(collected.phase(), collected.observer());
        }

        event.addBean()
                .types(TransactionalObserverRegistry.class)
                .scope(Singleton.class)
                .createWith(ctx -> registry);

        // Register @TransactionScoped context
        event.addContext(new TransactionScopeContext());

        // One capturing observer per distinct (phase, type, qualifiers): CDI fires it only for
        // events those vetoed observers would actually receive, so buffering the capture group's
        // qualifier set alongside the payload lets the replay match on type AND qualifiers.
        Map<CaptureKey, List<ObserverMethod<?>>> captures = new LinkedHashMap<>();
        for (CollectedObserver collected : transactionalObservers) {
            ObserverMethod<?> observer = collected.observer();
            captures.computeIfAbsent(
                            new CaptureKey(collected.phase(), observer.getObservedType(), observer.getObservedQualifiers()),
                            key -> new ArrayList<>())
                    .add(observer);
        }
        for (Map.Entry<CaptureKey, List<ObserverMethod<?>>> capture : captures.entrySet()) {
            event.addObserverMethod(new CapturingObserver(capture.getKey()));
        }
    }

    private record CollectedObserver(TransactionPhase phase, ObserverMethod<?> observer) {
    }

    private record CaptureKey(TransactionPhase phase, Type observedType, Set<Annotation> qualifiers) {
    }

    /**
     * Captures the events one vetoed-observer group would receive — its declared type and
     * qualifier set are the capture key, so CDI delivers exactly the events those observers
     * would get, and the buffer can record the qualifiers for the replay.
     */
    private static final class CapturingObserver implements ObserverMethod<Object> {

        private final CaptureKey key;

        CapturingObserver(CaptureKey key) {
            this.key = key;
        }

        @Override
        public Class<?> getBeanClass() {
            return TransactionalCdiExtension.class;
        }

        @Override
        public Type getObservedType() {
            return key.observedType();
        }

        @Override
        public Set<Annotation> getObservedQualifiers() {
            return key.qualifiers();
        }

        @Override
        public Reception getReception() {
            return Reception.ALWAYS;
        }

        @Override
        public TransactionPhase getTransactionPhase() {
            return TransactionPhase.IN_PROGRESS;
        }

        @Override
        public int getPriority() {
            return Interceptor.Priority.APPLICATION + 500;
        }

        @Override
        public void notify(Object event) {
            TransactionContext ctx = TransactionContextHolder.get();
            if (ctx != null && ctx.isActualTransactionActive()) {
                TransactionEventBuffer buffer = ctx.getResource(TransactionEventBuffer.class);
                if (buffer != null) {
                    buffer.add(event, key.qualifiers());
                }
            }
        }
    }
}

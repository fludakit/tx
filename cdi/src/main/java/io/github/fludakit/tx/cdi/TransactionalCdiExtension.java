package io.github.fludakit.tx.cdi;

import io.github.fludakit.tx.support.TransactionContext;
import io.github.fludakit.tx.support.TransactionContextHolder;

import java.lang.annotation.Annotation;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import jakarta.enterprise.event.Observes;
import jakarta.enterprise.event.Reception;
import jakarta.enterprise.event.TransactionPhase;
import jakarta.enterprise.inject.spi.AfterBeanDiscovery;
import jakarta.enterprise.inject.spi.AnnotatedType;
import jakarta.enterprise.inject.spi.Extension;
import jakarta.enterprise.inject.spi.ObserverMethod;
import jakarta.enterprise.inject.spi.ProcessAnnotatedType;
import jakarta.enterprise.inject.spi.ProcessObserverMethod;
import jakarta.inject.Singleton;
import jakarta.interceptor.Interceptor;

/**
 * Portable extension that wires {@code jakarta.transaction.Transactional} to the CDI container:
 *
 * <ul>
 *   <li>Resolves each {@code @Transactional} type's transaction attributes once at deployment and
 *       adds the internal {@link TransactionalBinding} to it, so the single
 *       {@link TransactionalInterceptor} matches all six {@code TxType} values without resolving
 *       annotations per invocation.</li>
 *   <li>Vetoes every {@code @Observes(during != IN_PROGRESS)} observer and replays it at the matching
 *       phase through a {@link TransactionalObserverRegistry}.</li>
 * </ul>
 */
public class TransactionalCdiExtension implements Extension {

    private final List<CollectedObserver> transactionalObservers = new ArrayList<>();
    private final Map<Class<?>, TransactionalMetadata> metadata = new LinkedHashMap<>();

    public <T> void processAnnotatedType(@Observes ProcessAnnotatedType<T> event) {
        AnnotatedType<T> annotatedType = event.getAnnotatedType();
        Class<?> beanClass = annotatedType.getJavaClass();
        TransactionalMetadata resolved = TransactionalMetadata.forClass(beanClass);
        if (resolved.isTransactional()) {
            event.configureAnnotatedType().add(TransactionalBinding.Literal.INSTANCE);
            metadata.put(beanClass, resolved);
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
        event.addBean()
                .types(TransactionalMetadataRegistry.class)
                .scope(Singleton.class)
                .createWith(ctx -> new TransactionalMetadataRegistry(metadata));

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

        // One capturing observer per distinct (type, qualifiers): CDI fires it only for the
        // events the vetoed observers of that shape would actually receive, so the buffer records
        // the payload under that group's key and the replay delivers it to exactly those
        // observers. The phase is deliberately not part of the key — capture happens at
        // IN_PROGRESS for every group, so keying by phase would register one capture observer per
        // phase and buffer each fired event once per phase.
        Set<TransactionEventBuffer.CaptureKey> captures = new LinkedHashSet<>();
        for (CollectedObserver collected : transactionalObservers) {
            ObserverMethod<?> observer = collected.observer();
            captures.add(captureKey(observer));
        }
        for (TransactionEventBuffer.CaptureKey capture : captures) {
            event.addObserverMethod(new CapturingObserver(capture));
        }
    }

    private static TransactionEventBuffer.CaptureKey captureKey(ObserverMethod<?> observer) {
        return new TransactionEventBuffer.CaptureKey(observer.getObservedType(), observer.getObservedQualifiers());
    }

    private record CollectedObserver(TransactionPhase phase, ObserverMethod<?> observer) {
    }

    /**
     * Captures the events one vetoed-observer group would receive — its declared type and
     * qualifier set identify the group, so CDI hands it exactly the events those observers
     * would get, and the buffer files them under the same key for the replay.
     */
    private static final class CapturingObserver implements ObserverMethod<Object> {

        private final TransactionEventBuffer.CaptureKey key;

        CapturingObserver(TransactionEventBuffer.CaptureKey key) {
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
                    buffer.add(key, event);
                }
            }
        }
    }
}

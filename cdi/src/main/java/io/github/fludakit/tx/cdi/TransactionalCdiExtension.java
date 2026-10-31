package io.github.fludakit.tx.cdi;

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
 *   <li>Vetoes every {@code @Observes(during != IN_PROGRESS)} observer — a resource-local
 *       transaction is invisible to the container, so it would never fire them — and registers a
 *       {@link DeferredTransactionalObserver} in its place. The replacement keeps the original
 *       type and qualifiers, so CDI still matches the events, and hands them to a
 *       {@link TransactionEventDispatcher} to be delivered at the declared phase.</li>
 * </ul>
 */
public class TransactionalCdiExtension implements Extension {

    private final List<ObserverMethod<?>> deferredObservers = new ArrayList<>();
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
        TransactionPhase phase = observer.getTransactionPhase();
        if (phase != TransactionPhase.IN_PROGRESS) {
            event.veto();
            deferredObservers.add(new DeferredTransactionalObserver<>(observer, phase));
        }
    }

    public void afterBeanDiscovery(@Observes AfterBeanDiscovery event) {
        event.addBean()
                .types(TransactionalMetadataRegistry.class)
                .scope(Singleton.class)
                .createWith(ctx -> new TransactionalMetadataRegistry(metadata));

        // Register @TransactionScoped context
        event.addContext(new TransactionScopeContext());

        // One replacement observer per vetoed observer: CDI keeps doing the type and qualifier
        // matching, and the dispatcher only owns the timing.
        deferredObservers.forEach(event::addObserverMethod);
    }
}

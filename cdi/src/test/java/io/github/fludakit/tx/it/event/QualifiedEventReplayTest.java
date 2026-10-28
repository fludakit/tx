package io.github.fludakit.tx.it.event;

import io.github.fludakit.tx.TransactionDefinition;
import io.github.fludakit.tx.TransactionManager;
import io.github.fludakit.tx.support.TransactionContext;
import io.github.fludakit.tx.cdi.TransactionalCdiExtension;
import io.github.fludakit.tx.cdi.TransactionalInterceptor;
import jakarta.enterprise.context.Dependent;
import jakarta.enterprise.event.Event;
import jakarta.enterprise.event.Observes;
import jakarta.enterprise.event.TransactionPhase;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import jakarta.transaction.Transactional;
import org.jboss.weld.junit5.WeldInitiator;
import org.jboss.weld.junit5.WeldSetup;
import org.jboss.weld.junit5.WeldJunit5Extension;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.lang.annotation.Retention;
import java.lang.annotation.Target;
import java.util.ArrayList;
import java.util.List;

import static java.lang.annotation.ElementType.FIELD;
import static java.lang.annotation.ElementType.PARAMETER;
import static java.lang.annotation.ElementType.TYPE;
import static java.lang.annotation.RetentionPolicy.RUNTIME;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Transactional observers must honor event qualifiers: a qualified deferred observer receives
 * only the events fired with its qualifier, while an unqualified observer on the same payload
 * type receives both. The capture used to be a catch-all Object observer that recorded no
 * qualifiers, so every deferred observer received every event of a matching type.
 */
@ExtendWith(WeldJunit5Extension.class)
class QualifiedEventReplayTest {

    @WeldSetup
    public WeldInitiator weld = WeldInitiator.from(
            StubTransactionManager.class,
            EventService.class,
            EventObservers.class,
            TransactionalInterceptor.class,
            TransactionalCdiExtension.class
    ).build();

    @Inject
    private EventService service;

    @Inject
    private EventObservers observers;

    @Test
    void qualifiedObserverReceivesOnlyQualifiedEvents() {
        service.fireBoth();

        assertEquals(List.of("plain", "qualified"), observers.unqualifiedSeen);
        assertEquals(List.of("qualified"), observers.qualifiedSeen);
    }

    @Singleton
    public static class EventService {

        @Inject
        Event<OrderEvent> events;

        @Transactional
        public void fireBoth() {
            events.fire(new OrderEvent("plain"));
            events.select(Admin.Literal.INSTANCE).fire(new OrderEvent("qualified"));
        }
    }

    @Singleton
    public static class EventObservers {

        final List<String> unqualifiedSeen = new ArrayList<>();
        final List<String> qualifiedSeen = new ArrayList<>();

        void afterSuccessUnqualified(@Observes(during = TransactionPhase.AFTER_SUCCESS) OrderEvent event) {
            unqualifiedSeen.add(event.label());
        }

        void afterSuccessQualified(@Observes(during = TransactionPhase.AFTER_SUCCESS) @Admin OrderEvent event) {
            qualifiedSeen.add(event.label());
        }
    }

    public record OrderEvent(String label) {
    }

    @Retention(RUNTIME)
    @Target({FIELD, PARAMETER, TYPE})
    @jakarta.inject.Qualifier
    public @interface Admin {

        class Literal extends jakarta.enterprise.util.AnnotationLiteral<Admin> implements Admin {
            public static final Literal INSTANCE = new Literal();
        }
    }

    @Singleton
    public static class StubTransactionManager implements TransactionManager<String> {

        @Override
        public TransactionContext getTransaction(TransactionDefinition definition) {
            return new TransactionContext(true);
        }

        @Override
        public void commit(TransactionContext context) {
            // phases are triggered by the interceptor around this call; nothing to do here
        }

        @Override
        public void rollback(TransactionContext context) {
        }

        @Override
        public String getResource() {
            return "stub-resource";
        }
    }
}

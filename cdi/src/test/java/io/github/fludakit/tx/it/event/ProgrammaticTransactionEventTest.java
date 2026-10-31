package io.github.fludakit.tx.it.event;

import io.github.fludakit.tx.Propagation;
import io.github.fludakit.tx.TransactionDefinition;
import io.github.fludakit.tx.TransactionManager;
import io.github.fludakit.tx.cdi.TransactionalCdiExtension;
import io.github.fludakit.tx.cdi.TransactionalInterceptor;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Event;
import jakarta.enterprise.event.Observes;
import jakarta.enterprise.event.TransactionPhase;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import org.jboss.weld.junit5.WeldInitiator;
import org.jboss.weld.junit5.WeldJunit5Extension;
import org.jboss.weld.junit5.WeldSetup;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Transactional observers must be driven by the transaction itself, not by the {@code @Transactional}
 * interceptor: an event fired inside a programmatic {@link TransactionManager#execute} boundary
 * belongs to the same transaction and has to reach the declared phases. A participant
 * ({@code REQUIRED} inside a running transaction) must deliver each event exactly once, at the
 * boundary that owns the transaction.
 */
@ExtendWith(WeldJunit5Extension.class)
class ProgrammaticTransactionEventTest {

    @WeldSetup
    public WeldInitiator weld = WeldInitiator.from(
            StubTransactionManager.class,
            ProgrammaticPublisher.class,
            PayloadObserver.class,
            EventObserver.class,
            TransactionalInterceptor.class,
            TransactionalCdiExtension.class
    ).build();

    @Inject
    private EventObserver observer;
    @Inject
    private PayloadObserver payloads;
    @Inject
    private ProgrammaticPublisher publisher;

    @BeforeEach
    void reset() {
        observer.reset();
        payloads.reset();
    }

    @Test
    void commitDeliversToTheCompletionPhases() throws Exception {
        publisher.fireAndCommit("programmatic");

        assertEquals(List.of("IN_PROGRESS", "BEFORE_COMPLETION", "AFTER_SUCCESS", "AFTER_COMPLETION"),
                observer.getCalls());
    }

    @Test
    void rollbackDeliversToTheFailurePhases() {
        org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class,
                () -> publisher.fireAndRollback("programmatic"));

        assertEquals(List.of("IN_PROGRESS", "BEFORE_COMPLETION", "AFTER_FAILURE", "AFTER_COMPLETION"),
                observer.getCalls());
    }

    @Test
    void joinedParticipantDeliversEachEventOnce() throws Exception {
        publisher.fireInJoinedParticipant("programmatic");

        assertEquals(List.of(
                "IN_PROGRESS", "IN_PROGRESS",
                "BEFORE_COMPLETION", "BEFORE_COMPLETION",
                "AFTER_SUCCESS", "AFTER_SUCCESS",
                "AFTER_COMPLETION", "AFTER_COMPLETION"), observer.getCalls());
    }

    @Test
    void requiresNewDeliversItsEventsAtItsOwnBoundary() throws Exception {
        publisher.fireAroundRequiresNew("outer-1", "inner", "outer-2");

        assertEquals(List.of(
                "outer-1:IN_PROGRESS",
                // the inner transaction completes on its own, before the outer fires its second event
                "inner:IN_PROGRESS", "inner:BEFORE_COMPLETION", "inner:AFTER_SUCCESS",
                "inner:AFTER_COMPLETION",
                "outer-2:IN_PROGRESS",
                // both outer events are delivered together, at the outer boundary
                "outer-1:BEFORE_COMPLETION", "outer-2:BEFORE_COMPLETION",
                "outer-1:AFTER_SUCCESS", "outer-2:AFTER_SUCCESS",
                "outer-1:AFTER_COMPLETION", "outer-2:AFTER_COMPLETION"), payloads.getCalls());
    }

    @Singleton
    public static class ProgrammaticPublisher {

        @Inject
        private TransactionManager<String> transactionManager;

        @Inject
        private Event<TestEvent> events;

        public void fireAndCommit(String payload) throws Exception {
            transactionManager.execute(TransactionDefinition.DEFAULT, (resource, status) -> {
                events.fire(new TestEvent(payload));
                return null;
            });
        }

        public void fireAndRollback(String payload) throws Exception {
            transactionManager.execute(TransactionDefinition.DEFAULT, (resource, status) -> {
                events.fire(new TestEvent(payload));
                throw new IllegalStateException("rollback");
            });
        }

        public void fireInJoinedParticipant(String payload) throws Exception {
            transactionManager.execute(TransactionDefinition.DEFAULT, (resource, status) -> {
                transactionManager.execute(TransactionDefinition.DEFAULT, (joined, joinedStatus) -> {
                    if (joinedStatus.isNewTransaction()) {
                        throw new AssertionError("a REQUIRED participant must join, not begin");
                    }
                    events.fire(new TestEvent(payload + "-joined"));
                    return null;
                });
                events.fire(new TestEvent(payload + "-outer"));
                return null;
            });
        }

        public void fireAroundRequiresNew(String first, String inner, String last) throws Exception {
            transactionManager.execute(TransactionDefinition.DEFAULT, (resource, status) -> {
                events.fire(new TestEvent(first));
                transactionManager.execute(
                        TransactionDefinition.withDefaults().withPropagation(Propagation.REQUIRES_NEW),
                        (ownResource, ownStatus) -> {
                            if (!ownStatus.isNewTransaction()) {
                                throw new AssertionError("REQUIRES_NEW must begin its own transaction");
                            }
                            events.fire(new TestEvent(inner));
                            return null;
                        });
                events.fire(new TestEvent(last));
                return null;
            });
        }
    }

    /** Records the payload with the phase it was delivered in, so boundaries can be told apart. */
    @ApplicationScoped
    public static class PayloadObserver {

        private final List<String> calls = new CopyOnWriteArrayList<>();

        public List<String> getCalls() {
            return List.copyOf(calls);
        }

        public void reset() {
            calls.clear();
        }

        public void onInProgress(@Observes TestEvent event) {
            calls.add(event.getPayload() + ":IN_PROGRESS");
        }

        public void onBeforeCompletion(@Observes(during = TransactionPhase.BEFORE_COMPLETION) TestEvent event) {
            calls.add(event.getPayload() + ":BEFORE_COMPLETION");
        }

        public void onAfterSuccess(@Observes(during = TransactionPhase.AFTER_SUCCESS) TestEvent event) {
            calls.add(event.getPayload() + ":AFTER_SUCCESS");
        }

        public void onAfterCompletion(@Observes(during = TransactionPhase.AFTER_COMPLETION) TestEvent event) {
            calls.add(event.getPayload() + ":AFTER_COMPLETION");
        }
    }
}

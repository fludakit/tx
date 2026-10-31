package io.github.fludakit.tx.cdi;

import io.github.fludakit.tx.Propagation;
import io.github.fludakit.tx.TransactionDefinition;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.Map;
import jakarta.transaction.Transactional;
import jakarta.transaction.Transactional.TxType;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for the deployment-time resolution that {@link TransactionalCdiExtension} performs,
 * and for the registry {@link TransactionalInterceptor} consults at invocation time.
 */
class TransactionalMetadataTest {

    // --- fixtures ---

    static class Untouched {
        public void work() {
        }
    }

    @Transactional
    static class TypeLevel {
        public void work() {
        }
    }

    static class InheritsTypeLevel extends TypeLevel {
        @Override
        public void work() {
        }
    }

    @Transactional(TxType.SUPPORTS)
    static class MethodOverridesTypeLevel {
        @Transactional(TxType.NOT_SUPPORTED)
        public void work() {
        }

        public void inherited() {
        }
    }

    interface AnnotatedApi {
        @Transactional(TxType.MANDATORY)
        void work();
    }

    static class ImplementsAnnotatedApi implements AnnotatedApi {
        @Override
        public void work() {
        }
    }

    @Transactional(TxType.REQUIRES_NEW)
    interface TypeLevelApi {
        void work();
    }

    static class ImplementsTypeLevelApi implements TypeLevelApi {
        @Override
        public void work() {
        }
    }

    @Transactional(TxType.NEVER)
    interface DeepApi {
        void deep();
    }

    interface SubApi extends DeepApi {
    }

    static class ImplementsSubApi implements SubApi {
        @Override
        public void deep() {
        }
    }

    @Transactional(TxType.SUPPORTS)
    static class AnnotatedSuper {
        public void work() {
        }
    }

    @Transactional(TxType.MANDATORY)
    static class AnnotatedSub extends AnnotatedSuper {
    }

    // --- metadata resolution ---

    @Test
    void reportsNothingForUnannotatedType() {
        TransactionalMetadata metadata = TransactionalMetadata.forClass(Untouched.class);

        assertFalse(metadata.isTransactional());
        assertNull(metadata.definitionFor(method(Untouched.class, "work")));
    }

    @Test
    void resolvesTypeLevelAnnotation() {
        TransactionalMetadata metadata = TransactionalMetadata.forClass(TypeLevel.class);

        assertTrue(metadata.isTransactional());
        assertEquals(Propagation.REQUIRED, metadata.definitionFor(method(TypeLevel.class, "work")).propagation());
    }

    @Test
    void resolvesTypeLevelAnnotationFromSuperclass() {
        TransactionalMetadata metadata = TransactionalMetadata.forClass(InheritsTypeLevel.class);

        assertTrue(metadata.isTransactional());
        assertEquals(Propagation.REQUIRED, metadata.definitionFor(method(InheritsTypeLevel.class, "work")).propagation());
    }

    @Test
    void nearestTypeLevelAnnotationWins() {
        TransactionalMetadata metadata = TransactionalMetadata.forClass(AnnotatedSub.class);

        assertEquals(Propagation.MANDATORY, metadata.definitionFor(method(AnnotatedSub.class, "work")).propagation());
    }

    @Test
    void methodLevelAnnotationOverridesTypeLevel() {
        TransactionalMetadata metadata = TransactionalMetadata.forClass(MethodOverridesTypeLevel.class);

        assertEquals(Propagation.NOT_SUPPORTED, metadata.definitionFor(method(MethodOverridesTypeLevel.class, "work")).propagation());
        assertEquals(Propagation.SUPPORTS, metadata.definitionFor(method(MethodOverridesTypeLevel.class, "inherited")).propagation());
    }

    @Test
    void resolvesMethodLevelAnnotationFromInterface() {
        TransactionalMetadata metadata = TransactionalMetadata.forClass(ImplementsAnnotatedApi.class);

        assertTrue(metadata.isTransactional());
        assertEquals(Propagation.MANDATORY, metadata.definitionFor(method(ImplementsAnnotatedApi.class, "work")).propagation());
    }

    @Test
    void resolvesTypeLevelAnnotationFromInterface() {
        TransactionalMetadata metadata = TransactionalMetadata.forClass(ImplementsTypeLevelApi.class);

        assertTrue(metadata.isTransactional());
        assertEquals(Propagation.REQUIRES_NEW, metadata.definitionFor(method(ImplementsTypeLevelApi.class, "work")).propagation());
    }

    @Test
    void resolvesAnnotationsFromSuperInterfaces() {
        TransactionalMetadata metadata = TransactionalMetadata.forClass(ImplementsSubApi.class);

        assertEquals(Propagation.NEVER, metadata.definitionFor(method(ImplementsSubApi.class, "deep")).propagation());
    }

    // --- registry lookup ---

    @Test
    void resolvesThroughTheSuperclassChainForUnregisteredSubclasses() {
        TransactionalMetadataRegistry registry = new TransactionalMetadataRegistry(
                Map.of(TypeLevel.class, TransactionalMetadata.forClass(TypeLevel.class)));

        // simulates a container-generated subclass of the bean class
        TransactionDefinition definition = registry.definitionFor(InheritsTypeLevel.class,
                method(InheritsTypeLevel.class, "work"));

        assertNotNull(definition);
        assertEquals(Propagation.REQUIRED, definition.propagation());
    }

    @Test
    void resolvesAndCachesTypesTheExtensionDidNotSee() {
        TransactionalMetadataRegistry registry = new TransactionalMetadataRegistry(Map.of());

        TransactionDefinition first = registry.definitionFor(ImplementsAnnotatedApi.class,
                method(ImplementsAnnotatedApi.class, "work"));
        TransactionDefinition second = registry.definitionFor(ImplementsAnnotatedApi.class,
                method(ImplementsAnnotatedApi.class, "work"));

        assertEquals(Propagation.MANDATORY, first.propagation());
        assertEquals(Propagation.MANDATORY, second.propagation());
    }

    @Test
    void returnsNullWhenNothingIsTransactional() {
        TransactionalMetadataRegistry registry = new TransactionalMetadataRegistry(Map.of());

        assertNull(registry.definitionFor(Untouched.class, method(Untouched.class, "work")));
        assertNull(registry.definitionFor(null, method(Untouched.class, "work")));
        assertNull(registry.definitionFor(Untouched.class, null));
    }

    private static Method method(Class<?> type, String name) {
        try {
            return type.getMethod(name);
        } catch (NoSuchMethodException e) {
            throw new AssertionError(e);
        }
    }
}

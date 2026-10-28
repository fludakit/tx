package io.github.fludakit.tx.cdi;

import io.github.fludakit.tx.TransactionDefinition;

import java.lang.reflect.Method;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Deployment-time index of the {@link TransactionalMetadata} resolved by
 * {@link TransactionalCdiExtension}, keyed by bean class.
 *
 * <p>Registered as a singleton bean so {@link TransactionalInterceptor} can look the metadata up
 * instead of resolving annotations on every invocation.</p>
 *
 * <p>A lookup that misses the index (the target is a container-generated subclass of a known
 * type, or a type the extension never saw) walks the superclass chain, then — once per unknown
 * class — resolves and caches the metadata lazily. Precomputation therefore stays the normal
 * path, and an unusual target still behaves exactly as the old per-invocation resolution did.</p>
 */
final class TransactionalMetadataRegistry {

    private final Map<Class<?>, TransactionalMetadata> metadata = new ConcurrentHashMap<>();

    TransactionalMetadataRegistry(Map<Class<?>, TransactionalMetadata> precomputed) {
        metadata.putAll(precomputed);
    }

    /**
     * The effective definition for the invoked method, or {@code null} when the type carries no
     * {@code @Transactional} the interceptor should honor.
     *
     * @param targetClass the class of the intercepted bean instance
     * @param method      the invoked method
     */
    TransactionDefinition definitionFor(Class<?> targetClass, Method method) {
        if (targetClass == null || method == null) {
            return null;
        }
        TransactionalMetadata resolved = lookup(targetClass);
        return resolved == null ? null : resolved.definitionFor(method);
    }

    private TransactionalMetadata lookup(Class<?> targetClass) {
        for (Class<?> current = targetClass; current != null && current != Object.class; current = current.getSuperclass()) {
            TransactionalMetadata found = metadata.get(current);
            if (found != null) {
                return found;
            }
        }
        TransactionalMetadata resolved = metadata.computeIfAbsent(targetClass, TransactionalMetadata::forClass);
        return resolved.isTransactional() ? resolved : null;
    }
}

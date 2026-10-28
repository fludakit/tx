package io.github.fludakit.tx.cdi;

import io.github.fludakit.tx.TransactionDefinition;

import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import jakarta.transaction.Transactional;

/**
 * The transaction attributes resolved for one bean class, computed once at deployment time by
 * {@link TransactionalCdiExtension}.
 *
 * <p>{@link TransactionalInterceptor} used to walk superclasses and interfaces on every
 * invocation to find the effective {@code @Transactional}. That resolution is a deployment-time
 * concern — the type graph cannot change while the application runs — so it happens here once and
 * the interceptor only dispatches on the result.</p>
 *
 * <h2>Resolution Rules</h2>
 * <ol>
 *   <li><b>Method-level</b> annotations win over type-level ones.</li>
 *   <li>Between method declarations, the nearest one to the bean class wins: the class itself
 *       before its superclass, a directly implemented interface before a super-interface.</li>
 *   <li><b>Type-level</b> annotations follow the same proximity order, so an annotation on a
 *       subclass or near interface shadows one further up the hierarchy.</li>
 * </ol>
 *
 * <p>Instances are immutable and shared by every invocation of the bean, so the interceptor's
 * hot path is a single map lookup.</p>
 */
final class TransactionalMetadata {

    /** Marker for a type with no {@code @Transactional} reachable from it. */
    private static final TransactionalMetadata NONE =
            new TransactionalMetadata(null, Map.of());

    private final TransactionDefinition typeDefinition;
    private final Map<MethodKey, TransactionDefinition> methodDefinitions;

    private TransactionalMetadata(TransactionDefinition typeDefinition,
                                  Map<MethodKey, TransactionDefinition> methodDefinitions) {
        this.typeDefinition = typeDefinition;
        this.methodDefinitions = methodDefinitions;
    }

    /** The empty metadata for a type without a reachable {@code @Transactional}. */
    static TransactionalMetadata none() {
        return NONE;
    }

    /**
     * Resolves the transaction attributes reachable from the given bean class.
     *
     * @param beanClass the bean class registered by the container
     * @return the resolved metadata, or {@link #none()} when nothing is annotated
     */
    static TransactionalMetadata forClass(Class<?> beanClass) {
        Transactional typeAnnotation = findTypeAnnotation(beanClass);

        Map<MethodKey, TransactionDefinition> methods = new HashMap<>();
        for (MethodKey signature : methodSignatures(beanClass)) {
            Transactional annotation = findMethodAnnotation(beanClass, signature);
            if (annotation != null) {
                methods.put(signature, new TransactionDefinition(annotation));
            }
        }

        if (typeAnnotation == null && methods.isEmpty()) {
            return NONE;
        }
        return new TransactionalMetadata(
                typeAnnotation == null ? null : new TransactionDefinition(typeAnnotation),
                Map.copyOf(methods));
    }

    /**
     * Whether this type participates in transaction demarcation at all — the condition for adding
     * {@link TransactionalBinding} to it.
     */
    boolean isTransactional() {
        return typeDefinition != null || !methodDefinitions.isEmpty();
    }

    /**
     * The effective definition for the invoked method: its method-level annotation if there is
     * one, otherwise the type-level fallback. {@code null} when the type has no type-level
     * annotation and the method is not annotated.
     */
    TransactionDefinition definitionFor(Method method) {
        TransactionDefinition definition = methodDefinitions.get(MethodKey.of(method));
        return definition != null ? definition : typeDefinition;
    }

    // --- resolution ---

    private static Transactional findMethodAnnotation(Class<?> beanClass, MethodKey signature) {
        for (Class<?> current = beanClass; current != null && current != Object.class; current = current.getSuperclass()) {
            Transactional annotation = declaredMethodAnnotation(current, signature);
            if (annotation != null) {
                return annotation;
            }
            annotation = findInterfaceMethodAnnotation(current, signature);
            if (annotation != null) {
                return annotation;
            }
        }
        return null;
    }

    private static Transactional findInterfaceMethodAnnotation(Class<?> type, MethodKey signature) {
        for (Class<?> iface : type.getInterfaces()) {
            Transactional annotation = declaredMethodAnnotation(iface, signature);
            if (annotation != null) {
                return annotation;
            }
            annotation = findInterfaceMethodAnnotation(iface, signature);
            if (annotation != null) {
                return annotation;
            }
        }
        return null;
    }

    private static Transactional declaredMethodAnnotation(Class<?> type, MethodKey signature) {
        try {
            return type.getDeclaredMethod(signature.name(), signature.parameterTypesArray())
                    .getAnnotation(Transactional.class);
        } catch (NoSuchMethodException e) {
            return null;
        }
    }

    private static Transactional findTypeAnnotation(Class<?> beanClass) {
        for (Class<?> current = beanClass; current != null && current != Object.class; current = current.getSuperclass()) {
            Transactional annotation = current.getAnnotation(Transactional.class);
            if (annotation != null) {
                return annotation;
            }
            annotation = findInterfaceTypeAnnotation(current);
            if (annotation != null) {
                return annotation;
            }
        }
        return null;
    }

    private static Transactional findInterfaceTypeAnnotation(Class<?> type) {
        for (Class<?> iface : type.getInterfaces()) {
            Transactional annotation = iface.getAnnotation(Transactional.class);
            if (annotation != null) {
                return annotation;
            }
            annotation = findInterfaceTypeAnnotation(iface);
            if (annotation != null) {
                return annotation;
            }
        }
        return null;
    }

    /**
     * Every method signature reachable from the bean class (superclasses and interfaces), so a
     * method-level annotation inherited from anywhere in the type graph is found once, here.
     */
    private static Set<MethodKey> methodSignatures(Class<?> beanClass) {
        Set<MethodKey> signatures = new HashSet<>();
        for (Class<?> current = beanClass; current != null && current != Object.class; current = current.getSuperclass()) {
            for (Method method : current.getDeclaredMethods()) {
                signatures.add(MethodKey.of(method));
            }
        }
        collectInterfaceSignatures(beanClass, signatures, new HashSet<>());
        return signatures;
    }

    private static void collectInterfaceSignatures(Class<?> type, Set<MethodKey> signatures, Set<Class<?>> visited) {
        for (Class<?> iface : type.getInterfaces()) {
            if (!visited.add(iface)) {
                continue;
            }
            for (Method method : iface.getDeclaredMethods()) {
                signatures.add(MethodKey.of(method));
            }
            collectInterfaceSignatures(iface, signatures, visited);
        }
    }

    /**
     * Method identity by name and erased parameter types — the shape CDI hands to
     * {@code InvocationContext#getMethod()}, independent of the declaration the container reports.
     */
    record MethodKey(String name, List<Class<?>> parameterTypes) {

        MethodKey {
            parameterTypes = List.copyOf(parameterTypes);
        }

        static MethodKey of(Method method) {
            return new MethodKey(method.getName(), List.of(method.getParameterTypes()));
        }

        Class<?>[] parameterTypesArray() {
            return parameterTypes.toArray(new Class<?>[0]);
        }
    }
}

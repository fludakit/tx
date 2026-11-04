package io.github.fludakit.tx;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import jakarta.inject.Qualifier;

/**
 * Qualifies the {@link javax.sql.DataSource} bean that is the connection pool itself, as opposed
 * to a {@code TransactionAwareDataSourceProxy} view of it.
 *
 * <p>Transaction managers, persistence units, and anything else that opens connections of its own
 * must be handed the pool; only application code that participates in the running transaction may
 * see the proxy. When an application exposes both, it marks the pool and leaves the proxy
 * unqualified:</p>
 *
 * <pre>{@code
 * @Produces
 * @RawDataSource
 * public DataSource pool() {
 *     return hikariDataSource;                       // the pool itself
 * }
 *
 * @Produces
 * public DataSource dataSource() {
 *     return new TransactionAwareDataSourceProxy(pool());   // the default bean
 * }
 * }</pre>
 *
 * <p>The producers shipped with this library reuse an unqualified
 * {@code TransactionAwareDataSourceProxy} when the application exposes one as its default bean,
 * so an application with a single pool needs no qualifier at all; otherwise they prefer a
 * {@code @RawDataSource} bean and fall back to the default unqualified {@code DataSource}.</p>
 *
 * @see javax.sql.DataSource
 */
@Qualifier
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.METHOD, ElementType.FIELD, ElementType.PARAMETER, ElementType.TYPE})
public @interface RawDataSource {
}

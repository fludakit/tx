# Mixing JDBC and JPA

Because the same physical `Connection` backs both the `EntityManager` and the `TransactionAwareDataSourceProxy`, JPA and JDBC operations share a single transaction:

```java
@ApplicationScoped
public class OrderService {

    @Inject
    EntityManager em;

    @Inject
    JdbcClient client;

    @Transactional
    public void createOrder(Order order) {
        em.persist(order);                                    // JPA
        client.sql("INSERT INTO audit_log (msg) VALUES (:m)") // JDBC
              .param("m", "Order created: " + order.getId())
              .update();
    }
}
```

This allows you to leverage the strengths of both APIs: JPA for entity management and relationships, JDBC for bulk operations or complex queries that benefit from direct SQL.

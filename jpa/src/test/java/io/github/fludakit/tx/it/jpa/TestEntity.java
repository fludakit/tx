package io.github.fludakit.tx.it.jpa;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "test_items")
public class TestEntity {

    @Id
    private Long id;
    private String name;

    public TestEntity() {}

    public TestEntity(Long id, String name) {
        this.id = id;
        this.name = name;
    }

    public Long getId() { return id; }
    public String getName() { return name; }
}

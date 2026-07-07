package com.aitaskcenter.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.persistence.Column;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.MappedSuperclass;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import java.time.OffsetDateTime;

@MappedSuperclass
public abstract class BaseEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @JsonProperty("ID")
    // 字段：主键 ID
    private Long id;

    @Column(nullable = false, updatable = false)
    @JsonProperty("CreatedAt")
    // 字段：创建时间
    private OffsetDateTime createdAt;

    @Column(nullable = false)
    @JsonProperty("UpdatedAt")
    // 字段：更新时间
    private OffsetDateTime updatedAt;

    @PrePersist
    void prePersist() {
        OffsetDateTime now = OffsetDateTime.now();
        createdAt = now;
        updatedAt = now;
    }

    @PreUpdate
    void preUpdate() {
        updatedAt = OffsetDateTime.now();
    }

    // 方法：getId
    public Long getId() {
        return id;
    }

    // 方法：setId
    public void setId(Long id) {
        this.id = id;
    }

    // 方法：getCreatedAt
    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }

    // 方法：getUpdatedAt
    public OffsetDateTime getUpdatedAt() {
        return updatedAt;
    }
}

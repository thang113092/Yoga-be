package com.company.yoga.common.entity;

import com.company.yoga.common.util.UuidUtils;
import jakarta.persistence.Column;
import jakarta.persistence.Id;
import jakarta.persistence.MappedSuperclass;
import jakarta.persistence.PrePersist;
import java.time.Instant;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

/**
 * Base mapped superclass for immutable / append-only records (e.g. line item snapshots, payments).
 * Contains primary key UUIDv7 and created_at timestamp without updated_at.
 */
@MappedSuperclass
@Getter
@Setter
public abstract class BaseCreationAuditEntity {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    protected void onPrePersist() {
        if (this.id == null) {
            this.id = UuidUtils.uuidV7();
        }
        if (this.createdAt == null) {
            this.createdAt = Instant.now();
        }
    }
}

package com.company.yoga.schedule.booking.entity;

import com.company.yoga.common.entity.BaseAuditEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "class_types", schema = "yoga")
@Getter
@Setter
@NoArgsConstructor
public class ClassTypeEntity extends BaseAuditEntity {

    @Column(name = "code", nullable = false, unique = true, length = 50)
    private String code;

    @Column(name = "name", nullable = false, length = 100)
    private String name;

    @Column(name = "description", columnDefinition = "TEXT")
    private String description;

    @Column(name = "default_duration_minutes", nullable = false)
    private Integer defaultDurationMinutes = 60;

    @Column(name = "intensity_level", nullable = false, length = 20)
    private String intensityLevel = "ALL_LEVELS";

    @Column(name = "is_active", nullable = false)
    private Boolean isActive = true;
}

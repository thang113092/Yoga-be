package com.company.yoga.schedule.course;

import com.company.yoga.common.entity.BaseAuditEntity;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** Read projection used by schedule joins; writes belong to CourseService. */
@Entity
@Table(name="course_classes",schema="yoga")
@Getter
@NoArgsConstructor
public class CourseClassEntity extends BaseAuditEntity {
    @Column(name="name",nullable=false) private String name;
}

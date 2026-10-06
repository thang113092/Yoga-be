package com.company.yoga.schedule.booking.service;

import com.company.yoga.schedule.booking.entity.*;
import com.company.yoga.schedule.booking.repository.*;
import com.company.yoga.branch.facility.entity.BranchEntity;
import com.company.yoga.branch.facility.entity.RoomEntity;
import com.company.yoga.identity.account.entity.UserEntity;
import org.hibernate.boot.MetadataSources;
import org.hibernate.boot.registry.StandardServiceRegistryBuilder;
import org.junit.jupiter.api.Test;
import org.springframework.data.jpa.repository.Query;

class RepositoryQueryTest {
    @Test
    void hibernateCanCompileRepositoryQueriesWithoutDatabase() {
        var registry = new StandardServiceRegistryBuilder()
                .applySetting("hibernate.dialect", "org.hibernate.dialect.PostgreSQLDialect")
                .applySetting("hibernate.boot.allow_jdbc_metadata_access", false)
                .applySetting("hibernate.hbm2ddl.auto", "none")
                .build();
        try {
            var metadata = new MetadataSources(registry);
            for (var entity : new Class<?>[]{ClassScheduleEntity.class, ClassTypeEntity.class,
                    BookingEntity.class, WaitlistEntity.class, BranchEntity.class, RoomEntity.class, UserEntity.class}) {
                metadata.addAnnotatedClass(entity);
            }
            try (var factory = metadata.buildMetadata().buildSessionFactory(); var session = factory.openSession()) {
                for (var repository : new Class<?>[]{ClassScheduleRepository.class, BookingRepository.class, WaitlistRepository.class, com.company.yoga.identity.account.repository.UserRepository.class}) {
                    for (var method : repository.getDeclaredMethods()) {
                        var query = method.getAnnotation(Query.class);
                        if (query != null && !query.nativeQuery()) session.createQuery(query.value());
                    }
                }
            }
        } finally {
            StandardServiceRegistryBuilder.destroy(registry);
        }
    }
}

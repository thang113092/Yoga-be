package com.company.yoga.content;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verifyNoInteractions;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

@ExtendWith(MockitoExtension.class)
class BunnyWebhookServiceTest {

    @Mock private VideoAdminService videos;
    @Mock private NamedParameterJdbcTemplate jdbc;

    @Test
    void rejectsWrongOrMissingSecretWithoutTouchingAnything() {
        var service = new BunnyWebhookService(videos, new ObjectMapper(), jdbc, "s3cret");
        assertThat(service.handle("wrong", "{\"VideoGuid\":\"g\",\"Status\":4}")).isFalse();
        assertThat(service.handle(null, "{}")).isFalse();
        verifyNoInteractions(videos, jdbc);
    }

    @Test
    void disabledWhenNoSecretConfigured() {
        var service = new BunnyWebhookService(videos, new ObjectMapper(), jdbc, "");
        assertThat(service.handle("", "{}")).isFalse();
        verifyNoInteractions(videos, jdbc);
    }
}

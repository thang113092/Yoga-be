package com.company.yoga.content;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withResourceNotFound;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.company.yoga.content.VideoHostingProvider.Phase;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class BunnyStreamProviderTest {

    private MockRestServiceServer server;
    private BunnyStreamProvider bunny;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder().baseUrl("https://bunny.test");
        server = MockRestServiceServer.bindTo(builder).build();
        bunny = new BunnyStreamProvider(builder.build(), "https://player.test/", "https://upload.test/tus", "lib1", "api-key", "embed-key", "cdn.test");
    }

    @Test
    void createsVideoWithTheLibraryKey() {
        server.expect(requestTo("https://bunny.test/library/lib1/videos")).andExpect(method(HttpMethod.POST))
                .andExpect(header("AccessKey", "api-key")).andExpect(content().json("{\"title\":\"Giãn cơ\"}"))
                .andRespond(withSuccess("{\"guid\":\"g-1\"}", MediaType.APPLICATION_JSON));
        assertThat(bunny.createVideo("Giãn cơ")).isEqualTo("g-1");
        server.verify();
    }

    @Test
    void signsTusUploadsAsBunnyExpects() {
        var ticket = bunny.uploadTicket("g-1", Duration.ofHours(1));
        assertThat(ticket.endpoint()).isEqualTo("https://upload.test/tus");
        assertThat(ticket.signature()).isEqualTo(BunnyStreamProvider.sha256Hex("lib1" + "api-key" + ticket.expiresEpochSeconds() + "g-1"));
        assertThat(ticket.expiresEpochSeconds()).isBetween(Instant.now().getEpochSecond() + 3500, Instant.now().getEpochSecond() + 3700);
    }

    @Test
    void signsTheEmbedPlayerUrl() {
        var playback = bunny.playback("g-1", Duration.ofMinutes(120));
        long expires = playback.expiresAt().getEpochSecond();
        assertThat(playback.embedUrl()).startsWith("https://player.test/embed/lib1/g-1?")
                .contains("&token=" + BunnyStreamProvider.sha256Hex("embed-key" + "g-1" + expires) + "&expires=" + expires);
    }

    @Test
    void unsignedUrlWithoutTokenKey() {
        var plain = new BunnyStreamProvider(RestClient.create(), "https://player.test", "t", "lib1", "k", "", "");
        assertThat(plain.playback("g-1", Duration.ofMinutes(5)).embedUrl()).doesNotContain("token=");
        assertThat(new BunnyStreamProvider(RestClient.create(), "e", "t", "", "k", "", "").isConfigured()).isFalse();
    }

    @Test
    void mapsBunnyStatuses() {
        expectStatus("{\"status\":4,\"length\":600,\"thumbnailFileName\":\"thumbnail.jpg\"}");
        var ready = bunny.state("g-1");
        assertThat(ready.phase()).isEqualTo(Phase.READY);
        assertThat(ready.durationSeconds()).isEqualTo(600);
        assertThat(ready.thumbnailUrl()).isEqualTo("https://cdn.test/g-1/thumbnail.jpg");

        expectStatus("{\"status\":3,\"length\":0}");
        assertThat(bunny.state("g-1").phase()).isEqualTo(Phase.PROCESSING);
        expectStatus("{\"status\":4,\"length\":0}");
        assertThat(bunny.state("g-1").phase()).isEqualTo(Phase.PROCESSING);
        expectStatus("{\"status\":0,\"length\":0}");
        assertThat(bunny.state("g-1").phase()).isEqualTo(Phase.WAITING_FOR_UPLOAD);
        expectStatus("{\"status\":5}");
        assertThat(bunny.state("g-1").phase()).isEqualTo(Phase.FAILED);
        expectStatus("{\"status\":6}");
        assertThat(bunny.state("g-1").phase()).isEqualTo(Phase.FAILED);
    }

    @Test
    void deletingAMissingVideoSucceeds() {
        server.expect(requestTo("https://bunny.test/library/lib1/videos/g-1")).andExpect(method(HttpMethod.DELETE)).andRespond(withResourceNotFound());
        bunny.delete("g-1");
        server.verify();
    }

    @Test
    void serverErrorsBecomeProviderErrors() {
        server.expect(requestTo("https://bunny.test/library/lib1/videos")).andRespond(withServerError());
        assertThatThrownBy(() -> bunny.createVideo("x")).isInstanceOf(VideoHostingProvider.ProviderException.class);
    }

    @Test
    void fetchesByUrlWithHeadersAndFindsTheVideoByItsTitle() {
        server.expect(requestTo("https://bunny.test/library/lib1/videos/fetch")).andExpect(method(HttpMethod.POST))
                .andExpect(header("AccessKey", "api-key"))
                .andExpect(content().json("{\"url\":\"https://zoom.us/rec/1\",\"title\":\"rec-1\",\"headers\":{\"Authorization\":\"Bearer t\"}}"))
                .andRespond(withSuccess("{\"success\":true,\"statusCode\":200}", MediaType.APPLICATION_JSON));
        server.expect(requestTo("https://bunny.test/library/lib1/videos?search=rec-1&itemsPerPage=20"))
                .andRespond(withSuccess("{\"items\":[{\"guid\":\"other\",\"title\":\"rec-10\"},{\"guid\":\"g-9\",\"title\":\"rec-1\"}]}", MediaType.APPLICATION_JSON));
        bunny.fetchFromUrl("https://zoom.us/rec/1", java.util.Map.of("Authorization", "Bearer t"), "rec-1");
        assertThat(bunny.findVideoIdByTitle("rec-1")).contains("g-9");
        server.verify();
    }

    @Test
    void refusedFetchIsAProviderError() {
        server.expect(requestTo("https://bunny.test/library/lib1/videos/fetch"))
                .andRespond(withSuccess("{\"success\":false,\"message\":\"queue full\"}", MediaType.APPLICATION_JSON));
        assertThatThrownBy(() -> bunny.fetchFromUrl("https://x", java.util.Map.of(), "t")).isInstanceOf(VideoHostingProvider.ProviderException.class);
    }

    private void expectStatus(String body) {
        server.reset();
        server.expect(requestTo("https://bunny.test/library/lib1/videos/g-1")).andExpect(header("AccessKey", "api-key"))
                .andRespond(withSuccess(body, MediaType.APPLICATION_JSON));
    }
}

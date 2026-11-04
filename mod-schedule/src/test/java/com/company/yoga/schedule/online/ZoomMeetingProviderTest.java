package com.company.yoga.schedule.online;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class ZoomMeetingProviderTest {

    private MockRestServiceServer api;
    private MockRestServiceServer oauth;
    private ZoomMeetingProvider zoom;

    @BeforeEach
    void setUp() {
        RestClient.Builder apiBuilder = RestClient.builder().baseUrl("https://zoom.test/v2");
        RestClient.Builder oauthBuilder = RestClient.builder().baseUrl("https://zoom.test/oauth/token");
        api = MockRestServiceServer.bindTo(apiBuilder).build();
        oauth = MockRestServiceServer.bindTo(oauthBuilder).build();
        zoom = new ZoomMeetingProvider(apiBuilder.build(), oauthBuilder.build(), "acc", "cid", "csecret", "me");
    }

    private void expectToken() {
        oauth.expect(once(), requestTo("https://zoom.test/oauth/token?grant_type=account_credentials&account_id=acc"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("Authorization", "Basic Y2lkOmNzZWNyZXQ="))
                .andRespond(withSuccess("{\"access_token\":\"T1\",\"expires_in\":3600}", MediaType.APPLICATION_JSON));
    }

    @Test
    @DisplayName("Tạo phòng có đăng ký tự duyệt, giờ UTC, host của phòng ảo; token được dùng lại")
    void createMeeting_thenRegistrant() {
        expectToken();
        api.expect(requestTo("https://zoom.test/v2/users/host1%40example.com/meetings")).andExpect(method(HttpMethod.POST))
                .andExpect(header("Authorization", "Bearer T1"))
                .andExpect(jsonPath("$.type").value(2))
                .andExpect(jsonPath("$.start_time").value("2026-11-05T12:00:00Z"))
                .andExpect(jsonPath("$.duration").value(60))
                .andExpect(jsonPath("$.settings.approval_type").value(0))
                .andExpect(jsonPath("$.settings.registrants_email_notification").value(false))
                .andRespond(withSuccess("{\"id\":85746065432,\"join_url\":\"https://zoom.us/j/1\"}", MediaType.APPLICATION_JSON));
        api.expect(requestTo("https://zoom.test/v2/meetings/85746065432/registrants")).andExpect(method(HttpMethod.POST))
                .andExpect(header("Authorization", "Bearer T1"))
                .andExpect(jsonPath("$.email").value("b-1@students.test"))
                .andExpect(jsonPath("$.first_name").value("Lan"))
                .andRespond(withSuccess("{\"registrant_id\":\"R1\",\"join_url\":\"https://zoom.us/w/1?tk=abc\"}", MediaType.APPLICATION_JSON));

        String id = zoom.createMeeting(new LiveMeetingProvider.MeetingSpec("host1@example.com", "Hatha", Instant.parse("2026-11-05T12:00:00.123Z"), 60));
        var registrant = zoom.addRegistrant(id, new LiveMeetingProvider.RegistrantSpec("b-1@students.test", "Lan", null));

        assertThat(id).isEqualTo("85746065432");
        assertThat(registrant.registrantId()).isEqualTo("R1");
        assertThat(registrant.joinUrl()).isEqualTo("https://zoom.us/w/1?tk=abc");
        api.verify();
        oauth.verify();
    }

    @Test
    @DisplayName("Xóa phòng hoặc registrant đã không còn trên Zoom (404) coi như thành công")
    void delete_missingIsDone() {
        expectToken();
        api.expect(requestTo("https://zoom.test/v2/meetings/1?schedule_for_reminder=false")).andExpect(method(HttpMethod.DELETE))
                .andRespond(withStatus(HttpStatus.NOT_FOUND));
        api.expect(requestTo("https://zoom.test/v2/meetings/1/registrants/R1")).andExpect(method(HttpMethod.DELETE))
                .andRespond(withStatus(HttpStatus.NOT_FOUND));

        zoom.deleteMeeting("1");
        zoom.removeRegistrant("1", "R1");
        api.verify();
    }

    @Test
    @DisplayName("Zoom quá tải (429) là lỗi tạm thời; 400 là lỗi vĩnh viễn")
    void errorsClassified() {
        expectToken();
        api.expect(requestTo("https://zoom.test/v2/meetings/2")).andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS));
        api.expect(requestTo("https://zoom.test/v2/meetings/3")).andRespond(withStatus(HttpStatus.BAD_REQUEST));

        assertThatThrownBy(() -> zoom.hostStartUrl("2")).isInstanceOfSatisfying(LiveMeetingProvider.ProviderException.class,
                ex -> assertThat(ex.isPermanent()).isFalse());
        assertThatThrownBy(() -> zoom.hostStartUrl("3")).isInstanceOfSatisfying(LiveMeetingProvider.ProviderException.class,
                ex -> assertThat(ex.isPermanent()).isTrue());
    }

    @Test
    @DisplayName("Chế độ Zoom Basic: phòng không bật đăng ký, mọi học viên nhận link chung, hủy chỗ không gọi Zoom")
    void sharedLinkMode() {
        RestClient.Builder apiBuilder = RestClient.builder().baseUrl("https://zoom.test/v2");
        RestClient.Builder oauthBuilder = RestClient.builder().baseUrl("https://zoom.test/oauth/token");
        api = MockRestServiceServer.bindTo(apiBuilder).build();
        oauth = MockRestServiceServer.bindTo(oauthBuilder).build();
        var basic = new ZoomMeetingProvider(apiBuilder.build(), oauthBuilder.build(), "acc", "cid", "csecret", "me", false);
        expectToken();
        api.expect(requestTo("https://zoom.test/v2/users/me/meetings")).andExpect(method(HttpMethod.POST))
                .andExpect(jsonPath("$.settings.approval_type").doesNotExist())
                .andExpect(jsonPath("$.settings.registration_type").doesNotExist())
                .andExpect(jsonPath("$.settings.join_before_host").value(false))
                .andRespond(withSuccess("{\"id\":77}", MediaType.APPLICATION_JSON));
        api.expect(requestTo("https://zoom.test/v2/meetings/77")).andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("{\"id\":77,\"join_url\":\"https://zoom.us/j/77?pwd=abc\"}", MediaType.APPLICATION_JSON));

        String id = basic.createMeeting(new LiveMeetingProvider.MeetingSpec(null, "Hatha", Instant.parse("2026-11-05T12:00:00Z"), 60));
        var link = basic.addRegistrant(id, new LiveMeetingProvider.RegistrantSpec("b-1@students.test", "Lan", null));
        basic.removeRegistrant(id, link.registrantId());

        assertThat(basic.personalLinks()).isFalse();
        assertThat(link.registrantId()).isEqualTo(ZoomMeetingProvider.SHARED_LINK);
        assertThat(link.joinUrl()).isEqualTo("https://zoom.us/j/77?pwd=abc");
        api.verify();
    }

    @Test
    @DisplayName("Thiếu khóa thì coi như chưa cấu hình")
    void notConfigured() {
        assertThat(new ZoomMeetingProvider(RestClient.create(), RestClient.create(), "", "cid", "s", "me").isConfigured()).isFalse();
        assertThat(zoom.isConfigured()).isTrue();
    }
}

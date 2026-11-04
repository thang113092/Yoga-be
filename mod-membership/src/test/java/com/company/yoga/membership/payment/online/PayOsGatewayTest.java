package com.company.yoga.membership.payment.online;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class PayOsGatewayTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private MockRestServiceServer server;
    private PayOsGateway gateway;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder().baseUrl("https://payos.test");
        server = MockRestServiceServer.bindTo(builder).build();
        gateway = new PayOsGateway(builder.build(), "secret", true);
    }

    @Test
    @DisplayName("Tạo link gửi chữ ký, hạn dùng theo giây và đọc QR từ phản hồi")
    void createLink_signsRequestAndReadsQr() {
        Instant expires = Instant.parse("2026-11-03T10:15:30Z");
        server.expect(requestTo("https://payos.test/v2/payment-requests")).andExpect(method(HttpMethod.POST))
                .andExpect(jsonPath("$.orderCode").value(100001))
                .andExpect(jsonPath("$.amount").value(2000))
                .andExpect(jsonPath("$.expiredAt").value(expires.getEpochSecond()))
                .andExpect(jsonPath("$.signature").value(PayOsSignature.paymentRequest(2000, "https://r", "YG100001", 100001, "https://r", "secret")))
                .andRespond(withSuccess("""
                        {"code":"00","desc":"success","data":{"paymentLinkId":"L1","checkoutUrl":"https://pay.payos.vn/web/L1","qrCode":"000201..."}}
                        """, MediaType.APPLICATION_JSON));

        var link = gateway.createLink(new PaymentGateway.LinkRequest(100001, 2000, "YG100001", expires, "https://r", "https://r"));

        assertThat(link.linkId()).isEqualTo("L1");
        assertThat(link.qrPayload()).isEqualTo("000201...");
        server.verify();
    }

    @Test
    @DisplayName("Mã lỗi nghiệp vụ của PayOS (HTTP 200, code khác 00) được coi là lỗi phía yêu cầu")
    void nonSuccessCode_isClientError() {
        server.expect(requestTo("https://payos.test/v2/payment-requests/7"))
                .andRespond(withSuccess("{\"code\":\"101\",\"desc\":\"Không tìm thấy\"}", MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> gateway.getStatus(7))
                .isInstanceOfSatisfying(PaymentGateway.GatewayException.class, ex -> assertThat(ex.isClientError()).isTrue());
    }

    @Test
    @DisplayName("Lỗi máy chủ PayOS không bị coi là link không tồn tại")
    void serverError_isNotClientError() {
        server.expect(requestTo("https://payos.test/v2/payment-requests/7")).andRespond(withStatus(HttpStatus.BAD_GATEWAY));

        assertThatThrownBy(() -> gateway.getStatus(7))
                .isInstanceOfSatisfying(PaymentGateway.GatewayException.class, ex -> assertThat(ex.isClientError()).isFalse());
    }

    @Test
    @DisplayName("Trạng thái link đọc số tiền đã nhận và mã giao dịch ngân hàng")
    void getStatus_readsPaidAmountAndReference() {
        server.expect(requestTo("https://payos.test/v2/payment-requests/7")).andRespond(withSuccess("""
                {"code":"00","data":{"status":"PAID","amount":2000,"amountPaid":2000,
                 "transactions":[{"reference":"FT123","amount":2000,"transactionDateTime":"2026-11-03 17:20:00"}]}}
                """, MediaType.APPLICATION_JSON));

        var status = gateway.getStatus(7);

        assertThat(status.amountPaid()).isEqualTo(2000);
        assertThat(status.reference()).isEqualTo("FT123");
        assertThat(status.paidAt()).isEqualTo(Instant.parse("2026-11-03T10:20:00Z"));
    }

    @Test
    @DisplayName("Webhook có chữ ký hợp lệ được chấp nhận, sửa nội dung thì bị từ chối")
    void verifyWebhook_acceptsOnlyValidSignature() throws Exception {
        ObjectNode data = (ObjectNode) JSON.readTree("""
                {"orderCode":100001,"amount":2000,"reference":"FT1","paymentLinkId":"L1","code":"00","desc":"success",
                 "transactionDateTime":"2026-11-03 17:20:00"}
                """);
        ObjectNode body = JSON.createObjectNode().put("code", "00").put("desc", "success").put("success", true);
        body.set("data", data);
        body.put("signature", PayOsSignature.data(data, "secret"));

        var notice = gateway.verifyWebhook(body).orElseThrow();
        assertThat(notice.eventReference()).isEqualTo("L1:FT1");
        assertThat(notice.orderCode()).isEqualTo(100001);
        assertThat(notice.success()).isTrue();

        data.put("amount", 1);
        assertThat(gateway.verifyWebhook(body)).isEmpty();
    }

    @Test
    @DisplayName("Chưa cấu hình khóa thì không chấp nhận webhook nào")
    void verifyWebhook_rejectsWhenNotConfigured() throws Exception {
        var unconfigured = new PayOsGateway(RestClient.create(), "", false);
        assertThat(unconfigured.verifyWebhook(JSON.readTree("{\"data\":{},\"signature\":\"x\"}"))).isEmpty();
    }
}

package com.company.yoga.membership.payment.online;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class PayOsSignatureTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    // Sample webhook data and key from https://payos.vn/docs/tich-hop-webhook/kiem-tra-du-lieu-voi-signature/
    private static final String DOC_KEY = "1a54716c8f0efb2744fb28b6e38b25da7f67a925d98bc1c18bd8faaecadd7675";
    private static final String DOC_DATA = """
            {"orderCode":123,"amount":3000,"description":"VQRIO123","accountNumber":"12345678","reference":"TF230204212323",
             "transactionDateTime":"2023-02-04 18:25:00","currency":"VND","paymentLinkId":"124c33293c43417ab7879e14c8d9eb18",
             "code":"00","desc":"Thành công","counterAccountBankId":"","counterAccountBankName":"","counterAccountName":"",
             "counterAccountNumber":"","virtualAccountName":"","virtualAccountNumber":""}
            """;

    @Test
    @DisplayName("Chữ ký webhook khớp ví dụ trong tài liệu PayOS")
    void webhookSignature_matchesDocumentation() throws Exception {
        assertThat(PayOsSignature.data(JSON.readTree(DOC_DATA), DOC_KEY))
                .isEqualTo("412e915d2871504ed31be63c8f62a149a4410d34c4c42affc9006ef9917eaa03");
    }

    @Test
    @DisplayName("Chữ ký tạo link ký đúng chuỗi amount/cancelUrl/description/orderCode/returnUrl")
    void paymentRequestSignature_usesDocumentedFieldOrder() throws Exception {
        String expected = hmac("amount=2000&cancelUrl=https://a/c&description=YG100001&orderCode=100001&returnUrl=https://a/r", "k");
        assertThat(PayOsSignature.paymentRequest(2000, "https://a/c", "YG100001", 100001, "https://a/r", "k")).isEqualTo(expected);
    }

    @Test
    @DisplayName("Giá trị null thành chuỗi rỗng, mảng/đối tượng thành JSON có khóa đã sắp xếp")
    void dataSignature_normalisesNullsAndNestedValues() throws Exception {
        JsonNode data = JSON.readTree("{\"b\":null,\"a\":[{\"y\":1,\"x\":\"t\"}],\"c\":\"null\",\"d\":true}");
        assertThat(PayOsSignature.data(data, "k")).isEqualTo(hmac("a=[{\"x\":\"t\",\"y\":1}]&b=&c=&d=true", "k"));
    }

    @Test
    @DisplayName("So sánh chữ ký không phân biệt hoa thường và từ chối chữ ký thiếu")
    void matches_isCaseInsensitiveAndRejectsMissing() {
        assertThat(PayOsSignature.matches("abc123", "ABC123")).isTrue();
        assertThat(PayOsSignature.matches("abc123", "abc124")).isFalse();
        assertThat(PayOsSignature.matches("abc123", null)).isFalse();
    }

    private static String hmac(String message, String key) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return HexFormat.of().formatHex(mac.doFinal(message.getBytes(StandardCharsets.UTF_8)));
    }
}

package com.company.yoga.schedule.online;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

class LiveRecordingServiceTest {

    @Test
    void picksTheLargestCompletedMp4() throws Exception {
        var files = new ObjectMapper().readTree("""
                [{"id":"a","file_type":"M4A","file_size":900,"status":"completed"},
                 {"id":"b","file_type":"MP4","file_size":500,"status":"completed"},
                 {"id":"c","file_type":"MP4","file_size":800,"status":"completed"},
                 {"id":"d","file_type":"MP4","file_size":999,"status":"processing"},
                 {"id":"e","file_type":"CHAT","file_size":5}]""");
        assertThat(LiveRecordingService.mainFile(files).path("id").asText()).isEqualTo("c");
    }

    @Test
    void noVideoFileMeansNothingToImport() throws Exception {
        assertThat(LiveRecordingService.mainFile(new ObjectMapper().readTree("[{\"file_type\":\"M4A\"}]"))).isNull();
    }
}

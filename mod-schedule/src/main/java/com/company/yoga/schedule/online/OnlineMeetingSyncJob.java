package com.company.yoga.schedule.online;

import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Creates, updates and deletes Zoom meetings and registrants queued by the database. */
@Component
@RequiredArgsConstructor
public class OnlineMeetingSyncJob {

    private final OnlineMeetingSyncService sync;

    @Scheduled(fixedDelayString = "${yoga.online.sync-delay-ms:30000}")
    public void sync() {
        sync.syncDue();
    }
}

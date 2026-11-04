package com.company.yoga.schedule.online;

import java.time.Instant;
import java.util.UUID;

public final class OnlineDto {

    private OnlineDto() {}

    /** Personal Zoom link of the student; returned only inside the join window. */
    public record JoinResp(UUID bookingId, UUID scheduleId, String joinUrl, Instant startTime, Instant endTime) {}

    /**
     * Instructor/staff view of an online class. {@code startUrl} is the Zoom host link: only for the class instructor
     * or managers, and only from an hour before the class.
     */
    public record SessionResp(
            UUID scheduleId,
            boolean providerConfigured,
            String meetingStatus,
            String lastError,
            int syncAttempts,
            Instant startedAt,
            Instant endedAt,
            long registeredCount,
            long pendingCount,
            String startUrl,
            Instant hostLinkOpensAt,
            /** false: all students share the meeting link (Zoom Basic test mode), so cancelling cannot revoke it. */
            boolean personalLinks
    ) {}
}

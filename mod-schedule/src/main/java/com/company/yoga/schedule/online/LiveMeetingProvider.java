package com.company.yoga.schedule.online;

import java.time.Instant;

/** Streaming provider for online classes. Zoom today; an embedded provider can replace it later. */
public interface LiveMeetingProvider {

    String provider();

    boolean isConfigured();

    /** false when every attendee receives the same meeting link, which cannot be revoked per booking. */
    default boolean personalLinks() {
        return true;
    }

    /** {@code hostAccount} null means the provider's default host. */
    String createMeeting(MeetingSpec spec);

    void updateMeeting(String meetingId, MeetingSpec spec);

    /** Idempotent: a meeting that no longer exists counts as deleted. */
    void deleteMeeting(String meetingId);

    Registrant addRegistrant(String meetingId, RegistrantSpec spec);

    /** Idempotent: a registrant that no longer exists counts as removed. */
    void removeRegistrant(String meetingId, String registrantId);

    /** Host links expire, so they are fetched when needed and never stored. */
    String hostStartUrl(String meetingId);

    record MeetingSpec(String hostAccount, String topic, Instant start, int durationMinutes) {}

    record RegistrantSpec(String email, String firstName, String lastName) {}

    record Registrant(String registrantId, String joinUrl) {}

    class ProviderException extends RuntimeException {
        private final boolean permanent;

        public ProviderException(String message, boolean permanent) {
            super(message);
            this.permanent = permanent;
        }

        public boolean isPermanent() {
            return permanent;
        }
    }
}

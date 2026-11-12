package com.company.yoga.content;

import java.time.Duration;
import java.time.Instant;

/** Video hosting (Bunny Stream today). The browser uploads to the host directly; the backend never streams files. */
public interface VideoHostingProvider {

    boolean isConfigured();

    /** Creates an empty video on the host and returns its id. */
    String createVideo(String title);

    /** Short-lived credentials the browser uses to upload the file straight to the host (TUS). */
    UploadTicket uploadTicket(String providerVideoId, Duration ttl);

    /** Current transcoding state on the host. */
    VideoState state(String providerVideoId);

    /** Signed player URL that stops working after {@code ttl}. */
    SignedPlayback playback(String providerVideoId, Duration ttl);

    /** Asks the host to download a file (with extra request headers) into a new video titled {@code title}. */
    void fetchFromUrl(String url, java.util.Map<String, String> headers, String title);

    /** Id of the video with exactly this title, if the host has created it yet. */
    java.util.Optional<String> findVideoIdByTitle(String title);

    /** Deletes the video on the host; a video already gone counts as deleted. */
    void delete(String providerVideoId);

    record UploadTicket(String endpoint, String libraryId, String providerVideoId, String signature, long expiresEpochSeconds) {}

    record SignedPlayback(String embedUrl, Instant expiresAt) {}

    enum Phase { WAITING_FOR_UPLOAD, PROCESSING, READY, FAILED }

    record VideoState(Phase phase, Integer durationSeconds, String thumbnailUrl, String error) {}

    class ProviderException extends RuntimeException {
        public ProviderException(String message) {
            super(message);
        }
    }
}

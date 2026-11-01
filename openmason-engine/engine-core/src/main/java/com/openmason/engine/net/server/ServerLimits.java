package com.openmason.engine.net.server;

/**
 * What one remote connection may cost the server (issue #336). Applied to TCP connections
 * only: the in-JVM Local channel is the host's own client.
 *
 * @param maxConnections            TCP connections open at once; further accepts are closed
 * @param maxConnectionsPerAddress  TCP connections open at once from one remote IP
 * @param admissionTimeoutMillis    a connection that has not sent an admitting packet (the
 *                                  game's handshake, see {@link NetworkServer#setAdmissionFilter})
 *                                  within this long is closed
 * @param readIdleTimeoutSeconds    a connection that sends nothing for this long is closed
 *                                  (the game's keepalive answers count as traffic)
 * @param maxOutboundBytes          bytes queued for a connection that is not reading them;
 *                                  past this it is closed, whatever the packets are
 * @param maxInboundFrameBytes      largest frame a client may send
 * @param packetsPerSecond          sustained inbound packet rate before a kick
 * @param packetBurst               inbound packets allowed in a burst
 * @param bytesPerSecond            sustained inbound byte rate before a kick
 * @param byteBurst                 inbound bytes allowed in a burst
 * @param readPauseDepth            packets queued for the tick, per connection, at which
 *                                  reading from that connection pauses
 * @param readResumeDepth           queued packets at which reading resumes
 */
public record ServerLimits(
        int maxConnections,
        int maxConnectionsPerAddress,
        long admissionTimeoutMillis,
        int readIdleTimeoutSeconds,
        long maxOutboundBytes,
        int maxInboundFrameBytes,
        double packetsPerSecond,
        double packetBurst,
        double bytesPerSecond,
        double byteBurst,
        int readPauseDepth,
        int readResumeDepth) {

    /**
     * Defaults sized well above a legitimate client: a player sends ~20 state reports a
     * second plus bursts of edits and inventory drags, and its largest packet (a prefs
     * report) is under 70 KiB. The outbound cap is far above the chunk stream's own pacing
     * (it stops while the channel is over its write watermark).
     */
    public static final ServerLimits DEFAULTS = new ServerLimits(
            64,
            8,
            5_000L,
            60,
            16L * 1024 * 1024,
            256 * 1024,
            600,
            2_400,
            1024 * 1024,
            4L * 1024 * 1024,
            1024,
            256);

    public ServerLimits {
        if (readResumeDepth >= readPauseDepth) {
            throw new IllegalArgumentException("readResumeDepth must be below readPauseDepth");
        }
    }
}

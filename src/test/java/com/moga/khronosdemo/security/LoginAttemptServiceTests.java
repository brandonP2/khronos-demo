package com.moga.khronosdemo.security;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class LoginAttemptServiceTests {

    private final MutableClock clock = new MutableClock();
    private final LoginAttemptService service =
            new LoginAttemptService(new LoginAttemptProperties(3, Duration.ofMinutes(15)), clock);

    @Test
    void blocksThePairAfterTheMaximumFailuresUntilTheWindowEnds() {
        fail(3, "ana", "10.0.0.1");
        assertEquals(15 * 60, service.secondsUntilAllowed("ana", "10.0.0.1"));

        clock.advance(Duration.ofMinutes(10).plusMillis(1));
        assertEquals(5 * 60, service.secondsUntilAllowed("ana", "10.0.0.1"));

        clock.advance(Duration.ofMinutes(5));
        assertEquals(0, service.secondsUntilAllowed("ana", "10.0.0.1"));
    }

    @Test
    void failuresBelowTheMaximumDoNotBlock() {
        fail(2, "ana", "10.0.0.1");
        assertEquals(0, service.secondsUntilAllowed("ana", "10.0.0.1"));
    }

    @Test
    void aFreshWindowStartsOnceTheOldOneHasExpired() {
        fail(2, "ana", "10.0.0.1");
        clock.advance(Duration.ofMinutes(16));

        fail(2, "ana", "10.0.0.1");
        assertEquals(0, service.secondsUntilAllowed("ana", "10.0.0.1"));
    }

    @Test
    void usernameAndIpAreCountedTogether() {
        fail(3, "ana", "10.0.0.1");

        assertEquals(0, service.secondsUntilAllowed("ana", "10.0.0.2"));
        assertEquals(0, service.secondsUntilAllowed("bob", "10.0.0.1"));
    }

    @Test
    void successClearsTheFailures() {
        fail(2, "ana", "10.0.0.1");
        service.recordSuccess("ana", "10.0.0.1");
        fail(2, "ana", "10.0.0.1");

        assertEquals(0, service.secondsUntilAllowed("ana", "10.0.0.1"));
    }

    @Test
    void keepsAtMostTheMaximumNumberOfPairs() {
        for (int i = 0; i < LoginAttemptService.MAX_TRACKED + 500; i++) {
            service.recordFailure("user" + i, "10.0.0.1");
        }
        assertEquals(LoginAttemptService.MAX_TRACKED, service.trackedPairs());

        // Once their window is over, old pairs make room before any recent one is dropped
        clock.advance(Duration.ofMinutes(16));
        service.recordFailure("newcomer", "10.0.0.1");
        assertEquals(1, service.trackedPairs());
    }

    @Test
    void rejectsSettingsThatWouldNeverBlockOrNeverUnblock() {
        assertThrows(IllegalStateException.class,
                () -> new LoginAttemptService(new LoginAttemptProperties(0, Duration.ofMinutes(15))));
        assertThrows(IllegalStateException.class,
                () -> new LoginAttemptService(new LoginAttemptProperties(5, Duration.ZERO)));
    }

    private void fail(int times, String username, String ip) {
        for (int i = 0; i < times; i++) {
            service.recordFailure(username, ip);
        }
    }

    private static final class MutableClock extends Clock {

        private Instant now = Instant.parse("2030-01-01T10:00:00Z");

        void advance(Duration duration) {
            now = now.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}

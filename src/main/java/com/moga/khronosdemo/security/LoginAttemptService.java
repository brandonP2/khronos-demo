package com.moga.khronosdemo.security;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Counts failed sign-ins per username and client IP, in memory. Once a pair reaches maxAttempts
 * failures inside the window it is blocked until that window ends, and a successful sign-in clears
 * it. At most MAX_TRACKED pairs are kept, so a flood of made-up usernames cannot exhaust memory:
 * expired pairs are dropped first, then the least recently seen one.
 * The counts are per instance and are lost on restart.
 */
@Component
@EnableConfigurationProperties(LoginAttemptProperties.class)
public class LoginAttemptService {

    static final int MAX_TRACKED = 10_000;

    private final LoginAttemptProperties properties;
    private final Clock clock;
    // Access order, so the first entry is always the least recently seen pair
    private final LinkedHashMap<String, Attempts> attempts = new LinkedHashMap<>(16, 0.75f, true);

    @Autowired
    public LoginAttemptService(LoginAttemptProperties properties) {
        this(properties, Clock.systemUTC());
    }

    LoginAttemptService(LoginAttemptProperties properties, Clock clock) {
        if (properties.maxAttempts() < 1 || properties.window() == null || properties.window().isNegative()
                || properties.window().isZero()) {
            throw new IllegalStateException("app.login.max-attempts must be at least 1 and app.login.window must be positive");
        }
        this.properties = properties;
        this.clock = clock;
    }

    /**
     * @return how many seconds the pair must wait before trying again, or 0 if it may try now
     */
    public synchronized long secondsUntilAllowed(String username, String clientIp) {
        Attempts entry = attempts.get(key(username, clientIp));
        Instant now = clock.instant();
        if (entry == null || entry.failures() < properties.maxAttempts() || isExpired(entry, now)) {
            return 0;
        }
        long millis = Duration.between(now, entry.windowStart().plus(properties.window())).toMillis();
        return Math.max(1, (millis + 999) / 1000);
    }

    public synchronized void recordFailure(String username, String clientIp) {
        String key = key(username, clientIp);
        Instant now = clock.instant();
        Attempts entry = attempts.get(key);
        if (entry == null || isExpired(entry, now)) {
            if (entry == null) {
                makeRoom(now);
            }
            attempts.put(key, new Attempts(1, now));
        } else {
            attempts.put(key, new Attempts(entry.failures() + 1, entry.windowStart()));
        }
    }

    public synchronized void recordSuccess(String username, String clientIp) {
        attempts.remove(key(username, clientIp));
    }

    synchronized int trackedPairs() {
        return attempts.size();
    }

    private void makeRoom(Instant now) {
        if (attempts.size() < MAX_TRACKED) {
            return;
        }
        attempts.values().removeIf(entry -> isExpired(entry, now));
        if (attempts.size() >= MAX_TRACKED) {
            attempts.remove(attempts.keySet().iterator().next());
        }
    }

    private boolean isExpired(Attempts entry, Instant now) {
        return !now.isBefore(entry.windowStart().plus(properties.window()));
    }

    private static String key(String username, String clientIp) {
        return username + '\n' + clientIp;
    }

    private record Attempts(int failures, Instant windowStart) {
    }
}

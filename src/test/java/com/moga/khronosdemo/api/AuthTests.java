package com.moga.khronosdemo.api;

import com.jayway.jsonpath.JsonPath;
import com.moga.khronosdemo.entities.Worker;
import com.moga.khronosdemo.support.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.util.Map;

import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.startsWith;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class AuthTests extends IntegrationTest {

    @Test
    void signsInAndUsesTheTokenOnTheNextRequest() throws Exception {
        createOwner("ana");

        MvcResult result = login("ana", PASSWORD, "198.51.100.1")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").value(notNullValue()))
                .andExpect(jsonPath("$.tokenType").value("Bearer"))
                .andExpect(jsonPath("$.expiresIn").value(3600))
                .andReturn();
        String token = JsonPath.read(result.getResponse().getContentAsString(), "$.accessToken");

        mockMvc.perform(getJson("/api/users/me").with(bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.username").value("ana"));
    }

    @Test
    void rejectsAWrongPasswordOrUnknownUserWithTheSameError() throws Exception {
        createOwner("ana");

        login("ana", "wrong-password", "198.51.100.2")
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status").value(401))
                .andExpect(jsonPath("$.message").value("Invalid username or password"));
        login("nobody", PASSWORD, "198.51.100.2")
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("Invalid username or password"));
    }

    @Test
    void requestsWithoutAValidTokenAreRejected() throws Exception {
        mockMvc.perform(getJson("/api/users/me"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(getJson("/api/users/me").with(bearer("not-a-token")))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status").value(401));
    }

    @Test
    void aDeactivatedWorkerCannotSignIn() throws Exception {
        deactivatedWorker("lucia");

        login("lucia", PASSWORD, "198.51.100.3")
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("This account is disabled"));
    }

    @Test
    void aWrongPasswordDoesNotRevealThatAnAccountIsDeactivated() throws Exception {
        deactivatedWorker("lucia");
        createOwner("ana");
        String ip = "198.51.100.4";

        Map<String, Object> deactivated = bodyWithoutTimestamp(login("lucia", "wrong-password", ip)
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("Invalid username or password")));
        Map<String, Object> active = bodyWithoutTimestamp(login("ana", "wrong-password", ip)
                .andExpect(status().isUnauthorized()));
        Map<String, Object> unknown = bodyWithoutTimestamp(login("nobody", "wrong-password", ip)
                .andExpect(status().isUnauthorized()));

        assertEquals(active, deactivated);
        assertEquals(unknown, deactivated);
    }

    @Test
    void failedSignInsAgainstADeactivatedAccountCountTowardsTheLimit() throws Exception {
        deactivatedWorker("lucia");
        String ip = "198.51.100.5";

        for (int i = 0; i < 5; i++) {
            login("lucia", "wrong-password", ip).andExpect(status().isUnauthorized());
        }

        login("lucia", PASSWORD, ip).andExpect(status().isTooManyRequests());
    }

    @Test
    void blocksAUsernameAndIpAfterFiveFailures() throws Exception {
        createOwner("target");
        String ip = "203.0.113.10";

        for (int i = 0; i < 5; i++) {
            login("target", "wrong-password", ip).andExpect(status().isUnauthorized());
        }

        MvcResult blocked = login("target", "wrong-password", ip)
                .andExpect(status().isTooManyRequests())
                .andExpect(header().exists(HttpHeaders.RETRY_AFTER))
                .andExpect(jsonPath("$.status").value(429))
                .andExpect(jsonPath("$.error").value("Too Many Requests"))
                .andExpect(jsonPath("$.message").value(startsWith("Too many failed sign-in attempts")))
                .andExpect(jsonPath("$.path").value("/api/auth/login"))
                .andReturn();
        long retryAfter = Long.parseLong(blocked.getResponse().getHeader(HttpHeaders.RETRY_AFTER));
        assertTrue(retryAfter > 0 && retryAfter <= 15 * 60, "Retry-After was " + retryAfter);

        // Even the right password is refused while blocked...
        login("target", PASSWORD, ip).andExpect(status().isTooManyRequests());
        // ...but the same user from another address is not affected
        login("target", PASSWORD, "203.0.113.11").andExpect(status().isOk());
    }

    @Test
    void blocksAnIpThatSpraysMadeUpUsernamesToGetFreshAttempts() throws Exception {
        createOwner("victim");
        String ip = "203.0.113.30";

        for (int i = 0; i < 5; i++) {
            login("victim", "wrong-password", ip).andExpect(status().isUnauthorized());
        }
        login("victim", "wrong-password", ip).andExpect(status().isTooManyRequests());

        // Failing with made-up usernames, to push the blocked pair out of memory, stops at 50 failures
        // for the address...
        for (int i = 0; i < 45; i++) {
            login("made-up-" + i, "wrong-password", ip).andExpect(status().isUnauthorized());
        }
        login("made-up-45", "wrong-password", ip)
                .andExpect(status().isTooManyRequests())
                .andExpect(header().exists(HttpHeaders.RETRY_AFTER));

        // ...so the address stays blocked, even with the right password, while others are not affected
        login("victim", PASSWORD, ip).andExpect(status().isTooManyRequests());
        login("victim", PASSWORD, "203.0.113.31").andExpect(status().isOk());
    }

    @Test
    void aSuccessfulSignInClearsEarlierFailures() throws Exception {
        createOwner("forgetful");
        String ip = "203.0.113.20";

        for (int i = 0; i < 4; i++) {
            login("forgetful", "wrong-password", ip).andExpect(status().isUnauthorized());
        }
        login("forgetful", PASSWORD, ip).andExpect(status().isOk());
        for (int i = 0; i < 4; i++) {
            login("forgetful", "wrong-password", ip).andExpect(status().isUnauthorized());
        }
        login("forgetful", PASSWORD, ip).andExpect(status().isOk());
    }

    private void deactivatedWorker(String username) {
        Worker worker = createWorker(createEstablishment(createOwner(uniqueName("owner"))), username);
        worker.setActive(false);
        workerRepository.save(worker);
    }

    private static Map<String, Object> bodyWithoutTimestamp(ResultActions result) throws Exception {
        Map<String, Object> body = JsonPath.read(result.andReturn().getResponse().getContentAsString(), "$");
        body.remove("timestamp");
        return body;
    }

    private ResultActions login(String username, String password, String ip) throws Exception {
        return mockMvc.perform(postJson("/api/auth/login", """
                { "username": "%s", "password": "%s" }
                """.formatted(username, password)).with(from(ip)));
    }

    private static RequestPostProcessor from(String ip) {
        return request -> {
            request.setRemoteAddr(ip);
            return request;
        };
    }
}

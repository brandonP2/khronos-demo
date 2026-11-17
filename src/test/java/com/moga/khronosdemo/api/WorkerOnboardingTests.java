package com.moga.khronosdemo.api;

import com.moga.khronosdemo.entities.Establishment;
import com.moga.khronosdemo.entities.User;
import com.moga.khronosdemo.entities.enums.Role;
import com.moga.khronosdemo.support.IntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MvcResult;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class WorkerOnboardingTests extends IntegrationTest {

    private User admin;
    private User ownerA;
    private User ownerB;
    private Establishment venueA;
    private Establishment venueB;

    @BeforeEach
    void setUp() {
        admin = createAdmin("admin");
        ownerA = createOwner("owner-a");
        ownerB = createOwner("owner-b");
        venueA = createEstablishment(ownerA);
        venueB = createEstablishment(ownerB);
    }

    // ---- Who may link an existing account ----

    @Test
    void recordsWhoCreatedEachAccount() throws Exception {
        Long workerId = createAccountAs(ownerA, "lucia");
        Long ownerId = longAt(mockMvc.perform(postJson("/api/users/create?role=OWNER", userJson("carmen")).with(as(admin)))
                .andExpect(status().isCreated()).andReturn(), "$.id");

        assertEquals(ownerA.getId(), createdBy(workerId));
        assertEquals(admin.getId(), createdBy(ownerId));
    }

    @Test
    void ownerLinksAnAccountTheyCreated() throws Exception {
        Long accountId = createAccountAs(ownerA, "lucia");

        mockMvc.perform(postJson("/api/workers", linkJson(accountId, venueA.getId())).with(as(ownerA)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.userId").value(accountId))
                .andExpect(jsonPath("$.establishmentId").value(venueA.getId()));
    }

    @Test
    void ownerCannotClaimAnAccountAnotherOwnerCreated() throws Exception {
        Long accountId = createAccountAs(ownerA, "lucia");

        mockMvc.perform(postJson("/api/workers", linkJson(accountId, venueB.getId())).with(as(ownerB)))
                .andExpect(status().isForbidden());

        assertFalse(workerRepository.existsById(accountId));
    }

    @Test
    void ownerCannotClaimAnAccountWithNoKnownCreator() throws Exception {
        // Accounts created before created_by existed have no creator
        User legacy = createUser("legacy", Role.WORKER);
        assertNull(createdBy(legacy.getId()));

        mockMvc.perform(postJson("/api/workers", linkJson(legacy.getId(), venueA.getId())).with(as(ownerA)))
                .andExpect(status().isForbidden());
    }

    @Test
    void platformAdminCanLinkAnyAccount() throws Exception {
        Long accountId = createAccountAs(ownerA, "lucia");

        mockMvc.perform(postJson("/api/workers", linkJson(accountId, venueB.getId())).with(as(admin)))
                .andExpect(status().isCreated());
    }

    @Test
    void deletingTheCreatorKeepsTheAccountsTheyCreated() throws Exception {
        Long accountId = createAccountAs(ownerA, "lucia");

        mockMvc.perform(deleteJson("/api/users/{id}", ownerA.getId()).with(as(admin)))
                .andExpect(status().isNoContent());

        assertEquals(true, userRepository.existsById(accountId));
        assertNull(createdBy(accountId));
    }

    // ---- Helpers ----

    private Long createAccountAs(User creator, String username) throws Exception {
        MvcResult result = mockMvc.perform(postJson("/api/users/create/worker", userJson(username)).with(as(creator)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.role").value("WORKER"))
                .andReturn();
        return longAt(result, "$.id");
    }

    private Long createdBy(Long userId) {
        return jdbcTemplate.queryForObject("select created_by from users where id = ?", Long.class, userId);
    }

    static String userJson(String username) {
        return """
                { "username": "%1$s", "password": "a-good-password", "firstName": "First", "lastName": "Last",
                  "email": "%1$s@example.test" }
                """.formatted(username);
    }

    private static String linkJson(Long userId, Long establishmentId) {
        return """
                { "userId": %d, "establishmentId": %d, "isActive": true }
                """.formatted(userId, establishmentId);
    }
}

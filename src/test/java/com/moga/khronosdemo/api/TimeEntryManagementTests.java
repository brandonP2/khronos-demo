package com.moga.khronosdemo.api;

import com.moga.khronosdemo.entities.Establishment;
import com.moga.khronosdemo.entities.User;
import com.moga.khronosdemo.entities.Worker;
import com.moga.khronosdemo.support.IntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;

import java.time.Instant;

import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Owners and admins entering, correcting and voiding time entries. Every change needs a reason and
 * is kept in the entry's history.
 */
class TimeEntryManagementTests extends IntegrationTest {

    private User owner;
    private Establishment venue;
    private Worker worker;
    private Worker colleague;

    @BeforeEach
    void setUp() {
        owner = createOwner("owner");
        venue = createEstablishment(owner);
        worker = createWorker(venue, "worker");
        colleague = createWorker(venue, "colleague");
        clock.setNow(Instant.parse("2030-05-10T12:00:00Z"));
    }

    @Test
    void anOwnerAddsAPastEntryWithAReason() throws Exception {
        Long id = longAt(mockMvc.perform(postJson("/api/time-entries", """
                        { "workerId": %d, "clockIn": "2030-05-09T16:00:00Z", "clockOut": "2030-05-09T22:00:00Z",
                          "breakMinutes": 15, "reason": "Forgot to clock in" }
                        """.formatted(worker.getUserId())).with(as(owner)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.source").value("MANAGER"))
                .andExpect(jsonPath("$.status").value("CLOSED"))
                .andExpect(jsonPath("$.businessDate").value("2030-05-09"))
                .andExpect(jsonPath("$.workedMinutes").value(345))
                .andExpect(jsonPath("$.isEdited").value(false))
                .andExpect(jsonPath("$.note").value(nullValue()))
                .andReturn(), "$.id");

        mockMvc.perform(getJson("/api/time-entries/{id}/history", id).with(as(owner)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].action").value("CREATED"))
                .andExpect(jsonPath("$[0].reason").value("Forgot to clock in"))
                .andExpect(jsonPath("$[0].changedByUserId").value(owner.getId()))
                .andExpect(jsonPath("$[0].changedByName").value("First owner Last owner"))
                .andExpect(jsonPath("$[0].clockIn").value("2030-05-09T16:00:00Z"))
                .andExpect(jsonPath("$[0].clockOut").value("2030-05-09T22:00:00Z"))
                .andExpect(jsonPath("$[0].breakMinutes").value(15));
    }

    @Test
    void anOwnerClocksAWorkerInWhoThenCannotClockInAgain() throws Exception {
        mockMvc.perform(postJson("/api/time-entries", """
                        { "workerId": %d, "clockIn": "2030-05-10T12:00:00Z", "reason": "Phone battery is dead" }
                        """.formatted(worker.getUserId())).with(as(owner)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("OPEN"))
                .andExpect(jsonPath("$.workedMinutes").value(nullValue()))
                .andExpect(jsonPath("$.breakMinutes").value(0));

        mockMvc.perform(getJson("/api/time-entries/me/open").with(as(worker)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.source").value("MANAGER"));
        mockMvc.perform(post("/api/time-entries/clock-in").with(as(worker)))
                .andExpect(status().isConflict());
    }

    @Test
    void rejectsEntriesThatBreakTheTimeRules() throws Exception {
        mockMvc.perform(postJson("/api/time-entries", """
                        { "workerId": %d, "clockIn": "2030-05-09T16:00:00Z", "clockOut": "2030-05-09T22:00:00Z",
                          "reason": "Paper sheet" }
                        """.formatted(worker.getUserId())).with(as(owner)))
                .andExpect(status().isCreated());

        expectConflict("2030-05-09T21:00:00Z", "\"2030-05-09T23:00:00Z\"", 0,
                "The time entry overlaps another time entry of this worker");
        expectConflict("2030-05-09T10:00:00Z", null, 0,
                "The time entry overlaps another time entry of this worker");
        expectConflict("2030-05-08T10:00:00Z", "\"2030-05-08T10:00:00Z\"", 0, "Clock-out must be after clock-in");
        expectConflict("2030-05-07T10:00:00Z", "\"2030-05-08T10:00:01Z\"", 0, "A time entry cannot last more than 24 hours");
        expectConflict("2030-05-10T13:00:00Z", null, 0, "Clock-in cannot be in the future");
        expectConflict("2030-05-10T08:00:00Z", "\"2030-05-10T14:00:00Z\"", 0, "Clock-out cannot be in the future");
        expectConflict("2030-05-08T10:00:00Z", "\"2030-05-08T10:30:00Z\"", 31,
                "The break cannot be longer than the time between clock-in and clock-out");

        assertEquals(1, timeEntryRepository.count());
    }

    @Test
    void aReasonIsRequired() throws Exception {
        mockMvc.perform(postJson("/api/time-entries", """
                        { "workerId": %d, "clockIn": "2030-05-09T16:00:00Z", "clockOut": "2030-05-09T22:00:00Z" }
                        """.formatted(worker.getUserId())).with(as(owner)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.reason").value("must not be blank"));
        mockMvc.perform(postJson("/api/time-entries", """
                        { "workerId": %d, "clockIn": "2030-05-09T16:00:00Z", "reason": "ok" }
                        """.formatted(worker.getUserId())).with(as(owner)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.reason").value("size must be between 3 and 255"));

        Long id = createdEntry();
        mockMvc.perform(putJson("/api/time-entries/{id}", """
                        { "clockIn": "2030-05-09T16:00:00Z", "clockOut": "2030-05-09T21:00:00Z", "breakMinutes": 0 }
                        """, id).with(as(owner)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.reason").value("must not be blank"));
        mockMvc.perform(putJson("/api/time-entries/{id}/void", "{}", id).with(as(owner)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.reason").value("must not be blank"));
    }

    @Test
    void correctionsAndVoidsAreKeptInOrderInTheHistory() throws Exception {
        clock.setNow(Instant.parse("2030-05-09T16:00:00Z"));
        Long id = longAt(mockMvc.perform(post("/api/time-entries/clock-in").with(as(worker)))
                .andExpect(status().isCreated())
                .andReturn(), "$.id");
        clock.setNow(Instant.parse("2030-05-09T22:00:00Z"));
        mockMvc.perform(post("/api/time-entries/clock-out").with(as(worker))).andExpect(status().isOk());

        clock.setNow(Instant.parse("2030-05-10T09:00:00Z"));
        mockMvc.perform(putJson("/api/time-entries/{id}", """
                        { "clockIn": "2030-05-09T15:50:00Z", "clockOut": "2030-05-09T22:00:00Z", "breakMinutes": 20,
                          "reason": "Opened the bar before clocking in" }
                        """, id).with(as(owner)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.isEdited").value(true))
                .andExpect(jsonPath("$.source").value("WORKER"))
                .andExpect(jsonPath("$.clockIn").value("2030-05-09T15:50:00Z"))
                .andExpect(jsonPath("$.workedMinutes").value(350));

        clock.setNow(Instant.parse("2030-05-10T09:05:00Z"));
        mockMvc.perform(putJson("/api/time-entries/{id}/void", """
                        { "reason": "Entered twice" }
                        """, id).with(as(owner)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("VOIDED"))
                .andExpect(jsonPath("$.isEdited").value(true));

        mockMvc.perform(putJson("/api/time-entries/{id}", """
                        { "clockIn": "2030-05-09T16:00:00Z", "clockOut": "2030-05-09T22:00:00Z", "breakMinutes": 0,
                          "reason": "Undo" }
                        """, id).with(as(owner)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("A voided time entry cannot be corrected"));
        mockMvc.perform(putJson("/api/time-entries/{id}/void", """
                        { "reason": "Again" }
                        """, id).with(as(owner)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("The time entry is already voided"));

        mockMvc.perform(getJson("/api/time-entries/{id}/history", id).with(as(worker)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].action", contains("CREATED", "CLOCKED_OUT", "CORRECTED", "VOIDED")))
                .andExpect(jsonPath("$[*].changedByName", contains("First worker Last worker",
                        "First worker Last worker", "First owner Last owner", "First owner Last owner")))
                .andExpect(jsonPath("$[*].reason", contains(null, null, "Opened the bar before clocking in", "Entered twice")))
                .andExpect(jsonPath("$[*].changedAt", contains("2030-05-09T16:00:00Z", "2030-05-09T22:00:00Z",
                        "2030-05-10T09:00:00Z", "2030-05-10T09:05:00Z")))
                .andExpect(jsonPath("$[2].clockIn").value("2030-05-09T15:50:00Z"))
                .andExpect(jsonPath("$[2].breakMinutes").value(20))
                .andExpect(jsonPath("$[3].clockIn").value("2030-05-09T15:50:00Z"));
    }

    @Test
    void voidedEntriesAreListedOnlyWhenAskedFor() throws Exception {
        Long voided = createdEntry();
        mockMvc.perform(postJson("/api/time-entries", """
                        { "workerId": %d, "clockIn": "2030-05-08T16:00:00Z", "clockOut": "2030-05-08T20:00:00Z",
                          "reason": "Paper sheet" }
                        """.formatted(worker.getUserId())).with(as(owner)))
                .andExpect(status().isCreated());
        mockMvc.perform(putJson("/api/time-entries/{id}/void", """
                        { "reason": "Wrong day" }
                        """, voided).with(as(owner)))
                .andExpect(status().isOk());

        mockMvc.perform(getJson("/api/time-entries/worker/{id}", worker.getUserId()).with(as(owner)))
                .andExpect(jsonPath("$[*].status", contains("CLOSED")));
        mockMvc.perform(getJson("/api/time-entries/worker/{id}?includeVoided=true", worker.getUserId()).with(as(worker)))
                .andExpect(jsonPath("$[*].status", contains("CLOSED", "VOIDED")));
        mockMvc.perform(getJson("/api/time-entries/establishment/{id}", venue.getId()).with(as(owner)))
                .andExpect(jsonPath("$", hasSize(1)));
        mockMvc.perform(getJson("/api/time-entries/establishment/{id}?includeVoided=true&from=2030-05-09&to=2030-05-09",
                        venue.getId()).with(as(owner)))
                .andExpect(jsonPath("$[*].status", contains("VOIDED")));
        mockMvc.perform(getJson("/api/time-entries/establishment/{id}?from=2030-05-10&to=2030-05-09", venue.getId())
                        .with(as(owner)))
                .andExpect(status().isBadRequest());

        // Voiding frees the slot, so the day can be entered again
        mockMvc.perform(postJson("/api/time-entries", """
                        { "workerId": %d, "clockIn": "2030-05-09T16:00:00Z", "clockOut": "2030-05-09T22:00:00Z",
                          "reason": "Entered again" }
                        """.formatted(worker.getUserId())).with(as(owner)))
                .andExpect(status().isCreated());
    }

    @Test
    void aWorkerCannotEnterCorrectOrVoidEntriesNorSeeAColleagues() throws Exception {
        Long id = createdEntry();
        Long colleaguesEntry = longAt(mockMvc.perform(postJson("/api/time-entries", """
                        { "workerId": %d, "clockIn": "2030-05-09T16:00:00Z", "reason": "Paper sheet" }
                        """.formatted(colleague.getUserId())).with(as(owner)))
                .andExpect(status().isCreated())
                .andReturn(), "$.id");

        mockMvc.perform(postJson("/api/time-entries", """
                        { "workerId": %d, "clockIn": "2030-05-08T16:00:00Z", "reason": "Self service" }
                        """.formatted(worker.getUserId())).with(as(worker)))
                .andExpect(status().isForbidden());
        mockMvc.perform(putJson("/api/time-entries/{id}", """
                        { "clockIn": "2030-05-09T15:00:00Z", "clockOut": "2030-05-09T23:00:00Z", "breakMinutes": 0,
                          "reason": "More hours" }
                        """, id).with(as(worker)))
                .andExpect(status().isForbidden());
        mockMvc.perform(putJson("/api/time-entries/{id}/void", """
                        { "reason": "Oops" }
                        """, id).with(as(worker)))
                .andExpect(status().isForbidden());

        mockMvc.perform(getJson("/api/time-entries/{id}/history", colleaguesEntry).with(as(worker)))
                .andExpect(status().isForbidden());
        mockMvc.perform(getJson("/api/time-entries/worker/{id}", colleague.getUserId()).with(as(worker)))
                .andExpect(status().isForbidden());
        mockMvc.perform(getJson("/api/time-entries/establishment/{id}", venue.getId()).with(as(worker)))
                .andExpect(status().isForbidden());
        mockMvc.perform(getJson("/api/time-entries/establishment/{id}/open", venue.getId()).with(as(worker)))
                .andExpect(status().isForbidden());

        mockMvc.perform(getJson("/api/time-entries/{id}/history", id).with(as(worker)))
                .andExpect(status().isOk());
        assertEquals(2, timeEntryChangeRepository.count());
    }

    @Test
    void anAdminCanManageAnyEntry() throws Exception {
        User admin = createAdmin("admin");
        Long id = createdEntry();

        mockMvc.perform(putJson("/api/time-entries/{id}", """
                        { "clockIn": "2030-05-09T16:00:00Z", "clockOut": "2030-05-09T20:00:00Z", "breakMinutes": 0,
                          "reason": "Support request" }
                        """, id).with(as(admin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.workedMinutes").value(240));
        mockMvc.perform(putJson("/api/time-entries/{id}/void", """
                        { "reason": "Support request" }
                        """, 999_999).with(as(admin)))
                .andExpect(status().isNotFound());
    }

    @Test
    void theDatabaseRefusesToRewriteTheHistory() throws Exception {
        createdEntry();

        assertThrows(DataAccessException.class,
                () -> jdbcTemplate.update("update time_entry_changes set reason = 'Rewritten'"));
        assertEquals("Paper sheet", timeEntryChangeRepository.findAll().get(0).getReason());
    }

    private Long createdEntry() throws Exception {
        return longAt(mockMvc.perform(postJson("/api/time-entries", """
                        { "workerId": %d, "clockIn": "2030-05-09T16:00:00Z", "clockOut": "2030-05-09T22:00:00Z",
                          "reason": "Paper sheet" }
                        """.formatted(worker.getUserId())).with(as(owner)))
                .andExpect(status().isCreated())
                .andReturn(), "$.id");
    }

    private void expectConflict(String clockIn, String clockOut, int breakMinutes, String message) throws Exception {
        mockMvc.perform(postJson("/api/time-entries", """
                        { "workerId": %d, "clockIn": "%s", "clockOut": %s, "breakMinutes": %d, "reason": "Paper sheet" }
                        """.formatted(worker.getUserId(), clockIn, clockOut, breakMinutes)).with(as(owner)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value(message));
    }
}

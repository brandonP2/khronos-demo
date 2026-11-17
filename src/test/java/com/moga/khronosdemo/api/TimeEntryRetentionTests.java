package com.moga.khronosdemo.api;

import com.moga.khronosdemo.entities.Establishment;
import com.moga.khronosdemo.entities.TimeEntryChange;
import com.moga.khronosdemo.entities.User;
import com.moga.khronosdemo.entities.Worker;
import com.moga.khronosdemo.entities.WorkerRate;
import com.moga.khronosdemo.entities.Zone;
import com.moga.khronosdemo.support.IntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * What happens to time entries when the rows around them are deleted. Deleting a worker or an
 * establishment takes the entries with it (a known limit, see the README); deleting the author of a
 * change keeps the change and its author's name.
 */
class TimeEntryRetentionTests extends IntegrationTest {

    private static final LocalDate DAY = LocalDate.of(2030, 5, 10);

    private User admin;
    private User owner;
    private Establishment venue;
    private Worker worker;
    private Long entryId;

    @BeforeEach
    void setUp() throws Exception {
        admin = createAdmin("admin");
        owner = createOwner("owner");
        venue = createEstablishment(owner);
        Zone bar = createZone(venue, "Bar");
        worker = createWorker(venue, "worker");
        createShift(worker, bar, DAY, "18:00", "02:00");
        workerRateRepository.save(new WorkerRate(worker, new BigDecimal("10.00"), DAY, Instant.now()));

        clock.setNow(Instant.parse("2030-05-10T16:00:00Z"));
        entryId = longAt(mockMvc.perform(post("/api/time-entries/clock-in").with(as(worker)))
                .andExpect(status().isCreated())
                .andReturn(), "$.id");
        clock.setNow(Instant.parse("2030-05-11T09:00:00Z"));
        mockMvc.perform(putJson("/api/time-entries/{id}", """
                        { "clockIn": "2030-05-10T16:00:00Z", "clockOut": "2030-05-11T00:00:00Z", "breakMinutes": 0,
                          "reason": "Forgot to clock out" }
                        """, entryId).with(as(owner)))
                .andExpect(status().isOk());
    }

    @Test
    void deletingAWorkerDeletesTheirEntriesHistoryAndRates() throws Exception {
        mockMvc.perform(deleteJson("/api/workers/{id}", worker.getUserId()).with(as(admin)))
                .andExpect(status().isNoContent());

        assertEquals(0, timeEntryRepository.count());
        assertEquals(0, timeEntryChangeRepository.count());
        assertEquals(0, workerRateRepository.count());
    }

    @Test
    void deletingAnEstablishmentDeletesItsEntries() throws Exception {
        mockMvc.perform(deleteJson("/api/establishments/{id}", venue.getId()).with(as(admin)))
                .andExpect(status().isNoContent());

        assertEquals(0, timeEntryRepository.count());
        assertEquals(0, timeEntryChangeRepository.count());
    }

    @Test
    void deletingTheAuthorOfAChangeKeepsTheChangeAndTheirName() throws Exception {
        // The venue moves to a new owner, then the old owner's account is deleted
        User newOwner = createOwner("new-owner");
        mockMvc.perform(putJson("/api/establishments/{id}", """
                        { "establishmentName": "Venue", "establishmentAddress": "1 New Street", "ownerId": %d }
                        """.formatted(newOwner.getId()), venue.getId()).with(as(admin)))
                .andExpect(status().isOk());
        mockMvc.perform(deleteJson("/api/users/{id}", owner.getId()).with(as(admin)))
                .andExpect(status().isNoContent());

        TimeEntryChange correction = timeEntryChangeRepository.findByTimeEntry_IdOrderByChangedAtAscIdAsc(entryId).get(1);
        assertEquals("First owner Last owner", correction.getChangedByName());
        mockMvc.perform(getJson("/api/time-entries/{id}/history", entryId).with(as(newOwner)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].action", contains("CREATED", "CORRECTED")))
                .andExpect(jsonPath("$[1].changedByUserId").value(nullValue()))
                .andExpect(jsonPath("$[1].changedByName").value("First owner Last owner"));
    }
}

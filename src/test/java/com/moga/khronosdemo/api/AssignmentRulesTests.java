package com.moga.khronosdemo.api;

import com.moga.khronosdemo.entities.Establishment;
import com.moga.khronosdemo.entities.Event;
import com.moga.khronosdemo.entities.User;
import com.moga.khronosdemo.entities.Worker;
import com.moga.khronosdemo.entities.WorkerShift;
import com.moga.khronosdemo.entities.Zone;
import com.moga.khronosdemo.support.IntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.startsWith;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class AssignmentRulesTests extends IntegrationTest {

    private static final LocalDate DAY = LocalDate.of(2030, 5, 10);

    private User owner;
    private Zone hall;
    private Zone terrace;
    private Worker worker;
    private WorkerShift eveningShift;

    @BeforeEach
    void setUp() {
        owner = createOwner("owner");
        Establishment venue = createEstablishment(owner);
        hall = createZone(venue, "Hall");
        terrace = createZone(venue, "Terrace");
        worker = createWorker(venue, "worker");
        eveningShift = createShift(worker, hall, DAY, "18:00", "23:00");
    }

    @Test
    void assignsAWorkerWhoseShiftCoversTheEvent() throws Exception {
        Event dinner = createEvent(hall, DAY.atTime(19, 0));
        Event cocktails = createEvent(hall, DAY.atTime(22, 30));

        mockMvc.perform(postJson("/api/worker-events", """
                        [ { "workerId": %1$d, "eventId": %2$d }, { "workerId": %1$d, "eventId": %3$d } ]
                        """.formatted(worker.getUserId(), dinner.getId(), cocktails.getId())).with(as(owner)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$[0].workerId").value(worker.getUserId()))
                .andExpect(jsonPath("$[0].eventId").value(dinner.getId()));
    }

    @Test
    void rejectsAnEventOutsideTheWorkersShift() throws Exception {
        Event lateEvent = createEvent(hall, DAY.atTime(23, 0));
        Event otherZone = createEvent(terrace, DAY.atTime(19, 0));

        mockMvc.perform(postJson("/api/worker-events", """
                        [ { "workerId": %d, "eventId": %d } ]
                        """.formatted(worker.getUserId(), lateEvent.getId())).with(as(owner)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value(startsWith("Assignment [0]: The worker has no shift in zone 'Hall'")));

        mockMvc.perform(postJson("/api/worker-events", """
                        [ { "workerId": %d, "eventId": %d } ]
                        """.formatted(worker.getUserId(), otherZone.getId())).with(as(owner)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value(startsWith("Assignment [0]: The worker has no shift in zone 'Terrace'")));

        assertEquals(0, workerEventRepository.count());
    }

    @Test
    void savesNoAssignmentWhenOneOfTheBatchIsInvalid() throws Exception {
        Event dinner = createEvent(hall, DAY.atTime(19, 0));

        mockMvc.perform(postJson("/api/worker-events", """
                        [ { "workerId": %1$d, "eventId": %2$d }, { "workerId": %1$d, "eventId": %2$d } ]
                        """.formatted(worker.getUserId(), dinner.getId())).with(as(owner)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("Assignment [1]: Worker is already assigned to this event"));

        assertEquals(0, workerEventRepository.count());
    }

    @Test
    void deletingTheCoveringShiftUnassignsTheWorker() throws Exception {
        Event dinner = createEvent(hall, DAY.atTime(19, 0));
        Long assignmentId = assign(worker, dinner).getId();

        mockMvc.perform(deleteJson("/api/worker-shifts/{id}", eveningShift.getId()).with(as(owner)))
                .andExpect(status().isNoContent());

        assertEquals(false, workerEventRepository.existsById(assignmentId));
    }

    @Test
    void movingTheEventOutsideTheShiftUnassignsTheWorker() throws Exception {
        Event dinner = createEvent(hall, DAY.atTime(19, 0));
        Long assignmentId = assign(worker, dinner).getId();

        mockMvc.perform(putJson("/api/events/{id}", """
                        { "title": "Dinner", "dateTime": "2030-05-10T19:00:00", "zoneId": %d, "peopleNumber": 40 }
                        """.formatted(terrace.getId()), dinner.getId()).with(as(owner)))
                .andExpect(status().isOk());

        assertEquals(false, workerEventRepository.existsById(assignmentId));
    }
}

package com.moga.khronosdemo.repositories;

import com.moga.khronosdemo.entities.WorkerEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;

public interface WorkerEventRepository extends JpaRepository<WorkerEvent, Long> {

    List<WorkerEvent> findByWorker_UserId(Long workerId);

    List<WorkerEvent> findByEvent_Id(Long eventId);

    boolean existsByWorker_UserIdAndEvent_Id(Long workerId, Long eventId);

    // Assignments to the establishment's events, filtered on the event's date-time and, unless
    // workerId is null, on the worker. A null bound leaves that side of the range open; the casts
    // give PostgreSQL its type.
    @Query("""
            select we from WorkerEvent we join we.event e
            where e.zone.establishment.id = :establishmentId
              and (:workerId is null or we.worker.userId = :workerId)
              and (cast(:from as LocalDateTime) is null or e.dateTime >= :from)
              and (cast(:until as LocalDateTime) is null or e.dateTime < :until)
            order by e.dateTime, we.id
            """)
    List<WorkerEvent> findByEstablishment(@Param("establishmentId") Long establishmentId,
                                          @Param("workerId") Long workerId,
                                          @Param("from") LocalDateTime from,
                                          @Param("until") LocalDateTime until);
}

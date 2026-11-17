package com.moga.khronosdemo.repositories;

import com.moga.khronosdemo.entities.WorkerShift;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

public interface WorkerShiftRepository extends JpaRepository<WorkerShift, Long> {

    // The listings only map the worker and zone ids, which need no extra query.
    // from and to are inclusive, and a null one leaves that side of the range open. The casts
    // tell PostgreSQL the type of a null date parameter, which it cannot work out on its own.
    @Query("""
            select s from WorkerShift s
            where s.worker.establishment.owner.id = :ownerId
              and (cast(:from as LocalDate) is null or s.date >= :from)
              and (cast(:to as LocalDate) is null or s.date <= :to)
            order by s.date, s.startTime, s.id
            """)
    List<WorkerShift> findByOwner(@Param("ownerId") Long ownerId,
                                  @Param("from") LocalDate from,
                                  @Param("to") LocalDate to);

    @Query("""
            select s from WorkerShift s
            where s.worker.userId = :workerId
              and (cast(:from as LocalDate) is null or s.date >= :from)
              and (cast(:to as LocalDate) is null or s.date <= :to)
            order by s.date, s.startTime, s.id
            """)
    List<WorkerShift> findByWorker(@Param("workerId") Long workerId,
                                   @Param("from") LocalDate from,
                                   @Param("to") LocalDate to);

    @Query("""
            select s from WorkerShift s
            where s.zone.id = :zoneId
              and (cast(:from as LocalDate) is null or s.date >= :from)
              and (cast(:to as LocalDate) is null or s.date <= :to)
            order by s.date, s.startTime, s.id
            """)
    List<WorkerShift> findByZone(@Param("zoneId") Long zoneId,
                                 @Param("from") LocalDate from,
                                 @Param("to") LocalDate to);

    @Query("""
            select count(s) > 0 from WorkerShift s
            where s.worker.userId = :workerId
              and s.zone.id = :zoneId
              and s.date = :date
              and s.startTime <= :eventTime
              and s.endTime > :eventTime
            """)
    boolean existsShiftCoveringEvent(@Param("workerId") Long workerId,
                                     @Param("zoneId") Long zoneId,
                                     @Param("date") LocalDate date,
                                     @Param("eventTime") LocalTime eventTime);

    @Query("""
            select count(s) > 0 from WorkerShift s
            where s.worker.userId = :workerId
              and s.date = :date
              and s.startTime < :endTime
              and s.endTime > :startTime
              and (:excludeId is null or s.id <> :excludeId)
            """)
    boolean existsOverlappingShift(@Param("workerId") Long workerId,
                                   @Param("date") LocalDate date,
                                   @Param("startTime") LocalTime startTime,
                                   @Param("endTime") LocalTime endTime,
                                   @Param("excludeId") Long excludeId);
}

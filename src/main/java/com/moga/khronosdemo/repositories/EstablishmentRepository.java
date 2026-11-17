package com.moga.khronosdemo.repositories;

import com.moga.khronosdemo.entities.Establishment;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface EstablishmentRepository extends JpaRepository<Establishment, Long> {

    // Locks the establishment's row until the transaction ends. Every change to its cash closings and
    // expenses takes this lock first, so closing a day and adding a cash expense to it take turns. On
    // PostgreSQL it is FOR NO KEY UPDATE, which doesn't stop other requests from inserting rows that
    // reference the establishment (shifts, time entries...). Nothing else locks establishments.
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select e from Establishment e where e.id = :id")
    Optional<Establishment> lockById(@Param("id") Long id);

    List<Establishment> findByOwner_Id(Long ownerId);

    boolean existsByOwner_Id(Long ownerId);

    boolean existsByEstablishmentAddress(String establishmentAddress);

    boolean existsByEstablishmentAddressAndIdNot(String establishmentAddress, Long id);
}

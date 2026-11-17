package com.moga.khronosdemo.services;

import com.moga.khronosdemo.dtos.request.WorkerEventRequestDTO;
import com.moga.khronosdemo.dtos.response.WorkerEventResponseDTO;

import java.time.LocalDate;
import java.util.List;

public interface WorkerEventService {

    List<WorkerEventResponseDTO> create(List<WorkerEventRequestDTO> requests);

    WorkerEventResponseDTO findById(Long id);

    List<WorkerEventResponseDTO> findByWorker(Long workerId);

    List<WorkerEventResponseDTO> findByEvent(Long eventId);

    List<WorkerEventResponseDTO> findByEstablishment(Long establishmentId, LocalDate from, LocalDate to);

    void delete(Long id);
}

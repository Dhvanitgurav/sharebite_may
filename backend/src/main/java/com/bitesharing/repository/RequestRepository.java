package com.bitesharing.repository;

import com.bitesharing.model.Request;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import jakarta.persistence.LockModeType;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface RequestRepository extends JpaRepository<Request, Long> {
    List<Request> findByDonationId(Long donationId);
    boolean existsByDonationIdAndStatusIn(Long donationId, List<Request.RequestStatus> statuses);
    List<Request> findByRequesterId(Long requesterId);
    List<Request> findByAssignedVolunteerId(Long volunteerId);
    List<Request> findByDonationDonorId(Long donorId);
    List<Request> findByStatus(Request.RequestStatus status);
    long countByStatus(Request.RequestStatus status);
    long countByRequesterPhoneAndCreatedAtAfter(String requesterPhone, LocalDateTime createdAt);
    long countByCreatedAtAfter(LocalDateTime createdAt);
    long countByStatusIn(List<Request.RequestStatus> statuses);
    List<Request> findByRequesterType(Request.RequesterType requesterType);
    List<Request> findByRequesterTypeAndStatus(Request.RequesterType requesterType, Request.RequestStatus status);
    long countByRequesterIdAndStatus(Long requesterId, Request.RequestStatus status);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from Request r where r.id = :id")
    Optional<Request> findByIdForUpdate(@Param("id") Long id);
}


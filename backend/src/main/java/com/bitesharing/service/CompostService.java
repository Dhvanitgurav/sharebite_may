package com.bitesharing.service;

import com.bitesharing.model.Request;
import com.bitesharing.repository.RequestRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Comparator;
import java.util.List;

@Service
@RequiredArgsConstructor
public class CompostService {

    private final RequestRepository requestRepository;
    private final RequestService requestService;

    public List<Request> getCompostRequestsForUser(Long requesterId) {
        return requestRepository.findByRequesterId(requesterId).stream()
                .filter(r -> r.getRequesterType() == Request.RequesterType.COMPOST_AGENCY)
                .sorted(Comparator.comparing(Request::getCreatedAt, Comparator.nullsLast(Comparator.naturalOrder())).reversed())
                .toList();
    }

    @Transactional
    public Request updateCompostStatus(Long id, String status, String proofUrl) {
        Request.RequestStatus next = mapStatus(status);
        if (next == Request.RequestStatus.COMPOSTED && (proofUrl == null || proofUrl.isBlank())) {
            throw new RuntimeException("Compost proof image is required");
        }
        return requestService.updateRequestStatus(
                id,
                next,
                null,
                null,
                proofUrl,
                null
        );
    }

    private Request.RequestStatus mapStatus(String status) {
        return switch (status.toUpperCase()) {
            case "PICKED_UP" -> Request.RequestStatus.COMPOST_PICKED_UP;
            case "COMPOSTING" -> Request.RequestStatus.COMPOSTING;
            case "COMPOSTED", "COMPLETED" -> Request.RequestStatus.COMPOSTED;
            default -> throw new RuntimeException("Unsupported compost status");
        };
    }
}

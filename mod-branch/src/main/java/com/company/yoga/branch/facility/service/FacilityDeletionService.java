package com.company.yoga.branch.facility.service;

import com.company.yoga.branch.facility.repository.BranchRepository;
import com.company.yoga.branch.facility.repository.RoomRepository;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Isolates constraint failures so the caller can still deactivate the facility. */
@Service
@RequiredArgsConstructor
public class FacilityDeletionService {
    private final BranchRepository branchRepository;
    private final RoomRepository roomRepository;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void deleteBranch(UUID id) {
        branchRepository.deleteById(id);
        branchRepository.flush();
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void deleteRoom(UUID id) {
        roomRepository.deleteById(id);
        roomRepository.flush();
    }
}

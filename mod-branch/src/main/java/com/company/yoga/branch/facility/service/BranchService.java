package com.company.yoga.branch.facility.service;

import com.company.yoga.branch.BranchResultCodes;
import com.company.yoga.branch.facility.dto.BranchDto.BranchResponse;
import com.company.yoga.branch.facility.dto.BranchDto.CreateBranchRequest;
import com.company.yoga.branch.facility.dto.BranchDto.UpdateBranchRequest;
import com.company.yoga.branch.facility.dto.RoomDto.CreateRoomRequest;
import com.company.yoga.branch.facility.dto.RoomDto.RoomResponse;
import com.company.yoga.branch.facility.dto.RoomDto.UpdateRoomRequest;
import com.company.yoga.branch.facility.entity.BranchEntity;
import com.company.yoga.branch.facility.entity.RoomEntity;
import com.company.yoga.branch.facility.repository.BranchRepository;
import com.company.yoga.branch.facility.repository.RoomRepository;
import com.company.yoga.common.exception.BusinessException;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class BranchService {

    private final BranchRepository branchRepository;
    private final RoomRepository roomRepository;

    @Transactional(readOnly = true)
    public List<RoomResponse> getRoomsByBranch(UUID branchId) {
        return getRoomsByBranch(branchId, false);
    }

    @Transactional(readOnly = true)
    public List<RoomResponse> getRoomsByBranch(UUID branchId, boolean all) {
        if (!branchRepository.existsById(branchId)) {
            throw new BusinessException(BranchResultCodes.BRANCH_NOT_FOUND);
        }
        return roomRepository.findByBranchId(branchId).stream()
                .filter(r -> all || Boolean.TRUE.equals(r.getIsActive()))
                .map(this::toRoomResponse)
                .toList();
    }

    @Transactional
    @PreAuthorize("hasAuthority('SUPER_ADMIN')")
    public RoomResponse createRoom(UUID branchId, CreateRoomRequest req) {
        if (!branchRepository.existsById(branchId)) {
            throw new BusinessException(BranchResultCodes.BRANCH_NOT_FOUND);
        }
        String trimmedName = req.name().trim();
        if (roomRepository.existsByBranchIdAndName(branchId, trimmedName)) {
            throw new BusinessException(BranchResultCodes.ROOM_NAME_EXISTS);
        }
        if (req.maxCapacity() <= 0) {
            throw new BusinessException(BranchResultCodes.ROOM_CAPACITY_EXCEEDED);
        }

        RoomEntity entity = new RoomEntity();
        entity.setBranchId(branchId);
        entity.setName(trimmedName);
        entity.setFloor(req.floor() != null ? req.floor().trim() : null);
        entity.setMaxCapacity(req.maxCapacity());
        entity.setIsActive(req.isActive() != null ? req.isActive() : true);

        RoomEntity saved = roomRepository.save(entity);
        return toRoomResponse(saved);
    }

    @Transactional
    @PreAuthorize("hasAuthority('SUPER_ADMIN')")
    public RoomResponse updateRoom(UUID roomId, UpdateRoomRequest req) {
        RoomEntity entity = roomRepository.findById(roomId)
                .orElseThrow(() -> new BusinessException(BranchResultCodes.ROOM_NOT_FOUND));

        String trimmedName = req.name().trim();
        if (roomRepository.existsByBranchIdAndNameAndIdNot(entity.getBranchId(), trimmedName, roomId)) {
            throw new BusinessException(BranchResultCodes.ROOM_NAME_EXISTS);
        }
        if (req.maxCapacity() <= 0) {
            throw new BusinessException(BranchResultCodes.ROOM_CAPACITY_EXCEEDED);
        }

        entity.setName(trimmedName);
        entity.setFloor(req.floor() != null ? req.floor().trim() : null);
        entity.setMaxCapacity(req.maxCapacity());
        if (req.isActive() != null) {
            entity.setIsActive(req.isActive());
        }

        RoomEntity saved = roomRepository.save(entity);
        return toRoomResponse(saved);
    }

    @Transactional
    @PreAuthorize("hasAuthority('SUPER_ADMIN')")
    public void deleteRoom(UUID roomId) {
        RoomEntity entity = roomRepository.findById(roomId)
                .orElseThrow(() -> new BusinessException(BranchResultCodes.ROOM_NOT_FOUND));

        try {
            roomRepository.delete(entity);
            roomRepository.flush();
        } catch (Exception ex) {
            entity.setIsActive(false);
            roomRepository.save(entity);
        }
    }

    @Transactional(readOnly = true)
    public List<BranchResponse> getAllBranches() {
        return branchRepository.findAll().stream()
                .map(this::toResponse)
                .toList();
    }

    @Transactional(readOnly = true)
    public BranchResponse getBranchById(UUID id) {
        return branchRepository.findById(id)
                .map(this::toResponse)
                .orElseThrow(() -> new BusinessException(BranchResultCodes.BRANCH_NOT_FOUND));
    }

    @Transactional
    @PreAuthorize("hasAuthority('SUPER_ADMIN')")
    public BranchResponse createBranch(CreateBranchRequest request) {
        String trimmedCode = request.code().trim().toUpperCase();
        if (branchRepository.existsByCode(trimmedCode)) {
            throw new BusinessException(BranchResultCodes.BRANCH_CODE_EXISTS);
        }

        BranchEntity entity = new BranchEntity();
        entity.setCode(trimmedCode);
        entity.setName(request.name().trim());
        entity.setAddress(request.address().trim());
        entity.setPhone(request.phone().trim());
        entity.setEmail(request.email() != null ? request.email().trim() : null);
        entity.setBankName(request.bankName() != null ? request.bankName().trim() : null);
        entity.setBankAccountNumber(request.bankAccountNumber() != null ? request.bankAccountNumber().trim() : null);
        entity.setBankAccountHolder(request.bankAccountHolder() != null ? request.bankAccountHolder().trim() : null);
        entity.setIsActive(true);

        BranchEntity saved = branchRepository.save(entity);
        return toResponse(saved);
    }

    @Transactional
    @PreAuthorize("hasAuthority('SUPER_ADMIN')")
    public BranchResponse updateBranch(UUID id, UpdateBranchRequest request) {
        BranchEntity entity = branchRepository.findById(id)
                .orElseThrow(() -> new BusinessException(BranchResultCodes.BRANCH_NOT_FOUND));

        if (request.code() != null && !request.code().isBlank()) {
            String trimmedCode = request.code().trim().toUpperCase();
            if (branchRepository.existsByCodeAndIdNot(trimmedCode, id)) {
                throw new BusinessException(BranchResultCodes.BRANCH_CODE_EXISTS);
            }
            entity.setCode(trimmedCode);
        }

        entity.setName(request.name().trim());
        entity.setAddress(request.address().trim());
        entity.setPhone(request.phone().trim());
        entity.setEmail(request.email() != null ? request.email().trim() : null);
        entity.setBankName(request.bankName() != null ? request.bankName().trim() : null);
        entity.setBankAccountNumber(request.bankAccountNumber() != null ? request.bankAccountNumber().trim() : null);
        entity.setBankAccountHolder(request.bankAccountHolder() != null ? request.bankAccountHolder().trim() : null);
        if (request.isActive() != null) {
            entity.setIsActive(request.isActive());
        }

        BranchEntity saved = branchRepository.save(entity);
        return toResponse(saved);
    }

    @Transactional
    @PreAuthorize("hasAuthority('SUPER_ADMIN')")
    public void deleteBranch(UUID id) {
        BranchEntity entity = branchRepository.findById(id)
                .orElseThrow(() -> new BusinessException(BranchResultCodes.BRANCH_NOT_FOUND));

        try {
            branchRepository.delete(entity);
            branchRepository.flush();
        } catch (Exception ex) {
            entity.setIsActive(false);
            branchRepository.save(entity);
        }
    }

    private BranchResponse toResponse(BranchEntity e) {
        long roomCount = roomRepository.countByBranchId(e.getId());
        return new BranchResponse(
                e.getId(),
                e.getCode(),
                e.getName(),
                e.getAddress(),
                e.getPhone(),
                e.getEmail(),
                e.getBankName(),
                e.getBankAccountNumber(),
                e.getBankAccountHolder(),
                e.getIsActive(),
                e.getCreatedAt(),
                roomCount
        );
    }

    private RoomResponse toRoomResponse(RoomEntity r) {
        return new RoomResponse(
                r.getId(),
                r.getBranchId(),
                r.getName(),
                r.getFloor(),
                r.getMaxCapacity(),
                r.getIsActive()
        );
    }
}

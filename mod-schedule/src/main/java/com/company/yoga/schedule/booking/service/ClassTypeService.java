package com.company.yoga.schedule.booking.service;

import com.company.yoga.common.exception.BusinessException;
import com.company.yoga.schedule.booking.dto.ClassTypeDto;
import com.company.yoga.schedule.booking.entity.ClassTypeEntity;
import com.company.yoga.schedule.booking.repository.ClassTypeRepository;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class ClassTypeService {

    private final ClassTypeRepository classTypeRepository;
    private final com.company.yoga.schedule.booking.repository.ClassScheduleRepository scheduleRepository;

    @Transactional(readOnly = true)
    public List<ClassTypeDto.Resp> getAllClassTypes(Boolean activeOnly) {
        return classTypeRepository.findAll().stream()
                .filter(ct -> activeOnly == null || !activeOnly || Boolean.TRUE.equals(ct.getIsActive()))
                .map(this::toResp)
                .toList();
    }

    @Transactional(readOnly = true)
    public ClassTypeDto.Resp getClassTypeById(UUID id) {
        ClassTypeEntity entity = classTypeRepository.findById(id)
                .orElseThrow(() -> new BusinessException(com.company.yoga.schedule.ScheduleResultCodes.CLASS_TYPE_NOT_FOUND));
        return toResp(entity);
    }

    @Transactional
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'BRANCH_MANAGER')")
    public ClassTypeDto.Resp createClassType(ClassTypeDto.CreateReq req) {
        if (classTypeRepository.findByCode(req.code().trim().toUpperCase()).isPresent()) {
            throw new BusinessException(com.company.yoga.common.api.CommonErrorCode.CONFLICT);
        }

        ClassTypeEntity entity = new ClassTypeEntity();
        entity.setCode(req.code().trim().toUpperCase());
        entity.setName(req.name().trim());
        entity.setDescription(req.description() != null ? req.description().trim() : null);
        entity.setDefaultDurationMinutes(req.defaultDurationMinutes());
        entity.setIntensityLevel(req.intensityLevel().trim());
        entity.setIsActive(true);

        ClassTypeEntity saved = classTypeRepository.save(entity);
        return toResp(saved);
    }

    @Transactional
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'BRANCH_MANAGER')")
    public void deleteClassType(UUID id) {
        ClassTypeEntity entity = classTypeRepository.findById(id)
                .orElseThrow(() -> new BusinessException(com.company.yoga.schedule.ScheduleResultCodes.CLASS_TYPE_NOT_FOUND));

        if (scheduleRepository.existsByClassTypeId(id)) {
            // Nếu đã phát sinh lịch học, thực hiện soft delete (ngưng hoạt động) để bảo toàn lịch sử
            entity.setIsActive(false);
            classTypeRepository.save(entity);
        } else {
            // Nếu chưa phát sinh ca học nào, xóa hoàn toàn khỏi hệ thống
            classTypeRepository.delete(entity);
        }
    }

    private ClassTypeDto.Resp toResp(ClassTypeEntity entity) {
        return new ClassTypeDto.Resp(
                entity.getId(),
                entity.getCode(),
                entity.getName(),
                entity.getDescription(),
                entity.getDefaultDurationMinutes(),
                entity.getIntensityLevel(),
                entity.getIsActive()
        );
    }
}

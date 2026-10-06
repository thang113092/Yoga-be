package com.company.yoga.membership.plan.service;

import com.company.yoga.common.exception.BusinessException;
import com.company.yoga.membership.MembershipResultCodes;
import com.company.yoga.membership.plan.dto.MembershipPlanDto;
import com.company.yoga.membership.plan.entity.MembershipPlanEntity;
import com.company.yoga.membership.plan.repository.MembershipPlanRepository;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class MembershipPlanService {

    private final MembershipPlanRepository repository;

    @Transactional(readOnly = true)
    public List<MembershipPlanDto.Resp> getAllPlans(boolean all) {
        if (all) {
            return repository.findAll().stream()
                    .map(MembershipPlanDto.Resp::from)
                    .toList();
        }
        return getAllActivePlans();
    }

    @Transactional(readOnly = true)
    public List<MembershipPlanDto.Resp> getAllActivePlans() {
        return repository.findAll().stream()
                .filter(p -> Boolean.TRUE.equals(p.getIsActive()))
                .map(MembershipPlanDto.Resp::from)
                .toList();
    }

    @Transactional(readOnly = true)
    public MembershipPlanDto.Resp getPlanById(UUID id) {
        MembershipPlanEntity entity = repository.findById(id)
                .orElseThrow(() -> new BusinessException(MembershipResultCodes.PLAN_NOT_FOUND));
        return MembershipPlanDto.Resp.from(entity);
    }

    @Transactional
    @org.springframework.security.access.prepost.PreAuthorize("hasRole('SUPER_ADMIN')")
    public MembershipPlanDto.Resp createPlan(MembershipPlanDto.CreateReq req) {
        if (repository.findByCode(req.code().trim().toUpperCase(java.util.Locale.ROOT)).isPresent()) {
            throw new BusinessException(MembershipResultCodes.PLAN_CODE_EXISTS);
        }

        MembershipPlanEntity entity = new MembershipPlanEntity();
        entity.setCode(req.code().trim().toUpperCase());
        entity.setName(req.name().trim());
        entity.setDescription(req.description());
        entity.setPrice(req.price());
        entity.setPlanType(req.planType().trim().toUpperCase());
        entity.setDurationDays(req.durationDays());
        entity.setTotalSessions(req.totalSessions());
        entity.setIsAllBranches(Boolean.TRUE.equals(req.isAllBranches()));
        entity.setIsActive(true);

        repository.save(entity);
        return MembershipPlanDto.Resp.from(entity);
    }

    @Transactional
    @org.springframework.security.access.prepost.PreAuthorize("hasRole('SUPER_ADMIN')")
    public MembershipPlanDto.Resp updatePlan(UUID id, MembershipPlanDto.UpdateReq req) {
        MembershipPlanEntity entity = repository.findById(id)
                .orElseThrow(() -> new BusinessException(MembershipResultCodes.PLAN_NOT_FOUND));

        entity.setName(req.name().trim());
        entity.setDescription(req.description());
        entity.setPrice(req.price());
        if (req.isAllBranches() != null) {
            entity.setIsAllBranches(req.isAllBranches());
        }
        if (req.isActive() != null) {
            entity.setIsActive(req.isActive());
        }

        repository.save(entity);
        return MembershipPlanDto.Resp.from(entity);
    }

    @Transactional
    @org.springframework.security.access.prepost.PreAuthorize("hasRole('SUPER_ADMIN')")
    public void deletePlan(UUID id) {
        MembershipPlanEntity entity = repository.findById(id)
                .orElseThrow(() -> new BusinessException(MembershipResultCodes.PLAN_NOT_FOUND));
        try {
            repository.delete(entity);
            repository.flush();
        } catch (Exception ex) {
            // Soft delete if referenced by existing orders/memberships
            entity.setIsActive(false);
            repository.save(entity);
        }
    }
}

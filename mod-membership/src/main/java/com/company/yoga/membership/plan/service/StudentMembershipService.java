package com.company.yoga.membership.plan.service;

import com.company.yoga.common.exception.BusinessException;
import com.company.yoga.membership.MembershipResultCodes;
import com.company.yoga.membership.plan.dto.MembershipDto;
import com.company.yoga.membership.plan.entity.MembershipEntity;
import com.company.yoga.membership.plan.repository.MembershipRepository;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import com.company.yoga.identity.account.service.AccessPolicy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class StudentMembershipService {

    private final AccessPolicy accessPolicy;
    private final MembershipRepository repository;

    @Transactional(readOnly = true)
    public List<MembershipDto.Resp> getMembershipsByStudent(UUID studentId) {
        accessPolicy.requireStudent(studentId);
        return repository.findByStudentId(studentId).stream()
                .map(MembershipDto.Resp::from)
                .toList();
    }

    @Transactional(readOnly = true)
    public MembershipDto.Resp getMembershipByCode(String code) {
        MembershipEntity entity = repository.findByMembershipCode(code)
                .orElseThrow(() -> new BusinessException(MembershipResultCodes.MEMBERSHIP_NOT_FOUND));
        accessPolicy.requireBooking(entity.getStudentId(), entity.getRegisteredBranchId());
        return MembershipDto.Resp.from(entity);
    }
}

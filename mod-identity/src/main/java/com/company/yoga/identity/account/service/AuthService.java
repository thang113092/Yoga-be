package com.company.yoga.identity.account.service;

import com.company.yoga.common.exception.BusinessException;
import com.company.yoga.identity.IdentityResultCodes;
import com.company.yoga.identity.account.dto.AuthDto.CurrentUserResponse;
import com.company.yoga.identity.account.entity.RoleEntity;
import com.company.yoga.identity.account.entity.UserEntity;
import com.company.yoga.identity.account.repository.RoleRepository;
import com.company.yoga.identity.account.repository.UserRepository;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class AuthService {

    private final UserRepository userRepository;
    private final RoleRepository roleRepository;
    @Transactional(readOnly = true)
    public CurrentUserResponse getCurrentUser(UUID userId) {
        UserEntity user = userRepository.findById(userId)
                .orElseThrow(() -> new BusinessException(IdentityResultCodes.USER_NOT_FOUND));

        RoleEntity role = roleRepository.findById(user.getRoleId())
                .orElseThrow(() -> new BusinessException(IdentityResultCodes.ROLE_NOT_FOUND));

        return new CurrentUserResponse(
                user.getId(),
                user.getPhone(),
                user.getEmail(),
                user.getFullName(),
                role.getCode(),
                role.getName(),
                user.getHomeBranchId(),
                user.getIsActive()
        );
    }
}

package com.company.yoga.membership.order.service;

import com.company.yoga.branch.facility.repository.BranchRepository;
import com.company.yoga.common.exception.BusinessException;
import com.company.yoga.identity.account.entity.UserEntity;
import com.company.yoga.identity.account.repository.UserBranchRepository;
import com.company.yoga.identity.account.repository.UserRepository;
import com.company.yoga.identity.account.service.AccessPolicy;
import com.company.yoga.membership.order.repository.OrderRepository;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class OrderListServiceTest {
    private final AccessPolicy policy = mock(AccessPolicy.class);
    private final UserBranchRepository assignments = mock(UserBranchRepository.class);
    private final OrderRepository orders = mock(OrderRepository.class);
    private final OrderListService service = new OrderListService(policy, assignments, orders,
            mock(UserRepository.class), mock(BranchRepository.class));

    private UserEntity actor(String role) {
        var user = new UserEntity();
        user.setId(UUID.randomUUID());
        when(policy.actor()).thenReturn(user);
        when(policy.role(user)).thenReturn(role);
        when(assignments.branchIdsForUser(user.getId())).thenReturn(List.of());
        return user;
    }

    @Test void managerWithoutBranchesGetsNoOrders() {
        actor("BRANCH_MANAGER");
        assertThat(service.list(null, null, null, 0, 20).content()).isEmpty();
        verifyNoInteractions(orders);
    }

    @Test void managerCannotRequestAnotherBranch() {
        actor("BRANCH_MANAGER").setHomeBranchId(UUID.randomUUID());
        assertThatThrownBy(() -> service.list(UUID.randomUUID(), null, null, 0, 20))
                .isInstanceOf(BusinessException.class);
        verifyNoInteractions(orders);
    }

    @Test void receptionistCannotListOrders() {
        actor("RECEPTIONIST");
        assertThatThrownBy(() -> service.list(null, null, null, 0, 20)).isInstanceOf(BusinessException.class);
        verifyNoInteractions(orders);
    }

    @Test void rejectsInvalidPaginationAndStatus() {
        actor("SUPER_ADMIN");
        assertThatThrownBy(() -> service.list(null, null, null, -1, 20)).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> service.list(null, null, null, 0, 101)).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> service.list(null, "INVALID", null, 0, 20)).isInstanceOf(BusinessException.class);
        verifyNoInteractions(orders);
    }
}

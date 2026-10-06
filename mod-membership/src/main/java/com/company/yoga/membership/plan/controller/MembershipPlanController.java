package com.company.yoga.membership.plan.controller;

import com.company.yoga.common.api.ApiResponse;
import com.company.yoga.membership.plan.dto.MembershipPlanDto;
import com.company.yoga.membership.plan.service.MembershipPlanService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Membership Plans", description = "Quản lý danh mục gói thẻ tập Yoga")
@RestController
@RequestMapping("/api/v1/membership-plans")
@RequiredArgsConstructor
public class MembershipPlanController {

    private final MembershipPlanService service;

    @GetMapping
    @Operation(summary = "Lấy danh sách các gói tập")
    public ApiResponse<List<MembershipPlanDto.Resp>> getAllPlans(
            @org.springframework.web.bind.annotation.RequestParam(required = false, defaultValue = "false") boolean all
    ) {
        return ApiResponse.success(service.getAllPlans(all));
    }

    @GetMapping("/{id}")
    @Operation(summary = "Lấy chi tiết một gói tập")
    public ApiResponse<MembershipPlanDto.Resp> getById(@PathVariable UUID id) {
        return ApiResponse.success(service.getPlanById(id));
    }

    @PostMapping
    @Operation(summary = "Tạo mới gói tập")
    public ApiResponse<MembershipPlanDto.Resp> create(@Valid @RequestBody MembershipPlanDto.CreateReq req) {
        return ApiResponse.success(service.createPlan(req));
    }

    @PutMapping("/{id}")
    @Operation(summary = "Cập nhật thông tin gói tập")
    public ApiResponse<MembershipPlanDto.Resp> update(
            @PathVariable UUID id,
            @Valid @RequestBody MembershipPlanDto.UpdateReq req
    ) {
        return ApiResponse.success(service.updatePlan(id, req));
    }

    @org.springframework.web.bind.annotation.DeleteMapping("/{id}")
    @Operation(summary = "Xóa hoặc vô hiệu hóa gói tập")
    public ApiResponse<Void> delete(@PathVariable UUID id) {
        service.deletePlan(id);
        return ApiResponse.success(null);
    }
}

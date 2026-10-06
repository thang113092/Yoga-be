package com.company.yoga.membership.plan.controller;

import com.company.yoga.common.api.ApiResponse;
import com.company.yoga.membership.plan.dto.MembershipDto;
import com.company.yoga.membership.plan.service.StudentMembershipService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Memberships", description = "Quản lý hợp đồng thẻ tập và thẻ hội viên")
@RestController
@RequestMapping("/api/v1/memberships")
@RequiredArgsConstructor
public class MembershipController {

    private final StudentMembershipService service;

    @GetMapping("/student/{studentId}")
    @Operation(summary = "Lấy danh sách thẻ tập của một học viên")
    public ApiResponse<List<MembershipDto.Resp>> getByStudent(@PathVariable UUID studentId) {
        return ApiResponse.success(service.getMembershipsByStudent(studentId));
    }

    @GetMapping("/code/{code}")
    @Operation(summary = "Tra cứu thẻ tập theo mã thẻ")
    public ApiResponse<MembershipDto.Resp> getByCode(@PathVariable String code) {
        return ApiResponse.success(service.getMembershipByCode(code));
    }
}

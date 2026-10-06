package com.company.yoga.schedule.booking.controller;

import com.company.yoga.common.api.ApiResponse;
import com.company.yoga.schedule.booking.dto.ClassTypeDto;
import com.company.yoga.schedule.booking.service.ClassTypeService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Loại lớp học (Class Types)", description = "APIs tra cứu và quản lý danh mục loại lớp học/bộ môn")
@RestController
@RequestMapping("/api/v1/class-types")
@RequiredArgsConstructor
public class ClassTypeController {

    private final ClassTypeService classTypeService;

    @Operation(summary = "Lấy danh mục loại lớp học (Bộ môn yoga)")
    @GetMapping
    public ApiResponse<List<ClassTypeDto.Resp>> getAllClassTypes(
            @RequestParam(required = false, defaultValue = "false") Boolean activeOnly
    ) {
        return ApiResponse.success(classTypeService.getAllClassTypes(activeOnly));
    }

    @Operation(summary = "Xem chi tiết một loại lớp học")
    @GetMapping("/{id}")
    public ApiResponse<ClassTypeDto.Resp> getClassTypeById(@PathVariable UUID id) {
        return ApiResponse.success(classTypeService.getClassTypeById(id));
    }

    @Operation(summary = "Thêm loại lớp học mới (Super Admin / Quản lý cơ sở)")
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<ClassTypeDto.Resp> createClassType(@Valid @RequestBody ClassTypeDto.CreateReq req) {
        return ApiResponse.success(classTypeService.createClassType(req), "Tạo loại lớp học thành công");
    }

    @Operation(summary = "Xóa loại lớp học (Super Admin / Quản lý cơ sở)")
    @org.springframework.web.bind.annotation.DeleteMapping("/{id}")
    public ApiResponse<Void> deleteClassType(@PathVariable UUID id) {
        classTypeService.deleteClassType(id);
        return ApiResponse.success(null, "Xóa bộ môn thành công");
    }
}

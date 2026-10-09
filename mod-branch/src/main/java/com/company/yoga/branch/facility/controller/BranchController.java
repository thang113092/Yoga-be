package com.company.yoga.branch.facility.controller;

import com.company.yoga.branch.facility.dto.BranchDto.BranchResponse;
import com.company.yoga.branch.facility.dto.BranchDto.CreateBranchRequest;
import com.company.yoga.branch.facility.dto.BranchDto.UpdateBranchRequest;
import com.company.yoga.branch.facility.dto.RoomDto.CreateRoomRequest;
import com.company.yoga.branch.facility.dto.RoomDto.RoomResponse;
import com.company.yoga.branch.facility.dto.RoomDto.UpdateRoomRequest;
import com.company.yoga.branch.facility.service.BranchService;
import com.company.yoga.common.api.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/branches")
@RequiredArgsConstructor
@Tag(name = "Branch API", description = "Quản lý chi nhánh phòng tập và phòng học")
public class BranchController {

    private final BranchService branchService;

    @GetMapping
    @Operation(summary = "Lấy danh sách tất cả các chi nhánh")
    public ApiResponse<List<BranchResponse>> getAllBranches() {
        return ApiResponse.success(branchService.getAllBranches());
    }

    @GetMapping("/{id}")
    @Operation(summary = "Xem chi tiết một chi nhánh")
    public ApiResponse<BranchResponse> getBranchById(@PathVariable UUID id) {
        return ApiResponse.success(branchService.getBranchById(id));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Thêm mới một chi nhánh (Super Admin)")
    public ApiResponse<BranchResponse> createBranch(@Valid @RequestBody CreateBranchRequest request) {
        return ApiResponse.success(branchService.createBranch(request), "Tạo chi nhánh mới thành công");
    }

    @PutMapping("/{id}")
    @Operation(summary = "Cập nhật chi nhánh (Super Admin)")
    public ApiResponse<BranchResponse> updateBranch(
            @PathVariable UUID id,
            @Valid @RequestBody UpdateBranchRequest request
    ) {
        return ApiResponse.success(branchService.updateBranch(id, request), "Cập nhật chi nhánh thành công");
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "Xóa hoặc ngưng hoạt động chi nhánh (Super Admin)")
    public ApiResponse<Void> deleteBranch(@PathVariable UUID id) {
        branchService.deleteBranch(id);
        return ApiResponse.success(null, "Xóa chi nhánh thành công");
    }

    @GetMapping("/{id}/rooms")
    @Operation(summary = "Lấy danh sách các phòng tập của chi nhánh")
    public ApiResponse<List<RoomResponse>> getRoomsByBranch(
            @PathVariable UUID id,
            @RequestParam(required = false, defaultValue = "false") boolean all
    ) {
        return ApiResponse.success(branchService.getRoomsByBranch(id, all));
    }

    @GetMapping("/all-rooms")
    @Operation(summary = "Lấy danh sách tất cả các phòng tập")
    public ApiResponse<List<RoomResponse>> getAllRooms() {
        return ApiResponse.success(branchService.getAllRooms());
    }

    @PostMapping("/{id}/rooms")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Thêm mới phòng tập cho chi nhánh (Super Admin)")
    public ApiResponse<RoomResponse> createRoom(
            @PathVariable UUID id,
            @Valid @RequestBody CreateRoomRequest request
    ) {
        return ApiResponse.success(branchService.createRoom(id, request), "Tạo phòng học mới thành công");
    }

    @PutMapping({"/rooms/{roomId}", "/{branchId}/rooms/{roomId}"})
    @Operation(summary = "Cập nhật thông tin phòng tập (Super Admin)")
    public ApiResponse<RoomResponse> updateRoom(
            @PathVariable UUID roomId,
            @Valid @RequestBody UpdateRoomRequest request
    ) {
        return ApiResponse.success(branchService.updateRoom(roomId, request), "Cập nhật phòng học thành công");
    }

    @DeleteMapping({"/rooms/{roomId}", "/{branchId}/rooms/{roomId}"})
    @Operation(summary = "Xóa hoặc ngưng hoạt động phòng tập (Super Admin)")
    public ApiResponse<Void> deleteRoom(@PathVariable UUID roomId) {
        branchService.deleteRoom(roomId);
        return ApiResponse.success(null, "Xóa phòng học thành công");
    }
}

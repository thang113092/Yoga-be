package com.company.yoga.branch.facility.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.company.yoga.branch.BranchResultCodes;
import com.company.yoga.branch.facility.dto.BranchDto.CreateBranchRequest;
import com.company.yoga.branch.facility.dto.BranchDto.UpdateBranchRequest;
import com.company.yoga.branch.facility.dto.RoomDto.CreateRoomRequest;
import com.company.yoga.branch.facility.dto.RoomDto.UpdateRoomRequest;
import com.company.yoga.branch.facility.entity.BranchEntity;
import com.company.yoga.branch.facility.entity.RoomEntity;
import com.company.yoga.branch.facility.repository.BranchRepository;
import com.company.yoga.branch.facility.repository.RoomRepository;
import com.company.yoga.common.exception.BusinessException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class BranchServiceTest {

    @Mock
    private BranchRepository branchRepository;

    @Mock
    private RoomRepository roomRepository;

    @InjectMocks
    private BranchService branchService;

    private UUID branchId;
    private UUID roomId;
    private BranchEntity sampleBranch;
    private RoomEntity sampleRoom;

    @BeforeEach
    void setUp() {
        branchId = UUID.randomUUID();
        roomId = UUID.randomUUID();

        sampleBranch = new BranchEntity();
        sampleBranch.setId(branchId);
        sampleBranch.setCode("CS01");
        sampleBranch.setName("Cơ sở Hoàn Kiếm");
        sampleBranch.setAddress("12 Hàng Bông, Hà Nội");
        sampleBranch.setPhone("0901234567");
        sampleBranch.setIsActive(true);

        sampleRoom = new RoomEntity();
        sampleRoom.setId(roomId);
        sampleRoom.setBranchId(branchId);
        sampleRoom.setName("Phòng Sen Hồng");
        sampleRoom.setFloor("Tầng 2");
        sampleRoom.setMaxCapacity(20);
        sampleRoom.setIsActive(true);
    }

    @Test
    @DisplayName("Tạo chi nhánh mới thành công")
    void createBranch_Success() {
        CreateBranchRequest req = new CreateBranchRequest(
                "CS02", "Cơ sở Ba Đình", "10 Liễu Giai", "0909999888",
                "badinh@anyen.vn", "Vietcombank", "999888777", "AN YEN YOGA"
        );
        when(branchRepository.existsByCode("CS02")).thenReturn(false);
        when(branchRepository.save(any(BranchEntity.class))).thenAnswer(i -> {
            BranchEntity e = i.getArgument(0);
            e.setId(UUID.randomUUID());
            return e;
        });

        var resp = branchService.createBranch(req);

        assertThat(resp).isNotNull();
        assertThat(resp.code()).isEqualTo("CS02");
        assertThat(resp.name()).isEqualTo("Cơ sở Ba Đình");
    }

    @Test
    @DisplayName("Tạo chi nhánh thất bại khi mã chi nhánh đã tồn tại")
    void createBranch_DuplicateCode() {
        CreateBranchRequest req = new CreateBranchRequest(
                "CS01", "Cơ sở Trùng Mã", "Địa chỉ", "0909999888",
                null, null, null, null
        );
        when(branchRepository.existsByCode("CS01")).thenReturn(true);

        assertThatThrownBy(() -> branchService.createBranch(req))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining(BranchResultCodes.BRANCH_CODE_EXISTS.getMessage());
    }

    @Test
    @DisplayName("Cập nhật chi nhánh thành công")
    void updateBranch_Success() {
        UpdateBranchRequest req = new UpdateBranchRequest(
                "CS01_NEW", "Cơ sở Đã Sửa", "Địa chỉ mới", "0911222333",
                "new@anyen.vn", "Techcombank", "123456", "CONG TY AN YEN", true
        );
        when(branchRepository.findById(branchId)).thenReturn(Optional.of(sampleBranch));
        when(branchRepository.existsByCodeAndIdNot("CS01_NEW", branchId)).thenReturn(false);
        when(branchRepository.save(any(BranchEntity.class))).thenAnswer(i -> i.getArgument(0));

        var resp = branchService.updateBranch(branchId, req);

        assertThat(resp.name()).isEqualTo("Cơ sở Đã Sửa");
        assertThat(resp.code()).isEqualTo("CS01_NEW");
    }

    @Test
    @DisplayName("Xóa chi nhánh thành công")
    void deleteBranch_Success() {
        when(branchRepository.findById(branchId)).thenReturn(Optional.of(sampleBranch));

        branchService.deleteBranch(branchId);

        verify(branchRepository).delete(sampleBranch);
    }

    @Test
    @DisplayName("Lấy danh sách phòng theo chi nhánh: chỉ active khi all=false")
    void getRoomsByBranch_ActiveOnly() {
        RoomEntity inactiveRoom = new RoomEntity();
        inactiveRoom.setId(UUID.randomUUID());
        inactiveRoom.setBranchId(branchId);
        inactiveRoom.setName("Phòng Bảo Trì");
        inactiveRoom.setMaxCapacity(10);
        inactiveRoom.setIsActive(false);

        when(branchRepository.existsById(branchId)).thenReturn(true);
        when(roomRepository.findByBranchId(branchId)).thenReturn(List.of(sampleRoom, inactiveRoom));

        var activeOnly = branchService.getRoomsByBranch(branchId, false);
        assertThat(activeOnly).hasSize(1);
        assertThat(activeOnly.get(0).name()).isEqualTo("Phòng Sen Hồng");

        var allRooms = branchService.getRoomsByBranch(branchId, true);
        assertThat(allRooms).hasSize(2);
    }

    @Test
    @DisplayName("Tạo phòng học mới thành công")
    void createRoom_Success() {
        CreateRoomRequest req = new CreateRoomRequest("Phòng Trúc Xanh", "Tầng 3", 18, true);
        when(branchRepository.existsById(branchId)).thenReturn(true);
        when(roomRepository.existsByBranchIdAndName(branchId, "Phòng Trúc Xanh")).thenReturn(false);
        when(roomRepository.save(any(RoomEntity.class))).thenAnswer(i -> {
            RoomEntity e = i.getArgument(0);
            e.setId(UUID.randomUUID());
            return e;
        });

        var resp = branchService.createRoom(branchId, req);

        assertThat(resp).isNotNull();
        assertThat(resp.name()).isEqualTo("Phòng Trúc Xanh");
        assertThat(resp.maxCapacity()).isEqualTo(18);
    }

    @Test
    @DisplayName("Tạo phòng học thất bại khi trùng tên trong cùng chi nhánh")
    void createRoom_DuplicateName() {
        CreateRoomRequest req = new CreateRoomRequest("Phòng Sen Hồng", "Tầng 2", 20, true);
        when(branchRepository.existsById(branchId)).thenReturn(true);
        when(roomRepository.existsByBranchIdAndName(branchId, "Phòng Sen Hồng")).thenReturn(true);

        assertThatThrownBy(() -> branchService.createRoom(branchId, req))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining(BranchResultCodes.ROOM_NAME_EXISTS.getMessage());
    }

    @Test
    @DisplayName("Cập nhật phòng học thành công")
    void updateRoom_Success() {
        UpdateRoomRequest req = new UpdateRoomRequest("Phòng Sen Hồng VIP", "Tầng 4", 25, true);
        when(roomRepository.findById(roomId)).thenReturn(Optional.of(sampleRoom));
        when(roomRepository.existsByBranchIdAndNameAndIdNot(branchId, "Phòng Sen Hồng VIP", roomId)).thenReturn(false);
        when(roomRepository.save(any(RoomEntity.class))).thenAnswer(i -> i.getArgument(0));

        var resp = branchService.updateRoom(roomId, req);

        assertThat(resp.name()).isEqualTo("Phòng Sen Hồng VIP");
        assertThat(resp.maxCapacity()).isEqualTo(25);
    }

    @Test
    @DisplayName("Xóa phòng học thành công")
    void deleteRoom_Success() {
        when(roomRepository.findById(roomId)).thenReturn(Optional.of(sampleRoom));

        branchService.deleteRoom(roomId);

        verify(roomRepository).delete(sampleRoom);
    }
}

package com.company.yoga.schedule.booking.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.company.yoga.branch.facility.entity.BranchEntity;
import com.company.yoga.branch.facility.entity.RoomEntity;
import com.company.yoga.branch.facility.repository.BranchRepository;
import com.company.yoga.branch.facility.repository.RoomRepository;
import com.company.yoga.common.exception.BusinessException;
import com.company.yoga.identity.account.entity.RoleEntity;
import com.company.yoga.identity.account.entity.UserEntity;
import com.company.yoga.identity.account.repository.RoleRepository;
import com.company.yoga.identity.account.repository.UserBranchRepository;
import com.company.yoga.identity.account.repository.UserRepository;
import com.company.yoga.identity.account.service.AccessPolicy;
import com.company.yoga.schedule.ScheduleResultCodes;
import com.company.yoga.schedule.booking.dto.ScheduleDto;
import com.company.yoga.schedule.booking.entity.ClassScheduleEntity;
import com.company.yoga.schedule.booking.entity.ClassTypeEntity;
import com.company.yoga.schedule.booking.repository.BookingRepository;
import com.company.yoga.schedule.booking.repository.ClassScheduleRepository;
import com.company.yoga.schedule.booking.repository.ClassTypeRepository;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
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
class ClassScheduleServiceTest {

    @Mock
    private AccessPolicy accessPolicy;
    @Mock
    private ClassScheduleRepository scheduleRepository;
    @Mock
    private ClassTypeRepository classTypeRepository;
    @Mock
    private RoomRepository roomRepository;
    @Mock
    private BranchRepository branchRepository;
    @Mock
    private UserRepository userRepository;
    @Mock
    private RoleRepository roleRepository;
    @Mock
    private UserBranchRepository userBranchRepository;
    @Mock
    private BookingRepository bookingRepository;

    @InjectMocks
    private ClassScheduleService classScheduleService;

    private UUID branchId;
    private UUID roomId;
    private UUID classTypeId;
    private UUID instructorId;
    private UUID roleId;
    private Instant startTime;
    private Instant endTime;
    private ScheduleDto.CreateReq createReq;
    private RoomEntity room;
    private UserEntity instructor;
    private RoleEntity instructorRole;

    @BeforeEach
    void setUp() {
        branchId = UUID.randomUUID();
        roomId = UUID.randomUUID();
        classTypeId = UUID.randomUUID();
        instructorId = UUID.randomUUID();
        roleId = UUID.randomUUID();

        startTime = Instant.now().plus(1, ChronoUnit.DAYS);
        endTime = startTime.plus(60, ChronoUnit.MINUTES);

        createReq = new ScheduleDto.CreateReq(
                branchId,
                roomId,
                classTypeId,
                instructorId,
                startTime,
                endTime,
                20
        );

        room = new RoomEntity();
        room.setId(roomId);
        room.setBranchId(branchId);
        room.setName("Studio Sen Vàng");
        room.setMaxCapacity(30);
        room.setIsActive(true);

        instructor = new UserEntity();
        instructor.setId(instructorId);
        instructor.setFullName("Huấn Luyện Viên An Yên");
        instructor.setRoleId(roleId);
        instructor.setIsActive(true);

        instructorRole = new RoleEntity();
        instructorRole.setId(roleId);
        instructorRole.setCode("INSTRUCTOR");
    }

    @Test
    @DisplayName("Tạo lịch thất bại khi phòng tập đã có lớp khác cùng thời điểm (trùng phòng)")
    void createSchedule_ThrowsRoomOverlap_WhenRoomHasOverlappingSchedule() {
        when(roomRepository.findById(roomId)).thenReturn(Optional.of(room));
        when(userRepository.findById(instructorId)).thenReturn(Optional.of(instructor));
        when(roleRepository.findById(roleId)).thenReturn(Optional.of(instructorRole));
        when(userBranchRepository.existsByUserIdAndBranchId(instructorId, branchId)).thenReturn(true);
        when(scheduleRepository.existsOverlappingRoomSchedule(roomId, startTime, endTime)).thenReturn(true);

        BusinessException ex = assertThrows(BusinessException.class, () ->
                classScheduleService.createSchedule(createReq));

        assertEquals(ScheduleResultCodes.ROOM_SCHEDULE_OVERLAP, ex.getErrorCode());
    }

    @Test
    @DisplayName("Tạo lịch thất bại khi huấn luyện viên đã có lịch dạy lớp khác cùng thời điểm (trùng HLV)")
    void createSchedule_ThrowsInstructorOverlap_WhenInstructorHasOverlappingSchedule() {
        when(roomRepository.findById(roomId)).thenReturn(Optional.of(room));
        when(userRepository.findById(instructorId)).thenReturn(Optional.of(instructor));
        when(roleRepository.findById(roleId)).thenReturn(Optional.of(instructorRole));
        when(userBranchRepository.existsByUserIdAndBranchId(instructorId, branchId)).thenReturn(true);
        when(scheduleRepository.existsOverlappingRoomSchedule(roomId, startTime, endTime)).thenReturn(false);
        when(scheduleRepository.existsOverlappingInstructorSchedule(instructorId, startTime, endTime)).thenReturn(true);

        BusinessException ex = assertThrows(BusinessException.class, () ->
                classScheduleService.createSchedule(createReq));

        assertEquals(ScheduleResultCodes.INSTRUCTOR_SCHEDULE_OVERLAP, ex.getErrorCode());
    }

    @Test
    @DisplayName("Tạo lịch thành công khi không trùng phòng và không trùng huấn luyện viên")
    void createSchedule_Success_WhenNoOverlap() {
        when(roomRepository.findById(roomId)).thenReturn(Optional.of(room));
        when(userRepository.findById(instructorId)).thenReturn(Optional.of(instructor));
        when(roleRepository.findById(roleId)).thenReturn(Optional.of(instructorRole));
        when(userBranchRepository.existsByUserIdAndBranchId(instructorId, branchId)).thenReturn(true);
        when(scheduleRepository.existsOverlappingRoomSchedule(roomId, startTime, endTime)).thenReturn(false);
        when(scheduleRepository.existsOverlappingInstructorSchedule(instructorId, startTime, endTime)).thenReturn(false);

        ClassScheduleEntity savedEntity = new ClassScheduleEntity();
        savedEntity.setId(UUID.randomUUID());
        savedEntity.setBranchId(branchId);
        savedEntity.setRoomId(roomId);
        savedEntity.setClassTypeId(classTypeId);
        savedEntity.setInstructorId(instructorId);
        savedEntity.setStartTime(startTime);
        savedEntity.setEndTime(endTime);
        savedEntity.setMaxCapacity(20);
        savedEntity.setStatus("SCHEDULED");
        savedEntity.setBookedCount(0);

        when(scheduleRepository.save(any(ClassScheduleEntity.class))).thenReturn(savedEntity);

        ClassTypeEntity ct = new ClassTypeEntity();
        ct.setId(classTypeId);
        ct.setName("Hatha Yoga Căn Bản");
        when(classTypeRepository.findById(classTypeId)).thenReturn(Optional.of(ct));

        BranchEntity b = new BranchEntity();
        b.setId(branchId);
        b.setName("An Yên Quận 1");
        when(branchRepository.findById(branchId)).thenReturn(Optional.of(b));

        ScheduleDto.Resp resp = classScheduleService.createSchedule(createReq);

        assertNotNull(resp);
        assertEquals(branchId, resp.branchId());
        assertEquals(roomId, resp.roomId());
        assertEquals("Hatha Yoga Căn Bản", resp.className());
        assertEquals("Huấn Luyện Viên An Yên", resp.instructorName());
        verify(scheduleRepository).save(any(ClassScheduleEntity.class));
    }
}

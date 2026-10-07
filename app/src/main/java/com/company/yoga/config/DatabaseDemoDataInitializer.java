package com.company.yoga.config;

import com.company.yoga.branch.facility.entity.BranchEntity;
import com.company.yoga.branch.facility.entity.RoomEntity;
import com.company.yoga.branch.facility.repository.BranchRepository;
import com.company.yoga.branch.facility.repository.RoomRepository;
import com.company.yoga.identity.account.entity.RoleEntity;
import com.company.yoga.identity.account.entity.UserBranchEntity;
import com.company.yoga.identity.account.entity.UserEntity;
import com.company.yoga.identity.account.repository.RoleRepository;
import com.company.yoga.identity.account.repository.UserBranchRepository;
import com.company.yoga.identity.account.repository.UserRepository;
import com.company.yoga.membership.order.dto.PaymentDto;
import com.company.yoga.membership.order.dto.PosOrderDto;
import com.company.yoga.membership.order.service.PaymentService;
import com.company.yoga.membership.order.service.PosOrderService;
import com.company.yoga.membership.plan.entity.MembershipPlanEntity;
import com.company.yoga.membership.plan.repository.MembershipPlanRepository;
import com.company.yoga.membership.plan.repository.MembershipRepository;
import com.company.yoga.schedule.booking.entity.ClassScheduleEntity;
import com.company.yoga.schedule.booking.entity.ClassTypeEntity;
import com.company.yoga.schedule.booking.repository.ClassScheduleRepository;
import com.company.yoga.schedule.booking.repository.ClassTypeRepository;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Slf4j
@org.springframework.context.annotation.Profile("demo")
@Component
@RequiredArgsConstructor
public class DatabaseDemoDataInitializer implements CommandLineRunner {

    private final RoleRepository roleRepository;
    private final BranchRepository branchRepository;
    private final RoomRepository roomRepository;
    private final ClassTypeRepository classTypeRepository;
    private final MembershipPlanRepository planRepository;
    private final UserRepository userRepository;
    private final UserBranchRepository userBranchRepository;
    private final ClassScheduleRepository scheduleRepository;
    private final MembershipRepository membershipRepository;
    private final PosOrderService posOrderService;
    private final PaymentService paymentService;
    private final PlatformTransactionManager transactionManager;

    public static final UUID ROLE_SUPER_ADMIN = UUID.fromString("a0000000-0000-0000-0000-000000000001");
    public static final UUID ROLE_MANAGER = UUID.fromString("a0000000-0000-0000-0000-000000000002");
    public static final UUID ROLE_RECEPTIONIST = UUID.fromString("a0000000-0000-0000-0000-000000000003");
    public static final UUID ROLE_INSTRUCTOR = UUID.fromString("a0000000-0000-0000-0000-000000000004");
    public static final UUID ROLE_STUDENT = UUID.fromString("a0000000-0000-0000-0000-000000000005");

    public static final UUID BRANCH_Q1 = UUID.fromString("b0000000-0000-0000-0000-000000000001");
    public static final UUID BRANCH_CG = UUID.fromString("b0000000-0000-0000-0000-000000000002");

    public static final UUID ROOM_LOTUS = UUID.fromString("c0000000-0000-0000-0000-000000000001");
    public static final UUID ROOM_BAMBOO = UUID.fromString("c0000000-0000-0000-0000-000000000002");

    public static final UUID TYPE_HATHA = UUID.fromString("d0000000-0000-0000-0000-000000000001");
    public static final UUID TYPE_VINYASA = UUID.fromString("d0000000-0000-0000-0000-000000000002");
    public static final UUID TYPE_YIN = UUID.fromString("d0000000-0000-0000-0000-000000000003");
    public static final UUID TYPE_AERIAL = UUID.fromString("d0000000-0000-0000-0000-000000000004");

    public static final UUID PLAN_ALL_12M = UUID.fromString("e0000000-0000-0000-0000-000000000001");
    public static final UUID PLAN_ALL_30S = UUID.fromString("e0000000-0000-0000-0000-000000000002");
    public static final UUID PLAN_SINGLE_10S = UUID.fromString("e0000000-0000-0000-0000-000000000004");

    public static final UUID USER_SUPER_ADMIN = UUID.fromString("f0000000-0000-0000-0000-000000000001");
    public static final UUID USER_BRANCH_MANAGER = UUID.fromString("f0000000-0000-0000-0000-000000000002");
    public static final UUID USER_RECEPTIONIST = UUID.fromString("f0000000-0000-0000-0000-000000000003");
    public static final UUID USER_INSTRUCTOR = UUID.fromString("f0000000-0000-0000-0000-000000000004");
    public static final UUID USER_STUDENT_AN = UUID.fromString("f0000000-0000-0000-0000-000000000005");

    @Override
    public void run(String... args) {
        log.info("Checking database seed records for multi-branch yoga system...");
        TransactionTemplate tx = new TransactionTemplate(transactionManager);

        tx.executeWithoutResult(status -> initRoles());
        tx.executeWithoutResult(status -> initBranches());
        tx.executeWithoutResult(status -> initRooms());
        tx.executeWithoutResult(status -> initClassTypes());
        tx.executeWithoutResult(status -> initPlans());

        log.info("Database verification and initialization completed successfully.");
    }

    private void initRoles() {
        if (!roleRepository.existsById(ROLE_SUPER_ADMIN)) {
            RoleEntity r1 = new RoleEntity();
            r1.setId(ROLE_SUPER_ADMIN);
            r1.setCode("SUPER_ADMIN");
            r1.setName("Chủ chuỗi (Super Admin)");
            r1.setDescription("Toàn quyền quản trị hệ thống");
            roleRepository.save(r1);
        }

        if (!roleRepository.existsById(ROLE_MANAGER)) {
            RoleEntity r2 = new RoleEntity();
            r2.setId(ROLE_MANAGER);
            r2.setCode("BRANCH_MANAGER");
            r2.setName("Quản lý Chi nhánh");
            r2.setDescription("Quản lý chi nhánh");
            roleRepository.save(r2);
        }

        if (!roleRepository.existsById(ROLE_RECEPTIONIST)) {
            RoleEntity r3 = new RoleEntity();
            r3.setId(ROLE_RECEPTIONIST);
            r3.setCode("RECEPTIONIST");
            r3.setName("Lễ tân");
            r3.setDescription("Bán thẻ POS và điểm danh");
            roleRepository.save(r3);
        }

        if (!roleRepository.existsById(ROLE_INSTRUCTOR)) {
            RoleEntity r4 = new RoleEntity();
            r4.setId(ROLE_INSTRUCTOR);
            r4.setCode("INSTRUCTOR");
            r4.setName("Huấn luyện viên");
            r4.setDescription("Giảng viên lớp học");
            roleRepository.save(r4);
        }

        if (!roleRepository.existsById(ROLE_STUDENT)) {
            RoleEntity r5 = new RoleEntity();
            r5.setId(ROLE_STUDENT);
            r5.setCode("STUDENT");
            r5.setName("Học viên");
            r5.setDescription("Học viên tập luyện");
            roleRepository.save(r5);
        }
        roleRepository.flush();
    }

    private void initBranches() {
        if (!branchRepository.existsById(BRANCH_Q1)) {
            BranchEntity b1 = new BranchEntity();
            b1.setId(BRANCH_Q1);
            b1.setCode("CN_Q1");
            b1.setName("Yoga Center - Cơ sở Quận 1");
            b1.setAddress("Số 45 Lê Duẩn, Bến Nghé, Quận 1, TP. Hồ Chí Minh");
            b1.setPhone("02838112233");
            b1.setEmail("q1@yogacenter.vn");
            b1.setBankName("Vietcombank");
            b1.setBankAccountNumber("0071001234567");
            b1.setBankAccountHolder("CONG TY TNHH YOGA CENTER CN Q1");
            b1.setIsActive(true);
            branchRepository.save(b1);
        }

        if (!branchRepository.existsById(BRANCH_CG)) {
            BranchEntity b2 = new BranchEntity();
            b2.setId(BRANCH_CG);
            b2.setCode("CN_CG");
            b2.setName("Yoga Center - Cơ sở Cầu Giấy");
            b2.setAddress("Số 88 Trần Duy Hưng, Cầu Giấy, Hà Nội");
            b2.setPhone("02439887766");
            b2.setEmail("caugiay@yogacenter.vn");
            b2.setBankName("Techcombank");
            b2.setBankAccountNumber("1903678999888");
            b2.setBankAccountHolder("CONG TY TNHH YOGA CENTER CN CG");
            b2.setIsActive(true);
            branchRepository.save(b2);
        }
        branchRepository.flush();
    }

    private void initRooms() {
        if (!roomRepository.existsById(ROOM_LOTUS)) {
            RoomEntity r1 = new RoomEntity();
            r1.setId(ROOM_LOTUS);
            r1.setBranchId(BRANCH_Q1);
            r1.setName("Studio Lotus (Hoa Sen)");
            r1.setFloor("Tầng 2");
            r1.setMaxCapacity(25);
            r1.setIsActive(true);
            roomRepository.save(r1);
        }

        if (!roomRepository.existsById(ROOM_BAMBOO)) {
            RoomEntity r2 = new RoomEntity();
            r2.setId(ROOM_BAMBOO);
            r2.setBranchId(BRANCH_Q1);
            r2.setName("Studio Bamboo (Tre Xanh)");
            r2.setFloor("Tầng 3");
            r2.setMaxCapacity(15);
            r2.setIsActive(true);
            roomRepository.save(r2);
        }
        roomRepository.flush();
    }

    private void initClassTypes() {
        if (!classTypeRepository.existsById(TYPE_HATHA)) {
            ClassTypeEntity t1 = new ClassTypeEntity();
            t1.setId(TYPE_HATHA);
            t1.setCode("HATHA_YOGA");
            t1.setName("Hatha Yoga Căn Bản");
            t1.setDescription("Tập trung vào định tuyến tư thế, hít thở và giải tỏa căng thẳng");
            t1.setDefaultDurationMinutes(60);
            t1.setIntensityLevel("BEGINNER");
            t1.setIsActive(true);
            classTypeRepository.save(t1);
        }

        if (!classTypeRepository.existsById(TYPE_VINYASA)) {
            ClassTypeEntity t2 = new ClassTypeEntity();
            t2.setId(TYPE_VINYASA);
            t2.setCode("VINYASA_FLOW");
            t2.setName("Vinyasa Flow Linh Hoạt");
            t2.setDescription("Chuỗi chuyển động nhịp nhàng theo hơi thở");
            t2.setDefaultDurationMinutes(60);
            t2.setIntensityLevel("INTERMEDIATE");
            t2.setIsActive(true);
            classTypeRepository.save(t2);
        }

        if (!classTypeRepository.existsById(TYPE_YIN)) {
            ClassTypeEntity t3 = new ClassTypeEntity();
            t3.setId(TYPE_YIN);
            t3.setCode("YIN_YOGA");
            t3.setName("Yin Yoga Phục Hồi & Chuông");
            t3.setDescription("Tác động sâu vào mô liên kết và giải phóng căng cơ");
            t3.setDefaultDurationMinutes(75);
            t3.setIntensityLevel("ALL_LEVELS");
            t3.setIsActive(true);
            classTypeRepository.save(t3);
        }

        if (!classTypeRepository.existsById(TYPE_AERIAL)) {
            ClassTypeEntity t4 = new ClassTypeEntity();
            t4.setId(TYPE_AERIAL);
            t4.setCode("AERIAL_YOGA");
            t4.setName("Aerial Yoga (Yoga Võng Bay)");
            t4.setDescription("Kéo giãn cột sống không trọng lực");
            t4.setDefaultDurationMinutes(60);
            t4.setIntensityLevel("ALL_LEVELS");
            t4.setIsActive(true);
            classTypeRepository.save(t4);
        }
        classTypeRepository.flush();
    }

    private void initPlans() {
        if (!planRepository.existsById(PLAN_ALL_12M)) {
            MembershipPlanEntity p1 = new MembershipPlanEntity();
            p1.setId(PLAN_ALL_12M);
            p1.setCode("PLAN_ALL_12M");
            p1.setName("Thẻ Kim Cương Toàn Chuỗi 12 Tháng");
            p1.setDescription("Tập không giới hạn tại mọi cơ sở trong 365 ngày");
            p1.setPrice(new BigDecimal("15000000.00"));
            p1.setPlanType("TIME_BASED");
            p1.setDurationDays(365);
            p1.setIsAllBranches(true);
            p1.setIsActive(true);
            planRepository.save(p1);
        }

        if (!planRepository.existsById(PLAN_ALL_30S)) {
            MembershipPlanEntity p2 = new MembershipPlanEntity();
            p2.setId(PLAN_ALL_30S);
            p2.setCode("PLAN_ALL_30S");
            p2.setName("Thẻ 30 Buổi Toàn Chuỗi");
            p2.setDescription("30 buổi tập linh hoạt tại mọi chi nhánh trong 180 ngày");
            p2.setPrice(new BigDecimal("4500000.00"));
            p2.setPlanType("COMBO");
            p2.setDurationDays(180);
            p2.setTotalSessions(30);
            p2.setIsAllBranches(true);
            p2.setIsActive(true);
            planRepository.save(p2);
        }

        if (!planRepository.existsById(PLAN_SINGLE_10S)) {
            MembershipPlanEntity p3 = new MembershipPlanEntity();
            p3.setId(PLAN_SINGLE_10S);
            p3.setCode("PLAN_SINGLE_10S");
            p3.setName("Thẻ 10 Buổi (Đơn Chi Nhánh)");
            p3.setDescription("10 buổi tập tại cơ sở đăng ký trong 60 ngày");
            p3.setPrice(new BigDecimal("1800000.00"));
            p3.setPlanType("COMBO");
            p3.setDurationDays(60);
            p3.setTotalSessions(10);
            p3.setIsAllBranches(false);
            p3.setIsActive(true);
            planRepository.save(p3);
        }
        planRepository.flush();
    }

    // BCrypt hash của password "123456" - pre-computed để tránh encode nhiều lần
    private static final String HASH_123456 = "$2a$10$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lhWy";

    private void initUsers() {
        userRepository.findById(USER_SUPER_ADMIN).ifPresentOrElse(
                u -> {
                    // Existing accounts retain their status, role and branch.
                    if ("!NOPASS".equals(u.getPasswordHash())) u.setPasswordHash(HASH_123456);
                    userRepository.save(u);
                },
                () -> {
                    UserEntity admin = new UserEntity();
                    admin.setId(USER_SUPER_ADMIN);
                    admin.setPhone("0901000001");
                    admin.setEmail("admin@yogacenter.vn");
                    admin.setPasswordHash(HASH_123456);
                    admin.setFullName("Ban Giám Đốc (Super Admin)");
                    admin.setRoleId(ROLE_SUPER_ADMIN);
                    admin.setHomeBranchId(BRANCH_Q1);
                    admin.setIsActive(true);
                    userRepository.save(admin);
                }
        );

        userRepository.findById(USER_BRANCH_MANAGER).ifPresentOrElse(
                u -> {
                    // Existing accounts retain their status, role and branch.
                    if ("!NOPASS".equals(u.getPasswordHash())) u.setPasswordHash(HASH_123456);
                    userRepository.save(u);
                },
                () -> {
                    UserEntity mgr = new UserEntity();
                    mgr.setId(USER_BRANCH_MANAGER);
                    mgr.setPhone("0901000002");
                    mgr.setEmail("quanly.q1@yogacenter.vn");
                    mgr.setPasswordHash(HASH_123456);
                    mgr.setFullName("Trần Quang Hải (Quản Lý Q1)");
                    mgr.setRoleId(ROLE_MANAGER);
                    mgr.setHomeBranchId(BRANCH_Q1);
                    mgr.setIsActive(true);
                    userRepository.save(mgr);
                }
        );

        userRepository.findById(USER_RECEPTIONIST).ifPresentOrElse(
                u -> {
                    // Existing accounts retain their status, role and branch.
                    if ("!NOPASS".equals(u.getPasswordHash())) u.setPasswordHash(HASH_123456);
                    userRepository.save(u);
                },
                () -> {
                    UserEntity rec = new UserEntity();
                    rec.setId(USER_RECEPTIONIST);
                    rec.setPhone("0901000003");
                    rec.setEmail("letan.q1@yogacenter.vn");
                    rec.setPasswordHash(HASH_123456);
                    rec.setFullName("Nguyễn Thu Thảo (Lễ Tân Q1)");
                    rec.setRoleId(ROLE_RECEPTIONIST);
                    rec.setHomeBranchId(BRANCH_Q1);
                    rec.setIsActive(true);
                    userRepository.save(rec);
                }
        );

        userRepository.findById(USER_INSTRUCTOR).ifPresentOrElse(
                u -> {
                    // Existing accounts retain their status, role and branch.
                    if ("!NOPASS".equals(u.getPasswordHash())) u.setPasswordHash(HASH_123456);
                    userRepository.save(u);
                },
                () -> {
                    UserEntity ins = new UserEntity();
                    ins.setId(USER_INSTRUCTOR);
                    ins.setPhone("0901000004");
                    ins.setEmail("instructor.ananda@yogacenter.vn");
                    ins.setPasswordHash(HASH_123456);
                    ins.setFullName("Master Ananda (Ấn Độ)");
                    ins.setRoleId(ROLE_INSTRUCTOR);
                    ins.setHomeBranchId(BRANCH_Q1);
                    ins.setIsActive(true);
                    userRepository.save(ins);
                }
        );

        userRepository.findById(USER_STUDENT_AN).ifPresentOrElse(
                u -> {
                    // Existing accounts retain their status, role and branch.
                    if ("!NOPASS".equals(u.getPasswordHash())) u.setPasswordHash(HASH_123456);
                    userRepository.save(u);
                },
                () -> {
                    UserEntity st1 = new UserEntity();
                    st1.setId(USER_STUDENT_AN);
                    st1.setPhone("0909111222");
                    st1.setEmail("student.an@gmail.com");
                    st1.setPasswordHash(HASH_123456);
                    st1.setFullName("Vũ Hoàng An");
                    st1.setRoleId(ROLE_STUDENT);
                    st1.setHomeBranchId(BRANCH_Q1);
                    st1.setIsActive(true);
                    userRepository.save(st1);
                }
        );
        userRepository.flush();
    }

    private void initUserBranches() {
        if (!userBranchRepository.existsByUserIdAndBranchId(USER_INSTRUCTOR, BRANCH_Q1)) {
            UserBranchEntity ub1 = new UserBranchEntity();
            ub1.setUserId(USER_INSTRUCTOR);
            ub1.setBranchId(BRANCH_Q1);
            ub1.setIsPrimary(true);
            userBranchRepository.save(ub1);
        }

        if (!userBranchRepository.existsByUserIdAndBranchId(USER_INSTRUCTOR, BRANCH_CG)) {
            UserBranchEntity ub2 = new UserBranchEntity();
            ub2.setUserId(USER_INSTRUCTOR);
            ub2.setBranchId(BRANCH_CG);
            ub2.setIsPrimary(false);
            userBranchRepository.save(ub2);
        }

        if (!userBranchRepository.existsByUserIdAndBranchId(USER_RECEPTIONIST, BRANCH_Q1)) {
            UserBranchEntity ub3 = new UserBranchEntity();
            ub3.setUserId(USER_RECEPTIONIST);
            ub3.setBranchId(BRANCH_Q1);
            ub3.setIsPrimary(true);
            userBranchRepository.save(ub3);
        }

        if (!userBranchRepository.existsByUserIdAndBranchId(USER_SUPER_ADMIN, BRANCH_Q1)) {
            UserBranchEntity ub4 = new UserBranchEntity();
            ub4.setUserId(USER_SUPER_ADMIN);
            ub4.setBranchId(BRANCH_Q1);
            ub4.setIsPrimary(true);
            userBranchRepository.save(ub4);
        }

        if (!userBranchRepository.existsByUserIdAndBranchId(USER_BRANCH_MANAGER, BRANCH_Q1)) {
            UserBranchEntity ub5 = new UserBranchEntity();
            ub5.setUserId(USER_BRANCH_MANAGER);
            ub5.setBranchId(BRANCH_Q1);
            ub5.setIsPrimary(true);
            userBranchRepository.save(ub5);
        }
        userBranchRepository.flush();
    }

    private void initSchedules() {
        if (scheduleRepository.count() == 0) {
            Instant now = Instant.now();

            ClassScheduleEntity s1 = new ClassScheduleEntity();
            s1.setBranchId(BRANCH_Q1);
            s1.setRoomId(ROOM_LOTUS);
            s1.setClassTypeId(TYPE_HATHA);
            s1.setInstructorId(USER_INSTRUCTOR);
            s1.setStartTime(now.plus(1, ChronoUnit.HOURS));
            s1.setEndTime(now.plus(2, ChronoUnit.HOURS).plus(15, ChronoUnit.MINUTES));
            s1.setMaxCapacity(20);
            s1.setStatus("SCHEDULED");
            scheduleRepository.save(s1);

            ClassScheduleEntity s2 = new ClassScheduleEntity();
            s2.setBranchId(BRANCH_Q1);
            s2.setRoomId(ROOM_BAMBOO);
            s2.setClassTypeId(TYPE_VINYASA);
            s2.setInstructorId(USER_INSTRUCTOR);
            s2.setStartTime(now.plus(3, ChronoUnit.HOURS));
            s2.setEndTime(now.plus(4, ChronoUnit.HOURS));
            s2.setMaxCapacity(15);
            s2.setStatus("SCHEDULED");
            scheduleRepository.save(s2);

            ClassScheduleEntity s3 = new ClassScheduleEntity();
            s3.setBranchId(BRANCH_Q1);
            s3.setRoomId(ROOM_LOTUS);
            s3.setClassTypeId(TYPE_YIN);
            s3.setInstructorId(USER_INSTRUCTOR);
            s3.setStartTime(now.plus(6, ChronoUnit.HOURS));
            s3.setEndTime(now.plus(7, ChronoUnit.HOURS).plus(15, ChronoUnit.MINUTES));
            s3.setMaxCapacity(22);
            s3.setStatus("SCHEDULED");
            scheduleRepository.save(s3);
            scheduleRepository.flush();
        }
    }

    private void initMemberships() {
        if (membershipRepository.count() == 0) {
                PosOrderDto.CreateOrderReq orderReq = new PosOrderDto.CreateOrderReq(
                        BRANCH_Q1,
                        USER_STUDENT_AN,
                        PLAN_SINGLE_10S,
                        USER_RECEPTIONIST,
                        "Hợp đồng thẻ tập kích hoạt qua hệ thống POS"
                );
                PosOrderDto.OrderResp orderResp = posOrderService.createMembershipOrder(orderReq);

                PaymentDto.PayReq payReq = new PaymentDto.PayReq(
                        orderResp.orderId(),
                        "BANK_TRANSFER_QR",
                        UUID.fromString("01923e02-0000-7000-8000-000000000099"),
                        USER_RECEPTIONIST,
                        "VCB-TRANS-987654",
                        "Thanh toán chuyển khoản ngân hàng Vietcombank"
                );
                paymentService.processPayment(payReq);
                log.info("Initialized demo active membership via POS & Payment flow successfully.");

        }
    }
}

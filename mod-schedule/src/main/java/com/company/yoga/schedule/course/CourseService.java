package com.company.yoga.schedule.course;

import com.company.yoga.common.api.CommonErrorCode;
import com.company.yoga.common.exception.BusinessException;
import com.company.yoga.identity.account.service.AccessPolicy;
import com.company.yoga.membership.order.entity.*;
import com.company.yoga.membership.order.repository.*;
import com.company.yoga.schedule.booking.dto.ScheduleDto;
import com.company.yoga.schedule.booking.entity.*;
import com.company.yoga.schedule.booking.repository.*;
import com.company.yoga.schedule.booking.service.ClassScheduleService;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class CourseService {
    private final CourseDao dao;
    private final AccessPolicy access;
    private final ClassScheduleService scheduleService;
    private final ClassScheduleRepository schedules;
    private final BookingRepository bookings;
    private final OrderRepository orders;
    private final OrderItemRepository items;
    private static final String CLASS_SELECT="""
        SELECT c.*,p.name AS course_name,p.description,b.name AS branch_name,
        (SELECT min(start_time) FROM yoga.class_schedules WHERE course_class_id=c.id AND status<>'CANCELLED') AS start_time,
        (SELECT max(end_time) FROM yoga.class_schedules WHERE course_class_id=c.id AND status<>'CANCELLED') AS end_time,
        (SELECT count(*) FROM yoga.course_enrollments WHERE course_class_id=c.id AND status<>'CANCELLED') AS enrolled_count,
        (SELECT count(*) FROM yoga.class_schedules WHERE course_class_id=c.id AND status<>'CANCELLED' AND end_time<CURRENT_TIMESTAMP) AS held_sessions
        FROM yoga.course_classes c JOIN yoga.courses p ON p.id=c.course_id JOIN yoga.branches b ON b.id=c.branch_id
        """;
    private Map<String,Object> one(String sql,Map<String,?> args) {
        var rows=dao.rows(sql,args);
        if(rows.isEmpty()) throw new BusinessException(CommonErrorCode.NOT_FOUND);
        return rows.getFirst();
    }
    private void reject(boolean condition,String message) { if(condition) throw new BusinessException(CommonErrorCode.CONFLICT,message); }
    private UUID uuid(Map<String,Object> row,String key) { return (UUID)row.get(key); }

    @Transactional(readOnly=true)
    public List<Map<String,Object>> courses() {
        access.actor();
        var courses = dao.rows("SELECT * FROM yoga.courses WHERE is_active ORDER BY name",Map.of());
        var plans = dao.rows("SELECT course_id, session_number, title, content, duration_minutes FROM yoga.course_program_sessions ORDER BY course_id, session_number",Map.of());
        for (var course : courses) course.put("sessions",plans.stream().filter(p -> Objects.equals(p.get("courseId"),course.get("id"))).toList());
        return courses;
    }

    @Transactional
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    public Map<String,Object> createCourse(CourseDto.CreateCourse r) {
        access.actor();
        reject(r.sessions()==null || r.sessions().size()!=r.totalSessions(),"Kế hoạch từng buổi phải khớp tổng số buổi của chương trình.");
        reject(r.sessions().stream().anyMatch(s -> s == null || s.title()==null || s.title().isBlank() || s.content()==null || s.content().isBlank() || s.durationMinutes()<15 || s.durationMinutes()>240),"Mỗi buổi cần có tên, nội dung và thời lượng từ 15 đến 240 phút.");
        UUID id=UUID.randomUUID();
        var a=new HashMap<String,Object>();
        a.put("id",id); a.put("code",r.code().trim()); a.put("name",r.name().trim()); a.put("description",r.description());
        a.put("type",r.classTypeId()); a.put("total",r.totalSessions()); a.put("duration",r.defaultDurationMinutes()); a.put("fee",r.defaultFee());
        dao.update("INSERT INTO yoga.courses(id,code,name,description,class_type_id,total_sessions,default_duration_minutes,default_fee) VALUES(:id,:code,:name,:description,:type,:total,:duration,:fee)",a);
        for (int i=0;i<r.sessions().size();i++) {
            var session=r.sessions().get(i);
            dao.update("INSERT INTO yoga.course_program_sessions(course_id,session_number,title,content,duration_minutes) VALUES(:course,:number,:title,:content,:duration)",
                Map.of("course",id,"number",i+1,"title",session.title().trim(),"content",session.content().trim(),"duration",session.durationMinutes()));
        }
        var course = new HashMap<>(one("SELECT * FROM yoga.courses WHERE id=:id",Map.of("id",id)));
        course.put("sessions",r.sessions());
        return course;
    }

    @Transactional(readOnly=true)
    public List<Map<String,Object>> classes(UUID branchId) {
        var actor=access.actor(); String role=access.role(actor);
        if(!Set.of("SUPER_ADMIN","STUDENT").contains(role)) {
            if(branchId==null) branchId=actor.getHomeBranchId();
            access.requireStaffBranch(branchId);
        }
        var a=new HashMap<String,Object>(); a.put("branch",branchId); a.put("student",role.equals("STUDENT"));
        return dao.rows(CLASS_SELECT+" WHERE (CAST(:branch AS uuid) IS NULL OR c.branch_id=CAST(:branch AS uuid)) AND (:student=false OR c.status<>'DRAFT') ORDER BY c.created_at DESC",a);
    }

    @Transactional(readOnly=true)
    public Map<String,Object> detail(UUID id) {
        var c=one(CLASS_SELECT+" WHERE c.id=:id",Map.of("id",id));
        var actor=access.actor();
        if("STUDENT".equals(access.role(actor))) reject("DRAFT".equals(c.get("status")),"Lớp chưa mở đăng ký.");
        else access.requireStaffBranch(uuid(c,"branchId"));
        c.put("sessions",dao.rows("""
            SELECT s.id,s.session_number,s.session_title,s.start_time,s.end_time,s.status,s.room_id,s.instructor_id,
            r.name AS room_name,u.full_name AS instructor_name,s.booked_count,s.max_capacity,p.content AS planned_content,p.duration_minutes AS planned_duration_minutes
            FROM yoga.class_schedules s JOIN yoga.rooms r ON r.id=s.room_id JOIN yoga.users u ON u.id=s.instructor_id
            LEFT JOIN yoga.course_class_program_sessions p ON p.course_class_id=s.course_class_id AND p.session_number=s.session_number
            WHERE s.course_class_id=:id ORDER BY s.start_time
            """,Map.of("id",id)));
        return c;
    }

    @Transactional(readOnly=true)
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','BRANCH_MANAGER')")
    public List<CourseDto.Conflict> preview(CourseDto.CreateClass r) {
        access.requireStaffBranch(r.branchId());
        var course=one("SELECT * FROM yoga.courses WHERE id=:id AND is_active",Map.of("id",r.courseId()));
        reject(((Number)course.get("totalSessions")).intValue()!=r.sessions().size(),"Số buổi phải đúng với chương trình khóa.");
        List<CourseDto.Conflict> result=new ArrayList<>();
        for(int i=0;i<r.sessions().size();i++) {
            var s=r.sessions().get(i); int n=i+1;
            if(!s.startTime().isAfter(Instant.now()) || !s.endTime().isAfter(s.startTime())) result.add(new CourseDto.Conflict(n,"Ngày giờ buổi học không hợp lệ."));
            var args=Map.of("room",s.roomId(),"branch",r.branchId(),"capacity",r.maxCapacity(),"teacher",s.instructorId());
            if(dao.count("SELECT count(*) FROM yoga.rooms WHERE id=:room AND branch_id=:branch AND is_active AND max_capacity>=:capacity",args)==0) result.add(new CourseDto.Conflict(n,"Phòng không phù hợp chi nhánh hoặc sĩ số."));
            if(dao.count("SELECT count(*) FROM yoga.users u JOIN yoga.roles r ON r.id=u.role_id WHERE u.id=:teacher AND u.is_active AND r.code='INSTRUCTOR'",args)==0) result.add(new CourseDto.Conflict(n,"Huấn luyện viên không hợp lệ."));
            if(schedules.existsOverlappingRoomSchedule(s.roomId(),s.startTime(),s.endTime())) result.add(new CourseDto.Conflict(n,"Phòng đã có lớp trong khoảng giờ này."));
            if(schedules.existsOverlappingInstructorSchedule(s.instructorId(),s.startTime(),s.endTime())) result.add(new CourseDto.Conflict(n,"Huấn luyện viên đã có lịch trong khoảng giờ này."));
            for(int j=0;j<i;j++) { var prior=r.sessions().get(j);
                if(s.startTime().isBefore(prior.endTime()) && prior.startTime().isBefore(s.endTime()) &&
                    (s.roomId().equals(prior.roomId()) || s.instructorId().equals(prior.instructorId())))
                    result.add(new CourseDto.Conflict(n,"Trùng phòng hoặc huấn luyện viên với buổi "+(j+1)+" của khóa."));
            }
        }
        return result;
    }

    @Transactional
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','BRANCH_MANAGER')")
    public Map<String,Object> createClass(CourseDto.CreateClass r) {
        reject(!preview(r).isEmpty(),"Lịch còn xung đột. Vui lòng kiểm tra lại các buổi.");
        var course=one("SELECT * FROM yoga.courses WHERE id=:id",Map.of("id",r.courseId()));
        UUID id=UUID.randomUUID();
        dao.update("INSERT INTO yoga.course_classes(id,course_id,branch_id,code,name,planned_sessions,max_capacity,tuition_fee) VALUES(:id,:course,:branch,:code,:name,:total,:capacity,:fee)",
            Map.of("id",id,"course",r.courseId(),"branch",r.branchId(),"code",r.code().trim(),"name",r.name().trim(),"total",r.sessions().size(),"capacity",r.maxCapacity(),"fee",r.tuitionFee()));
        dao.update("INSERT INTO yoga.course_class_program_sessions(course_class_id,session_number,title,content,duration_minutes) SELECT :class,session_number,title,content,duration_minutes FROM yoga.course_program_sessions WHERE course_id=:course",
            Map.of("class",id,"course",r.courseId()));
        var sorted=r.sessions().stream().sorted(Comparator.comparing(CourseDto.Session::startTime)).toList();
        for(int i=0;i<sorted.size();i++) { var s=sorted.get(i);
            scheduleService.createCourseSchedule(new ScheduleDto.CreateReq(r.branchId(),s.roomId(),uuid(course,"classTypeId"),s.instructorId(),s.startTime(),s.endTime(),r.maxCapacity()),id,i+1,s.title());
        }
        dao.update("UPDATE yoga.course_classes SET status='OPEN' WHERE id=:id",Map.of("id",id));
        return detail(id);
    }

    @Transactional
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','BRANCH_MANAGER','RECEPTIONIST','STUDENT')")
    public Map<String,Object> enroll(UUID classId,UUID studentId) {
        var c=one("SELECT * FROM yoga.course_classes WHERE id=:id FOR UPDATE",Map.of("id",classId));
        access.requireBooking(studentId,uuid(c,"branchId"));
        var actor=access.actor();
        // Expire abandoned reservations before checking capacity. Payment confirmation uses the same order lock.
        for(var row:dao.rows("SELECT i.order_id FROM yoga.course_enrollments e JOIN yoga.order_items i ON i.id=e.source_order_item_id WHERE e.course_class_id=:id AND e.status='PENDING_PAYMENT' AND e.reserved_until<=CURRENT_TIMESTAMP AND NOT EXISTS(SELECT 1 FROM yoga.payments p WHERE p.order_id=i.order_id AND p.payment_status<>'FAILED')",Map.of("id",classId)))
            dao.rows("SELECT yoga.cancel_pending_course_order(:id)",Map.of("id",uuid(row,"orderId")));
        reject(!"OPEN".equals(c.get("status")),"Lớp không còn mở đăng ký.");
        reject(dao.count("SELECT count(*) FROM yoga.course_enrollments WHERE course_class_id=:id AND student_id=:student AND status<>'CANCELLED'",Map.of("id",classId,"student",studentId))>0,"Học viên đã đăng ký lớp này.");
        reject(dao.count("SELECT count(*) FROM yoga.course_enrollments WHERE course_class_id=:id AND status<>'CANCELLED'",Map.of("id",classId))>=((Number)c.get("maxCapacity")).longValue(),"Lớp đã đủ sĩ số.");
        var sessions=dao.rows("SELECT * FROM yoga.class_schedules WHERE course_class_id=:id AND status<>'CANCELLED' ORDER BY id FOR UPDATE",Map.of("id",classId));
        reject(sessions.size()!=((Number)c.get("plannedSessions")).intValue(),"Lịch khóa chưa đủ số buổi.");
        Instant first=sessions.stream().map(s->(Instant)s.get("startTime")).min(Comparator.naturalOrder()).orElseThrow();
        reject(!first.isAfter(Instant.now()),"Khóa đã bắt đầu, không nhận đăng ký mới.");
        for(var s:sessions) reject(bookings.existsStudentTimeConflict(studentId,uuid(s,"id"),(Instant)s.get("startTime"),(Instant)s.get("endTime")),"Học viên đã có lịch trùng với một buổi của khóa.");
        BigDecimal fee=(BigDecimal)c.get("tuitionFee");
        OrderEntity order=new OrderEntity(); order.setOrderCode("ORD-"+UUID.randomUUID()); order.setBranchId(uuid(c,"branchId"));
        order.setCustomerId(studentId); order.setCashierId(actor.getId()); order.setSubtotal(fee); order.setTotalAmount(fee); order=orders.saveAndFlush(order);
        OrderItemEntity item=new OrderItemEntity(); item.setOrderId(order.getId()); item.setItemType("COURSE_CLASS"); item.setItemId(classId); item.setCourseClassId(classId);
        item.setItemNameSnapshot((String)c.get("name")); item.setUnitPriceSnapshot(fee); item.setLineTotal(fee); item=items.saveAndFlush(item);
        UUID enrollment=UUID.randomUUID(); Instant until=Instant.now().plus(Duration.ofHours(24)); if(until.isAfter(first)) until=first;
        dao.update("INSERT INTO yoga.course_enrollments(id,enrollment_code,course_class_id,student_id,source_order_item_id,tuition_fee_snapshot,reserved_until) VALUES(:id,:code,:class,:student,:item,:fee,:until)",
            Map.of("id",enrollment,"code","CE-"+UUID.randomUUID(),"class",classId,"student",studentId,"item",item.getId(),"fee",fee,"until",java.sql.Timestamp.from(until)));
        for(var s:sessions) {
            UUID scheduleId=uuid(s,"id"); var occupied=bookings.occupiedMats(scheduleId);
            int mat=java.util.stream.IntStream.rangeClosed(1,((Number)s.get("maxCapacity")).intValue()).filter(n->!occupied.contains(n)).findFirst().orElseThrow(()->new BusinessException(CommonErrorCode.CONFLICT,"Một buổi đã kín chỗ."));
            BookingEntity b=new BookingEntity(); b.setBookingCode("BK-"+UUID.randomUUID()); b.setScheduleId(scheduleId); b.setStudentId(studentId); b.setCourseEnrollmentId(enrollment); b.setMatNumber(mat); bookings.saveAndFlush(b);
        }
        return enrollment(enrollment);
    }

    private static final String ENROLL_SELECT="""
        SELECT e.*,i.order_id,o.status AS payment_status,o.total_amount,u.full_name AS student_name,u.phone AS student_phone,
        c.name AS class_name,c.code AS class_code,c.branch_id,p.name AS course_name,c.planned_sessions,b.name AS branch_name,
        (SELECT min(start_time) FROM yoga.class_schedules WHERE course_class_id=c.id AND status<>'CANCELLED') AS start_time,
        (SELECT max(end_time) FROM yoga.class_schedules WHERE course_class_id=c.id AND status<>'CANCELLED') AS end_time,
        (SELECT count(*) FROM yoga.attendance_records a JOIN yoga.class_schedules s ON s.id=a.schedule_id WHERE a.course_enrollment_id=e.id AND s.status<>'CANCELLED') AS attended_sessions,
        (SELECT count(*) FROM yoga.class_schedules WHERE course_class_id=c.id AND status<>'CANCELLED' AND end_time<CURRENT_TIMESTAMP) AS held_sessions
        FROM yoga.course_enrollments e JOIN yoga.order_items i ON i.id=e.source_order_item_id JOIN yoga.orders o ON o.id=i.order_id
        JOIN yoga.course_classes c ON c.id=e.course_class_id JOIN yoga.courses p ON p.id=c.course_id
        JOIN yoga.users u ON u.id=e.student_id JOIN yoga.branches b ON b.id=c.branch_id
        """;
    @Transactional(readOnly=true)
    public List<Map<String,Object>> mine() { return dao.rows(ENROLL_SELECT+" WHERE e.student_id=:student ORDER BY e.enrolled_at DESC",Map.of("student",access.actor().getId())); }
    @Transactional(readOnly=true)
    public Map<String,Object> enrollment(UUID id) {
        var e=one(ENROLL_SELECT+" WHERE e.id=:id",Map.of("id",id));
        access.requireBooking(uuid(e,"studentId"),uuid(e,"branchId"));
        if("INSTRUCTOR".equals(access.role(access.actor()))) reject(dao.count("SELECT count(*) FROM yoga.class_schedules WHERE course_class_id=:id AND instructor_id=:actor",Map.of("id",uuid(e,"courseClassId"),"actor",access.actor().getId()))==0,"Chỉ xem học viên lớp được phân công.");
        e.put("sessions",dao.rows("""
            SELECT s.id,s.session_number,s.session_title,s.start_time,s.end_time,s.status,r.name AS room_name,u.full_name AS instructor_name,
            b.id AS booking_id,b.booking_code,b.status AS booking_status,a.checked_in_at,
            CASE WHEN s.status='CANCELLED' OR b.status='CANCELLED' THEN 'CANCELLED' WHEN a.id IS NOT NULL THEN 'ATTENDED'
             WHEN s.end_time+INTERVAL '12 hours'<CURRENT_TIMESTAMP THEN 'ABSENT' ELSE 'UPCOMING' END AS attendance_state
            FROM yoga.class_schedules s JOIN yoga.rooms r ON r.id=s.room_id JOIN yoga.users u ON u.id=s.instructor_id
            LEFT JOIN yoga.bookings b ON b.schedule_id=s.id AND b.course_enrollment_id=:id
            LEFT JOIN yoga.attendance_records a ON a.booking_id=b.id
            WHERE s.course_class_id=:class ORDER BY s.start_time
            """,Map.of("id",id,"class",uuid(e,"courseClassId"))));
        return e;
    }
    @Transactional(readOnly=true)
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','BRANCH_MANAGER','RECEPTIONIST','INSTRUCTOR')")
    public List<Map<String,Object>> roster(UUID id) {
        var c=detail(id); access.requireStaffBranch(uuid(c,"branchId"));
        if("INSTRUCTOR".equals(access.role(access.actor()))) reject(dao.count("SELECT count(*) FROM yoga.class_schedules WHERE course_class_id=:id AND instructor_id=:actor",Map.of("id",id,"actor",access.actor().getId()))==0,"Chỉ xem học viên lớp được phân công.");
        return dao.rows(ENROLL_SELECT+" WHERE e.course_class_id=:id ORDER BY u.full_name",Map.of("id",id));
    }

    @Transactional
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','BRANCH_MANAGER')")
    public void changeSession(UUID classId,UUID sessionId,CourseDto.ChangeSession r) {
        var c=one("SELECT * FROM yoga.course_classes WHERE id=:id FOR UPDATE",Map.of("id",classId)); access.requireStaffBranch(uuid(c,"branchId"));
        var s=schedules.findByIdWithLock(sessionId).orElseThrow(()->new BusinessException(CommonErrorCode.NOT_FOUND));
        reject(!classId.equals(s.getCourseClassId()) || !s.getStartTime().isAfter(Instant.now()),"Chỉ thay đổi buổi tương lai của lớp.");
        reject(!r.startTime().isAfter(Instant.now()) || !r.endTime().isAfter(r.startTime()),"Ngày giờ không hợp lệ.");
        var args=Map.of("id",sessionId,"room",r.roomId(),"teacher",r.instructorId(),"start",java.sql.Timestamp.from(r.startTime()),"end",java.sql.Timestamp.from(r.endTime()));
        reject(dao.count("SELECT count(*) FROM yoga.class_schedules WHERE id<>:id AND status<>'CANCELLED' AND (room_id=:room OR instructor_id=:teacher) AND start_time<:end AND end_time>:start",args)>0,"Trùng phòng hoặc huấn luyện viên.");
        s.setRoomId(r.roomId()); s.setInstructorId(r.instructorId()); s.setStartTime(r.startTime()); s.setEndTime(r.endTime()); s.setSessionTitle(r.title()); schedules.saveAndFlush(s);
    }
}

# Review backend — 03/10/2026

Phạm vi: app, common, mod-identity, mod-branch, mod-membership, mod-schedule; đối chiếu entity và nghiệp vụ với migrations V1–V3 và provision_runtime.sql. Đây là review mã nguồn, chưa kiểm chứng trên database đang triển khai. Không sửa mã nghiệp vụ, không khởi động ứng dụng hoặc ghi vào database từ xa.

## Kết quả kiểm tra

Chạy thành công `mvnw.cmd -o -Dmaven.repo.local=C:\Users\Admin\.m2\repository test`: 14 tests, 0 failures, 0 errors; toàn bộ reactor SUCCESS. Các test dùng mock repository, không chạy PostgreSQL, trigger, Spring Security hoặc transaction advisor. Common, branch, identity và app chưa có test. Kết quả này không xác nhận các luồng tích hợp hoạt động đúng. Maven sử dụng các class đã biên dịch sẵn khi báo up to date.

P0: cần xử lý ngay nếu đã triển khai với cấu hình mặc định. P1: ảnh hưởng bảo mật hoặc nghiệp vụ chính. P2: lỗi chức năng, độ ổn định hoặc hiệu năng.

## Phát hiện

### 1. [P0] Secret database và JWT nằm trong source

Vị trí: `app/src/main/resources/application.yml:13–15,50`; `mod-identity/.../service/JwtTokenProvider.java:20`.

Datasource có mật khẩu mặc định và tài khoản postgres; JWT có khóa ký mặc định cố định tại cả YAML và provider. Nếu môi trường không override JWT_SECRET, người có source có thể tự ký token mang role SUPER_ADMIN. Filter tin role trong token, nên tác động là chiếm quyền API. Không cần chứng minh bằng cách tạo token tấn công.

Khắc phục: bắt buộc cấu hình secret từ môi trường, bỏ mọi fallback, thay các secret đã lộ nếu từng dùng; dùng tài khoản runtime có quyền giới hạn. Không lặp lại giá trị secret trong báo cáo.

### 2. [P1] Học viên có thể tạo lịch và sửa giá gói tập

Vị trí: `mod-membership/.../controller/MembershipPlanController.java:40–52`; `mod-membership/.../service/MembershipPlanService.java:36,57`; `mod-schedule/.../controller/ClassScheduleController.java:49–52` và service `createSchedule`.

Các thao tác POST/PUT này không có kiểm tra role trong controller hoặc service. SecurityConfig chỉ yêu cầu authenticated. Tài khoản STUDENT tự đăng ký có thể tạo/sửa gói, thay giá hoặc ngừng bán, và tạo ca học khi gửi dữ liệu hợp lệ. Trigger kiểm tra tính hợp lệ dữ liệu không kiểm tra quyền của người gọi HTTP.

Khắc phục: giới hạn role quản trị cho từng thao tác, kiểm tra phạm vi chi nhánh khi tạo lịch; thêm test HTTP cho STUDENT và nhân sự khác chi nhánh.

### 3. [P1] Thiếu kiểm tra chủ sở hữu booking, waitlist và thẻ tập

Vị trí: `mod-schedule/.../service/BookingService.java:46,119,161,177`; `WaitlistServiceImpl.java:50,130,221`; `mod-membership/.../service/StudentMembershipService.java:21,28`.

studentId do request cung cấp không được đối chiếu với tài khoản đăng nhập. cancelBooking chỉ cần bookingId, không kiểm tra người sở hữu. cancelWaitlist đối chiếu studentId trong request với bản ghi, nhưng cả hai đầu vào vẫn do caller cung cấp. Khi biết ID, một học viên có thể đọc thẻ/lịch/hàng chờ và hủy booking của người khác; lấy membershipId qua API đọc còn cho phép đặt chỗ dưới danh tính người khác.

Khắc phục: lấy studentId từ principal cho thao tác tự phục vụ; chỉ cho nhân sự được phân quyền thao tác thay học viên, và kiểm tra chi nhánh.

### 4. [P1] POS/check-in không giới hạn chi nhánh và tin danh tính nhân sự từ request

Vị trí: `mod-membership/.../order/service/PosOrderService.java:43–46`; `PaymentService.java:49–76`; `mod-schedule/.../service/CheckInService.java:100–106`.

Controller có kiểm tra role nhưng không kiểm tra caller được giao chi nhánh của order/schedule hay không. cashierId, confirmedBy và checkedInBy lấy trực tiếp từ body. Nhân sự một chi nhánh có thể thao tác chi nhánh khác, đồng thời ghi nhận nghiệp vụ dưới tên nhân sự khác có ID hợp lệ. Foreign key không chứng minh người thực hiện chính là người đăng nhập.

Khắc phục: lấy actor từ principal, kiểm tra user_branches/homeBranch và phạm vi lớp được giao cho INSTRUCTOR; xử lý ngoại lệ SUPER_ADMIN rõ ràng.

### 5. [P1] Seed demo chạy ở mọi môi trường và bật lại tài khoản bị khóa

Vị trí: `app/.../config/DatabaseDemoDataInitializer.java:40–42,95–115,309–420`; `mod-identity/.../service/AuthService.java:41–44`.

CommandLineRunner không bị giới hạn bằng profile hoặc property. Mỗi lần startup, initUsers bật isActive=true, gán lại role và homeBranch của các tài khoản seed đã tồn tại. Vì vậy việc khóa hoặc đổi quyền các tài khoản này bị đảo ngược sau restart. Seed cũng tạo tài khoản quản trị với hash cố định trong source. AuthService còn có nhánh chấp nhận mật khẩu cố định cho hash !NOPASS, không giới hạn vào môi trường demo.

Khắc phục: seed chỉ chạy khi bật cấu hình dev/demo rõ ràng, không tự sửa quyền/trạng thái tài khoản hiện hữu; bỏ cơ chế đăng nhập !NOPASS khỏi runtime.

### 6. [P1] Hủy booking không đôn hàng chờ vì sĩ số trong Hibernate bị cũ

Vị trí: `mod-schedule/.../service/BookingService.java:127–143`; `WaitlistServiceImpl.java:160–167`; database `V3__integrity_and_transaction_rules.sql:186`.

cancelBooking load và khóa schedule khi lớp còn đầy. saveAndFlush booking kích hoạt trigger giảm booked_count trong database. autoPromoteTopCandidate chạy cùng transaction và truy vấn lại schedule, nhưng cùng persistence context vẫn giữ entity đã load, không refresh trường bookedCount. Kiểm tra bookedCount >= maxCapacity trả về empty dù database vừa có chỗ trống. Luồng hủy từ lớp đầy là tình huống cần đôn hàng chờ thường gặp nhất.

Khắc phục: refresh schedule sau thay đổi từ trigger hoặc truy vấn giá trị mới theo cách không dùng entity cache. Kiểm chứng bằng integration test PostgreSQL: lớp đầy, có WAITING, hủy một booking và xác nhận một booking mới cùng số dư đúng.

### 7. [P1] Nuốt exception không bảo vệ được transaction hủy booking

Vị trí: `mod-schedule/.../service/BookingService.java:142–146`; `WaitlistServiceImpl.java:158,203–208`.

autoPromoteTopCandidate dùng @Transactional mặc định, tham gia transaction hủy. Nếu runtime exception thoát khỏi service này, transaction có thể bị đánh dấu rollback-only trước khi caller catch; lỗi SQL PostgreSQL còn làm transaction thất bại. Caller log “non-fatal” rồi trả DTO nhưng commit có thể rollback và báo lỗi. Ứng dụng không thực sự bảo đảm “hủy thành công dù promotion lỗi”.

Khắc phục: chọn rõ tính nguyên tử của cancel + promotion; nếu cần độc lập thì chạy promotion sau commit trong transaction riêng, có retry. Không chỉ thêm catch.

### 8. [P1] Câu lệnh ghi ORM không khớp quyền runtime đã provision

Vị trí: `database/scripts/provision_runtime.sql:16–25`; `common/.../entity/BaseAuditEntity.java:18–33`; `mod-membership/.../order/service/PaymentService.java:79–80` và `OrderEntity.java`.

Quyền INSERT class_schedules không cho created_at/updated_at, nhưng entity kế thừa audit và ORM gửi cả hai khi tạo ca. UPDATE bookings/waitlists chỉ cho vài cột nghiệp vụ, trong khi BaseAuditEntity tự ghi updated_at; cancel còn ghi cancelled_at. PaymentService ghi order.status=PAID trong ứng dụng dù runtime không có quyền UPDATE status và trigger đã thực hiện việc đó. OrderEntity không dùng DynamicUpdate, nên SQL update còn có thể chứa nhiều cột không được cấp quyền. Khi chuyển từ postgres mặc định sang tài khoản yoga_app đúng thiết kế, các luồng này sẽ bị permission denied.

Khắc phục: thống nhất mapping với cột do database quản lý và quyền cột; bỏ cập nhật dư thừa đã thuộc trigger. Chạy integration tests bằng chính role runtime, không bằng owner.

### 9. [P2] Audit actor được gán trước khi transaction bắt đầu

Vị trí: `app/.../config/AuditingActorAspect.java:15,27–36`.

Aspect có @Order(10), trong khi không có cấu hình đổi order của transaction advisor. Với order mặc định, before advice chạy trước bước mở transaction của service. executeUpdate qua EntityManager có thể ném TransactionRequiredException; exception bị nuốt ở DEBUG và service vẫn chạy, khiến audit log thiếu actor_id. Trường hợp đã có transaction từ caller có thể hoạt động, nhưng HTTP gọi service trực tiếp không được bảo đảm.

Khắc phục: gán context sau khi transaction thực sự mở, thiết lập thứ tự advisor rõ ràng hoặc dùng cơ chế transaction phù hợp; test đọc actor_id từ audit_logs sau request có xác thực.

### 10. [P1] JWT tiếp tục cấp quyền sau khi khóa hoặc đổi role của tài khoản

Vị trí: `app/.../config/JwtAuthenticationFilter.java:38–53`; `application.yml:51`.

Filter chỉ kiểm tra chữ ký/hạn và lấy role từ claims, không đối chiếu user.isActive, role hiện tại hay phiên bản token. Token cũ vẫn được chấp nhận đến hết hạn mặc định 24 giờ, kể cả khi tài khoản đã bị vô hiệu hóa hoặc hạ quyền trong database. Một số service tự đọc role hiện tại nhưng POS/check-in và BranchService dựa vào authority trong token.

Khắc phục: kiểm tra trạng thái tài khoản và cơ chế vô hiệu hóa token/phiên; kiểm thử token đã cấp trước lúc khóa hoặc đổi quyền.

### 11. [P2] Idempotency thanh toán không kiểm tra request tương ứng và không xử lý cạnh tranh

Vị trí: `mod-membership/.../order/service/PaymentService.java:33–45,70`.

Có payment cùng key thì trả thành công ngay, không kiểm tra orderId/phương thức/caller có khớp. Dùng lại key của đơn A cho đơn B trả receipt đơn A nhưng không thu tiền đơn B. Hai request cùng key đồng thời có thể cùng vượt qua bước SELECT; unique constraint/trigger chặn ghi đúp nhưng request sau nhận exception thay vì receipt cũ. activatedMembershipCode ở retry còn là chuỗi IDEMPOTENT_RETRY, được frontend hiển thị ở vị trí mã thẻ.

Khắc phục: từ chối key gắn payload khác; xử lý cạnh tranh bằng transaction/retry có kiểm soát; trả kết quả nghiệp vụ nhất quán cho retry. Trigger hiện có giúp chặn thu tiền vượt số dư, nên không kết luận rằng cạnh tranh này gây thu tiền đúp.

### 12. [P2] Mã booking/order/payment/membership có thể trùng khi tạo đồng thời

Vị trí: `BookingService.java:94`; `WaitlistServiceImpl.java:195`; `PosOrderService.java:41,69`; `PaymentService.java:57`.

Các mã dùng prefix + System.currentTimeMillis, trong khi database có UNIQUE toàn bảng. Hai request cùng mili giây ở hai lịch/chi nhánh hoặc hai instance tạo cùng mã, khiến một transaction thất bại. Khóa một lịch không giải quyết tính duy nhất trên các lịch khác nhau.

Khắc phục: dùng UUID/sequence hoặc bộ sinh mã có bảo đảm duy nhất; không dựa riêng vào đồng hồ hệ thống.

### 13. [P2] Filter thời gian bị bỏ qua và API danh sách có N+1

Vị trí: `mod-schedule/.../service/ClassScheduleService.java:30–40,65–78`; `BookingService.java:177–215`; `WaitlistServiceImpl.java:221–254`.

Chỉ áp dụng start/end khi cả ba branchId/start/end đều có. GET schedules?start=...&end=... không có branchId sẽ trả toàn bộ lịch, kể cả ngoài khoảng; gửi một đầu mút thời gian cũng bị bỏ qua. Nhánh fallback tải toàn bảng rồi lọc trong Java. Mapping mỗi lịch lại query loại lớp, instructor và room; booking/waitlist details còn query schedule/branch theo từng dòng, chưa có phân trang.

Khắc phục: query database theo từng bộ filter được hỗ trợ, validate start <= end, phân trang và dùng projection/join hoặc batch lookup.

### 14. [P2] Route công khai gói tập không khớp route thực tế; lỗi request/DB thường thành 500

Vị trí: `app/.../config/SecurityConfig.java:54`; `MembershipPlanController.java:22`; `common/.../exception/GlobalExceptionHandler.java:57–75`.

SecurityConfig mở GET /api/v1/memberships/plans/** nhưng controller/frontend dùng /api/v1/membership-plans. Khách chưa đăng nhập vì vậy không truy cập được danh mục như comment cấu hình dự định. Handler có DuplicateKeyException nhưng chưa xử lý DataIntegrityViolationException thường xuất hiện từ JPA; trigger RAISE EXCEPTION cũng không được ánh xạ thành lỗi nghiệp vụ. JSON hỏng hoặc UUID/date sai có thể rơi vào catch-all Exception và trả 500 thay vì 400; ràng buộc đầu vào nhiều DTO còn dừng ở NotNull/NotBlank.

Khắc phục: sửa matcher GET đúng route, giữ phân quyền write riêng; xử lý lỗi parse và constraint theo mã lỗi ổn định, bổ sung validate đầu vào. Không trả chi tiết SQL hoặc secret cho client.

## Thứ tự xử lý đề xuất

1. Secret/JWT, phân quyền role/chủ sở hữu/chi nhánh, seed demo và hiệu lực tài khoản.
2. Cache sau trigger, ranh giới transaction promotion, mapping với role runtime và audit actor.
3. Idempotency, mã duy nhất, filter/query/phân trang và ánh xạ lỗi HTTP.
4. Bổ sung test HTTP security và integration PostgreSQL dùng migrations hiện tại cùng tài khoản runtime giới hạn quyền.

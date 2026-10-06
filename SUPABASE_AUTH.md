# Đăng ký và đăng nhập qua Supabase Auth

## Luồng đã chuyển

Frontend gọi Supabase SDK signUp/signInWithPassword bằng email và mật khẩu. Supabase quản lý token, refresh token, persistence, xác nhận email và khôi phục mật khẩu. Backend không còn endpoint cấp JWT hoặc kiểm tra password_hash để đăng nhập. Token local yoga_token cũ được bỏ qua.

Backend gửi access token tới /auth/v1/user của đúng project để xác minh danh tính. Role và chi nhánh luôn lấy từ yoga.users/yoga.roles/user_branches, không lấy từ user_metadata. Mỗi request xác minh online, có timeout kết nối 5 giây và đọc 10 giây; khi Supabase không truy cập được request không được cấp quyền. Access token vẫn chịu chính sách TTL/revocation của Supabase; logout frontend không tạo cơ chế thu hồi JWT tức thì riêng.

Hồ sơ liên kết qua users.supabase_user_id; giữ business user ID cho dữ liệu hiện hữu. Tài khoản tự đăng ký chỉ được cấp STUDENT. Metadata phone chỉ là thông tin liên hệ, không chứng minh quyền sở hữu một hồ sơ cũ. Nhân sự không tự nhận role bằng email/metadata. Nếu có hồ sơ nhân sự cũ cần operator liên kết rõ ràng.

## Đã thao tác trên project hiện tại

Đã kiểm tra đúng project cagwkxiccmayrtajwdtc, kết nối pooler Singapore như cấu hình hoạt động trước đây. Đã áp dụng V4: thêm cột nullable supabase_user_id và unique index; không thay ID nghiệp vụ hoặc chạy lại baseline.

Theo yêu cầu xóa 7 tài khoản cũ: 4 tài khoản không có lịch sử nghiệp vụ đã xóa khỏi yoga.users cùng phân công chi nhánh. 3 tài khoản đang được tham chiếu bởi order/payment/member/booking/schedule/rate policy đã vô hiệu hóa và ẩn danh (password_hash=!DELETED); giữ row ID làm liên kết lịch sử và ẩn khỏi API danh sách user. Không tạo hoặc gửi email cho tài khoản Auth cũ. Demo initializer không còn gọi các bước tạo tài khoản mẫu/lịch/thẻ mẫu.

Tài khoản mới đăng ký là STUDENT. Sau khi chọn tài khoản quản trị thật, operator cấp role SUPER_ADMIN trong SQL Editor dựa trên ID đã xác minh; không tự cấp quyền cho tài khoản đăng ký đầu tiên.

## Cấu hình

DB endpoint và password đã khôi phục ở application-local.properties; không thay đổi sang localhost/direct connection. Backend sử dụng SUPABASE_URL và SUPABASE_PUBLISHABLE_KEY (public key), mặc định theo project đã có trong frontend. SUPABASE_SERVICE_ROLE_KEY là secret backend, cần điền khi tạo nhân sự qua Admin API; không đặt trong frontend, không gửi qua chat. JWT_SECRET không còn được dùng để xác thực.

Dashboard Authentication → URL Configuration: thêm origin thực tế, /auth/login và /auth/reset-password vào Redirect URLs, ví dụ http://localhost:4200/auth/login và http://localhost:4200/auth/reset-password. Bật provider Email và cấu hình confirmation/password policy theo môi trường. Không tắt xác nhận email để lách lỗi cấu hình.

Đăng ký chưa có session sẽ hiển thị hướng dẫn xác nhận email. Trang login có gửi lại xác nhận và quên mật khẩu. Tạo nhân sự trong UI quản trị bắt buộc email; Supabase Admin API tạo tài khoản, backend gắn role/branch. Nếu transaction profile rollback, backend cố gắng xóa Auth user vừa tạo, và ghi ID để đối soát nếu thao tác bù thất bại.

## Kiểm thử

Backend: test policy, Supabase verification, provisioning/linking, query Hibernate và nghiệp vụ. Frontend: signup confirmation, signin qua SDK, refresh token, logout, forms/POS/lịch/kiosk. Production build và lint phải pass.

Đã khởi động backend thực tế trên port 18080: kết nối DB và schema validate thành công, public membership-plans trả 200, auth/me thiếu token/sai token trả 401. Server kiểm thử đã dừng. Chưa thử tạo user hoặc gửi email thật vì cần chọn tài khoản/email kiểm thử và cấu hình service role.

Công cụ tools/migrate-supabase-users.ps1 mặc định preview, chỉ --Apply mới tạo/link Auth users. Công cụ không gửi email và không sửa mật khẩu của Auth user đã tồn tại; account không email hoặc hash không hỗ trợ được bỏ qua. Hiện 7 tài khoản cũ đã xử lý theo yêu cầu xóa, không cần nhập chúng sang Auth.

## Kết quả cuối

33 test backend và 25 test frontend đạt; lint sạch; frontend production build thành công. Kiểm tra cấu hình Auth public từ project: Email enabled=true, disable_signup=false, mailer_autoconfirm=false (đăng ký cần xác nhận email). Đã chặn đặt lớp có HLV không hoạt động và ẩn lớp của HLV đã xóa khỏi danh mục đặt mới.

Cấp quản trị cho tài khoản thật sau khi người dùng xác nhận email và đăng nhập để tạo profile (operator thay email trong SQL Editor, không chạy từ frontend):

```sql
UPDATE yoga.users u SET role_id = r.id
FROM yoga.roles r, auth.users a
WHERE r.code = 'SUPER_ADMIN'
  AND u.supabase_user_id = a.id
  AND a.email = 'EMAIL_QUAN_TRI_DA_XAC_MINH'
  AND a.email_confirmed_at IS NOT NULL
  AND u.is_active = true;
```

Không dùng lại baseline schema.sql trên database hiện hữu. V4 đã được áp dụng, không cần chạy lại schema.sql.

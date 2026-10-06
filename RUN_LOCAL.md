# Chạy backend trong IntelliJ — Supabase Auth

Kết nối Supabase pooler hoạt động trước đây đã khôi phục trong application-local.properties. Giữ working directory D:\DA\backend. Không copy lại file example lên file local đang có password.

Backend không còn tự cấp JWT; JWT_SECRET không còn dùng. SUPABASE_URL và SUPABASE_PUBLISHABLE_KEY trỏ project hiện tại. Chỉ cần thêm SUPABASE_SERVICE_ROLE_KEY ở backend để tạo tài khoản nhân sự qua Supabase Admin API. Không đưa key này vào frontend hoặc chat.

Xem SUPABASE_AUTH.md để biết cách đăng ký, xác nhận email, khôi phục mật khẩu, redirect URLs và cấp role nghiệp vụ cho tài khoản thật.

File .env.example chỉ là mẫu; Spring tự nạp application-local.properties từ workspace root, backend hoặc backend/app. File local được bỏ qua trong Git.

provision_runtime.sql tạo group role yoga_app NOLOGIN; nếu dùng quyền runtime cần LOGIN riêng được cấp group role này. Không dùng yoga_app làm username trực tiếp.

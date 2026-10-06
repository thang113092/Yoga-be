# Hướng Dẫn Deploy Backend (Spring Boot 3 / Java 21) Lên Render

Tài liệu này hướng dẫn chi tiết cách triển khai Backend Yoga Management System lên nền tảng **Render (Render.com)** bằng Docker.

---

## 1. Các thành phần đã được chuẩn bị sẵn

1. **`Dockerfile` (Multi-stage build)**:
   - Stage 1: Dùng `maven:3.9.9-eclipse-temurin-21-alpine` để build toàn bộ các module Maven (`common`, `mod-branch`, `mod-identity`, `mod-membership`, `mod-schedule`, `app`) thành file JAR duy nhất (`app-1.0.0-SNAPSHOT.jar`).
   - Stage 2: Dùng `eclipse-temurin:21-jre-alpine` cực nhẹ, chạy user bảo mật không quyền root (`appuser`).
   - Tối ưu RAM cho **Render Free Tier (512MB RAM)** với các cờ JVM:
     `-XX:+UseContainerSupport -XX:MaxRAMPercentage=75.0 -Xss512k -XX:+ExitOnOutOfMemoryError`
2. **`render.yaml`**: Cấu hình Render Blueprint (hỗ trợ tạo service tự động nếu dùng Blueprints).
3. **Cổng mạng động (`PORT`)**: Đã cập nhật `server.port: ${PORT:8080}` trong `application.yml` để tự khớp với cổng Render cung cấp (Render cấp cổng mặc định 10000).
4. **Health Check Endpoint**: Đã thêm `spring-boot-starter-actuator` cung cấp URL `/actuator/health` giúp Render xác nhận dịch vụ sẵn sàng trước khi nhận request.

---

## 2. Các bước triển khai chi tiết trên Render

### Bước 1: Commit & Push code lên GitHub

Mở terminal tại thư mục `backend` và đẩy các file mới lên repo GitHub (`Yoga-be`):

```bash
git add .
git commit -m "feat: config Docker and Render deployment"
git push origin main
```

---

### Bước 2: Tạo Web Service trên Render

1. Truy cập vào [Render Dashboard](https://dashboard.render.com/) và đăng nhập.
2. Nhấn nút **New +** ở góc trên bên phải, chọn **Web Service**.
3. Chọn kết nối với kho mã nguồn GitHub của bạn: tìm repo **`Yoga-be`** (hoặc dán đường dẫn Git URL) rồi nhấn **Connect**.

---

### Bước 3: Điền thông tin cấu hình Service

Điền các thông tin sau:
- **Name**: `yoga-be` (hoặc tên tùy ý)
- **Region**: **Singapore (Southeast Asia)** *(Khuyên dùng: độ trễ thấp nhất về Việt Nam)*
- **Branch**: `main`
- **Root Directory**: *(Để trống nếu repo GitHub `Yoga-be` chứa trực tiếp mã nguồn backend)*
- **Runtime**: Chọn **Docker**
- **Instance Type**: Chọn **Free** (0.1 CPU, 512 MB RAM)

Bấm vào **Advanced Settings**:
- **Health Check Path**: `/actuator/health`
- **Auto-Deploy**: `Yes` (tự động re-deploy mỗi khi bạn push code lên nhánh main)

---

### Bước 4: Cấu hình biến môi trường (Environment Variables)

Tại mục **Environment Variables** (hoặc tab **Environment** trong service), nhấn **Add Environment Variable** và thêm các biến sau:

| Tên biến (Key) | Giá trị mẫu (Value) | Giải thích |
| :--- | :--- | :--- |
| `YOGA_DB_JDBC_URL` | `jdbc:postgresql://aws-0-ap-southeast-1.pooler.supabase.com:6543/postgres?sslmode=require&prepareThreshold=0` | URL kết nối Supabase Pooler (Transaction pooler port 6543 hoặc Session pooler port 5432). |
| `YOGA_DB_USERNAME` | `postgres.cagwkxiccmayrtajwdtc` | Tên user đăng nhập Supabase Pooler |
| `YOGA_DB_PASSWORD` | `<mật_khẩu_database_của_bạn>` | Mật khẩu database Supabase |
| `SUPABASE_URL` | `https://cagwkxiccmayrtajwdtc.supabase.co` | URL Supabase project |
| `SUPABASE_PUBLISHABLE_KEY` | `<supabase_publishable_anon_key>` | Supabase Public / Anon Key |
| `SUPABASE_SERVICE_ROLE_KEY`| `<supabase_service_role_key>` | Service Role Key (Backend cần để tạo tài khoản nhân viên qua Supabase Admin API) |
| `YOGA_CORS_ORIGINS` | `http://localhost:4200,https://your-frontend.vercel.app` | Danh sách URL Frontend được phép gọi API (phân cách bằng dấu phẩy) |

> ⚠️ **Lưu ý đặc biệt về Supabase URL**: Render Free Tier không hỗ trợ kết nối IPv6 trực tiếp. Hãy luôn sử dụng **Supabase Pooler URL** (có dạng `aws-0-...pooler.supabase.com` ở cổng `6543` hoặc `5432`), không dùng direct connection host (`db.xxxx.supabase.co`).

---

### Bước 5: Khởi tạo và kiểm tra kết quả

1. Nhấn **Create Web Service**.
2. Render sẽ bắt đầu kéo image, tải dependencies Maven, build file JAR và khởi chạy Spring Boot container.
3. Khi log hiển thị:
   ```
   Started YogaAppApplication in ... seconds
   ==> Your service is live 🎉 at https://<your-service-name>.onrender.com
   ```
4. Kiểm tra các URL sau trên trình duyệt:
   - **Health check**: `https://<your-service-name>.onrender.com/actuator/health` -> Trả về `{"status":"UP"}`
   - **Swagger UI**: `https://<your-service-name>.onrender.com/swagger-ui.html`

---

## 3. Đặc điểm gói Free của Render & Giải pháp khắc phục

| Đặc điểm | Hiện tượng | Giải pháp |
| :--- | :--- | :--- |
| **Spin down sau 15 phút** | Sau 15 phút không có request, Render sẽ tạm tắt service. Request tiếp theo sẽ mất **30 - 45 giây** để khởi động lại (Cold Start). | Dùng dịch vụ ping miễn phí như [UptimeRobot](https://uptimerobot.com/) hoặc [cron-job.org](https://cron-job.org/) gọi vào URL `https://<your-service-name>.onrender.com/actuator/health` mỗi **10 phút** một lần để giữ service luôn thức. |
| **Giới hạn 512 MB RAM** | Nếu JVM chiếm quá 512MB RAM, Render sẽ tự kill container (OOMKilled). | Dockerfile đã cấu hình `-XX:MaxRAMPercentage=75.0` (giới hạn heap tối đa ~380MB, dành 130MB cho Metaspace và thread stack) giúp tránh hoàn toàn lỗi tràn RAM. |

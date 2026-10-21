# LOGO! Tủ – app Android điều khiển Siemens LOGO! 230RCE (8.4)

Một app, hai cách kết nối (chọn trong **Cài đặt → Cách kết nối**):

| Chế độ | Sơ đồ | Dùng khi |
|---|---|---|
| **Tại chỗ (Modbus TCP)** | Điện thoại ~WiFi~ Router ─LAN─ LOGO! | Đứng gần tủ, hoặc bật VPN vào mạng của tủ |
| **Từ xa (MQTT)** | LOGO! ─LAN─ Router ─Internet─ Máy chủ MQTT cloud ─Internet─ Điện thoại | Ở bất kỳ đâu có Internet |

Không cần mua gateway: LOGO! 8.4 tự gửi dữ liệu MQTT lên cloud, router chỉ cấp Internet.

---

## Phần 1 – Tạo link tải APK (làm một lần)

1. github.com → **New repository**, tên ví dụ `logo-tu-app`, chọn **Public**.
2. Bấm **uploading an existing file**, kéo **toàn bộ nội dung** thư mục này vào (gồm `.github`, `app` và các file `.kts`), bấm **Commit changes**. Windows ẩn thư mục `.github` thì bật hiện file ẩn trước.
3. Tab **Actions** → chờ "Build APK" có dấu tích xanh (5–8 phút).
4. Link tải cố định:
   `https://github.com/<tài-khoản>/logo-tu-app/releases/latest/download/LOGO-Tu.apk`

Cài: mở link bằng Chrome → tải → mở file → cho phép cài từ nguồn này. Play Protect cảnh báo thì chọn "Vẫn cài đặt".
Build đỏ: chụp phần log lỗi gửi lại để sửa.

---

## Phần 2 – Router

1. Cắm dây LAN từ LOGO! vào một cổng LAN của router.
2. Ghi lại dải mạng của router (vd router `192.168.1.1` → dải `192.168.1.x`).
3. Trong trang quản trị router, đặt **IP cố định cho LOGO!** ngoài vùng DHCP, ví dụ `192.168.1.10`. (Hoặc đặt IP tĩnh ngay trong LOGO! ở Phần 3.)
4. WiFi: đặt mật khẩu WPA2 mạnh, tắt WPS.
5. Router phải có Internet (cáp quang hoặc SIM 4G) thì chế độ từ xa mới chạy.
6. **Không** mở cổng (port forwarding) cho LOGO! ra Internet.

---

## Phần 3 – Cấu hình LOGO! trong Soft Comfort V8.4

### 3.1 Địa chỉ mạng
Tools → Ethernet Connections → IP `192.168.1.10`, subnet `255.255.255.0`, gateway = IP router (`192.168.1.1`). **Gateway bắt buộc đúng**, sai thì LOGO! không ra được Internet.
DNS: điền `8.8.8.8` nếu có ô DNS (cần để LOGO! tìm được địa chỉ máy chủ cloud).

### 3.2 Chương trình mẫu: chế độ Tự động / Tay

Cờ **M8** chọn chế độ: `0` = **Tự động** (đèn theo hẹn giờ), `1` = **Tay** (đèn theo nút trên app).

| Ngõ ra | Logic trong LOGO! |
|---|---|
| Q1 | `(NOT M8 AND Hẹn_giờ_1) OR (M8 AND M1)` |
| Q2 | `(NOT M8 AND Hẹn_giờ_2) OR (M8 AND M2)` |
| Q3 | `(NOT M8 AND Hẹn_giờ_3) OR (M8 AND M3)` |
| Q4 | `(NOT M8 AND Hẹn_giờ_4) OR (M8 AND M4)` |

- `Hẹn_giờ_x` = khối Weekly Timer / Astronomical Clock (đồng hồ thiên văn) của nhánh, có thể OR thêm nút tại tủ.
- Ở chế độ Tay, app **bật và tắt được** đèn kể cả trong giờ hẹn. Ở chế độ Tự động, nút trên app bị khóa.
- M8 **không đặt Retentive**: mất điện có lại thì LOGO! tự về Tự động, an toàn.
- Nút **Kích** của ngõ vào I1…I8 gửi xung 1 giây vào **M11…M18** (dùng khi cần).
- Thông số **VW0**, **VW2** làm tham chiếu cho khối hẹn giờ.
- Không dùng chế độ: đặt "Cờ M chọn chế độ" = 0 trong app, chương trình dùng `Qx = Ix OR Mx` như cũ.

**Phản hồi điện nhánh (khuyến nghị):** đấu tiếp điểm phụ của contactor (hoặc rơ-le dòng) từng nhánh vào ngõ vào trống, ví dụ I5…I8. Trong app nhập `5,6,7,8` ở mục "Ngõ vào phản hồi". App sẽ báo đỏ khi **Q bật nhưng nhánh không có điện** (contactor hỏng, mất pha, aptomat nhảy) hoặc **Q tắt nhưng nhánh vẫn có điện** (contactor dính).

Chạy thử trên tủ không tải, bật từng ngõ một trước khi đấu tải thật.

### 3.3 Modbus TCP (cho chế độ tại chỗ)
Ethernet Connections → chuột phải → **Add Modbus connection** → kiểu **Server**, bật quyền **ghi** (write access).

Địa chỉ mặc định trong app (tính từ 0, đối chiếu mục *Modbus address mapping* trong trợ giúp Soft Comfort; khác thì sửa ở Cài đặt):

| LOGO! | Loại Modbus | Địa chỉ |
|---|---|---|
| I1… | Discrete input | 0… |
| Q1… | Coil | 8192… |
| M1… | Coil | 8256… |
| VW0, VW2… | Holding register | 0, 1… |

### 3.4 MQTT (cho chế độ từ xa)
Trong phần cấu hình Cloud/MQTT của Soft Comfort (Ethernet Connections hoặc Online Settings, tùy bản):

1. **Broker:** địa chỉ máy chủ ở Phần 4, cổng **8883**, bật **TLS/SSL**, nhập tài khoản và mật khẩu dành cho LOGO!.
2. **Publish topic:** `LOGO_Pub` · **Subscribe topic:** `LOGO_Sub` (đúng chữ hoa/thường, phải trùng với app).
3. **Data format:** để mặc định (LOGO! Web Server format).
4. **Danh sách biến gửi lên (Cloud transfer values)** – đặt **tên biến đúng như bảng**:

| Tên biến | Biến LOGO! | Gửi lên | Cho phép ghi từ cloud |
|---|---|---|---|
| Q1–Q4 | Q1–Q4 | Có | Không |
| I1–I8 | I1–I8 | Có | Không |
| M1–M4 | M1–M4 | Có | **Có** |
| M8 | M8 (chế độ Tay/Tự động) | Có | **Có** |
| M11–M18 | M11–M18 | Có | **Có** |
| VW0, VW2 | VW0, VW2 | Có | **Có** |

   Nếu có tùy chọn gửi theo chu kỳ (cyclic), đặt 10–30 giây để app mở lên là thấy trạng thái ngay.
   Tên khác bảng thì sửa trong app: thông số có cột "tag MQTT" ở Cài đặt; Q, I, M dùng tên Q1, I1, M1… cố định.
5. Nạp chương trình xuống LOGO!.

LOGO! gửi dạng `{"state":{"reported":{"Q1":{"value":[1]}}}}`, chỉ gửi biến vừa thay đổi, và khoảng 10 giây gửi một nhịp `$logotime`. App dùng nhịp này để báo **LOGO! online**. Lệnh từ app có dạng `{"state":{"M1":{"value":[1]}}}`.

---

## Phần 4 – Máy chủ MQTT trên cloud (HiveMQ Cloud, miễn phí)

1. Đăng ký tại hivemq.com/cloud → tạo **Serverless cluster** (gói Free).
2. Ghi lại **Cluster URL**, dạng `xxxx.s1.eu.hivemq.cloud`.
3. Tab **Access Management** → tạo 2 tài khoản: `logo-tu1` (cho LOGO!) và `app-tu1` (cho điện thoại), mật khẩu mạnh.
4. Trong app: Cài đặt → **Từ xa (MQTT)**:
   - Máy chủ: `ssl://xxxx.s1.eu.hivemq.cloud:8883`
   - Tài khoản/mật khẩu: `app-tu1`
   - Topic LOGO! gửi lên: `LOGO_Pub` · Topic gửi lệnh: `LOGO_Sub`
5. Lưu. Đèn trạng thái xanh "LOGO! online · qua cloud" trong khoảng 10 giây.

**Nếu LOGO! không kết nối được HiveMQ** (Soft Comfort không có ô TLS, hoặc báo lỗi bắt tay): HiveMQ Cloud bắt buộc TLS. Khi đó dùng máy chủ Mosquitto riêng (VPS rẻ, có mật khẩu). Gửi ảnh màn hình cấu hình MQTT của Soft Comfort để chọn cách phù hợp.

---

## Phần 5 – Kiểm tra từng bước

1. **Tại chỗ:** điện thoại vào WiFi router → app chế độ Tại chỗ, IP `192.168.1.10` → đèn xanh, bấm Bật Q1 thấy Q1 sáng.
   Không được: thử app *Modbus Monitor* đọc coil 8192 để biết lỗi ở LOGO! hay ở app.
2. **Cloud:** cài **MQTT Explorer** trên máy tính, kết nối HiveMQ bằng tài khoản `app-tu1` → phải thấy topic `LOGO_Pub` có dữ liệu mỗi ~10 giây.
   Gửi thử vào `LOGO_Sub`: `{"state":{"M1":{"value":[1]}}}` → Q1 phải bật.
3. **App từ xa:** tắt WiFi điện thoại, dùng 4G → app chế độ Từ xa → điều khiển được.

Bước 2 không thấy dữ liệu → lỗi nằm ở cấu hình LOGO!/router (gateway, DNS, TLS, tài khoản), không phải ở app.

---

## Tính năng app
- Đèn Q1–Q4, I1–I8; báo online/mất tín hiệu; khóa nút khi mất tín hiệu.
- Chế độ **Tự động / Tay** (cờ M8): ở chế độ Tay bật/tắt từng nhánh đèn từ app.
- Theo dõi đèn sáng/tắt qua ngõ ra Q; báo lỗi khi có phản hồi điện nhánh không khớp.
- Nút Kích tạo xung (mặc định 1 giây) cho ngõ vào ảo.
- Đọc/ghi thông số VW có giới hạn nhỏ nhất/lớn nhất.
- Hỏi xác nhận trước lệnh (tắt được).
- Chế độ từ xa nhớ giá trị cuối cùng, mở app là thấy ngay.
- Đổi tên tủ, tên từng ngõ, số ngõ, địa chỉ, máy chủ ngay trong app.

## Bảo mật
- Mỗi tủ một tài khoản MQTT riêng cho LOGO!; điện thoại dùng tài khoản khác.
- Đặt mật khẩu WiFi mạnh và mật khẩu bảo vệ chương trình LOGO!.
- Mật khẩu MQTT lưu trong điện thoại; mất máy thì đổi mật khẩu tài khoản `app-tu1` trên HiveMQ.

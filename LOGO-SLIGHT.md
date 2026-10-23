# Bổ sung chương trình LOGO-SLIGHT (ban hành) để dùng với app

Chương trình gốc giữ nguyên toàn bộ logic. Chỉ thêm 5 khối Network Input, sửa 4 rung ngõ ra và gắn thêm giờ đèn PARK vào VM.
**Trước khi sửa: lưu một bản sao file .lld gốc.**

## 1. Chương trình hiện tại làm gì

| Ngõ ra | Điều khiển bởi | Ý nghĩa |
|---|---|---|
| Q1 | M7 | Đèn PARK: T007 (18:25–23:00 và 04:00–05:20), hoặc nút tay I2, hoặc bật cưỡng bức NI1 |
| Q2 | M2 | Nhánh B1: T001 (18:25–05:20), hoặc I2, hoặc NI1; bị cấm khi NI2 = 1 |
| Q3 | M3 | Nhánh B2: T002 (18:26–05:20) sau trễ 30 s (T003), hoặc I2, hoặc NI1; bị cấm khi NI3 = 1 |
| Q4 | T006 | HPS: 22:00–04:00 |

| Biến | Ý nghĩa |
|---|---|
| I2 | Nút bật tay, tự tắt sau 15 phút (T004) |
| I5, I6 | Phản hồi nhánh B1, B2 (M4 = M2·I5, M5 = M3·I6, M6 = M4·M5) |
| NI1 = V0.0 | Bật cưỡng bức từ xa, tự tắt sau 15 phút (T005) |
| NI2 = V0.1 | Cấm nhánh B1 |
| NI3 = V0.2 | Cấm nhánh B2 |
| VW2 / VW4 | Giờ bật / tắt T001 (B1) |
| VW6 / VW8 | Giờ bật / tắt T002 (B2) |
| VW22 / VW24 | Giờ bật / tắt T006 (HPS) |
| VW10–VW20 | Thời gian T004, T005 – **app không ghi vào vùng này** |

Mạng: IP 192.168.0.3, gateway 192.168.0.1, Modbus TCP server cổng 502 (đã có).

## 2. Bổ sung A – Chế độ TAY từ app

Thêm 5 khối **Network Input** (giống NI1–NI3 đang có):

| Khối | Biến VM | Ý nghĩa |
|---|---|---|
| NI4 | V1.0 | Chế độ TAY (1 = Tay, 0 = Tự động) |
| NI5 | V1.1 | Lệnh tay Q1 – PARK |
| NI6 | V1.2 | Lệnh tay Q2 – B1 |
| NI7 | V1.3 | Lệnh tay Q3 – B2 |
| NI8 | V1.4 | Lệnh tay Q4 – HPS |

Sửa 4 rung ngõ ra. Mỗi rung có 2 nhánh song song cùng đi vào cuộn Q:

```
Q1:  ──[ M7 ]────[/ NI4 ]──┬──( Q1 )
     ──[ NI4 ]───[ NI5 ]───┘

Q2:  ──[ M2 ]────[/ NI4 ]──┬──( Q2 )
     ──[ NI4 ]───[ NI6 ]───┘

Q3:  ──[ M3 ]────[/ NI4 ]──┬──( Q3 )
     ──[ NI4 ]───[ NI7 ]───┘

Q4:  ──[ T006 ]──[/ NI4 ]──┬──( Q4 )
     ──[ NI4 ]───[ NI8 ]───┘
```

`[ ]` = tiếp điểm thường mở, `[/ ]` = tiếp điểm thường đóng.

- Chế độ Tự động (V1.0 = 0): chạy đúng như chương trình gốc.
- Chế độ Tay (V1.0 = 1): mỗi ngõ ra theo công tắc trên app, bật/tắt được cả trong giờ hẹn.
- Vùng VM không lưu khi mất điện: LOGO! khởi động lại luôn về Tự động.
- Nút I2 và bật cưỡng bức NI1 chỉ tác động ở chế độ Tự động.

## 3. Bổ sung B – Chỉnh giờ đèn PARK từ app

**Tools → Parameter VM Mapping**, thêm 4 dòng sau dòng 12 đang có:

| Khối | Tham số | Kiểu | Địa chỉ |
|---|---|---|---|
| T007 PARK timer | On Time1 | Word | 26 |
| T007 PARK timer | Off Time1 | Word | 28 |
| T007 PARK timer | On Time2 | Word | 30 |
| T007 PARK timer | Off Time2 | Word | 32 |

## 4. Mạng khi dùng router H927PRO

H927PRO mặc định dùng dải 192.168.1.x, LOGO! đang ở 192.168.0.3. Cách nhẹ nhất là **đổi router**, không phải nạp lại LOGO!:
trang quản trị 192.168.1.1 → LAN → đặt IP router **192.168.0.1**, DHCP 192.168.0.100–192.168.0.200 → lưu, router khởi động lại → vào lại bằng 192.168.0.1.

Trong app: Cài đặt → Tại chỗ → IP **192.168.0.3** (mặc định sẵn).

## 5. Điều khiển từ xa qua cloud (MQTT) – làm sau khi chạy ổn tại chỗ

Chương trình chưa có cấu hình MQTT. Thêm trong phần Cloud/MQTT của Soft Comfort: broker HiveMQ, cổng 8883, TLS, publish `LOGO_Pub`, subscribe `LOGO_Sub`. Danh sách biến gửi lên, **đặt tên đúng như cột Tên**:

| Tên | Biến | Ghi từ cloud |
|---|---|---|
| Q1–Q4 | Q1–Q4 | Không |
| I1–I8 | I1–I8 | Không |
| V0_0, V0_1, V0_2 | V0.0, V0.1, V0.2 | Có |
| V1_0 … V1_4 | V1.0 … V1.4 | Có |
| VW2, VW4, VW6, VW8, VW22, VW24, VW26, VW28, VW30, VW32 | các word giờ hẹn | Có |

## 6. Kiểm tra lần đầu (theo thứ tự)

1. Nạp chương trình đã sửa, tủ chưa đấu tải.
2. App chế độ Tại chỗ: đèn trạng thái xanh, Q1–Q4 đúng với LOGO!.
3. **Kiểm tra giờ trước khi ghi:** mục Lịch nhánh B1 phải hiện **18:25 → 05:20**.
   - Nếu hiện "Chưa đọc được giờ" kèm giá trị thô: Cài đặt → dòng Lịch đổi kiểu `bcd` sang `goi` hoặc `hhmm` cho đến khi hiện đúng 18:25. Hoặc gửi giá trị thô cho người hỗ trợ.
   - **Chưa đọc đúng thì chưa bấm Đổi giờ.**
4. Thử Sang Tay → bật/tắt từng nhánh → Về Tự động.
5. Thử Cấm nhánh B1, bật cưỡng bức, rồi tắt lại.
6. Đổi giờ một lịch, xem lại trên LOGO! (hoặc Online Test) xem giờ đã đổi; tắt nguồn LOGO! bật lại xem giờ còn giữ.

Bật cưỡng bức (V0.0): đèn sáng tối đa 15 phút rồi tự tắt dù công tắc trên app vẫn bật. Muốn bật cưỡng bức lần nữa thì gạt tắt rồi bật lại.

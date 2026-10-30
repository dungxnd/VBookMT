# VBookMT

Bộ dịch truyện Trung $\rightarrow$ Việt offline cho ứng dụng **vBook**, sử dụng mô hình MarianMT (HachimiMT, MoxhiMT) chạy cục bộ qua CTranslate2.

## ✨ Điểm chính
- **Dịch offline 100%**: Không tốn API key, không cần mạng internet.
- **Chuẩn văn phong convert**: Giữ đúng đại từ nhân xưng (*ta, ngươi, hắn, nàng, huynh, đệ*), không bị lẫn ngôi.
- **Tốc độ nhanh**: Nén INT8 tối ưu (~1,600+ ký tự/giây, tầm 1-2 giây một chương).
- **Fallback an toàn**: Nếu chưa bật server VBookMT, extension tự động chuyển sang QuickTranslator để việc đọc không bị ngắt quãng.
- **Chạy nền ổn định**: App Android chạy dạng Foreground Service kèm thông báo, không lo bị hệ điều hành tắt ngầm.

---

## 🚀 Bước 1: Cài đặt app & khởi chạy server

Extension chỉ là "ống dẫn": nó gửi yêu cầu dịch về máy chủ nội bộ tại `http://127.0.0.1:8765/translate`. Vì vậy **cài app và bật server trước**, rồi mới thêm extension.

### 📱 Trên Android
1. Cài file `VBookMT-Android.apk` từ trang [Releases](https://github.com/DungxND/VBookMT/releases).
2. Mở app **VBookMT**, cấp quyền thông báo.
3. Model mặc định **HachimiMT-60-QT** đã được cấu hình sẵn (có thể tải thêm **MoxhiMT-30-QT** nếu muốn bản nhẹ) — model được chọn **trong app**, không chọn ở extension.
4. Bật switch khởi động server.

### 💻 Trên Windows
1. Tải và giải nén `VBookMT-Windows-x64.zip` từ trang [Releases](https://github.com/DungxND/VBookMT/releases).
2. Chạy server:
   ```powershell
   ./vbookmt-server.exe --port 8765 --model models/hachimi60_ct2
   ```

Kiểm tra nhanh: mở `http://127.0.0.1:8765/health` — server đã sẵn sàng khi thấy `"ready":true`.

---

## 🔌 Bước 2: Cài đặt Extension vào vBook

### Cách 1: Thêm link nguồn trong vBook (tiện nhất)
1. Mở **vBook** $\rightarrow$ **Cài đặt** $\rightarrow$ **Tiện ích mở rộng**.
2. Nhấn biểu tượng dấu **+** (Thêm nguồn).
3. Dán URL sau rồi bấm Thêm:
   ```text
   https://raw.githubusercontent.com/DungxND/VBookMT/main/plugin.json
   ```
4. Chọn cài đặt **VBookMT (Offline AI)**.

### Cách 2: Cài bằng file ZIP
1. Tải file [`plugin.zip`](https://raw.githubusercontent.com/DungxND/VBookMT/main/vbook-extension/plugin.zip) từ repo hoặc mục [Releases](https://github.com/DungxND/VBookMT/releases).
2. Vào **vBook** $\rightarrow$ **Tiện ích mở rộng** $\rightarrow$ chọn **Cài đặt từ tệp (ZIP)**.

## Tinh chỉnh

### Muốn nhanh hơn
Trong app có thẻ **Hiệu năng** với nút **“Đo & tối ưu cho máy này”**. App sẽ thử vài cấu hình trên chính máy (khoảng 8–12 giây), chọn cái nhanh nhất và **ghi nhớ**.

### Dành cho người muốn tinh chỉnh tay (nâng cao)
Ai muốn vọc thì server vẫn mở `GET /health` và `POST /config`; giá trị đặt tay cũng được ghi nhớ như khi bấm nút.

```powershell
# Xem cấu hình hiện tại + thống kê bộ nhớ đệm
curl.exe -s http://127.0.0.1:8765/health

# Đổi kiểu tính toán (mỗi lần đổi sẽ nạp lại model ~1 giây)
curl.exe -s -X POST http://127.0.0.1:8765/config -H "Content-Type: application/json" -d "{\"computeType\":\"int8\"}"

# Đổi số luồng và cách gom batch
curl.exe -s -X POST http://127.0.0.1:8765/config -H "Content-Type: application/json" -d "{\"threads\":6,\"maxBatch\":16,\"batchType\":\"tokens\"}"

# Xoá cache / tắt cache để đo chênh lệch
curl.exe -s -X POST http://127.0.0.1:8765/config -H "Content-Type: application/json" -d "{\"clearCache\":true}"
curl.exe -s -X POST http://127.0.0.1:8765/config -H "Content-Type: application/json" -d "{\"cacheEnabled\":false}"
```

**Vì sao phải đo trên máy thay vì chọn sẵn?** Trên CPU x86 không có VNNI (đo trên Ryzen 7 5800H), `int8` và `int8_float32` gần như trùng nhau (203 vs 205 ms, đầu ra giống nhau 100%), còn `float32` chậm hơn 46% mà chỉ hơn ~0,7% độ tương đồng. Trên ARMv8.2 có `dotprod`/`i8mm` thì `int8` mới thực sự có cửa nhanh hơn — nên app để nút bấm tự đo, kết quả phụ thuộc máy chứ không thể đoán.

---

## 📜 Tác quyền & Ghi nhận (Credits)

- **HachimiMT-60-QT** & **HachimiMT-40-QT** bởi **[ngocdang83](https://huggingface.co/ngocdang83)** ([CC BY 4.0](https://creativecommons.org/licenses/by/4.0/)).
- **MoxhiMT-30-QT** bởi **[DanVP](https://huggingface.co/DanVP)** ([CC BY-NC 4.0](https://creativecommons.org/licenses/by-nc/4.0/)).
- **vBook Ecosystem** bởi **[Darkrai9x](https://github.com/Darkrai9x)**.

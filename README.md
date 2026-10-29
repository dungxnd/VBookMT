# VBookMT

Bộ dịch truyện Trung $\rightarrow$ Việt offline cho ứng dụng **vBook**, sử dụng mô hình MarianMT (HachimiMT, MoxhiMT) chạy cục bộ qua CTranslate2.

## ✨ Điểm chính
- **Dịch offline 100%**: Không tốn API key, không cần mạng internet.
- **Chuẩn văn phong convert**: Giữ đúng đại từ nhân xưng (*ta, ngươi, hắn, nàng, huynh, đệ*), không bị lẫn ngôi.
- **Tốc độ nhanh**: Nén INT8 tối ưu (~1,600+ ký tự/giây, tầm 1-2 giây một chương).
- **Fallback an toàn**: Nếu chưa bật server VBookMT, extension tự động chuyển sang QuickTranslator để việc đọc không bị ngắt quãng.
- **Chạy nền ổn định**: App Android chạy dạng Foreground Service kèm thông báo, không lo bị hệ điều hành tắt ngầm.

---

## 📦 Cài đặt Extension vào vBook

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

---

## 🚀 Khởi chạy Server

Extension gửi yêu cầu dịch về máy chủ nội bộ tại `http://127.0.0.1:8765/translate`.

### 📱 Trên Android
1. Cài file `VBookMT-Android.apk` từ trang Releases.
2. Mở app **VBookMT**, cấp quyền thông báo.
3. Model mặc định **HachimiMT-60-QT** đã được cấu hình sẵn (có thể tải thêm **MoxhiMT-30-QT** nếu muốn bản nhẹ).
4. Bật switch khởi động server, sau đó mở vBook đọc truyện như bình thường.

### 💻 Trên Windows
1. Tải và giải nén `VBookMT-Windows-x64.zip` từ Releases.
2. Chạy server:
   ```powershell
   ./vbookmt-server.exe --port 8765 --model models/hachimi60_ct2
   ```

---

## 📜 Tác quyền & Ghi nhận (Credits)

- **Tác giả phát triển**: **[dungxnd](https://github.com/dungxnd)** - Thiết kế kiến trúc và phát triển hệ sinh thái VBookMT (Android Companion App, Windows CLI Server, vBook Extension).
- **HachimiMT-60-QT** & **HachimiMT-40-QT** bởi **[ngocdang83](https://huggingface.co/ngocdang83)** ([CC BY 4.0](https://creativecommons.org/licenses/by/4.0/)).
- **MoxhiMT-30-QT** bởi **[DanVP](https://huggingface.co/DanVP)** ([CC BY-NC 4.0](https://creativecommons.org/licenses/by-nc/4.0/)).
- **vBook Ecosystem** bởi **[Darkrai9x](https://github.com/Darkrai9x)**.

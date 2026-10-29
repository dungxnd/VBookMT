# Changelog

Tất cả các thay đổi đáng chú ý của dự án **VBookMT** sẽ được ghi chép tại đây theo định dạng [Keep a Changelog](https://keepachangelog.com/en/1.0.0/).

## [v1.0.0] - 2026-10-29

### Added
- **Mô hình Dịch Mặc định**: Tích hợp mô hình CTranslate2 INT8 `HachimiMT-60-QT` của tác giả `ngocdang83` (tốc độ > 1.600 ký tự/giây trên CPU, chuẩn văn phong convert, giữ nguyên 100% ngôi xưng hô).
- **Mô hình Tùy chọn Tải thêm**: Hỗ trợ tải thêm mô hình `MoxhiMT-30-QT` của tác giả `DanVP` (chỉ 36MB, tiết kiệm RAM) và chuyển đổi trực tiếp trên ứng dụng.
- **Android Companion App (Kotlin)**:
  - Chạy `Foreground Service` kèm thông báo cố định chống bị hệ điều hành tắt ngầm do tối ưu pin.
  - Giao diện Jetpack Compose điều khiển máy chủ 1 chạm và quản lý mô hình.
  - Nhúng máy chủ HTTP cục bộ lắng nghe tại `http://127.0.0.1:8765/translate`.
  - Cầu nối C++ JNI tương thích Android NDK.
- **Windows Standalone App (Kotlin/Native)**:
  - Biên dịch bản địa `mingwX64` thành file `.exe` độc lập, không cần cài đặt Python hay Java.
  - C-Interop wrapper liên kết CTranslate2.
- **Extension vBook**:
  - Gói plugin vBook chuẩn (`type: "translate"`).
  - Tự động gọi máy chủ cục bộ và có cơ chế fallback về QuickTranslator nếu máy chủ chưa bật.
- **Tài liệu & Bản quyền**:
  - `NOTICE.md` ghi nhận tác quyền chi tiết cho `ngocdang83`, `DanVP`, `OpenNMT`, và `Darkrai9x`.

# Changelog

Tất cả các thay đổi đáng chú ý của dự án **VBookMT** sẽ được ghi chép tại đây theo định dạng [Keep a Changelog](https://keepachangelog.com/en/1.0.0/).

## [v1.1.0] - 2026-10-30

### Mới
- Mở lại chương đã dịch thì gần như hiện ngay, kể cả sau khi đã thoát app rồi mở lại. Trước đây bản
  dịch chỉ được nhớ trong lúc app còn chạy.
- Thẻ Hiệu năng hiện tốc độ ước tính, số giây cho một chương và số đoạn đã dịch sẵn.
- Nút “Đo & tối ưu cho máy này” tự thử vài cấu hình trên chính máy bạn rồi nhớ cái nhanh nhất cho
  những lần sau.
- Chỉnh được độ dài mỗi đoạn dịch, để thử trên máy khác.

### Tốt hơn
- Chương mới dịch nhanh hơn: câu lặp trong cùng chương chỉ dịch một lần, và đoạn nào không cần model
  thì không gửi cho model.
- Chương dài không còn bị cắt giữa lúc đang dịch: extension chờ server lâu hơn trước khi chuyển sang
  QuickTranslator.
- Đoạn không có chữ Hán (số, dấu ngắt cảnh, tên Latinh) giữ nguyên văn, thay vì để model viết lại.
- App tự chọn cấu hình hợp với máy, nên máy yếu ít nóng và đỡ hao pin hơn.
- Mã nguồn được chia lại theo chức năng; phần quyết định cách chia chương có kiểm thử tự động.
- Có thêm tài liệu kiến trúc cho ai muốn đọc hoặc đóng góp mã nguồn.

### Đã sửa
- Bấm “Đo & tối ưu” đúng lúc đang dịch có thể làm bộ nhớ đệm của app hỏng lặng lẽ, khiến những lần
  đọc sau chậm dần. Đã sửa.
- Gói cài extension có thể mang cấu hình cũ (thời gian chờ 15 giây thay vì 120 giây), nên bản cập nhật
  vẫn hành xử như bản trước.
- Một số đoạn có thể bị bỏ qua im lặng khi bộ tách từ gặp lỗi; giờ được xử lý và ghi vào nhật ký.
- APK phát hành không còn có thể được dựng với bộ thư viện lệch với mã nguồn.

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

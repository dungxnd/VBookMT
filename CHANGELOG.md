# Changelog

Tất cả các thay đổi đáng chú ý của dự án **VBookMT** sẽ được ghi chép tại đây theo định dạng [Keep a Changelog](https://keepachangelog.com/en/1.0.0/).

## [v1.1.0] - 2026-10-30

### Added
- Bộ nhớ bản dịch được lưu xuống đĩa và nạp lại khi khởi động: mở lại chương đã dịch sau khi app bị
  kill không phải chạy lại model. Nhật ký nằm trong `noBackupFilesDir`, ghi thêm theo từng lô, tự nén
  khi lớn quá 2 lần dữ liệu thật; không schema, xoá được bất cứ lúc nào.
- `/config` nhận `chunkChars` (40–400) — độ dài mỗi đoạn dịch, thông số ảnh hưởng tốc độ nhiều nhất
  trên ARM. Không lưu vào `SharedPreferences`: là thông số để đo, không phải tuỳ chọn người dùng.
- `/health` thêm `cache_disk_bytes` và `cache_disk_restored`.

### Changed
- Việc chọn đoạn từ bộ nhớ đệm và bỏ qua đoạn không có Hán tự được làm **trước** khi chia lô, nên lô
  gửi tới engine chỉ chứa đoạn thật sự cần dịch; đoạn trùng trong cùng chương chỉ dịch một lần; các lô
  xếp theo độ dài để giảm padding của CTranslate2.
- Timeout: server 110 giây, extension mặc định 120 giây (trước là 60 giây ở extension, nên client tự
  bỏ trước khi server kịp trả kết quả).
- Đổi model hoặc `compute_type` xoá cả bộ nhớ đệm trên đĩa, không chỉ trong RAM.
- Chia chương chuyển vào module lõi dùng chung (test JVM, không phụ thuộc Android); màn hình và phần
  xử lý tách theo chức năng; lớp JNI tách thành 4 module, biên dịch bằng C++20.
- CI: thêm bước biên dịch module app, kiểm tra `plugin.zip` khớp mã nguồn, preflight so tag với
  `versionName` và CHANGELOG, timeout + quyền tối thiểu cho từng job, và job dựng lại thư viện native
  hàng tuần.

### Fixed
- Xoá bộ nhớ đệm không còn chạy song song với lúc đang ghi: bấm tối ưu đúng lúc đang dịch có thể làm
  lệch số đếm dung lượng, khiến bộ nhớ đệm tự đẩy hết bản ghi về sau.
- `plugin.zip` phát hành lệch với mã nguồn (v1.0.0 còn thời gian chờ 15 giây trong khi mã nguồn đã là
  120 giây).
- Cache thư viện native của CI khoá cứng `v4.5.0` trong khi script dựng CTranslate2 4.8.4, nên lần chạy
  sau nhận header của bộ phụ thuộc khác. Khoá giờ theo nội dung script.
- Script dựng native không cài shim `third_party/absl/...` và header abseil mà
  `sentencepiece_processor.h` yêu cầu, nên một clone mới không thể biên dịch. Script giờ cài cả hai và
  tự biên dịch thử một TU để kiểm tra trước khi kết thúc.

### Docs
- `ARCHITECTURE.md`: mô tả hệ thống ở mức hộp đen — bối cảnh, khối, luồng, bất biến, nhật ký quyết định.

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

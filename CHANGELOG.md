# Changelog

Tất cả các thay đổi đáng chú ý của dự án **VBookMT** sẽ được ghi chép tại đây theo định dạng [Keep a Changelog](https://keepachangelog.com/en/1.0.0/).

## [v1.2.0] - 2026-11-01

### Thêm mới
- **Tiện ích vBook đồng nhất**: Gộp thành một bản tiện ích duy nhất cho mọi thiết bị, tự động tìm và kết nối máy chủ dịch qua mạng Wi-Fi nội bộ, tự chuyển sang bộ dịch dự phòng khi máy chủ chưa bật.
- **Tùy chọn dung lượng mô hình**: Bổ sung lựa chọn các mức dung lượng (FP16, Q16, Q8) ngay trên màn hình chọn mô hình cho cả điện thoại và máy tính, giúp tiết kiệm bộ nhớ máy và mạng.
- **Giao diện máy tính mới**: Đồng bộ bố cục 3 mục (Tổng quan, Mô hình, Cài đặt) tương tự như trên điện thoại; bổ sung khu vực Dịch thử nghiệm nhanh để kiểm tra kết quả ngay trong app; cung cấp gói cài đặt Windows hoàn chỉnh (.msi).

### Thay đổi
- **Tối ưu dung lượng ứng dụng**: Ứng dụng Android nhẹ hơn khoảng 48 MB, giảm bớt chiếm dụng bộ nhớ khi cài đặt.
- **Tự động đo tốc độ chuẩn xác**: Chức năng "Đo và tối ưu" phản ánh đúng tốc độ thực tế của máy, ghi nhớ cấu hình nhanh nhất và không làm mất dữ liệu đã dịch trước đó.

### Sửa lỗi
- Khắc phục tình trạng mất kết nối hoặc treo ứng dụng khi đo tốc độ trên một số dòng máy.
- Sửa lỗi hiển thị sai trạng thái tải và dung lượng mô hình trên bản máy tính.
- Nâng cao độ ổn định và khả năng phục hồi kết nối khi dịch truyện dài.

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

### Thêm mới
- **Mô hình Dịch Mặc định**: Tích hợp mô hình CTranslate2 INT8 `HachimiMT-60-QT` của tác giả `ngocdang83` (chuẩn văn phong convert, giữ nguyên 100% ngôi xưng hô).
- **Mô hình Tùy chọn Tải thêm**: Hỗ trợ tải thêm mô hình `MoxhiMT-30-QT` của tác giả `DanVP` (chỉ 36MB, tiết kiệm RAM) và chuyển đổi trực tiếp trên ứng dụng.
- **Android Companion App**: Chạy `Foreground Service` kèm thông báo cố định chống bị hệ điều hành tắt ngầm do tối ưu pin.
- **Windows Companion App**: Giao diện điều khiển máy chủ dịch và quản lý mô hình.
- **Tiện ích vBook**: Kết nối qua HTTP nội bộ với app chạy nền.

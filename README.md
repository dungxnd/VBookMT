# VBookMT (VBook Offline Neural Machine Translation)

Hệ sinh thái dịch thuật offline bằng mô hình AI cục bộ (MarianMT: HachimiMT / MoxhiMT) dành riêng cho ứng dụng **vBook** trên Android và Windows.

## 🌟 Điểm nổi bật
- **100% Offline**: Không dùng Cloud, mất mạng hoặc tắt WiFi vẫn dịch mượt mà.
- **Zero Pronoun Flips (Chuẩn Convert)**: Sử dụng các mô hình `HachimiMT-60-QT` / `HachimiMT-40-QT` chuẩn văn phong kiếm hiệp/tiên hiệp (*ta, ngươi, hắn, nàng, tỷ muội*).
- **Siêu nhẹ & Tiết kiệm pin**: Model nén INT8 chỉ từ 40MB – 55MB. Khi nghỉ ngơi tiêu thụ 0.0% CPU.
- **Không lo bị sập (Android)**: App Android chạy dưới dạng Foreground Service (có Notification) chống bị hệ điều hành tắt ngầm do tối ưu pin.

---

## 🏗 Cấu trúc Monorepo

```text
VBookMT/
├── core/                        # Kotlin Multiplatform (KMP) shared logic
│   ├── src/commonMain/kotlin/   # Protocol, text pre/post-processing, <NL> handlers
│   └── build.gradle.kts
│
├── apps/
│   ├── android/                 # Android Companion App (Kotlin)
│   │   ├── src/main/            # Foreground Service + Embedded HTTP Server + ONNX Runtime
│   │   └── build.gradle.kts
│   │
│   └── windows/                 # Windows Native App (Kotlin/Native mingwX64)
│       ├── src/nativeMain/      # Standalone Native Executable (.exe) + ONNX Runtime C API
│       └── build.gradle.kts
│
├── vbook-extension/             # Extension vBook chuẩn
│   ├── plugin.json              # Khai báo type: "translate", version, metadata
│   ├── icon.png                 # Icon extension
│   └── src/
│       ├── config.js            # Cài đặt server URL (mặc định: http://127.0.0.1:8765)
│       ├── language.js          # Khai báo cặp ngôn ngữ (zh -> vi)
│       ├── model.js             # Danh sách model lựa chọn
│       └── translate.js         # Gửi text sang local server qua fetch
│
└── tools/
    └── model_converter/         # Bộ công cụ xuất và lượng tử hóa mô hình
        ├── export_onnx.py       # Xuất Hugging Face MarianMT sang ONNX INT8
        └── benchmark.py         # Kiểm tra tốc độ và chất lượng dịch
```

---

## 🚀 Luồng hoạt động

```
[vBook Reader (Android / Windows)]
             │
             ▼ (fetch qua http://127.0.0.1:8765/translate)
[VBookMT Companion Server (Android Service / Windows Native .exe)]
             │
             ▼ (Nạp model HachimiMT-40-QT / 60-QT qua ONNX Runtime)
[Kết quả dịch Tiếng Việt chuẩn convert siêu tốc]
```

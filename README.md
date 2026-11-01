# VBookMT

Bộ công cụ dịch truyện Trung $\rightarrow$ Việt offline cho ứng dụng **vBook**, sử dụng mô hình MarianMT (HachimiMT, MoxhiMT).

---

## 🚀 Hướng dẫn cài đặt & Sử dụng

### Bước 1: Cài đặt App máy chủ dịch (VBookMT)

Bạn cần cài đặt ứng dụng VBookMT trên điện thoại hoặc máy tính để chạy mô hình AI dịch nền:

* **Trên điện thoại Android**:
  1. Tải tệp cài đặt `VBookMT-Android.apk` từ mục [Releases](https://github.com/DungxND/VBookMT/releases).
  2. Mở app, chọn mô hình dịch mong muốn và nhấn **Tải về**.
  3. Gạt nút **Bật dịch offline** (ứng dụng sẽ chạy ngầm và mở cổng dịch nội bộ `8765`).

* **Trên máy tính Windows**:
  1. Tải và cài đặt `VBookMT-1.2.2.msi` từ mục [Releases](https://github.com/DungxND/VBookMT/releases).
  2. Mở app, tải mô hình dịch và nhấn **Bật máy chủ**.
  3. *(Tùy chọn)*: Bật **"Cho phép thiết bị khác kết nối & dò tìm qua LAN"** nếu bạn muốn dùng máy tính để dịch cho vBook trên điện thoại cùng mạng Wi-Fi.

---

### Bước 2: Cài đặt Tiện ích mở rộng vào vBook

Sau khi đã bật app VBookMT, bạn cài tiện ích vào vBook để đọc truyện:

* **Cách 1: Thêm link nguồn trực tiếp trong vBook (Khuyên dùng)**
  1. Mở **vBook** $\rightarrow$ **Cài đặt** $\rightarrow$ **Tiện ích mở rộng**.
  2. Nhấn nút **+** (Thêm nguồn) và dán link:
     ```text
     https://raw.githubusercontent.com/DungxND/VBookMT/main/plugin.json
     ```
  3. Nhấn cài đặt tiện ích **VBookMT**.

* **Cách 2: Cài từ tệp ZIP**
  1. Tải tệp [`vbookmt-extension.zip`](https://raw.githubusercontent.com/DungxND/VBookMT/main/vbook-extension/plugin.zip) từ [Releases](https://github.com/DungxND/VBookMT/releases).
  2. Mở **vBook** $\rightarrow$ **Tiện ích mở rộng** $\rightarrow$ chọn **Cài đặt từ tệp (ZIP)**.

---

## 🤖 Các mô hình hỗ trợ

| Mô hình | Kiểu chạy | Dung lượng | Tác giả | Đặc tính |
| :--- | :--- | :--- | :--- | :--- |
| **HachimiMT 60 QT** | ONNX | 124–205 MB | ngocdang83 (ONNX: DungxND) | Mặc định — Chất lượng dịch cao nhất, chuẩn phong cách convert kiếm hiệp/tiên hiệp |
| **MoxhiMT 30 QT** | ONNX | 88–142 MB | DanVP (ONNX: DungxND) | Siêu nhẹ — Tốc độ dịch nhanh, tiết kiệm RAM cho máy cấu hình thấp |

Mỗi mô hình có **hai mức lượng tử hoá**, chọn ngay trong app trước khi tải:

| Mức | HachimiMT 60 | MoxhiMT 30 | Chất lượng (chrF) | Ghi chú |
| :--- | ---: | ---: | ---: | :--- |
| **FP16** | 205 MB | 142 MB | 100.00 | Cao nhất, mặc định |
| **Q8** | 124 MB | 88 MB | 96.0 – 96.25 | Nhẹ nhất, nhanh nhất, ít RAM nhất (−50 MB) |

> chrF đo trên Snapdragon 8 Gen 3, so với chính bản fp32 của từng mô hình (100 = giống hệt).

> **Về ONNX:** đây là engine **duy nhất** — CTranslate2 đã được loại bỏ hoàn toàn khỏi ứng dụng
> (không còn thư viện native, không còn trong APK, giảm ~48 MB).
> **Lưu ý về backend xử lý (Không dùng QNN):** Ứng dụng **không còn sử dụng Qualcomm QNN (NPU/HTP)**
> do runtime QNN làm tăng kích thước APK thêm hơn 180 MB nhưng trên thực tế CPU đa luồng cho tốc độ
> cao hơn, ổn định và mát máy hơn. Hiện tại cả Android và Windows đều chạy trên nền **CPUExecutionProvider**
> được tối ưu hóa số luồng theo phần cứng từng máy.


---

## 📜 Bản quyền & Ghi nhận (Credits)

- **Tác giả dự án & Tiện ích**: DungxND
- **Mô hình dịch thuật**:
  - **HachimiMT-60-QT** bởi **[ngocdang83](https://huggingface.co/ngocdang83)** ([CC BY 4.0](https://creativecommons.org/licenses/by/4.0/)).
  - **MoxhiMT-30-QT** bởi **[DanVP](https://huggingface.co/DanVP)** ([CC BY-NC 4.0](https://creativecommons.org/licenses/by-nc/4.0/)).
  - Các bản port và tối ưu hóa **ONNX 6k** (FP16 / Q8) lưu trữ tại Hugging Face của **[dungxnd](https://huggingface.co/dungxnd)**.
- **Hệ sinh thái vBook** bởi **[Darkrai9x](https://github.com/Darkrai9x)**.

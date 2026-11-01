# Notices & Acknowledgements (Ghi nhận Tác quyền & Bản quyền)

Dự án **VBookMT** được phát triển bởi **dungxnd** và tích hợp các mô hình dịch máy, công cụ mã nguồn mở và bộ dữ liệu của các tác giả trong cộng đồng. 

---

## 0. Tác giả & Phát triển Dự án (Developer & Maintainer)

### VBookMT Architecture & Ecosystem
* **Tác giả / Nhà phát triển**: [dungxnd](https://github.com/dungxnd)
* **Kho lưu trữ**: [`dungxnd/VBookMT`](https://github.com/dungxnd/VBookMT)
* **Giấy phép**: [MIT License](LICENSE)
* **Mô tả**: Thiết kế và phát triển VBookMT: Android Companion App, Windows Native CLI Server, vBook Extension.

---

## 1. Các Mô hình Dịch thuật (Translation Models)

### HachimiMT (`HachimiMT-60-QT`, `HachimiMT-40-QT`)
* **Tác giả**: [ngocdang83](https://huggingface.co/ngocdang83)
* **Kho lưu trữ**: 
  - [`ngocdang83/HachimiMT-60-QT`](https://huggingface.co/ngocdang83/HachimiMT-60-QT) (Mô hình mặc định của VBookMT)
  - [`ngocdang83/HachimiMT-40-QT`](https://huggingface.co/ngocdang83/HachimiMT-40-QT)
* **Giấy phép**: [Creative Commons Attribution 4.0 International (CC BY 4.0)](https://creativecommons.org/licenses/by/4.0/)
* **Dữ liệu huấn luyện**: 
  - `ngocdang83/tran-vi-teacher`
  - `chi-vi/hirashiba-mt-zh2vi-b-filtered`
* **Ghi chú**: Tác giả đã tối ưu xuất sắc ngôi xưng hô convert chuẩn kiếm hiệp/tiên hiệp (*ta, ngươi, hắn, nàng, tỷ tỷ, ca ca...*) với tỷ lệ nhảy ngôi 0% và xuất sẵn định dạng CTranslate2 INT8.

### MoxhiMT (`MoxhiMT-30-QT`, `MoxhiMT-60`, `vp2vi`)
* **Tác giả**: [DanVP](https://huggingface.co/DanVP)
* **Kho lưu trữ**: 
  - [`DanVP/MoxhiMT-30-QT`](https://huggingface.co/DanVP/MoxhiMT-30-QT) (Mô hình tùy chọn chuyển đổi trong VBookMT)
  - [`DanVP/MoxhiMT-60`](https://huggingface.co/DanVP/MoxhiMT-60)
  - [`DanVP/vp2vi`](https://huggingface.co/DanVP/vp2vi)
* **Giấy phép**: 
  - `MoxhiMT-30-QT`: [Creative Commons Attribution-NonCommercial 4.0 International (CC BY-NC 4.0)](https://creativecommons.org/licenses/by-nc/4.0/)
  - `vp2vi`: CC BY 4.0
* **Dữ liệu huấn luyện**: `ngocdang83/tran-vi-teacher`
* **Ghi chú**: Phiên bản nén gọn nhẹ ~36MB siêu tốc với ngôi xưng hô QT ổn định tuyệt vời.

---

## 2. Công nghệ Cốt lõi & Engine Thực thi (Core Engines)

### ONNX Runtime
* **Tổ chức**: [Microsoft](https://github.com/microsoft/onnxruntime)
* **Giấy phép**: [MIT License](https://github.com/microsoft/onnxruntime/blob/main/LICENSE)
* **Mô tả**: Engine suy luận **duy nhất** của VBookMT, dùng cho các mô hình ONNX (FP16 / Q16 / Q8, từ
  vựng 6k). Trên Android ứng dụng yêu cầu Qualcomm QNN (backend NPU/HTP); máy không đáp ứng sẽ tự lùi
  về CPU và lý do được ghi ở `/health`. Trên Windows chạy CPU vì gói `onnxruntime` tiêu chuẩn không kèm
  provider GPU.
* **Ghi chú**: CTranslate2 đã được **loại bỏ** khỏi ứng dụng — không còn liên kết, không còn trong APK.
  Các mô hình gốc vẫn được tác giả upstream xuất ở định dạng CT2, xem ghi chú từng mô hình bên dưới.

### SentencePiece
* **Tác giả**: [Google LLC](https://github.com/google/sentencepiece) (Taku Kudo, John Richardson)
* **Giấy phép**: [Apache License 2.0](https://github.com/google/sentencepiece/blob/master/LICENSE)
* **Mô tả**: Trình tách từ (tokenizer/detokenizer) không phụ thuộc ngôn ngữ.

---

## 3. Hệ sinh thái Ứng dụng Liên quan (Ecosystem)

### vBook
* **Tác giả**: [Darkrai9x](https://github.com/Darkrai9x)
* **Kho lưu trữ**: [`Darkrai9x/vbook-extensions`](https://github.com/Darkrai9x/vbook-extensions) & [`Darkrai9x/vbook-settings`](https://github.com/Darkrai9x/vbook-settings)
* **Mô tả**: Ứng dụng đọc truyện mã nguồn mở đa nền tảng tuyệt vời của cộng đồng người Việt.

### Gemini Book Translator 2.0 (Ý tưởng Khởi nguồn)
* **Tác giả**: [Luca D'Alessandro](https://github.com/luca-dalessandro)
* **Kho lưu trữ**: [`luca-dalessandro/gemini-book-translator-2.0`](https://github.com/luca-dalessandro/gemini-book-translator-2.0)
* **Giấy phép**: MIT License
* **Mô tả**: Dự án truyền cảm hứng về mô hình dịch sách chapter-by-chapter.

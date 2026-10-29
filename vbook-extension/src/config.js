function execute() {
    return Response.success({
        server_url: {
            title: "Địa chỉ Local Server",
            subtitle: "Mặc định: http://127.0.0.1:8765 (không cần đổi nếu chạy app trên cùng máy)",
            default: "http://127.0.0.1:8765",
            mode: "input",
            format: "text"
        },
        fallback_qt: {
            title: "Tự động dùng QuickTranslator nếu server tắt",
            subtitle: "Nếu chưa bật app VBookMT, tự động dùng bộ dịch QT có sẵn của vBook để không gián đoạn đọc",
            default: true,
            mode: "switch"
        },
        notify_fallback: {
            title: "Thông báo khi dùng QuickTranslator",
            subtitle: "Thêm tiền tố [QT Fallback] để nhận biết máy chủ AI VBookMT đang tắt",
            default: false,
            mode: "switch"
        },
        timeout_seconds: {
            title: "Thời gian chờ tối đa (giây)",
            subtitle: "Thời gian chờ server phản hồi trước khi thử lại hoặc fallback (5 - 60s)",
            default: "15",
            mode: "input",
            format: "number"
        },
        clean_crawler_artifacts: {
            title: "Lọc số rác cuối đoạn (Crawler artifacts)",
            subtitle: "Tự động xóa số lượng bình luận dính ở đuôi đoạn văn khi cào từ web truyện",
            default: true,
            mode: "switch"
        }
    });
}

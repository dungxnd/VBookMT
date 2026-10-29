function execute() {
    return Response.success({
        server_url: {
            title: "Địa chỉ Local Server",
            subtitle: "Mặc định: http://127.0.0.1:8765 (không cần sửa nếu chạy app trên cùng máy)",
            default: "http://127.0.0.1:8765",
            mode: "input",
            format: "text"
        },
        fallback_qt: {
            title: "Tự động dùng QuickTranslator nếu server tắt",
            subtitle: "Nếu chưa bật app VBookMT, tự động dùng bộ dịch QT có sẵn của vBook để không gián đoạn đọc",
            default: true,
            mode: "switch"
        }
    });
}

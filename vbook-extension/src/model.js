function execute() {
    return Response.success([
        {
            id: "hachimi-60-qt",
            name: "HachimiMT 60 QT (Mặc định)",
            description: "Mặc định (ngocdang83) — dịch mượt, chuẩn xưng hô convert.",
            isNetworkRequired: false
        },
        {
            id: "moxhi-30-qt",
            name: "MoxhiMT 30 QT (Tùy chọn tải thêm)",
            description: "Bản nhẹ 36MB (DanVP) — tốc độ nhanh, ít tốn RAM.",
            isNetworkRequired: false
        },
        {
            id: "hachimi-40-qt",
            name: "HachimiMT 40 QT",
            description: "Bản 40MB (ngocdang83) — tối ưu ngắt dòng đoạn văn.",
            isNetworkRequired: false
        }
    ]);
}

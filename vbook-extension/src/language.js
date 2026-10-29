function execute() {
    return Response.success([
        { id: "zh", name: "Tiếng Trung" },
        { id: "zh-CN", name: "Tiếng Trung giản thể" },
        { id: "zh-TW", name: "Tiếng Trung phồn thể" },
        { id: "vi", name: "Tiếng Việt" }
    ]);
}

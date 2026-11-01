/**
 * Dynamic settings for the VBookMT extension.
 *
 * **Contract (vBook `extension-api.md`).** Each entry is `{ title, subtitle?, default, values?, mode, format? }`:
 *  - `mode` is one of `input` / `select` / `toggle` / `list` / `database` — case-sensitive. An unknown
 *    mode **renders nothing**, so a typo silently produces a settings screen with no control.
 *  - `select` takes its choices from **`values`**, a plain array of strings. There is no `options` key
 *    and no `{title, value}` form; a value not in `values` falls back to the first entry.
 *  - `default` is typed **string** — `"true"`, `"120"` — even for `toggle`.
 *  - `subtitle` is only displayed for `toggle` and `list`, so any explanation a `select` needs lives in
 *    its `title`.
 *
 * This file previously used `options: [{title, value}]` and `mode: "switch"`. Both are invalid, which
 * is why the connection-mode picker did not work: vBook rendered no control for it.
 *
 * Values are read at runtime with `localConfig.getItem(key)` (see `readConfigValue` in translate.js);
 * the `default` here is *not* returned by the host, so each reader supplies its own fallback.
 */
function execute() {
    return Response.success({
        connection_mode: {
            title: "Chế độ kết nối (localhost = cùng máy · auto_discover = tự tìm PC qua LAN · manual = nhập IP)",
            default: "localhost",
            values: ["localhost", "auto_discover", "manual"],
            mode: "select"
        },
        subnet_prefix: {
            title: "Dải mạng LAN để quét (Auto Discover)",
            subtitle: "Tiền tố dải mạng Wi-Fi (mặc định: 192.168.1). Nếu router cấp dải khác, đổi thành 192.168.0 hoặc 10.0.0",
            default: "192.168.1",
            mode: "input",
            format: "text"
        },
        server_url: {
            title: "Địa chỉ máy chủ (khi chọn manual)",
            subtitle: "Mặc định: http://127.0.0.1:8765 (hoặc IP máy tính, ví dụ: http://192.168.1.15:8765)",
            default: "http://127.0.0.1:8765",
            mode: "input",
            format: "text"
        },
        fallback_qt: {
            title: "Tự động dùng QuickTranslator nếu server tắt",
            subtitle: "Nếu chưa bật app VBookMT, tự động dùng bộ dịch QT có sẵn của vBook để không gián đoạn đọc",
            default: "true",
            mode: "toggle"
        },
        notify_fallback: {
            title: "Thông báo khi dùng QuickTranslator",
            subtitle: "Thêm tiền tố [QT Fallback] để nhận biết máy chủ AI VBookMT đang tắt",
            default: "false",
            mode: "toggle"
        },
        timeout_seconds: {
            title: "Thời gian chờ tối đa (giây)",
            subtitle: "Thời gian chờ server phản hồi trước khi thử lại hoặc fallback (5 - 120s). Nên lớn hơn timeout phía server (110s) để nhận được lỗi rõ ràng thay vì tự bỏ giữa chừng",
            default: "120",
            mode: "input",
            format: "number"
        },
        clean_crawler_artifacts: {
            title: "Lọc số rác cuối đoạn (Crawler artifacts)",
            subtitle: "Tự động xóa số lượng bình luận dính ở đuôi đoạn văn khi cào từ web truyện",
            default: "true",
            mode: "toggle"
        }
    });
}

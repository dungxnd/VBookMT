/**
 * VBookMT Translation Script
 *
 * Calls local CTranslate2 engine (http://127.0.0.1:8765) with:
 * - Robust input sanitization (zero-width characters & crawler comment artifacts).
 * - Automatic retry on transient socket timeout.
 * - Granular error classification (Connection Refused, Timeout, HTTP 5xx, Bad JSON).
 * - Safe fallback to built-in QuickTranslator (Qt.translate) when server is offline.
 */

// Regex patterns
var RE_TRAILING_NUMS = /(\D)\s*\d+$/;
var RE_ZERO_WIDTH = /[\u200B\u200C\u200D\uFEFF\u00A0]/g;

function sanitizeInput(text, cleanArtifacts) {
    if (!text) return "";
    var clean = text.replace(/\r\n/g, "\n").replace(/\r/g, "\n");
    clean = clean.replace(RE_ZERO_WIDTH, "");

    if (cleanArtifacts) {
        var lines = clean.split("\n");
        for (var i = 0; i < lines.length; i++) {
            var line = lines[i].trim();
            if (line) {
                lines[i] = line.replace(RE_TRAILING_NUMS, "$1");
            }
        }
        clean = lines.join("\n");
    }
    return clean;
}

function normalizeServerUrl(rawUrl) {
    var url = (rawUrl || "http://127.0.0.1:8765").trim();
    if (!url.match(/^https?:\/\//i)) {
        url = "http://" + url;
    }
    return url.replace(/\/+$/, "");
}

function safeFallbackQt(text, notify) {
    if (typeof Qt !== "undefined" && typeof Qt.translate === "function") {
        try {
            var qtRes = Qt.translate(text, "vp");
            if (qtRes && qtRes.translateText) {
                var output = qtRes.translateText;
                if (notify) {
                    output = "[QT Fallback - VBookMT Server chưa bật]\n\n" + output;
                }
                return output;
            }
        } catch (e) {
            // QT error ignored to report main server failure
        }
    }
    return null;
}

function readConfigValue(key, defaultVal) {
    try {
        if (typeof globalThis !== "undefined" && typeof globalThis[key] !== "undefined" && globalThis[key] !== null && globalThis[key] !== "") {
            return globalThis[key];
        }
        if (typeof this !== "undefined" && typeof this[key] !== "undefined" && this[key] !== null && this[key] !== "") {
            return this[key];
        }
    } catch (e) {}
    try {
        if (typeof localConfig !== "undefined" && typeof localConfig.getItem === "function") {
            var val = localConfig.getItem(key);
            if (val !== null && val !== undefined && val !== "") return val;
        }
    } catch (e) {}
    return defaultVal;
}

function execute(text, from, to, source, model, style) {
    text = text || "";
    from = from || "zh";
    to = to || "vi";
    model = model || "hachimi-60-qt";
    style = style || "qt";

    // 1. Basic validation
    if (!text.trim()) {
        return Response.success("");
    }

    // 2. Read configuration
    var rawServerUrl = readConfigValue("server_url", "http://127.0.0.1:8765");
    var fallbackQt = String(readConfigValue("fallback_qt", "true")) === "true";
    var notifyFallback = String(readConfigValue("notify_fallback", "false")) === "true";
    // 60s default: a 7k-char chapter costs ~3-5s on desktop and ~10-20s on a mid-range phone, so
    // the old 15s default cut the request off client-side and silently fell back to QuickTranslator
    // while the server was still legitimately translating.
    var cfgTimeout = parseInt(readConfigValue("timeout_seconds", 60), 10);
    var timeoutMs = (!isNaN(cfgTimeout) && cfgTimeout >= 5 && cfgTimeout <= 120) ? cfgTimeout * 1000 : 60000;
    var cleanArtifacts = String(readConfigValue("clean_crawler_artifacts", "true")) === "true";

    // 3. Sanitize input text
    var sanitizedText = sanitizeInput(text, cleanArtifacts);
    var serverUrl = normalizeServerUrl(rawServerUrl);
    var endpoint = serverUrl + "/translate";

    var payload = {
        text: sanitizedText,
        from: from,
        to: to,
        model: model,
        style: style
    };

    var payloadJson = JSON.stringify(payload);
    var lastError = "";
    var maxAttempts = 2; // Initial attempt + 1 retry

    // 4. Network request loop with retry
    for (var attempt = 1; attempt <= maxAttempts; attempt++) {
        try {
            var response = fetch(endpoint, {
                method: "POST",
                headers: {
                    "Content-Type": "application/json",
                    "Accept": "application/json"
                },
                body: payloadJson,
                timeout: timeoutMs
            });

            if (!response) {
                throw new Error("Không nhận được phản hồi từ server (Response is null)");
            }

            // Check HTTP status code
            if (response.ok) {
                var jsonText = response.text();
                if (!jsonText || !jsonText.trim()) {
                    throw new Error("Server trả về nội dung rỗng");
                }

                var data = null;
                try {
                    data = JSON.parse(jsonText);
                } catch (parseErr) {
                    throw new Error("Dữ liệu từ server không phải định dạng JSON hợp lệ: " + jsonText.substring(0, 100));
                }

                if (data && data.success && data.translatedText !== undefined) {
                    return Response.success(data.translatedText);
                } else if (data && data.error) {
                    return Response.error("Lỗi từ server VBookMT: " + data.error);
                } else {
                    throw new Error("Cấu trúc phản hồi từ server không đúng quy chuẩn");
                }
            } else {
                var errDetail = "";
                try {
                    errDetail = response.text().substring(0, 150);
                } catch (readErr) {}
                
                if (response.status === 404) {
                    throw new Error("Endpoint không tồn tại (HTTP 404). Kiểm tra lại đường dẫn: " + endpoint);
                } else if (response.status >= 500) {
                    throw new Error("Lỗi nội bộ máy chủ (HTTP " + response.status + "): " + errDetail);
                } else {
                    throw new Error("Yêu cầu bị từ chối (HTTP " + response.status + "): " + errDetail);
                }
            }
        } catch (netErr) {
            lastError = netErr ? (netErr.message || String(netErr)) : "Lỗi mạng không xác định";
            // Sleep briefly before retry
            if (attempt < maxAttempts) {
                if (typeof sleep === "function") {
                    sleep(300);
                }
            }
        }
    }

    // 5. If all attempts failed -> Try Fallback to QuickTranslator
    if (fallbackQt) {
        var fallbackResult = safeFallbackQt(sanitizedText, notifyFallback);
        if (fallbackResult) {
            return Response.success(fallbackResult);
        }
    }

    // 6. Return friendly, actionable error message
    var userFriendlyMsg = "Không thể kết nối đến máy chủ VBookMT (" + serverUrl + ").\n\n" +
        "Chi tiết: " + lastError + "\n\n" +
        "👉 Hướng dẫn khắc phục:\n" +
        "1. Mở ứng dụng VBookMT trên máy và bấm 'BẬT DỊCH OFFLINE'.\n" +
        "2. Đảm bảo điện thoại và máy chủ kết nối cùng mạng nếu dùng máy tính khác.\n" +
        "3. Hoặc bật tùy chọn 'Tự động dùng QuickTranslator' trong cài đặt extension để không bị gián đoạn đọc.";

    return Response.error(userFriendlyMsg);
}

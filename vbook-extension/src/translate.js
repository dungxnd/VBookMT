/**
 * VBookMT Translation Script
 *
 * Speaks gRPC-Web (application/grpc-web-text+proto) to a local CTranslate2 host and nothing else.
 *
 * 1.1.0 shipped two transports — gRPC-Web with a JSON fallback — because the host builds in the wild
 * were mixed. That is gone. Every host from 1.2.0 onward serves only
 * `/vbookmt.v1.Translator/<Method>`, so a silent JSON fallback would no longer be "compatibility",
 * it would be a second code path that quietly hides a version mismatch. A host that answers 404 now
 * produces a message telling the reader to update the app.
 *
 * What is kept:
 *  - Robust input sanitisation (zero-width characters, crawler comment artifacts).
 *  - Retry on transient UNAVAILABLE / RESOURCE_EXHAUSTED; immediate fallback on DEADLINE_EXCEEDED.
 *  - Safe fallback to the built-in QuickTranslator when the host is off, so reading never stops.
 */

// Load wire codec if not already present in environment
if (typeof VBookWire === "undefined") {
    try {
        if (typeof load === "function") {
            try {
                load("wire.js");
            } catch (e1) {
                try {
                    load("src/wire.js");
                } catch (e2) {}
            }
        }
    } catch (e) {}
}

// Session state: remember auto-discovered server URL across calls
var sessionDiscoveredUrl = null;

function saveConfigValue(key, val) {
    try {
        if (typeof localConfig !== "undefined" && typeof localConfig.setItem === "function") {
            localConfig.setItem(key, val);
        }
    } catch (e) {}
}

/**
 * Probes `GET /` — the identity document — rather than `/health`.
 *
 * `/health` was a JSON route and no longer exists. `/` is the one endpoint that stays alongside the
 * RPC surface: it returns no reader content, cannot translate, and exists precisely so a discovery
 * scan can answer "is a VBookMT host here" in one cheap request.
 */
function verifyVBookServer(baseUrl, timeoutMs) {
    if (!baseUrl) return false;
    var normalized = normalizeServerUrl(baseUrl);
    try {
        var res = fetch(normalized + "/", {
            method: "GET",
            timeout: timeoutMs || 300
        });
        if (res && res.status === 200) {
            var body = typeof res.text === "function" ? res.text() : "";
            if (body && (body.indexOf('"service":"vbookmt"') !== -1 || body.indexOf('"service": "vbookmt"') !== -1)) {
                return true;
            }
        }
    } catch (e) {}
    return false;
}

function discoverLanServer(subnetPrefix) {
    // 1. Check in-memory session cache first (fastest)
    if (sessionDiscoveredUrl && verifyVBookServer(sessionDiscoveredUrl, 250)) {
        return sessionDiscoveredUrl;
    }

    // 2. Check persistent cache from localConfig
    var cached = readConfigValue("discovered_server_url", "");
    if (cached && verifyVBookServer(cached, 250)) {
        sessionDiscoveredUrl = cached;
        return cached;
    }

    // 3. Scan candidate IPs in subnet on fixed port 8765
    var prefix = (subnetPrefix || "192.168.1").trim().replace(/\.+$/, "");
    var hostCandidates = [
        2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15,
        16, 17, 18, 19, 20, 21, 22, 23, 24, 25, 26, 27, 28, 29, 30,
        100, 101, 102, 103, 104, 105, 106, 107, 108, 109, 110,
        1
    ];

    for (var i = 0; i < hostCandidates.length; i++) {
        var testUrl = "http://" + prefix + "." + hostCandidates[i] + ":8765";
        if (verifyVBookServer(testUrl, 150)) {
            sessionDiscoveredUrl = testUrl;
            saveConfigValue("discovered_server_url", testUrl);
            return testUrl;
        }
    }

    return null;
}

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
                    output = "[QT Fallback - Máy chủ VBookMT chưa bật]\n\n" + output;
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

function getHeader(response, name) {
    if (!response) return null;
    var lower = name.toLowerCase();
    try {
        if (response.headers) {
            if (typeof response.headers.get === "function") {
                var val = response.headers.get(name) || response.headers.get(lower);
                if (val !== null && val !== undefined) return val;
            }
            if (typeof response.headers === "object") {
                if (response.headers[name] !== undefined) return response.headers[name];
                if (response.headers[lower] !== undefined) return response.headers[lower];
            }
        }
    } catch (e) {}
    try {
        if (typeof response.header === "function") {
            var val2 = response.header(name) || response.header(lower);
            if (val2 !== null && val2 !== undefined) return val2;
        }
    } catch (e) {}
    return null;
}

// vBook's translate contract fixes this positional signature, so the model slot stays - but the
// model is chosen inside the VBookMT app. The plugin never selects one.
function execute(text, from, to, source, _model, style) {
    text = text || "";
    from = from || "zh";
    to = to || "vi";
    style = style || "qt";

    // 1. Basic validation
    if (!text.trim()) {
        return Response.success("");
    }

    // 2. Read configuration
    var connMode = String(readConfigValue("connection_mode", "localhost")).toLowerCase().trim();
    var subnetPrefix = String(readConfigValue("subnet_prefix", "192.168.1")).trim();
    var rawServerUrl = readConfigValue("server_url", "http://127.0.0.1:8765");
    var fallbackQt = String(readConfigValue("fallback_qt", "true")) === "true";
    var notifyFallback = String(readConfigValue("notify_fallback", "false")) === "true";
    var cfgTimeout = parseInt(readConfigValue("timeout_seconds", 120), 10);
    var timeoutMs = (!isNaN(cfgTimeout) && cfgTimeout >= 5 && cfgTimeout <= 120) ? cfgTimeout * 1000 : 120000;
    var cleanArtifacts = String(readConfigValue("clean_crawler_artifacts", "true")) === "true";

    // 3. Sanitize input text
    var sanitizedText = sanitizeInput(text, cleanArtifacts);

    // 4. Resolve target server URL based on connection mode
    var targetUrl = "http://127.0.0.1:8765";
    if (connMode === "manual") {
        targetUrl = normalizeServerUrl(rawServerUrl);
    } else if (connMode === "auto_discover") {
        var discovered = discoverLanServer(subnetPrefix);
        if (discovered) {
            targetUrl = discovered;
        } else {
            var discoverErr = "Không tìm thấy máy chủ VBookMT trên mạng LAN (dải " + subnetPrefix + ".x:8765). Hãy chắc chắn máy tính đã bật 'Cho phép thiết bị khác kết nối & dò tìm qua LAN' và chung Wi-Fi.";
            if (fallbackQt) {
                var qtText = safeFallbackQt(sanitizedText, notifyFallback);
                if (qtText !== null) return Response.success(qtText);
            }
            return Response.error(discoverErr);
        }
    } else {
        // "localhost" mode (default)
        targetUrl = "http://127.0.0.1:8765";
    }

    var serverUrl = normalizeServerUrl(targetUrl);

    // Wire codec is bundled with the plugin; without it there is nothing to speak.
    if (typeof VBookWire === "undefined") {
        var wireErr = "Thiếu bộ giải mã gRPC-Web (wire.js) trong gói extension. Hãy cài lại VBookMT từ nguồn chính thức.";
        if (fallbackQt) {
            var qtWire = safeFallbackQt(sanitizedText, notifyFallback);
            if (qtWire !== null) return Response.success(qtWire);
        }
        return Response.error(wireErr);
    }

    var grpcEndpoint = serverUrl + "/vbookmt.v1.Translator/Translate";
    var grpcBody = VBookWire.encodeGrpcWebText(VBookWire.encodeTranslateRequest({
        text: sanitizedText,
        from: from,
        to: to,
        style: style,
        model: ""
    }));

    var lastError = "";
    var maxAttempts = 2; // Initial attempt + 1 retry
    var hostOutdated = false;

    // -------------------------------------------------------------------------
    // 5. gRPC-Web call
    // -------------------------------------------------------------------------
    for (var attempt = 1; attempt <= maxAttempts; attempt++) {
        try {
            var response = fetch(grpcEndpoint, {
                method: "POST",
                headers: {
                    "Content-Type": "application/grpc-web-text+proto",
                    "Accept": "application/grpc-web-text+proto",
                    "x-grpc-web": "1"
                },
                body: grpcBody,
                timeout: timeoutMs
            });

            if (!response) {
                throw new Error("Không nhận được phản hồi từ server (Response is null)");
            }

            // 404 means the host predates the gRPC-only surface. Retrying cannot fix that, and a
            // silent downgrade would hide it — say so instead.
            if (response.status === 404) {
                hostOutdated = true;
                lastError = "Máy chủ không có endpoint gRPC-Web (HTTP 404).";
                break;
            }

            if (response.status !== 200) {
                throw new Error("Máy chủ trả về HTTP lỗi: " + response.status);
            }

            // The wire contract puts status in headers, never in the HTTP status: a well-formed
            // gRPC-Web call is always HTTP 200, success or failure.
            var rawGrpcStatus = getHeader(response, "grpc-status");
            var grpcStatusCode = rawGrpcStatus !== null ? parseInt(rawGrpcStatus, 10) : 0;
            var rawGrpcMessage = getHeader(response, "grpc-message");
            var grpcMessage = rawGrpcMessage ? decodeURIComponent(rawGrpcMessage) : "";

            if (grpcStatusCode === 0) {
                var bodyText = response.text();
                if (!bodyText || !bodyText.trim()) {
                    throw new Error("Server gRPC-Web trả về body rỗng");
                }
                var respData = VBookWire.decodeTranslateResponse(VBookWire.decodeGrpcWebText(bodyText));
                if (respData && respData.success && respData.translated_text !== undefined) {
                    return Response.success(respData.translated_text);
                }
                if (respData && respData.error) {
                    return Response.error("Lỗi từ máy chủ VBookMT: " + respData.error);
                }
                throw new Error("Phản hồi gRPC-Web không đúng quy chuẩn");
            }

            if (grpcStatusCode === 3) {
                // INVALID_ARGUMENT (e.g. > 50 000 chars): show the source unchanged, do not retry
                // and do not fall back — QuickTranslator would be no better on an oversized input.
                return Response.success(sanitizedText);
            }

            if (grpcStatusCode === 14) {
                // UNAVAILABLE: model still loading. Retrying is worth it; falling back is not yet.
                lastError = grpcMessage || "Mô hình chưa nạp xong";
                if (attempt < maxAttempts) {
                    if (typeof sleep === "function") sleep(2000);
                    continue;
                }
                break;
            }

            if (grpcStatusCode === 4) {
                // DEADLINE_EXCEEDED: the host already spent its whole budget. A retry would spend
                // another one, so go straight to the fallback.
                lastError = grpcMessage || "Máy chủ vượt quá thời gian xử lý";
                break;
            }

            if (grpcStatusCode === 8) {
                // RESOURCE_EXHAUSTED: oversized payload or a full queue. A short retry is cheap.
                lastError = grpcMessage || "Máy chủ quá tải";
                if (attempt < maxAttempts) {
                    if (typeof sleep === "function") sleep(300);
                    continue;
                }
                break;
            }

            lastError = grpcMessage || ("Lỗi gRPC máy chủ (" + grpcStatusCode + ")");
            break;
        } catch (netErr) {
            lastError = netErr ? (netErr.message || String(netErr)) : "Lỗi mạng gRPC không xác định";
            if (attempt < maxAttempts && typeof sleep === "function") {
                sleep(300);
            }
        }
    }

    // -------------------------------------------------------------------------
    // 6. Fallback to QuickTranslator if enabled
    // -------------------------------------------------------------------------
    if (fallbackQt) {
        var fallbackResult = safeFallbackQt(sanitizedText, notifyFallback);
        if (fallbackResult) {
            return Response.success(fallbackResult);
        }
    }

    // -------------------------------------------------------------------------
    // 7. Actionable error message
    // -------------------------------------------------------------------------
    if (connMode === "auto_discover") {
        sessionDiscoveredUrl = null;
        saveConfigValue("discovered_server_url", "");
    }

    if (hostOutdated) {
        return Response.error(
            "Máy chủ VBookMT đang chạy bản cũ (chưa hỗ trợ gRPC-Web).\n\n" +
            "👉 Hãy cập nhật app VBookMT lên bản 1.2.0 trở lên:\n" +
            "• Android: cài lại APK từ trang Releases.\n" +
            "• Windows: tải lại gói VBookMT-Windows mới nhất.\n\n" +
            "Chi tiết: " + lastError
        );
    }

    return Response.error(
        "Không thể kết nối đến máy chủ VBookMT (" + serverUrl + ").\n\n" +
        "Chi tiết: " + lastError + "\n\n" +
        "👉 Hướng dẫn khắc phục:\n" +
        "1. Mở ứng dụng VBookMT trên máy và bật máy chủ.\n" +
        "2. Đảm bảo điện thoại và máy chủ cùng mạng nếu dùng máy tính.\n" +
        "3. Hoặc bật 'Tự động dùng QuickTranslator' trong cài đặt extension để không bị gián đoạn đọc."
    );
}

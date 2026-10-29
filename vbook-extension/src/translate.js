function execute(text, from, to, source, model, style) {
    text = text || "";
    from = from || "zh";
    to = to || "vi";
    model = model || "hachimi-60-qt";
    style = style || "qt";

    if (!text.trim()) {
        return Response.success("");
    }

    var serverUrl = "http://127.0.0.1:8765";
    var fallbackQt = true;

    try {
        var cfgUrl = localConfig.getItem("server_url");
        if (cfgUrl && cfgUrl.trim()) {
            serverUrl = cfgUrl.trim().replace(/\/+$/, "");
        }
        var cfgFallback = localConfig.getItem("fallback_qt");
        if (cfgFallback !== null && cfgFallback !== undefined) {
            fallbackQt = String(cfgFallback) === "true";
        }
    } catch (e) {
        // Fallback default values
    }

    try {
        var endpoint = serverUrl + "/translate";
        var payload = {
            text: text,
            from: from,
            to: to,
            model: model,
            style: style
        };

        var response = fetch(endpoint, {
            method: "POST",
            headers: {
                "Content-Type": "application/json"
            },
            body: JSON.stringify(payload),
            timeout: 15000
        });

        if (response && response.ok) {
            var data = response.json();
            if (data && data.success && data.translatedText) {
                return Response.success(data.translatedText);
            } else if (data && data.error) {
                return Response.error("Lỗi từ server VBookMT: " + data.error);
            }
        }
        
        throw new Error("HTTP " + (response ? response.status : "unknown"));
    } catch (err) {
        // Nếu không kết nối được và bật fallback QT, dùng QuickTranslator có sẵn của vBook
        if (fallbackQt && typeof Qt !== "undefined" && typeof Qt.translate === "function") {
            try {
                var qtRes = Qt.translate(text, "vp");
                if (qtRes && qtRes.translateText) {
                    return Response.success(qtRes.translateText);
                }
            } catch (qtErr) {
                // Ignore QT error and report server connection error
            }
        }

        return Response.error(
            "Không kết nối được server VBookMT (" + serverUrl + "). " +
            "Vui lòng bật ứng dụng VBookMT trên máy để dịch offline!"
        );
    }
}

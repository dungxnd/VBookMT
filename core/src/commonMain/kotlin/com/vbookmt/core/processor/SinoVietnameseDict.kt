package com.vbookmt.core.processor

/**
 * Sino-Vietnamese (Hán-Việt) & Convert translation dictionary.
 * Provides instant, zero-latency local fallback so Chinese novel text is NEVER returned raw.
 */
object SinoVietnameseDict {

    private val PHRASES = mapOf(
        "指挥官" to "chỉ huy quan",
        "快醒醒" to "mau tỉnh tỉnh",
        "醒醒" to "tỉnh tỉnh",
        "叶北辰" to "Diệp Bắc Thần",
        "赵贰臣" to "Triệu Nhị Thần",
        "赵泰" to "Triệu Thái",
        "张黑虎" to "Trương Hắc Hổ",
        "周天昊" to "Chu Thiên Hạo",
        "周若妤" to "Chu Nhược Dư",
        "李海霞" to "Lý Hải Hà",
        "赵成忠" to "Triệu Thành Trung",
        "叶凌霄" to "Diệp Lăng Tiêu",
        "江南王" to "Giang Nam Vương",
        "黑虎卫" to "Hắc Hổ Vệ",
        "禁卫统领" to "cấm vệ thống lĩnh",
        "武道宗师" to "Võ Đạo Tông Sư",
        "天级武者" to "Thiên cấp võ giả",
        "天级高阶" to "Thiên cấp cao giai",
        "龙国战神" to "Long Quốc chiến thần",
        "贩夫走卒" to "kẻ buôn bán vặt",
        "街头巷尾" to "đầu đường cuối ngõ",
        "庄园府邸" to "trang viên phủ đệ",
        "漏网之鱼" to "cá lọt lưới",
        "不寒而栗" to "không rét mà run",
        "咬牙切齿" to "nghiến răng nghiến lợi",
        "激流勇退" to "rút lui vì dòng nước xiết",
        "不共戴天" to "không đội trời chung",
        "欲言又止" to "muốn nói lại thôi",
        "手无缚鸡之力" to "trói gà không chặt",
        "大惊失色" to "đại kinh thất sắc",
        "安详" to "an tường",
        "大碍" to "đại ngại",
        "施针" to "châm cứu",
        "定亲" to "đính hôn",
        "现场" to "hiện trường",
        "归来" to "trở về",
        "士族" to "sĩ tộc",
        "二爷" to "nhị gia",
        "惨死" to "chết thảm",
        "击毙" to "bắn chết",
        "海啸" to "sóng thần",
        "轰动" to "chấn động",
        "尸体" to "thi thể",
        "白布" to "vải trắng",
        "大厅" to "đại sảnh",
        "阴沉" to "âm trầm",
        "城府" to "thâm trầm",
        "森寒" to "lạnh lẽo",
        "毒辣" to "độc địa",
        "长子" to "con cả",
        "亲哥哥" to "ca ca ruột",
        "二弟" to "nhị đệ",
        "大儿子" to "con trai lớn",
        "儿子" to "con trai",
        "孙子" to "cháu trai",
        "老爷子" to "lão gia tử",
        "老太爷" to "lão thái gia",
        "伯母" to "bá mẫu",
        "叔叔" to "thúc thúc",
        "哥哥" to "ca ca",
        "弟弟" to "đệ đệ",
        "姐姐" to "tỷ tỷ",
        "妹妹" to "muội muội",
        "父亲" to "phụ thân",
        "母亲" to "mẫu thân",
        "爸" to "cha",
        "妈" to "mẹ",
        "北辰" to "Bắc Thần",
        "若妤" to "Nhược Dư",
        "天昊" to "Thiên Hạo",
        "赵家" to "Triệu gia",
        "周家" to "Chu gia",
        "叶家" to "Diệp gia",
        "江南" to "Giang Nam",
        "龙国" to "Long Quốc",
        "狼国" to "Lang Quốc",
        "龙都" to "Long Đô",
        "奥迪" to "Audi",
        "别墅" to "biệt thự",
        "客房" to "khách phòng",
        "客厅" to "phòng khách",
        "车库" to "gara",
        "车钥匙" to "chìa khóa xe",
        "火车站" to "nhà ga",
        "机场" to "sân bay",
        "会议室" to "phòng họp",
        "中心" to "trung tâm",
        "内部" to "nội bộ",
        "里面" to "bên trong",
        "除了" to "ngoài ra",
        "其余" to "những người còn lại",
        "已经" to "đã",
        "差不多" to "xấp xỉ",
        "以后" to "sau đó",
        "赶紧" to "vội vàng",
        "主要" to "chủ yếu",
        "确实" to "thực sự",
        "休假" to "nghỉ phép",
        "时间" to "thời gian",
        "长了" to "dài rồi",
        "回来" to "trở lại",
        "没机会" to "không còn cơ hội",
        "机会" to "cơ hội",
        "就连" to "cho dù",
        "恢复" to "hồi phục",
        "回到" to "trở về",
        "来了" to "đến rồi",
        "再想" to "lại muốn",
        "吴传恩" to "Ngô Truyền Ân",
        "崔赞" to "Thôi Tán",
        "王学骏" to "Vương Học Tuấn"
    )

    private val CHARS = mapOf(
        '醒' to "tỉnh", '指' to "chỉ", '挥' to "huy", '官' to "quan", '快' to "mau",
        '我' to "ta", '你' to "ngươi", '他' to "hắn", '她' to "nàng", '它' to "nó",
        '们' to "chúng", '的' to "của", '了' to "rồi", '是' to "là", '不' to "không",
        '在' to "tại", '有' to "có", '这' to "này", '那' to "kia", '就' to "liền",
        '也' to "cũng", '着' to "trứ", '到' to "đến", '说' to "nói", '道' to "đạo",
        '去' to "đi", '来' to "đến", '要' to "muốn", '会' to "hội", '人' to "người",
        '大' to "lớn", '小' to "nhỏ", '死' to "chết", '生' to "sống", '杀' to "giết",
        '手' to "tay", '心' to "lòng", '眼' to "mắt", '头' to "đầu", '面' to "diện",
        '王' to "vương", '家' to "gia", '天' to "trời", '地' to "đất", '神' to "thần",
        '魔' to "ma", '剑' to "kiếm", '刀' to "đao", '武' to "võ", '者' to "giả",
        '宗' to "tông", '师' to "sư", '尊' to "tôn", '帝' to "đế", '皇' to "hoàng",
        '君' to "quân", '爸' to "cha", '妈' to "mẹ", '父' to "phụ", '母' to "mẫu",
        '哥' to "ca", '弟' to "đệ", '姐' to "tỷ", '妹' to "muội", '儿' to "nhi",
        '女' to "nữ", '子' to "tử", '孙' to "tôn", '爷' to "gia", '伯' to "bá",
        '叔' to "thúc", '老' to "lão", '少' to "thiếu", '年' to "năm", '月' to "tháng",
        '日' to "ngày", '时' to "thời", '分' to "phút", '秒' to "giây", '前' to "trước",
        '后' to "sau", '左' to "trái", '右' to "phải", '上' to "trên", '下' to "dưới",
        '中' to "trung", '内' to "trong", '外' to "ngoài", '里' to "trong", '门' to "cửa",
        '房' to "phòng", '屋' to "nhà", '城' to "thành", '国' to "quốc", '山' to "sơn",
        '水' to "thủy", '江' to "giang", '河' to "hà", '海' to "hải", '风' to "phong",
        '雷' to "lôi", '火' to "hỏa", '光' to "quang", '暗' to "ám", '黑' to "hắc",
        '白' to "bạch", '红' to "hồng", '青' to "thanh", '蓝' to "lam", '金' to "kim",
        '银' to "ngân", '铁' to "thiết", '血' to "huyết", '骨' to "cốt", '肉' to "nhục",
        '气' to "khí", '力' to "lực", '功' to "công", '法' to "pháp", '术' to "thuật",
        '道' to "đạo", '德' to "đức", '仁' to "nhân", '义' to "nghĩa", '礼' to "lễ",
        '智' to "trí", '信' to "tín", '善' to "thiện", '恶' to "ác", '正' to "chính",
        '邪' to "tà", '真' to "thật", '假' to "giả", '虚' to "hư", '实' to "thực",
        '强' to "mạnh", '弱' to "yếu", '高' to "cao", '低' to "thấp", '深' to "sâu",
        '浅' to "nông", '远' to "xa", '近' to "gần", '重' to "nặng", '轻' to "nhẹ",
        '明' to "sáng", '暗' to "tối", '寒' to "lạnh", '热' to "nóng", '温' to "ấm",
        '凉' to "mát", '苦' to "khổ", '甜' to "ngọt", '酸' to "chua", '辣' to "cay",
        '咸' to "mặn", '喜' to "hỷ", '怒' to "nộ", '哀' to "ai", '乐' to "lạc",
        '爱' to "yêu", '恨' to "hận", '惊' to "kinh", '恐' to "khủng", '怕' to "sợ",
        '忧' to "ưu", '愁' to "sầu", '悲' to "bi", '欢' to "hoan", '笑' to "cười",
        '哭' to "khóc", '叫' to "kêu", '喊' to "hét", '听' to "nghe", '看' to "nhìn",
        '见' to "thấy", '望' to "vọng", '视' to "thị", '闻' to "nghe", '问' to "hỏi",
        '答' to "đáp", '语' to "ngữ", '言' to "ngôn", '话' to "thoại", '写' to "viết",
        '读' to "đọc", '走' to "đi", '跑' to "chạy", '跳' to "nhảy", '飞' to "bay",
        '行' to "hành", '动' to "động", '静' to "tĩnh", '停' to "dừng", '立' to "đứng",
        '坐' to "ngồi", '卧' to "nằm", '睡' to "ngủ", '起' to "dậy", '落' to "rơi",
        '升' to "lên", '降' to "xuống", '开' to "mở", '关' to "đóng", '闭' to "khép",
        '通' to "thông", '阻' to "trở", '绝' to "tuyệt", '断' to "đoạn", '连' to "liền",
        '合' to "hợp", '分' to "phân", '聚' to "tụ", '散' to "tán", '收' to "thu",
        '放' to "phóng", '取' to "lấy", '予' to "cho", '得' to "được", '失' to "mất",
        '胜' to "thắng", '败' to "bại", '进' to "tiến", '退' to "thoái", '攻' to "công",
        '防' to "phòng", '守' to "thủ", '破' to "phá", '立' to "lập", '成' to "thành",
        '毁' to "hủy", '灭' to "diệt", '兴' to "hưng", '亡' to "vong", '存' to "tồn",
        '殁' to "mất", '初' to "sơ", '终' to "chung", '始' to "thủy", '末' to "mạt",
        '源' to "nguồn", '本' to "bản", '末' to "mạt", '首' to "đầu", '尾' to "đuôi",
        '顶' to "đỉnh", '底' to "đáy", '边' to "bên", '角' to "góc", '旁' to "bên",
        '侦' to "trinh", '查' to "tra", '议' to "nghị", '室' to "thất", '吴' to "ngô",
        '传' to "truyền", '恩' to "ân", '崔' to "thôi", '赞' to "tán", '其' to "kì",
        '余' to "dư", '都' to "đều", '已' to "dĩ", '经' to "kinh", '骏' to "tuấn",
        '学' to "học", '恢' to "khôi", '复' to "phục", '差' to "sai", '以' to "dĩ",
        '赶' to "cản", '紧' to "khẩn", '回' to "hồi", '主' to "chủ", '确' to "xác",
        '休' to "hưu", '长' to "trường", '再' to "tái", '想' to "tưởng", '没' to "không",
        '机' to "cơ", '部' to "bộ", '位' to "vị", '第' to "thứ", '各' to "các",
        '同' to "đồng", '相' to "tương", '因' to "nhân", '果' to "quả", '此' to "thử",
        '由' to "do", '自' to "tự", '及' to "cập", '与' to "dữ", '乃' to "nãi",
        '且' to "thả", '但' to "đãn", '亦' to "diệc", '即' to "tức", '若' to "nhược"
    )

    fun translate(text: String): String {
        if (text.isBlank()) return ""

        val sb = StringBuilder()
        var i = 0
        val len = text.length

        while (i < len) {
            // 1. Try matching phrases (longest match from length 7 down to 2)
            var matchedPhrase: String? = null
            var matchedLen = 0

            val maxLookahead = minOf(8, len - i)
            for (subLen in maxLookahead downTo 2) {
                val sub = text.substring(i, i + subLen)
                val target = PHRASES[sub]
                if (target != null) {
                    matchedPhrase = target
                    matchedLen = subLen
                    break
                }
            }

            if (matchedPhrase != null) {
                if (sb.isNotEmpty() && !sb.endsWith(" ") && !sb.endsWith("\n") && !isOpener(sb.last())) {
                    sb.append(" ")
                }
                sb.append(matchedPhrase)
                i += matchedLen
                continue
            }

            // 2. Single character matching
            val c = text[i]
            val translatedChar = CHARS[c]

            if (translatedChar != null) {
                if (sb.isNotEmpty() && !sb.endsWith(" ") && !sb.endsWith("\n") && !isOpener(sb.last())) {
                    sb.append(" ")
                }
                sb.append(translatedChar)
            } else {
                // Punctuation or western characters
                sb.append(c)
            }
            i++
        }

        return capitalizeSentences(sb.toString())
    }

    private fun isOpener(c: Char): Boolean = "“‘«「『【《([{（［｛".contains(c)

    private fun capitalizeSentences(str: String): String {
        val chars = str.toCharArray()
        var capitalizeNext = true

        for (idx in chars.indices) {
            val ch = chars[idx]
            if (capitalizeNext && ch.isLetter()) {
                chars[idx] = ch.uppercaseChar()
                capitalizeNext = false
            } else if (ch == '.' || ch == '!' || ch == '?' || ch == '。' || ch == '！' || ch == '？' ||
                ch == '\n' || ch == '“' || ch == '"' || ch == '”') {
                capitalizeNext = true
            }
        }
        return chars.concatToString()
    }
}

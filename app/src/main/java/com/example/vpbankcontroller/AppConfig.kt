package com.example.vpbankcontroller

/**
 * Centralised configuration – dựa trên workflow thực tế của VPBank NEO.
 *
 * VPBank NEO thường obfuscate resource IDs, nên service dùng TEXT-based search
 * là chính. Các hằng TEXT_* là nội dung hiển thị trên nút/label.
 */
object AppConfig {

    /** Package VPBank NEO – người dùng có thể đổi trong UI. */
    var vpbankPackage: String = "com.vnpay.vpbankonline"

    // ── Smart OTP PIN (6 chữ số) ──────────────────────────────────────────────
    /** PIN Smart OTP lưu tạm trong bộ nhớ (KHÔNG lưu xuống disk). */
    var smartOtpPin: String = ""

    // ── Text content của các nút/màn hình (dùng để findNodesByText) ──────────

    /** Màn hình nhập MKH: hint/label của field Mã khách hàng */
    const val TEXT_MKH_HINT         = "Mã khách hàng"

    /** Nút "Tiếp tục" (xuất hiện ở nhiều màn hình) */
    const val TEXT_BTN_TIEP_TUC     = "Tiếp tục"

    // ── Loại điện (chọn theo prefix MKH) ─────────────────────────────────────
    /** PB → Điện toàn quốc (EVN) */
    const val TEXT_ELEC_TOAN_QUOC   = "Điện toàn quốc"
    /** PE → Điện Hồ Chí Minh (EVN HCM) */
    const val TEXT_ELEC_HCM         = "Điện Hồ Chí Minh"

    /** Popup kích hoạt thanh toán tự động: nút từ chối */
    const val TEXT_BTN_SKIP_AUTO    = "Không, tiếp tục thanh toán"

    /** Màn hình xác nhận: nút Xác nhận */
    const val TEXT_BTN_XAC_NHAN    = "Xác nhận"

    /** Màn hình Smart OTP PIN: title */
    const val TEXT_SMART_OTP_TITLE  = "Smart OTP"

    /** Màn hình OTP nâng cao: nút xác nhận giao dịch */
    const val TEXT_BTN_XAC_NHAN_GD  = "Xác nhận giao dịch"

    /** Màn hình Thành công */
    const val TEXT_SUCCESS           = "Thành công"

    /** Nút Giao dịch mới (màn hình thành công) */
    const val TEXT_BTN_NEW_TX        = "Giao dịch mới"

    /** Popup tiêu đề "Kích hoạt thanh toán tự động" */
    const val TEXT_AUTO_PAY_POPUP   = "Kích hoạt thanh toán tự động"

    /** Màn hình Xác nhận (title) */
    const val TEXT_CONFIRM_SCREEN   = "Xác nhận"

    /** Màn hình xác nhận OTP nâng cao (title) */
    const val TEXT_OTP_ADV_SCREEN   = "Xác nhận OTP nâng cao"

    // ── Timing (ms) ───────────────────────────────────────────────────────────
    /** Chờ sau khi nhập MKH trước khi bấm Tiếp tục. */
    const val DELAY_AFTER_TEXT_MS    = 700L
    /** Chờ màn hình mới load sau mỗi lần bấm nút. */
    const val DELAY_AFTER_CLICK_MS   = 3_000L
    /** Polling khi chưa thấy element cần thiết. */
    const val POLL_INTERVAL_MS       = 800L
    /** Số lần thử lại tối đa trước khi bỏ qua MKH. */
    const val MAX_RETRY              = 15

    // ── SharedPreferences ────────────────────────────────────────────────────
    const val PREFS_NAME             = "vpbank_controller"
    const val KEY_MKH_LIST           = "mkh_list"
    const val KEY_MKH_DRAFT          = "mkh_draft"
    const val KEY_CURRENT_INDEX      = "current_index"
    const val KEY_IS_RUNNING         = "is_running"
    const val KEY_VPBANK_PACKAGE     = "vpbank_package"

    // ── LocalBroadcast actions ───────────────────────────────────────────────
    const val ACTION_START    = "com.example.vpbankcontroller.START"
    const val ACTION_STOP     = "com.example.vpbankcontroller.STOP"
    const val ACTION_PROGRESS = "com.example.vpbankcontroller.PROGRESS"

    // ── Intent extras ────────────────────────────────────────────────────────
    const val EXTRA_CURRENT = "current"
    const val EXTRA_TOTAL   = "total"
    const val EXTRA_STATUS  = "status"
}

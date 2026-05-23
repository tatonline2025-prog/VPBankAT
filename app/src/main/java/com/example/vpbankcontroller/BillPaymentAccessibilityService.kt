package com.example.vpbat

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.Rect
import android.graphics.Path
import android.hardware.HardwareBuffer
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import org.json.JSONArray
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// ── File Logger ─────────────────────────────────────────────────────────────
object VpbatLogger {
    private val ts  = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault())
    private val tsd = SimpleDateFormat("yyyyMMdd", Locale.getDefault())
    private var logFile: File? = null
    private var appContext: Context? = null
    private const val MAX_LINES = 5000
    private var lineCount = 0

    fun init(context: android.content.Context) {
        appContext = context.applicationContext
        val baseDir = context.getExternalFilesDir(null) ?: context.filesDir
        val dir = File(baseDir, "logs").also { it.mkdirs() }
        val file = File(dir, "vpbat_${tsd.format(Date())}.log")
        if (!file.exists()) {
            file.createNewFile()
        }
        logFile = file
        lineCount = runCatching { file.readLines().size }.getOrDefault(0)
        write("\n=== SESSION START ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())} ===")
    }

    /** step = "B4-POPUP", result = "✓"/"✕"/"→", msg = detail */
    fun log(step: String, mkh: String, result: String, msg: String) {
        val line = "[${ts.format(Date())}] [$step] [${mkh.ifBlank { "-" }}] $result $msg"
        Log.d("VPBatLog", line)
        write(line)
        appContext?.let { context ->
            LocalBroadcastManager.getInstance(context).sendBroadcast(
                Intent(AppConfig.ACTION_DEBUG_LOG).putExtra(AppConfig.EXTRA_LOG_LINE, line)
            )
        }
    }

    fun logRetry(step: String, mkh: String, retry: Int, max: Int, reason: String) {
        log(step, mkh, "✕ retry $retry/$max", reason)
    }

    fun logSkip(step: String, mkh: String, reason: String) {
        log(step, mkh, "✕✕ Bỏ QUA", reason)
    }

    fun logSuccess(mkh: String, idx: Int, total: Int) {
        log("DONE", mkh, "✅ THÀNH CÔNG", "($idx/$total)")
        write("=== ✅ Hoàn tất MKH: $mkh ===")
    }

    private fun write(line: String) {
        try {
            if (lineCount > MAX_LINES) return   // tránh file quá lớn
            logFile?.appendText(line + "\n")
            lineCount++
        } catch (_: Exception) {}
    }
}

// â”€â”€ State machine â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
// Flow thá»±c táº¿ (tá»« áº£nh chá»¥p workflow):
//  INPUT_MKH
//    â†’ CLICK_TIEP_TUC_1      (mÃ n hÃ¬nh nháº­p MKH, báº¥m Tiáº¿p tá»¥c)
//    â†’ WAIT_INVOICE_SCREEN   (mÃ n hÃ¬nh hiá»‡n thÃ´ng tin KH + hÃ³a Ä‘Æ¡n)
//    â†’ DISMISS_AUTO_PAY      (popup "KÃ­ch hoáº¡t tá»± Ä‘á»™ng" náº¿u xuáº¥t hiá»‡n)
//    â†’ CLICK_TIEP_TUC_2      (báº¥m Tiáº¿p tá»¥c trÃªn mÃ n hÃ¬nh hÃ³a Ä‘Æ¡n)
//    â†’ CLICK_XAC_NHAN        (mÃ n hÃ¬nh XÃ¡c nháº­n, báº¥m XÃ¡c nháº­n)
//    â†’ INPUT_SMART_OTP_PIN   (bÃ n phÃ­m PIN Smart OTP, nháº­p tá»«ng chá»¯ sá»‘)
//    â†’ CLICK_XAC_NHAN_GD     (mÃ n hÃ¬nh OTP nÃ¢ng cao, báº¥m XÃ¡c nháº­n giao dá»‹ch)
//    â†’ WAIT_SUCCESS          (chá» mÃ n hÃ¬nh ThÃ nh cÃ´ng)
//    â†’ CLICK_NEW_TX          (báº¥m Giao dá»‹ch má»›i)
//    â†’ [láº·p láº¡i vá»›i MKH tiáº¿p theo]
private enum class PaymentState {
    IDLE,
    SELECT_ELEC_TYPE,   // Chọn "Điện toàn quốc" (PB) hoặc "Điện Hồ Chí Minh" (PE)
    INPUT_MKH,
    CLICK_TIEP_TUC_1,
    WAIT_INVOICE_SCREEN,
    DISMISS_AUTO_PAY,
    CLICK_TIEP_TUC_2,
    CLICK_XAC_NHAN,
    INPUT_SMART_OTP_PIN,
    CLICK_XAC_NHAN_GD,
    WAIT_SUCCESS,
    CLICK_NEW_TX,
    DONE
}

class BillPaymentAccessibilityService : AccessibilityService() {

    companion object {
        /** true khi onServiceConnected đã được gọi — dùng để MainActivity detect service thực sự đang chạy */
        @Volatile var isConnected: Boolean = false
    }

    private val tag = "VPBankAutoService"

    private val handler      = Handler(Looper.getMainLooper())
    private var state        = PaymentState.IDLE
    private var mkhList      = emptyList<String>()
    private var currentIndex = 0
    private var retryCount   = 0
    private var pinCharIndex = 0   // vá»‹ trÃ­ kÃ½ tá»± PIN Ä‘ang nháº­p
    private val lastDebugSnapshotKeyByStep = mutableMapOf<String, String>()

    private lateinit var prefs: SharedPreferences

    // â”€â”€ Broadcast receiver: START / STOP tá»« MainActivity â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
    private val commandReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                AppConfig.ACTION_START -> startAutomation()
                AppConfig.ACTION_STOP  -> stopAutomation()
            }
        }
    }

    // â”€â”€ Lifecycle â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

    override fun onServiceConnected() {
        super.onServiceConnected()
        isConnected = true
        prefs = getSharedPreferences(AppConfig.PREFS_NAME, Context.MODE_PRIVATE)
        AppConfig.vpbankPackage =
            prefs.getString(AppConfig.KEY_VPBANK_PACKAGE, AppConfig.vpbankPackage)
                ?: AppConfig.vpbankPackage

        VpbatLogger.init(this)
        LocalBroadcastManager.getInstance(this).registerReceiver(
            commandReceiver,
            IntentFilter().apply {
                addAction(AppConfig.ACTION_START)
                addAction(AppConfig.ACTION_STOP)
            }
        )
        Log.d(tag, "AccessibilityService connected")
        VpbatLogger.log("SERVICE", "", "→", "AccessibilityService đã kết nối")

        if (prefs.getBoolean(AppConfig.KEY_IS_RUNNING, false)) {
            startAutomation()
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        if (state == PaymentState.IDLE || state == PaymentState.DONE) return
        val pkg = event.packageName?.toString() ?: return
        if (pkg != AppConfig.vpbankPackage) return

        // Trong B6/B7 khong reset poll - Smart OTP/OTP nang cao sinh event lien tuc
        // neu removeCallbacks o day thi poll chuyen buoc se bi huy mai.
        if (state == PaymentState.INPUT_SMART_OTP_PIN ||
            state == PaymentState.CLICK_XAC_NHAN_GD) return

        // Báº¥t ká»³ sá»± kiá»‡n VPBank nÃ o â†’ schedule poll
        handler.removeCallbacks(pollRunnable)
        handler.postDelayed(pollRunnable, AppConfig.POLL_INTERVAL_MS)
    }

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        super.onDestroy()
        isConnected = false
        handler.removeCallbacksAndMessages(null)
        LocalBroadcastManager.getInstance(this).unregisterReceiver(commandReceiver)
    }

    // â”€â”€ Control â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

    private fun startAutomation() {
        val json = prefs.getString(AppConfig.KEY_MKH_LIST, "[]") ?: "[]"
        val arr  = JSONArray(json)
        mkhList      = (0 until arr.length()).map { arr.getString(it) }
        currentIndex = prefs.getInt(AppConfig.KEY_CURRENT_INDEX, 0)

        if (mkhList.isEmpty() || currentIndex >= mkhList.size) {
            broadcastStatus(0, 0, "", "Kh\u00f4ng c\u00f3 MKH n\u00e0o \u0111\u1ec3 x\u1eed l\u00fd")
            VpbatLogger.log("START", "", "✕", "Không có MKH nào")
            return
        }

        VpbatLogger.log("START", "", "→", "Đbầu từ index $currentIndex / ${mkhList.size}")
        retryCount   = 0
        pinCharIndex = 0
        state        = PaymentState.SELECT_ELEC_TYPE
        handler.post(pollRunnable)
    }

    private fun stopAutomation() {
        state = PaymentState.IDLE
        handler.removeCallbacksAndMessages(null)
        retryCount = 0
        pinCharIndex = 0
        currentIndex = 0
        prefs.edit()
            .putBoolean(AppConfig.KEY_IS_RUNNING, false)
            .putInt(AppConfig.KEY_CURRENT_INDEX, 0)
            .apply()
        VpbatLogger.log("STOP", "", "→", "Đã dừng và reset flow về bước 1")
        broadcastStatus(0, mkhList.size, "", "\u0110\u00e3 d\u1eebng - reset về bước 1")
    }

    // â”€â”€ Poll loop â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

    private val pollRunnable = Runnable { processCurrentState() }

    private fun processCurrentState() {
        if (state == PaymentState.IDLE || state == PaymentState.DONE) return

        val root = rootInActiveWindow
        if (root == null) {
            Log.w(tag, "rootInActiveWindow null")
            scheduleRetry(); return
        }

        try {
            when (state) {
                PaymentState.SELECT_ELEC_TYPE   -> handleSelectElecType(root)
                PaymentState.INPUT_MKH          -> handleInputMkh(root)
                PaymentState.CLICK_TIEP_TUC_1   -> handleTiepTuc1(root)
                PaymentState.WAIT_INVOICE_SCREEN -> handleInvoiceScreen(root)
                PaymentState.DISMISS_AUTO_PAY   -> handleDismissAutoPayPopup(root)
                PaymentState.CLICK_TIEP_TUC_2   -> handleTiepTuc2(root)
                PaymentState.CLICK_XAC_NHAN     -> handleXacNhan(root)
                PaymentState.INPUT_SMART_OTP_PIN -> handleSmartOtpPin(root)
                PaymentState.CLICK_XAC_NHAN_GD  -> handleXacNhanGiaoDich(root)
                PaymentState.WAIT_SUCCESS        -> handleWaitSuccess(root)
                PaymentState.CLICK_NEW_TX        -> handleNewTransaction(root)
                else -> Unit
            }
        } finally {
            root.recycle()
        }
    }

    // ── Bước 0: Chọn loại điện dựa theo prefix MKH ─────────────────────────────
    // PB → "Điện lực toàn quốc" | PE → "Điện lực HCM"
    // Nếu không tìm thấy nút (đã ở màn hình nhập MKH) → skip thẳng vào INPUT_MKH
    private fun handleSelectElecType(root: AccessibilityNodeInfo) {
        val mkh = mkhList[currentIndex]
        val prefix = mkh.uppercase().take(2)

        // Text variants cho mỗi loại điện (thử từ đặc thù → chung)
        val targetTexts = when (prefix) {
            "PB" -> listOf(
                AppConfig.TEXT_ELEC_TOAN_QUOC,   // "Điện lực toàn quốc"
                "Điện toàn quốc",
                "Dien luc toan quoc",
                "EVN",
                "EVNNPC",
                "Điện lực quốc gia"
            )
            "PE" -> listOf(
                AppConfig.TEXT_ELEC_HCM,          // "Điện lực HCM"
                "Điện Hồ Chí Minh",
                "Điện lực TP.HCM",
                "Điện lực TP HCM",
                "EVNHCMC",
                "EVN HCM"
            )
            else -> {
                VpbatLogger.logSkip("B0-ELEC", mkh, "Prefix lạ: $prefix")
                broadcastStatus(currentIndex + 1, mkhList.size, mkh, "⚠ Bỏ qua $mkh (prefix lạ: $prefix)")
                currentIndex++
                retryCount = 0
                prefs.edit().putInt(AppConfig.KEY_CURRENT_INDEX, currentIndex).apply()
                if (currentIndex >= mkhList.size) {
                    state = PaymentState.DONE
                    prefs.edit().putBoolean(AppConfig.KEY_IS_RUNNING, false).putInt(AppConfig.KEY_CURRENT_INDEX, 0).apply()
                    broadcastStatus(currentIndex, mkhList.size, "", "Hoàn tất (có MKH lạ)")
                } else {
                    state = PaymentState.SELECT_ELEC_TYPE
                    handler.postDelayed(pollRunnable, AppConfig.DELAY_AFTER_CLICK_MS)
                }
                return
            }
        }

        // Nếu đang mở popup "Nhà cung cấp" → chọn đúng option
        if (isProviderPickerVisible(root)) {
            for (text in targetTexts) {
                if (clickByText(root, text)) {
                    VpbatLogger.log("B0-ELEC", mkh, "✓", "Chọn '$text' trong popup nhà cung cấp")
                    broadcastStatus(currentIndex + 1, mkhList.size, mkh, "Đã chọn $text")
                    retryCount = 0
                    state = PaymentState.INPUT_MKH
                    handler.postDelayed(pollRunnable, AppConfig.DELAY_AFTER_CLICK_MS)
                    return
                }
            }
            VpbatLogger.logRetry("B0-ELEC", mkh, retryCount, AppConfig.MAX_RETRY, "Popup hiện nhưng không click được option")
            scheduleRetry()
            return
        }

        // Nếu đã ở màn hình MKH và đúng nhà cung cấp → vào INPUT_MKH luôn
        if (canDetectMkhInputField(root)) {
            val alreadyCorrect = targetTexts.any { isTextPresent(root, it) }
            if (alreadyCorrect) {
                VpbatLogger.log("B0-ELEC", mkh, "→", "Đã ở màn MKH, đúng nhà cung cấp → INPUT_MKH")
                retryCount = 0
                state = PaymentState.INPUT_MKH
                handler.post(pollRunnable)
                return
            }
            // Sai nhà cung cấp → mở picker để đổi
            if (openProviderPickerFromForm(root)) {
                VpbatLogger.log("B0-ELEC", mkh, "→", "Mở picker để đổi nhà cung cấp sang $prefix")
                retryCount = 0
                handler.postDelayed(pollRunnable, AppConfig.DELAY_AFTER_TEXT_MS)
                return
            }
            // Không mở được picker nhưng đã ở màn MKH → tiếp tục (có thể đúng rồi)
            VpbatLogger.log("B0-ELEC", mkh, "→", "Ở màn MKH, không mở picker được → INPUT_MKH")
            retryCount = 0
            state = PaymentState.INPUT_MKH
            handler.post(pollRunnable)
            return
        }

        // Chưa ở màn MKH → thử click trực tiếp text loại điện
        for (text in targetTexts) {
            if (clickByText(root, text)) {
                VpbatLogger.log("B0-ELEC", mkh, "✓", "Click '$text' → vào màn nhập MKH")
                broadcastStatus(currentIndex + 1, mkhList.size, mkh, "Chọn $text…")
                retryCount = 0
                state = PaymentState.INPUT_MKH
                handler.postDelayed(pollRunnable, AppConfig.DELAY_AFTER_CLICK_MS)
                return
            }
        }

        VpbatLogger.logRetry("B0-ELEC", mkh, retryCount, AppConfig.MAX_RETRY,
            "Không thấy option chọn loại điện ($prefix). " +
            "Đang chờ màn hình VPBank thanh toán điện...")
        scheduleRetry()
    }

    private fun handleInputMkh(root: AccessibilityNodeInfo) {
        val mkh = mkhList[currentIndex]

        detectTransientServiceIssue(root, "B1-INPUT_MKH")?.let { reason ->
            skipCurrentMkhWithDelay("B1-INPUT_MKH", mkh, reason, 3_000L)
            return
        }

        if (!setMkhWithFallback(root, mkh)) {
            VpbatLogger.logRetry("B1-INPUT_MKH", mkh, retryCount, AppConfig.MAX_RETRY, "Field MKH chưa hiện")
            scheduleRetry(); return
        }

        VpbatLogger.log("B1-INPUT_MKH", mkh, "✓", "Đã nhập MKH vào field")
        broadcastStatus(currentIndex + 1, mkhList.size, mkh, "\u0110ang nh\u1eadp MKH: $mkh")

        retryCount = 0
        state      = PaymentState.CLICK_TIEP_TUC_1
        handler.postDelayed(pollRunnable, AppConfig.DELAY_AFTER_TEXT_MS)
    }

    // Bước 2: Bấm Tiếp tục sau khi nhập MKH (text: "Tiếp tục" - nút xanh ở ảnh 5)
    private fun handleTiepTuc1(root: AccessibilityNodeInfo) {
        val mkh = mkhList[currentIndex]

        detectTransientServiceIssue(root, "B2-TIEP_TUC_1")?.let { reason ->
            skipCurrentMkhWithDelay("B2-TIEP_TUC_1", mkh, reason, 3_000L)
            return
        }

        if (clickContinueButton(root)) {
            VpbatLogger.log("B2-TIEP_TUC_1", mkh, "✓", "Bấm Tiếp tục 1 → chờ màn hóa đơn/kết quả tra cứu (6s)")
            broadcastStatus(currentIndex + 1, mkhList.size, mkh, "\u0110ang tra c\u1ee9u h\u00f3a \u0111\u01a1n\u2026")
            retryCount = 0
            state      = PaymentState.WAIT_INVOICE_SCREEN
            handler.postDelayed(pollRunnable, AppConfig.DELAY_LOOKUP_RESULT_MS)
        } else {
            VpbatLogger.logRetry("B2-TIEP_TUC_1", mkh, retryCount, AppConfig.MAX_RETRY, "Không thấy nút Tiếp tục")
            scheduleRetry()
        }
    }


    // Bước 3: Màn hóa đơn (ảnh 6): hiển thị thông tin KH + "Tổng tiền"
    // Sau khi bấm Tiếp tục → popup tự động xuất hiện (ảnh 7)
    private fun handleInvoiceScreen(root: AccessibilityNodeInfo) {
        val mkh = mkhList[currentIndex]

        // 1. Bỏ qua nếu MKH đã thanh toán / lỗi tra cứu
        detectLookupFailureReason(root)?.let { reason ->
            skipCurrentMkh("B3-HOA_DON", mkh, reason)
            return
        }

        // 2. Màn Xác nhận đã hiện → tiến thẳng B5
        if (isConfirmScreenVisible(root)) {
            VpbatLogger.log("B3-HOA_DON", mkh, "→", "Đã tới màn Xác nhận")
            retryCount = 0
            state = PaymentState.CLICK_XAC_NHAN
            handler.postDelayed(pollRunnable, 1_000L)
            return
        }

        // 3. Smart OTP đã hiện → tiến thẳng B6
        if (isSmartOtpScreenVisible(root)) {
            VpbatLogger.log("B3-HOA_DON", mkh, "→", "Đã tới Smart OTP (bỏ qua B4/B5)")
            retryCount = 0; pinCharIndex = 0
            state = PaymentState.INPUT_SMART_OTP_PIN
            handler.postDelayed(pollRunnable, 1_000L)
            return
        }

        // 4. Popup tự động xuất hiện sớm (trước khi bấm Tiếp tục)
        val popupNodes = root.findAccessibilityNodeInfosByText(AppConfig.TEXT_AUTO_PAY_POPUP)
        if (!popupNodes.isNullOrEmpty()) {
            popupNodes.forEach { it.recycle() }
            VpbatLogger.log("B3-HOA_DON", mkh, "→", "Popup xuất hiện sớm → DISMISS_AUTO_PAY")
            state = PaymentState.DISMISS_AUTO_PAY
            handler.postDelayed(pollRunnable, 1_500L)
            return
        }

        // 5. Log screen snapshot (3 retry đầu) để debug nếu có vấn đề
        if (retryCount <= 3) {
            VpbatLogger.log("B3-HOA_DON", mkh, "ℹ", "Screen[$retryCount]: ${captureVisibleTextSnapshot(root)}")
        }

        // 6. DEEP FIX: Không yêu cầu nhận dạng màn hóa đơn trước khi click.
        //    Chỉ cần không có điều kiện skip/advance → thử click Tiếp tục trực tiếp.
        //    clickInvoiceContinueButton loại trừ "Không, tiếp tục thanh toán" (popup).
        if (clickInvoiceContinueButton(root)) {
            VpbatLogger.log("B3-HOA_DON", mkh, "✓", "Bấm Tiếp tục (màn hóa đơn) → chờ popup/xác nhận (5s)")
            broadcastStatus(currentIndex + 1, mkhList.size, mkh, "Đang xác nhận hóa đơn…")
            retryCount = 0
            state      = PaymentState.DISMISS_AUTO_PAY
            handler.postDelayed(pollRunnable, AppConfig.DELAY_POPUP_RESULT_MS)
        } else {
            VpbatLogger.logRetry("B3-HOA_DON", mkh, retryCount, AppConfig.MAX_RETRY, "Không tìm/click được nút Tiếp tục")
            scheduleRetry()
        }
    }

    // Bước 4: Popup "Kích hoạt thanh toán tự động cho hóa đơn" (ảnh 7)
    // Nút: "Không, tiếp tục thanh toán" (chữ xanh dưới cùng popup)
    // Sau dismiss → màn Xác nhận trực tiếp (ảnh 8)
    private fun handleDismissAutoPayPopup(root: AccessibilityNodeInfo) {
        val mkh = mkhList[currentIndex]

        detectTransientServiceIssue(root, "B4-POPUP")?.let { reason ->
            skipCurrentMkh("B4-POPUP", mkh, reason)
            return
        }

        val popupNodes = root.findAccessibilityNodeInfosByText(AppConfig.TEXT_AUTO_PAY_POPUP)
        if (popupNodes.isNullOrEmpty()) {
            if (isConfirmScreenVisible(root)) {
                VpbatLogger.log("B4-POPUP", mkh, "→", "Popup đã đóng/không xuất hiện → tiến Xác nhận")
                retryCount = 0
                state = PaymentState.CLICK_XAC_NHAN
                handler.postDelayed(pollRunnable, 1_000L)
                return
            }

            if (isInvoiceReviewScreen(root)) {
                if (retryCount <= 1) {
                    VpbatLogger.log("B4-POPUP", mkh, "ℹ", "Screen snapshot: ${captureVisibleTextSnapshot(root)}")
                }
                VpbatLogger.logRetry("B4-POPUP", mkh, retryCount, AppConfig.MAX_RETRY, "Vẫn ở màn hóa đơn, chờ popup hoặc màn Xác nhận")
                scheduleRetry()
                return
            }

            if (retryCount <= 1) {
                VpbatLogger.log("B4-POPUP", mkh, "ℹ", "Screen snapshot: ${captureVisibleTextSnapshot(root)}")
            }
            VpbatLogger.logRetry("B4-POPUP", mkh, retryCount, AppConfig.MAX_RETRY, "Chưa thấy popup và chưa tới màn Xác nhận")
            scheduleRetry()
            return
        }
        popupNodes.forEach { it.recycle() }

        if (clickSkipAutoPayButton(root)) {
            VpbatLogger.log("B4-POPUP", mkh, "✓", "Bấm 'Không, tiếp tục thanh toán' (text) → chờ màn Xác nhận ổn định (5s)")
            retryCount = 0
            state      = PaymentState.CLICK_XAC_NHAN
            handler.postDelayed(pollRunnable, AppConfig.DELAY_CONFIRM_RESULT_MS)
            return
        }
        if (tapSkipButtonByPopupBounds(root)) {
            VpbatLogger.log("B4-POPUP", mkh, "✓", "Bấm 'Không' (toạ độ tap) → chờ màn Xác nhận ổn định (5s)")
            retryCount = 0
            state      = PaymentState.CLICK_XAC_NHAN
            handler.postDelayed(pollRunnable, AppConfig.DELAY_CONFIRM_RESULT_MS)
            return
        }
        VpbatLogger.logRetry("B4-POPUP", mkh, retryCount, AppConfig.MAX_RETRY, "Không bấm được nút 'Không'")
        scheduleRetry()
    }

    /** Tìm popup container qua title, rồi tap vào node 'Không' hoặc cuối popup. */
    private fun tapSkipButtonByPopupBounds(root: AccessibilityNodeInfo): Boolean {
        // Locate popup title to know where popup is on screen
        val titleNodes = root.findAccessibilityNodeInfosByText(AppConfig.TEXT_AUTO_PAY_POPUP)
        if (titleNodes.isNullOrEmpty()) return false
        val titleBounds = Rect()
        titleNodes[0].getBoundsInScreen(titleBounds)
        titleNodes.forEach { it.recycle() }

        // Try to find a 'Không' text node below the popup title
        val khongNodes = root.findAccessibilityNodeInfosByText("Kh\u00f4ng")
        val skipBounds = Rect()
        if (!khongNodes.isNullOrEmpty()) {
            val best = khongNodes
                .filter { it.isVisibleToUser }
                .filter { n -> val b = Rect(); n.getBoundsInScreen(b); b.centerY() > titleBounds.bottom }
                .maxByOrNull { n -> val b = Rect(); n.getBoundsInScreen(b); b.centerY() }
            best?.getBoundsInScreen(skipBounds)
            khongNodes.forEach { it.recycle() }
        }

        val tapX: Float
        val tapY: Float
        if (!skipBounds.isEmpty) {
            tapX = skipBounds.exactCenterX()
            tapY = skipBounds.exactCenterY()
            Log.d(tag, "tapSkipButtonByPopupBounds: 'K\u00f4ng' node at $skipBounds")
        } else {
            // Fallback: estimate button position at 80% down from title bottom to screen bottom
            val screenH = resources.displayMetrics.heightPixels
            val screenW = resources.displayMetrics.widthPixels
            tapX = screenW / 2f
            tapY = titleBounds.bottom + (screenH - titleBounds.bottom) * 0.70f
            Log.d(tag, "tapSkipButtonByPopupBounds: blind tap at ($tapX, $tapY)")
        }

        val path = android.graphics.Path().apply { moveTo(tapX, tapY) }
        val gesture = android.accessibilityservice.GestureDescription.Builder()
            .addStroke(android.accessibilityservice.GestureDescription.StrokeDescription(path, 0, 150))
            .build()
        return dispatchGesture(gesture, null, null)
    }

    // (handleTiepTuc2 giữ lại trong state machine nhưng thực tế sẽ không vào bước này)
    private fun handleTiepTuc2(root: AccessibilityNodeInfo) {
        val mkh = mkhList[currentIndex]
        if (clickContinueButton(root)) {
            VpbatLogger.log("B3c-TIEP_TUC_2", mkh, "✓", "Bấm Tiếp tục 2 → Xác nhận")
            broadcastStatus(currentIndex + 1, mkhList.size, mkh, "\u0110ang x\u00e1c nh\u1eadn\u2026")
            retryCount = 0
            state      = PaymentState.CLICK_XAC_NHAN
            handler.postDelayed(pollRunnable, AppConfig.DELAY_AFTER_CLICK_MS)
        } else {
            VpbatLogger.logRetry("B3c-TIEP_TUC_2", mkh, retryCount, AppConfig.MAX_RETRY, "Không thấy Tiếp tục 2")
            scheduleRetry()
        }
    }

    // Bước 5: Màn Xác nhận (ảnh 8): tiêu đề "Xác nhận", có thông tin KH + số tiền
    // Nút bấm: "Xác nhận" (nút xanh dưới cùng)
    private fun handleXacNhan(root: AccessibilityNodeInfo) {
        val mkh = mkhList[currentIndex]
        if (isSmartOtpScreenVisible(root)) {
            VpbatLogger.log("B5-XAC_NHAN", mkh, "→", "Không cần màn Xác nhận, đã tới Smart OTP")
            retryCount = 0
            pinCharIndex = 0
            state = PaymentState.INPUT_SMART_OTP_PIN
            handler.postDelayed(pollRunnable, 1_000L)
            return
        }

        if (!isConfirmScreenVisible(root)) {
            if (retryCount <= 1) {
                VpbatLogger.log("B5-XAC_NHAN", mkh, "ℹ", "Screen snapshot: ${captureVisibleTextSnapshot(root)}")
            }
            VpbatLogger.logRetry("B5-XAC_NHAN", mkh, retryCount, AppConfig.MAX_RETRY, "Chưa thấy màn Xác nhận")
            scheduleRetry(); return
        }

        if (clickConfirmButton(root)) {
            VpbatLogger.log("B5-XAC_NHAN", mkh, "✓", "Bấm 'Xác nhận' → chờ màn PIN Smart OTP (3s)")
            broadcastStatus(currentIndex + 1, mkhList.size, mkh, "\u0110ang nh\u1eadp PIN Smart OTP\u2026")
            retryCount   = 0
            pinCharIndex = 0
            state        = PaymentState.INPUT_SMART_OTP_PIN
            handler.postDelayed(pollRunnable, 3_000L)
        } else {
            VpbatLogger.logRetry("B5-XAC_NHAN", mkh, retryCount, AppConfig.MAX_RETRY, "Không bấm được nút Xác nhận")
            scheduleRetry()
        }
    }

    // Bước 6: Màn PIN Smart OTP (ảnh 9): "Nhập mã PIN Smart OTP"
    // Bàn phím 3x4 (1-9, 0), mỗi ô là 1 node text = chữ số
    private fun handleSmartOtpPin(root: AccessibilityNodeInfo) {
        val mkh = mkhList[currentIndex]
        val pin = AppConfig.smartOtpPin
        if (pin.length != 6 || !pin.all { it.isDigit() }) {
            VpbatLogger.log("B6-PIN_OTP", mkh, "⛔", "PIN không hợp lệ: '$pin'")
            stopAutomation()
            broadcastStatus(currentIndex, mkhList.size, mkh,
                "\u26d4 PIN Smart OTP ch\u01b0a h\u1ee3p l\u1ec7. Vui l\u00f2ng nh\u1eadp 6 ch\u1eef s\u1ed1 r\u1ed3i th\u1eed l\u1ea1i.")
            return
        }

        // Sau so thu 6 phai uu tien chuyen sang B7 ngay, khong duoc check title truoc.
        if (pinCharIndex >= pin.length) {
            VpbatLogger.log("B6-PIN_OTP", mkh, "✓", "Nhap xong 6 so PIN -> chuyen sang Xac nhan GD")
            retryCount   = 0
            state        = PaymentState.CLICK_XAC_NHAN_GD
            handler.postDelayed(pollRunnable, 600L)
            return
        }

        val otpTitleNodes = root.findAccessibilityNodeInfosByText(AppConfig.TEXT_SMART_OTP_TITLE)
        if (otpTitleNodes.isNullOrEmpty()) {
            if (isAdvancedOtpScreenVisible(root)) {
                VpbatLogger.log("B6-PIN_OTP", mkh, "→", "Đã sang màn Xác nhận giao dịch")
                retryCount = 0
                state = PaymentState.CLICK_XAC_NHAN_GD
                handler.postDelayed(pollRunnable, 1_000L)
                return
            }
            if (isTextPresent(root, AppConfig.TEXT_SUCCESS)) {
                VpbatLogger.log("B6-PIN_OTP", mkh, "→", "Đã thấy màn Thành công")
                retryCount = 0
                state = PaymentState.WAIT_SUCCESS
                handler.post(pollRunnable)
                return
            }
            VpbatLogger.logRetry("B6-PIN_OTP", mkh, retryCount, AppConfig.MAX_RETRY, "Chưa thấy Smart OTP hoặc OTP nâng cao")
            scheduleRetry()
            return
        }
        otpTitleNodes.forEach { it.recycle() }

        val digit = pin[pinCharIndex].toString()
        if (clickByText(root, digit)) {
            VpbatLogger.log("B6-PIN_OTP", mkh, "→", "Nhập số [$pinCharIndex]: $digit")
            pinCharIndex++
            handler.postDelayed(pollRunnable, 400L)
        } else {
            VpbatLogger.logRetry("B6-PIN_OTP", mkh, retryCount, AppConfig.MAX_RETRY, "Không bấm được số $digit")
            scheduleRetry()
        }
    }

    // Bước 7: Màn "Xác nhận OTP nâng cao" (ảnh 10)
    // Tiêu đề: "Xác nhận OTP nâng cao", hiển thị mã OTP 6 số tự cập nhật
    // Nút bấm: "Xác nhận giao dịch"
    private fun handleXacNhanGiaoDich(root: AccessibilityNodeInfo) {
        val mkh = mkhList[currentIndex]
        val advNodes = root.findAccessibilityNodeInfosByText(AppConfig.TEXT_OTP_ADV_SCREEN)
        val onAdvancedOtpScreen = !advNodes.isNullOrEmpty() || isAdvancedOtpScreenVisible(root)

        if (isTextPresent(root, AppConfig.TEXT_SUCCESS) || isTextPresent(root, AppConfig.TEXT_BTN_NEW_TX)) {
            advNodes?.forEach { it.recycle() }
            VpbatLogger.log("B7-OTP_ADV", mkh, "→", "Đã rời màn OTP nâng cao → chuyển WAIT_SUCCESS")
            retryCount = 0
            state = PaymentState.WAIT_SUCCESS
            handler.post(pollRunnable)
            return
        }

        if (onAdvancedOtpScreen) {
            if (retryCount <= 2) {
                VpbatLogger.log("B7-OTP_ADV", mkh, "ℹ", "Screen[$retryCount] adv=$onAdvancedOtpScreen: ${captureVisibleTextSnapshot(root)}")
                captureDebugArtifacts(root, "B7-OTP_ADV", mkh)
            }
            advNodes.forEach { it.recycle() }
            if (clickConfirmTransactionButton(root)) {
                VpbatLogger.log("B7-OTP_ADV", mkh, "→", "Đã thử bấm 'Xác nhận giao dịch' → giữ B7 để xác minh màn đổi")
                if (retryCount <= 2) {
                    captureDebugArtifacts(root, "B7-OTP_ADV-TAP", mkh)
                }
                broadcastStatus(currentIndex + 1, mkhList.size, mkh, "\u0110ang x\u00e1c minh thao t\u00e1c OTP n\u00e2ng cao\u2026")
                scheduleRetry()
            } else {
                VpbatLogger.logRetry("B7-OTP_ADV", mkh, retryCount, AppConfig.MAX_RETRY, "Không bấm được 'Xác nhận GD'")
                if (retryCount <= 2) {
                    captureDebugArtifacts(root, "B7-OTP_ADV-FAIL", mkh)
                }
                scheduleRetry()
            }
            return
        }

        advNodes?.forEach { it.recycle() }
        if (retryCount < 8) {
            VpbatLogger.logRetry("B7-OTP_ADV", mkh, retryCount, AppConfig.MAX_RETRY, "Không nhận ra màn OTP nâng cao - thử tap blind")
            tapScreenPercent(root, 0.50f, 0.88f)
            scheduleRetry()
            return
        }

        VpbatLogger.log("B7-OTP_ADV", mkh, "->", "Tap blind khong thanh cong sau ${retryCount} lan -> thu WAIT_SUCCESS")
        state = PaymentState.WAIT_SUCCESS
        handler.post(pollRunnable)
    }

    // Bước 8: Màn "Thành công" (ảnh 11) – chữ xanh + tick xanh + số tiền
    private fun handleWaitSuccess(root: AccessibilityNodeInfo) {
        val mkh = mkhList[currentIndex]
        // DEEP FIX B8: nhận dạng màn thành công theo nhiều pattern:
        // - "Thành công" (chính)
        // - "Giao dịch mới" (nút chỉ xuất hiện trên màn thành công)
        val successNodes = root.findAccessibilityNodeInfosByText(AppConfig.TEXT_SUCCESS)
        val isOnSuccess = !successNodes.isNullOrEmpty() || isTextPresent(root, AppConfig.TEXT_BTN_NEW_TX)
        successNodes?.forEach { it.recycle() }
        if (!isOnSuccess) {
            if (retryCount <= 2) {
                VpbatLogger.log("B8-SUCCESS", mkh, "ℹ", "Screen[$retryCount]: ${captureVisibleTextSnapshot(root)}")
                captureDebugArtifacts(root, "B8-SUCCESS", mkh)
            }
            VpbatLogger.logRetry("B8-SUCCESS", mkh, retryCount, AppConfig.MAX_RETRY, "Chưa thấy màn Thành công")
            scheduleRetry(); return
        }

        // Ưu tiên đi tiếp nếu nút "Giao dịch mới" đã bấm được và không có popup thật sự chặn thao tác.
        if (!hasSuccessOverlayPopup(root) && clickNewTransactionButton(root)) {
            VpbatLogger.logSuccess(mkh, currentIndex + 1, mkhList.size)
            VpbatLogger.log("B8-SUCCESS", mkh, "→", "Không có popup → bấm thẳng 'Giao dịch mới'")
            currentIndex++
            prefs.edit().putInt(AppConfig.KEY_CURRENT_INDEX, currentIndex).apply()
            retryCount = 0
            pinCharIndex = 0

            if (currentIndex >= mkhList.size) {
                VpbatLogger.log("B9-NEW_TX", mkh, "✅", "Hoàn tất tất cả ${mkhList.size} MKH")
                state = PaymentState.DONE
                prefs.edit()
                    .putBoolean(AppConfig.KEY_IS_RUNNING, false)
                    .putInt(AppConfig.KEY_CURRENT_INDEX, 0)
                    .apply()
                broadcastStatus(currentIndex, mkhList.size, "", "\u2705 Ho\u00e0n t\u1ea5t ${mkhList.size} MKH!")
            } else {
                broadcastStatus(currentIndex, mkhList.size, mkh, "\u2713 Th\u00e0nh c\u00f4ng: $mkh")
                VpbatLogger.log("B9-NEW_TX", mkh, "→", "Tiếp theo: index $currentIndex / ${mkhList.size}")
                state = PaymentState.SELECT_ELEC_TYPE
                handler.postDelayed(pollRunnable, AppConfig.DELAY_AFTER_CLICK_MS)
            }
            return
        }

        if (hasSuccessOverlayPopup(root)) {
            if (dismissSuccessOverlayPopup(root)) {
                VpbatLogger.log("B8-SUCCESS", mkh, "→", "Đã đóng popup đè màn Thành công")
                retryCount = 0
                handler.postDelayed(pollRunnable, 1_000L)
                return
            }

            VpbatLogger.logRetry("B8-SUCCESS", mkh, retryCount, AppConfig.MAX_RETRY, "Có popup đè màn Thành công nhưng chưa đóng được")
            scheduleRetry()
            return
        }

        VpbatLogger.logSuccess(mkh, currentIndex + 1, mkhList.size)
        broadcastStatus(currentIndex + 1, mkhList.size, mkh,
            "\u2713 Th\u00e0nh c\u00f4ng: $mkh")

        retryCount = 0
        state      = PaymentState.CLICK_NEW_TX
        handler.postDelayed(pollRunnable, 1_500L)
    }

    // Bước 9: Nút "Giao dịch mới" (ảnh 11) – icon mũi tên xanh, dưới màn Thành công
    private fun handleNewTransaction(root: AccessibilityNodeInfo) {
        val mkh = mkhList[currentIndex]
        if (dismissSuccessOverlayPopup(root)) {
            VpbatLogger.log("B9-NEW_TX", mkh, "→", "Đã đóng popup đè màn thành công")
            retryCount = 0
            handler.postDelayed(pollRunnable, 800L)
            return
        }

        if (clickNewTransactionButton(root)) {
            currentIndex++
            prefs.edit().putInt(AppConfig.KEY_CURRENT_INDEX, currentIndex).apply()
            retryCount   = 0
            pinCharIndex = 0

            if (currentIndex >= mkhList.size) {
                VpbatLogger.log("B9-NEW_TX", mkh, "✅", "Hoàn tất tất cả ${mkhList.size} MKH")
                state = PaymentState.DONE
                prefs.edit()
                    .putBoolean(AppConfig.KEY_IS_RUNNING, false)
                    .putInt(AppConfig.KEY_CURRENT_INDEX, 0)
                    .apply()
                broadcastStatus(currentIndex, mkhList.size, "", "\u2705 Ho\u00e0n t\u1ea5t ${mkhList.size} MKH!")
            } else {
                VpbatLogger.log("B9-NEW_TX", mkh, "→", "Tiếp theo: index $currentIndex / ${mkhList.size}")
                state = PaymentState.SELECT_ELEC_TYPE
                handler.postDelayed(pollRunnable, AppConfig.DELAY_AFTER_CLICK_MS)
            }
        } else {
            VpbatLogger.logRetry("B9-NEW_TX", mkh, retryCount, AppConfig.MAX_RETRY, "Không thấy nút 'Giao dịch mới'")
            scheduleRetry()
        }
    }


    private fun scheduleRetry() {
        retryCount++
        val mkh = if (currentIndex < mkhList.size) mkhList[currentIndex] else "?"

        if (retryCount > AppConfig.MAX_RETRY) {
            VpbatLogger.logSkip("$state", mkh, "Hết ${AppConfig.MAX_RETRY} lần thử")
            broadcastStatus(currentIndex + 1, mkhList.size, mkh,
                "\u26a0 B\u1ecf qua: $mkh (h\u1ebft l\u1ea7n th\u1eed t\u1ea1i $state)")

            currentIndex++
            retryCount   = 0
            pinCharIndex = 0
            prefs.edit().putInt(AppConfig.KEY_CURRENT_INDEX, currentIndex).apply()

            if (currentIndex >= mkhList.size) {
                state = PaymentState.DONE
                prefs.edit()
                    .putBoolean(AppConfig.KEY_IS_RUNNING, false)
                    .putInt(AppConfig.KEY_CURRENT_INDEX, 0)
                    .apply()
                broadcastStatus(currentIndex, mkhList.size, "", "Ho\u00e0n t\u1ea5t (c\u00f3 l\u1ed7i)")
            } else {
                state = PaymentState.SELECT_ELEC_TYPE
                handler.postDelayed(pollRunnable, AppConfig.DELAY_AFTER_CLICK_MS)
            }
        } else {
            VpbatLogger.log("$state", mkh, "↻ retry $retryCount/${AppConfig.MAX_RETRY}", "")
            handler.postDelayed(pollRunnable, AppConfig.POLL_INTERVAL_MS)
        }
    }

    private fun skipCurrentMkh(step: String, mkh: String, reason: String) {
        VpbatLogger.logSkip(step, mkh, reason)
        broadcastStatus(
            currentIndex + 1,
            mkhList.size,
            mkh,
            "⚠ Bỏ qua: $mkh ($reason)"
        )

        currentIndex++
        retryCount = 0
        pinCharIndex = 0
        prefs.edit().putInt(AppConfig.KEY_CURRENT_INDEX, currentIndex).apply()

        if (currentIndex >= mkhList.size) {
            state = PaymentState.DONE
            prefs.edit()
                .putBoolean(AppConfig.KEY_IS_RUNNING, false)
                .putInt(AppConfig.KEY_CURRENT_INDEX, 0)
                .apply()
            broadcastStatus(currentIndex, mkhList.size, "", "Hoàn tất (có lỗi)")
        } else {
            state = PaymentState.SELECT_ELEC_TYPE
            handler.postDelayed(pollRunnable, AppConfig.DELAY_AFTER_CLICK_MS)
        }
    }

    private fun skipCurrentMkhWithDelay(step: String, mkh: String, reason: String, delayMs: Long) {
        VpbatLogger.logSkip(step, mkh, "$reason → chờ ${delayMs / 1000}s rồi chuyển MKH khác")
        broadcastStatus(
            currentIndex + 1,
            mkhList.size,
            mkh,
            "⚠ Bỏ qua sau ${delayMs / 1000}s: $mkh ($reason)"
        )

        currentIndex++
        retryCount = 0
        pinCharIndex = 0
        prefs.edit().putInt(AppConfig.KEY_CURRENT_INDEX, currentIndex).apply()

        if (currentIndex >= mkhList.size) {
            state = PaymentState.DONE
            prefs.edit()
                .putBoolean(AppConfig.KEY_IS_RUNNING, false)
                .putInt(AppConfig.KEY_CURRENT_INDEX, 0)
                .apply()
            handler.postDelayed({
                broadcastStatus(currentIndex, mkhList.size, "", "Hoàn tất (có lỗi)")
            }, delayMs)
        } else {
            state = PaymentState.SELECT_ELEC_TYPE
            handler.postDelayed(pollRunnable, delayMs)
        }
    }

    private fun detectLookupFailureReason(root: AccessibilityNodeInfo): String? {
        val reasonPatterns = listOf(
            "Đã thanh toán" to listOf("da thanh toan"),
            "Hóa đơn không còn nợ cước" to listOf("hoa don khong con no cuoc"),
            "Không có hóa đơn" to listOf("khong co hoa don"),
            "Không tìm thấy thông tin khách hàng" to listOf("khong tim thay thong tin khach hang"),
            "Mã khách hàng không tồn tại" to listOf("ma khach hang khong ton tai"),
            "Khách hàng không tồn tại" to listOf("khach hang khong ton tai"),
            "Dữ liệu không tồn tại" to listOf("du lieu khong ton tai"),
            "Thông tin không hợp lệ" to listOf("thong tin khong hop le"),
            "Có lỗi xảy ra" to listOf("co loi xay ra"),
            "Lỗi tra cứu hóa đơn" to listOf("loi tra cuu hoa don", "loi tra cuu"),
            "Không thể thực hiện giao dịch" to listOf("khong the thuc hien giao dich")
        )

        val visibleTexts = mutableListOf<String>()
        collectVisibleTexts(root, visibleTexts)

        return reasonPatterns.firstNotNullOfOrNull { (reason, phrases) ->
            val matchedText = visibleTexts.firstOrNull { text ->
                val normalized = normalizeText(text)
                phrases.any { phrase -> containsNormalizedPhrase(normalized, phrase) }
            }
            if (matchedText != null) {
                VpbatLogger.log("B3-HOA_DON", mkhList.getOrElse(currentIndex) { "?" }, "ℹ", "Lookup fail match '$reason' from: ${matchedText.take(120)}")
                reason
            } else {
                null
            }
        }
    }

    private fun containsNormalizedPhrase(normalizedText: String, normalizedPhrase: String): Boolean {
        if (normalizedText.isBlank() || normalizedPhrase.isBlank()) return false
        val paddedText = " $normalizedText "
        val paddedPhrase = " $normalizedPhrase "
        return paddedText.contains(paddedPhrase)
    }

    private fun detectTransientServiceIssue(root: AccessibilityNodeInfo, step: String): String? {
        val visibleTexts = mutableListOf<String>()
        collectVisibleTexts(root, visibleTexts)
        val combinedText = normalizeText(visibleTexts.joinToString(" "))

        val hasInterruptionPhrase = containsNormalizedPhrase(combinedText, "he thong dang tam thoi gian doan") ||
            containsNormalizedPhrase(combinedText, "hien tai he thong dang tam thoi gian doan")
        val hasRetryLaterPhrase = containsNormalizedPhrase(combinedText, "quy khach vui long thu lai sau") ||
            containsNormalizedPhrase(combinedText, "vui long thu lai sau")

        if (hasInterruptionPhrase && hasRetryLaterPhrase) {
            VpbatLogger.log(step, mkhList.getOrElse(currentIndex) { "?" }, "ℹ", "Service issue match 'Hệ thống VPBank tạm thời gián đoạn' from: ${combinedText.take(160)}")
            return "Hệ thống VPBank tạm thời gián đoạn"
        }

        return null
    }

    private fun collectVisibleTexts(node: AccessibilityNodeInfo, out: MutableList<String>) {
        if (node.isVisibleToUser) {
            val text = node.text?.toString().orEmpty()
            val desc = node.contentDescription?.toString().orEmpty()
            if (text.isNotBlank()) out.add(text)
            if (desc.isNotBlank()) out.add(desc)
        }

        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            try {
                collectVisibleTexts(child, out)
            } finally {
                child.recycle()
            }
        }
    }

    private fun captureVisibleTextSnapshot(root: AccessibilityNodeInfo): String {
        val visibleTexts = mutableListOf<String>()
        collectVisibleTexts(root, visibleTexts)
        return visibleTexts
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinct()
            .take(12)
            .joinToString(" | ")
    }

    private fun captureDebugArtifacts(root: AccessibilityNodeInfo, step: String, mkh: String) {
        val snapshot = captureVisibleTextSnapshot(root)
        val dedupeKey = "$step|$retryCount|$snapshot"
        if (lastDebugSnapshotKeyByStep[step] == dedupeKey) return
        lastDebugSnapshotKeyByStep[step] = dedupeKey

        val baseDir = File(getExternalFilesDir(null) ?: filesDir, "debug_snapshots").also { it.mkdirs() }
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.getDefault()).format(Date())
        val safeStep = step.replace(Regex("[^A-Za-z0-9_-]"), "_")
        val safeMkh = mkh.ifBlank { "-" }.replace(Regex("[^A-Za-z0-9_-]"), "_")
        val baseName = "${stamp}_${safeStep}_${safeMkh}_r${retryCount}"

        val txtFile = File(baseDir, "$baseName.txt")
        runCatching {
            txtFile.writeText(
                buildString {
                    appendLine("step=$step")
                    appendLine("mkh=$mkh")
                    appendLine("state=$state")
                    appendLine("retry=$retryCount")
                    appendLine("timestamp=$stamp")
                    appendLine("snapshot=$snapshot")
                }
            )
        }.onFailure {
            VpbatLogger.log(step, mkh, "⚠", "Không ghi được debug txt: ${it.message}")
        }

        captureDebugScreenshot(File(baseDir, "$baseName.png"), step, mkh)
    }

    private fun captureDebugScreenshot(outputFile: File, step: String, mkh: String) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            VpbatLogger.log(step, mkh, "ℹ", "Screenshot API chưa hỗ trợ trên Android này")
            return
        }

        val displayId = runCatching {
            display?.displayId ?: Display.DEFAULT_DISPLAY
        }.getOrElse {
            VpbatLogger.log(step, mkh, "ℹ", "Service context không gắn display, fallback về DEFAULT_DISPLAY")
            Display.DEFAULT_DISPLAY
        }

        try {
            takeScreenshot(
                displayId,
                mainExecutor,
                object : AccessibilityService.TakeScreenshotCallback {
                    override fun onSuccess(screenshot: AccessibilityService.ScreenshotResult) {
                        val hardwareBuffer: HardwareBuffer = screenshot.hardwareBuffer
                        val bitmap = Bitmap.wrapHardwareBuffer(hardwareBuffer, screenshot.colorSpace)
                        if (bitmap == null) {
                            hardwareBuffer.close()
                            VpbatLogger.log(step, mkh, "⚠", "Không tạo được bitmap từ screenshot buffer")
                            return
                        }

                        runCatching {
                            FileOutputStream(outputFile).use { stream ->
                                bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)
                            }
                        }.onFailure {
                            VpbatLogger.log(step, mkh, "⚠", "Không lưu được screenshot: ${it.message}")
                        }

                        bitmap.recycle()
                        hardwareBuffer.close()
                    }

                    override fun onFailure(errorCode: Int) {
                        VpbatLogger.log(step, mkh, "⚠", "Screenshot thất bại với mã lỗi $errorCode")
                    }
                }
            )
        } catch (exception: Exception) {
            VpbatLogger.log(step, mkh, "⚠", "Không gọi được takeScreenshot: ${exception.message}")
        }
    }

    // â”€â”€ Helpers â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

    /**
     * TÃ¬m node clickable theo text hiá»ƒn thá»‹, click vÃ o node Ä‘Ã³.
     * Tráº£ vá» true náº¿u thÃ nh cÃ´ng.
     */
    private fun clickByText(root: AccessibilityNodeInfo, text: String): Boolean {
        val nodes = root.findAccessibilityNodeInfosByText(text)
        if (nodes.isNullOrEmpty()) return false

        val target = nodes
            .filter { it.isEnabled && it.isVisibleToUser }
            .ifEmpty { nodes }
            .maxByOrNull { node -> clickPriority(node) }
            ?: nodes[0]

        val clickableTarget = if (target.isClickable) target else findClickableParent(target) ?: target
        val success = clickNodeWithFallback(clickableTarget)
        nodes.forEach { it.recycle() }
        return success
    }

    private fun clickSkipAutoPayButton(root: AccessibilityNodeInfo): Boolean {
        val exactCandidates = listOf(
            AppConfig.TEXT_BTN_SKIP_AUTO,
            "Không tiếp tục thanh toán",
            "Không, tiếp tục",
            "Khong, tiep tuc thanh toan",
            "Khong tiep tuc thanh toan"
        )

        exactCandidates.forEach { candidate ->
            if (clickByText(root, candidate)) return true
        }

        val tokenSets = listOf(
            listOf("khong", "tiep", "tuc", "thanh", "toan"),
            listOf("khong", "tiep", "tuc"),
            listOf("khong", "thanh", "toan")
        )
        return tokenSets.any { tokenSet -> clickByNormalizedTokens(root, tokenSet) }
    }

    private fun clickContinueButton(root: AccessibilityNodeInfo): Boolean {
        val exactCandidates = listOf(
            AppConfig.TEXT_BTN_TIEP_TUC,
            "Tiếp Tục",
            "TIẾP TỤC",
            "Tiep tuc"
        )

        exactCandidates.forEach { candidate ->
            if (clickByText(root, candidate)) return true
        }

        return clickByNormalizedTokens(root, listOf("tiep", "tuc"))
    }

    /**
     * Click nút "Tiếp tục" dành riêng cho màn hóa đơn (B3).
     * Khác clickContinueButton: loại trừ các node có chứa "Không" ("Không, tiếp tục thanh toán")
     * để tránh click nhầm vào nút của popup thanh toán tự động.
     * Fallback cuối: gesture tap vùng đáy màn hình nơi nút Tiếp tục thường xuất hiện.
     */
    private fun clickInvoiceContinueButton(root: AccessibilityNodeInfo): Boolean {
        val exactCandidates = listOf(
            AppConfig.TEXT_BTN_TIEP_TUC, "Tiếp Tục", "TIẾP TỤC", "Tiep tuc"
        )
        for (text in exactCandidates) {
            val nodes = root.findAccessibilityNodeInfosByText(text)
            if (nodes.isNullOrEmpty()) continue
            // Ưu tiên node không chứa "không" (tránh click "Không, tiếp tục thanh toán")
            val preferred = nodes.filter { n ->
                val combined = ((n.text?.toString() ?: "") + " " + (n.contentDescription?.toString() ?: "")).trim()
                !normalizeText(combined).contains("khong")
            }.ifEmpty { nodes }
            val target = preferred.filter { it.isEnabled && it.isVisibleToUser }.ifEmpty { preferred }
                .maxByOrNull { clickPriority(it) }
            val ok = target?.let {
                val clickable = if (it.isClickable) it else findClickableParent(it) ?: it
                clickNodeWithFallback(clickable)
            } ?: false
            nodes.forEach { it.recycle() }
            if (ok) return true
        }

        // Token search ["tiep", "tuc"] cũng loại trừ "không"
        val matches = mutableListOf<AccessibilityNodeInfo>()
        collectMatchingTextNodes(root, matches, listOf("tiep", "tuc"))
        val preferred = matches.filter { n ->
            val combined = ((n.text?.toString() ?: "") + " " + (n.contentDescription?.toString() ?: "")).trim()
            !normalizeText(combined).contains("khong")
        }.ifEmpty { matches }
        val target = preferred.filter { it.isEnabled && it.isVisibleToUser }.ifEmpty { preferred }
            .maxByOrNull { clickPriority(it) }
        return if (target != null) {
            val clickable = if (target.isClickable) target else findClickableParent(target) ?: target
            val ok = clickNodeWithFallback(clickable)
            matches.forEach { it.recycle() }
            ok
        } else {
            matches.forEach { it.recycle() }
            // Fallback gesture: tap vùng đáy màn hình (92%) nơi Tiếp tục thường đặt
            VpbatLogger.log("B3-HOA_DON", mkhList.getOrElse(currentIndex) { "?" }, "⚠", "Không tìm thấy text Tiếp tục → thử gesture tap đáy màn hình")
            tapScreenPercent(root, 0.50f, 0.92f)
        }
    }

    private fun clickConfirmButton(root: AccessibilityNodeInfo): Boolean {
        val exactCandidates = listOf(
            AppConfig.TEXT_BTN_XAC_NHAN,
            "Xác nhận",
            "Xac nhan"
        )

        exactCandidates.forEach { candidate ->
            if (clickByText(root, candidate)) return true
        }

        if (clickByNormalizedTokens(root, listOf("xac", "nhan"))) return true

        return tapScreenPercent(root, 0.50f, 0.95f)
    }

    private fun isInvoiceReviewScreen(root: AccessibilityNodeInfo): Boolean {
        return isTextPresent(root, "Tổng tiền") ||
            isTextPresent(root, "Đăng ký thanh toán") ||
            isTextPresent(root, "hóa đơn chưa thanh toán") ||
            isTextPresent(root, "Vui lòng bỏ chọn các hóa đơn bạn không muốn thanh toán")
    }

    private fun isConfirmScreenVisible(root: AccessibilityNodeInfo): Boolean {
        return isTextPresent(root, AppConfig.TEXT_CONFIRM_SCREEN) ||
            isTextPresent(root, AppConfig.TEXT_BTN_XAC_NHAN)
    }

    private fun isSmartOtpScreenVisible(root: AccessibilityNodeInfo): Boolean {
        return isTextPresent(root, AppConfig.TEXT_SMART_OTP_TITLE)
    }

    private fun isAdvancedOtpScreenVisible(root: AccessibilityNodeInfo): Boolean {
        return isTextPresent(root, AppConfig.TEXT_OTP_ADV_SCREEN) ||
            isTextPresent(root, AppConfig.TEXT_BTN_XAC_NHAN_GD) ||
            isTextPresent(root, "Mã OTP sẽ tự động cập nhật sau") ||
            isTextPresent(root, "Mã OTP sẽ tự động cập nhật") ||
            isTextPresent(root, "Bấm Xác nhận giao dịch") ||
            isTextPresent(root, "Serial:")
    }

    private fun clickConfirmTransactionButton(root: AccessibilityNodeInfo): Boolean {
        val exactCandidates = listOf(
            AppConfig.TEXT_BTN_XAC_NHAN_GD,
            "Xác nhận giao dịch",
            "Xac nhan giao dich"
        )

        exactCandidates.forEach { candidate ->
            if (clickByText(root, candidate)) return true
        }

        if (clickByNormalizedTokens(root, listOf("xac", "nhan", "giao", "dich"))) {
            return true
        }

        if (tapConfirmTransactionButtonByLayout(root)) {
            return true
        }

        if (scrollToBottomAction(root)) {
            if (clickByText(root, AppConfig.TEXT_BTN_XAC_NHAN_GD)) return true
            if (clickByNormalizedTokens(root, listOf("xac", "nhan", "giao", "dich"))) return true
            if (tapConfirmTransactionButtonByLayout(root)) return true
        }

        return tapScreenPercent(root, 0.50f, 0.90f) ||
            tapScreenPercent(root, 0.50f, 0.92f) ||
            tapScreenPercent(root, 0.50f, 0.94f) ||
            tapScreenPercent(root, 0.50f, 0.96f) ||
            tapScreenPercent(root, 0.50f, 0.87f) ||
            tapScreenPercent(root, 0.50f, 0.93f)
    }

    private fun tapConfirmTransactionButtonByLayout(root: AccessibilityNodeInfo): Boolean {
        val hintCandidates = listOf(
            "Bấm Xác nhận giao dịch",
            "Bấm Xác nhận giao dịch mã OTP sẽ được điền tự động",
            "OTP sẽ được điền tự động",
            "Mã OTP sẽ tự động cập nhật sau",
            "Xác nhận giao dịch mã OTP sẽ được điền tự động"
        )

        for (hint in hintCandidates) {
            val nodes = root.findAccessibilityNodeInfosByText(hint)
            if (nodes.isNullOrEmpty()) continue

            val target = nodes
                .filter { it.isVisibleToUser }
                .maxByOrNull {
                    val bounds = Rect()
                    it.getBoundsInScreen(bounds)
                    bounds.bottom
                } ?: nodes[0]

            val hintBounds = Rect()
            val rootBounds = Rect()
            target.getBoundsInScreen(hintBounds)
            root.getBoundsInScreen(rootBounds)
            nodes.forEach { it.recycle() }

            if (hintBounds.isEmpty || rootBounds.isEmpty) continue

            val offsetY = (rootBounds.height() * 0.09f).coerceAtLeast(160f)
            val centerX = rootBounds.exactCenterX()
            val centerY = minOf(rootBounds.bottom - 80f, hintBounds.bottom + offsetY)

            if (tapScreenAt(centerX, centerY)) return true
            if (tapScreenAt(rootBounds.left + rootBounds.width() * 0.22f, centerY)) return true
            if (tapScreenAt(rootBounds.left + rootBounds.width() * 0.78f, centerY)) return true
            if (tapScreenAt(centerX, centerY + 40f)) return true
            if (tapScreenAt(centerX, centerY - 40f)) return true
        }

        return false
    }

    private fun scrollToBottomAction(root: AccessibilityNodeInfo): Boolean {
        if (performScrollForward(root)) {
            VpbatLogger.log("B7-OTP_ADV", mkhList.getOrElse(currentIndex) { "?" }, "→", "Đã scroll xuống để lộ nút xác nhận giao dịch")
            return true
        }
        return false
    }

    private fun performScrollForward(node: AccessibilityNodeInfo): Boolean {
        if (node.isScrollable && node.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)) {
            return true
        }

        for (index in 0 until node.childCount) {
            val child = node.getChild(index) ?: continue
            try {
                if (performScrollForward(child)) return true
            } finally {
                child.recycle()
            }
        }
        return false
    }

    private fun clickNewTransactionButton(root: AccessibilityNodeInfo): Boolean {
        val exactCandidates = listOf(
            AppConfig.TEXT_BTN_NEW_TX,
            "Giao dịch mới",
            "Giao Dich Moi"
        )

        exactCandidates.forEach { candidate ->
            if (clickByText(root, candidate)) return true
        }

        if (clickByNormalizedTokens(root, listOf("giao", "dich", "moi"))) {
            return true
        }

        // Nút Giao dịch mới nằm ở góc phải phía dưới màn hình thành công.
        return tapScreenPercent(root, 0.76f, 0.92f)
    }

    private fun dismissSuccessOverlayPopup(root: AccessibilityNodeInfo): Boolean {
        val exactCandidates = listOf(
            "Đóng",
            "Dong",
            "Để sau",
            "De sau",
            "Bỏ qua",
            "Bo qua",
            "X"
        )

        exactCandidates.forEach { candidate ->
            if (clickByText(root, candidate)) return true
        }

        val tokenSets = listOf(
            listOf("dong"),
            listOf("de", "sau"),
            listOf("bo", "qua"),
            listOf("close")
        )
        tokenSets.forEach { tokenSet ->
            if (clickByNormalizedTokens(root, tokenSet)) return true
        }

        if (tapTopLeftCloseCandidate(root)) return true

        return false
    }

    private fun hasSuccessOverlayPopup(root: AccessibilityNodeInfo): Boolean {
        val exactHints = listOf("Đóng", "Dong", "Để sau", "De sau", "Bỏ qua", "Bo qua")
        if (exactHints.any { hint -> isTextPresent(root, hint) }) return true

        // Nếu đã thấy nút "Giao dịch mới" thì không coi riêng dấu X/Xong ở header là popup đè.
        if (isTextPresent(root, AppConfig.TEXT_BTN_NEW_TX)) return false

        val matches = mutableListOf<AccessibilityNodeInfo>()
        collectTopLeftCloseCandidates(root, matches)
        val hasClose = matches.isNotEmpty()
        matches.forEach { it.recycle() }
        return hasClose
    }

    private fun clickByNormalizedTokens(root: AccessibilityNodeInfo, tokens: List<String>): Boolean {
        val matches = mutableListOf<AccessibilityNodeInfo>()
        collectMatchingTextNodes(root, matches, tokens)
        if (matches.isEmpty()) return false

        val target = matches.maxByOrNull { node ->
            clickPriority(node)
        }

        val success = if (target == null) {
            false
        } else {
            val clickable = if (target.isClickable) target else findClickableParent(target) ?: target
            clickNodeWithFallback(clickable)
        }

        matches.forEach { it.recycle() }
        return success
    }

    private fun collectMatchingTextNodes(
        node: AccessibilityNodeInfo,
        out: MutableList<AccessibilityNodeInfo>,
        tokens: List<String>
    ) {
        val rawText = node.text?.toString().orEmpty()
        val rawDesc = node.contentDescription?.toString().orEmpty()
        val combinedText = listOf(rawText, rawDesc)
            .filter { it.isNotBlank() }
            .joinToString(" ")
        if (combinedText.isNotBlank() && node.isEnabled && node.isVisibleToUser) {
            val normalized = normalizeText(combinedText)
            if (tokens.all { normalized.contains(it) }) {
                out.add(AccessibilityNodeInfo.obtain(node))
            }
        }

        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            try {
                collectMatchingTextNodes(child, out, tokens)
            } finally {
                child.recycle()
            }
        }
    }

    private fun normalizeText(value: String): String {
        return value
            .lowercase()
            .replace("đ", "d")
            .replace("[àáạảãâầấậẩẫăằắặẳẵ]".toRegex(), "a")
            .replace("[èéẹẻẽêềếệểễ]".toRegex(), "e")
            .replace("[ìíịỉĩ]".toRegex(), "i")
            .replace("[òóọỏõôồốộổỗơờớợởỡ]".toRegex(), "o")
            .replace("[ùúụủũưừứựửữ]".toRegex(), "u")
            .replace("[ỳýỵỷỹ]".toRegex(), "y")
            .replace("[^a-z0-9 ]".toRegex(), " ")
            .replace("\\s+".toRegex(), " ")
            .trim()
    }

    private fun clickPriority(node: AccessibilityNodeInfo): Int {
        val bounds = Rect()
        node.getBoundsInScreen(bounds)
        val isButtonClass = node.className?.toString()?.contains("Button", ignoreCase = true) == true
        val scoreClass = if (isButtonClass) 1_000_000 else 0
        return scoreClass + bounds.centerY()
    }

    private fun clickNodeWithFallback(node: AccessibilityNodeInfo): Boolean {
        if (node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
        val parent = findClickableParent(node)
        if (parent != null && parent.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
        return tapNodeCenter(node)
    }

    private fun tapScreenPercent(root: AccessibilityNodeInfo, xPercent: Float, yPercent: Float): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return false

        val bounds = Rect()
        root.getBoundsInScreen(bounds)
        if (bounds.isEmpty) return false

        val x = bounds.left + bounds.width() * xPercent
        val y = bounds.top + bounds.height() * yPercent
        return tapScreenAt(x, y)
    }

    private fun tapTopLeftCloseCandidate(root: AccessibilityNodeInfo): Boolean {
        val matches = mutableListOf<AccessibilityNodeInfo>()
        collectTopLeftCloseCandidates(root, matches)
        if (matches.isEmpty()) return false

        val target = matches.minWithOrNull(
            compareBy<AccessibilityNodeInfo> {
                val bounds = Rect()
                it.getBoundsInScreen(bounds)
                bounds.top
            }.thenByDescending {
                val bounds = Rect()
                it.getBoundsInScreen(bounds)
                bounds.right
            }
        )

        val success = if (target == null) false else clickNodeWithFallback(target)
        matches.forEach { it.recycle() }
        return success || tapScreenPercent(root, 0.94f, 0.08f)
    }

    private fun collectTopLeftCloseCandidates(
        node: AccessibilityNodeInfo,
        out: MutableList<AccessibilityNodeInfo>
    ) {
        val bounds = Rect()
        node.getBoundsInScreen(bounds)
        val combinedText = listOf(node.text?.toString().orEmpty(), node.contentDescription?.toString().orEmpty())
            .filter { it.isNotBlank() }
            .joinToString(" ")
        val normalized = normalizeText(combinedText)
        val clickable = node.isClickable || findClickableParent(node) != null
        val looksLikeClose = normalized == "x" ||
            normalized.contains("dong") ||
            normalized.contains("close") ||
            normalized.contains("tat") ||
            (bounds.right > resources.displayMetrics.widthPixels - 220 &&
                bounds.top < 260 &&
                bounds.width() in 24..120 &&
                bounds.height() in 24..120)

        if (node.isVisibleToUser && node.isEnabled && clickable && looksLikeClose) {
            out.add(AccessibilityNodeInfo.obtain(node))
        }

        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            try {
                collectTopLeftCloseCandidates(child, out)
            } finally {
                child.recycle()
            }
        }
    }

    private fun tapNodeCenter(node: AccessibilityNodeInfo): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return false

        val bounds = Rect()
        node.getBoundsInScreen(bounds)
        if (bounds.isEmpty) return false

        return tapScreenAt(bounds.exactCenterX(), bounds.exactCenterY())
    }

    private fun tapScreenAt(x: Float, y: Float): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return false

        val path = Path().apply {
            moveTo(x, y)
        }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 80))
            .build()

        return dispatchGesture(gesture, null, null)
    }

    /**
     * Äi lÃªn cÃ¢y view Ä‘á»ƒ tÃ¬m ancestor clickable.
     */
    private fun findClickableParent(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        var current: AccessibilityNodeInfo? = node.parent
        repeat(5) {
            val c = current ?: return null
            if (c.isClickable) return c
            current = c.parent
        }
        return null
    }

    private fun canDetectMkhInputField(root: AccessibilityNodeInfo): Boolean {
        val keywords = listOf("Mã khách hàng", "Mã KH", "Mã khách", "MKH", "Khách hàng")
        val foundByText = keywords.any { keyword ->
            val nodes = root.findAccessibilityNodeInfosByText(keyword)
            val hasInput = nodes.any { node ->
                val editableSelf = node.isEditable || node.className?.toString()?.contains("EditText", ignoreCase = true) == true
                val parent = node.parent
                val editableParent = parent?.isEditable == true ||
                    parent?.className?.toString()?.contains("EditText", ignoreCase = true) == true
                parent?.recycle()
                editableSelf || editableParent
            }
            nodes.forEach { it.recycle() }
            hasInput
        }
        return foundByText || hasLikelyMkhFieldRecursive(root)
    }

    private fun setMkhWithFallback(root: AccessibilityNodeInfo, mkh: String): Boolean {
        val keywords = listOf("Mã khách hàng", "MKH", "Khách hàng")

        // 1) Ưu tiên tìm bằng text gần trường MKH.
        keywords.forEach { keyword ->
            val nodes = root.findAccessibilityNodeInfosByText(keyword)
            for (node in nodes) {
                if (setTextOnCandidateOrParent(node, mkh)) {
                    nodes.forEach { it.recycle() }
                    return true
                }
            }
            nodes.forEach { it.recycle() }
        }

        // 2) Fallback: quét theo hint/content-desc/text để tìm EditText đúng ngữ cảnh.
        if (setTextOnLikelyMkhFieldRecursive(root, mkh)) return true

        return false
    }

    private fun setTextOnCandidateOrParent(node: AccessibilityNodeInfo, value: String): Boolean {
        if (setTextOnNode(node, value)) return true

        val parent = node.parent ?: return false
        return try {
            setTextOnNode(parent, value)
        } finally {
            parent.recycle()
        }
    }

    private fun setTextOnNode(node: AccessibilityNodeInfo, value: String): Boolean {
        val editable = node.isEditable || node.className?.toString()?.contains("EditText", ignoreCase = true) == true
        if (!editable) return false

        node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        return node.performAction(
            AccessibilityNodeInfo.ACTION_SET_TEXT,
            Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value)
            }
        )
    }

    private fun setTextOnLikelyMkhFieldRecursive(node: AccessibilityNodeInfo, value: String): Boolean {
        if (isLikelyMkhField(node) && setTextOnNode(node, value)) return true

        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val ok = try {
                setTextOnLikelyMkhFieldRecursive(child, value)
            } finally {
                child.recycle()
            }
            if (ok) return true
        }
        return false
    }

    private fun hasLikelyMkhFieldRecursive(node: AccessibilityNodeInfo): Boolean {
        if (isLikelyMkhField(node)) return true

        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val hasLikely = try {
                hasLikelyMkhFieldRecursive(child)
            } finally {
                child.recycle()
            }
            if (hasLikely) return true
        }
        return false
    }

    private fun isLikelyMkhField(node: AccessibilityNodeInfo): Boolean {
        val editable = node.isEditable || node.className?.toString()?.contains("EditText", ignoreCase = true) == true
        if (!editable) return false

        val text = node.text?.toString().orEmpty().lowercase()
        val desc = node.contentDescription?.toString().orEmpty().lowercase()
        val hint = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            node.hintText?.toString().orEmpty().lowercase()
        } else {
            ""
        }
        val combined = normalizeText("$text $desc $hint")

        if (combined.contains("khuyen mai") ||
            combined.contains("khuyenmai") ||
            combined.contains("promo") ||
            combined.contains("voucher") ||
            combined.contains("giam gia")) {
            return false
        }

        return combined.contains("ma khach hang") ||
            combined.contains("mkh") ||
            combined.contains("khach hang")
    }

    private fun isTextPresent(root: AccessibilityNodeInfo, text: String): Boolean {
        val nodes = root.findAccessibilityNodeInfosByText(text)
        val hasText = !nodes.isNullOrEmpty()
        nodes.forEach { it.recycle() }
        return hasText
    }

    private fun isProviderPickerVisible(root: AccessibilityNodeInfo): Boolean {
        val hasTitle = isTextPresent(root, AppConfig.TEXT_PROVIDER_DIALOG_TITLE)
        val hasSearch = isTextPresent(root, AppConfig.TEXT_PROVIDER_SEARCH)
        return hasTitle && hasSearch
    }

    private fun openProviderPickerFromForm(root: AccessibilityNodeInfo): Boolean {
        return clickByText(root, AppConfig.TEXT_PROVIDER_LABEL) ||
            clickByText(root, AppConfig.TEXT_ELEC_HCM) ||
            clickByText(root, AppConfig.TEXT_ELEC_TOAN_QUOC)
    }

    // â”€â”€ Broadcast â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

    private fun broadcastStatus(current: Int, total: Int, mkh: String, status: String) {
        val intent = Intent(AppConfig.ACTION_PROGRESS).apply {
            putExtra(AppConfig.EXTRA_CURRENT, current)
            putExtra(AppConfig.EXTRA_TOTAL,   total)
            putExtra(AppConfig.EXTRA_STATUS,  status)
        }
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent)
    }
}

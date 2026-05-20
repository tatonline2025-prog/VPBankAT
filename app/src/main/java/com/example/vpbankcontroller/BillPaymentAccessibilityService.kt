package com.example.vpbankcontroller

import android.accessibilityservice.AccessibilityService
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import org.json.JSONArray

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

    private val tag = "VPBankAutoService"

    private val handler      = Handler(Looper.getMainLooper())
    private var state        = PaymentState.IDLE
    private var mkhList      = emptyList<String>()
    private var currentIndex = 0
    private var retryCount   = 0
    private var pinCharIndex = 0   // vá»‹ trÃ­ kÃ½ tá»± PIN Ä‘ang nháº­p

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
        prefs = getSharedPreferences(AppConfig.PREFS_NAME, Context.MODE_PRIVATE)
        AppConfig.vpbankPackage =
            prefs.getString(AppConfig.KEY_VPBANK_PACKAGE, AppConfig.vpbankPackage)
                ?: AppConfig.vpbankPackage

        LocalBroadcastManager.getInstance(this).registerReceiver(
            commandReceiver,
            IntentFilter().apply {
                addAction(AppConfig.ACTION_START)
                addAction(AppConfig.ACTION_STOP)
            }
        )
        Log.d(tag, "AccessibilityService connected")

        if (prefs.getBoolean(AppConfig.KEY_IS_RUNNING, false)) {
            startAutomation()
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        if (state == PaymentState.IDLE || state == PaymentState.DONE) return
        val pkg = event.packageName?.toString() ?: return
        if (pkg != AppConfig.vpbankPackage) return

        // Báº¥t ká»³ sá»± kiá»‡n VPBank nÃ o â†’ schedule poll
        handler.removeCallbacks(pollRunnable)
        handler.postDelayed(pollRunnable, AppConfig.POLL_INTERVAL_MS)
    }

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        super.onDestroy()
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
            return
        }

        Log.d(tag, "Start at index $currentIndex / ${mkhList.size}")
        retryCount   = 0
        pinCharIndex = 0
        state        = PaymentState.SELECT_ELEC_TYPE
        handler.post(pollRunnable)
    }

    private fun stopAutomation() {
        state = PaymentState.IDLE
        handler.removeCallbacksAndMessages(null)
        prefs.edit().putBoolean(AppConfig.KEY_IS_RUNNING, false).apply()
        broadcastStatus(currentIndex, mkhList.size, "", "\u0110\u00e3 d\u1eebng")
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
    // PB → "Điện toàn quốc" | PE → "Điện Hồ Chí Minh"
    // Nếu không tìm thấy nút (đã ở màn hình nhập MKH) → skip thẳng vào INPUT_MKH
    private fun handleSelectElecType(root: AccessibilityNodeInfo) {
        val mkh = mkhList[currentIndex]
        val prefix = mkh.uppercase().take(2)
        val targetText = when (prefix) {
            "PB" -> AppConfig.TEXT_ELEC_TOAN_QUOC
            "PE" -> AppConfig.TEXT_ELEC_HCM
            else -> {
                Log.w(tag, "Prefix không xác định: $prefix → bỏ qua MKH $mkh")
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

        // Thử click nút loại điện
        if (clickByText(root, targetText)) {
            Log.d(tag, "Chọn loại điện: $targetText (MKH=$mkh)")
            broadcastStatus(currentIndex + 1, mkhList.size, mkh, "Chọn $targetText…")
            retryCount = 0
            state      = PaymentState.INPUT_MKH
            handler.postDelayed(pollRunnable, AppConfig.DELAY_AFTER_CLICK_MS)
            return
        }

        // Nút loại điện không có → kiểm tra xem đã ở màn hình nhập MKH chưa
        val mkhNodes = root.findAccessibilityNodeInfosByText(AppConfig.TEXT_MKH_HINT)
        if (!mkhNodes.isNullOrEmpty()) {
            mkhNodes.forEach { it.recycle() }
            Log.d(tag, "Đã ở màn hình MKH, skip SELECT_ELEC_TYPE")
            retryCount = 0
            state      = PaymentState.INPUT_MKH
            handler.post(pollRunnable)
            return
        }

        scheduleRetry()
    }

    private fun handleInputMkh(root: AccessibilityNodeInfo) {
        val mkh = mkhList[currentIndex]

        val nodes = root.findAccessibilityNodeInfosByText(AppConfig.TEXT_MKH_HINT)
        if (nodes.isNullOrEmpty()) {
            Log.d(tag, "Field MKH chưa xuất hiện")
            scheduleRetry(); return
        }

        val inputNode = nodes.firstOrNull { it.isEditable }
            ?: nodes.firstOrNull { it.className?.contains("EditText") == true }
            ?: nodes[0]

        inputNode.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        inputNode.performAction(
            AccessibilityNodeInfo.ACTION_SET_TEXT,
            Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, mkh)
            }
        )
        nodes.forEach { it.recycle() }

        Log.d(tag, "\u0110\u00e3 nh\u1eadp MKH: $mkh")
        broadcastStatus(currentIndex + 1, mkhList.size, mkh, "\u0110ang nh\u1eadp MKH: $mkh")

        retryCount = 0
        state      = PaymentState.CLICK_TIEP_TUC_1
        handler.postDelayed(pollRunnable, AppConfig.DELAY_AFTER_TEXT_MS)
    }

    // â”€â”€ BÆ°á»›c 2: Báº¥m "Tiáº¿p tá»¥c" láº§n 1 (sau khi nháº­p MKH) â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
    private fun handleTiepTuc1(root: AccessibilityNodeInfo) {
        if (clickByText(root, AppConfig.TEXT_BTN_TIEP_TUC)) {
            Log.d(tag, "Clicked Tiáº¿p tá»¥c 1")
            broadcastStatus(currentIndex + 1, mkhList.size, mkhList[currentIndex], "\u0110ang tra c\u1ee9u h\u00f3a \u0111\u01a1n\u2026")
            retryCount = 0
            state      = PaymentState.WAIT_INVOICE_SCREEN
            handler.postDelayed(pollRunnable, AppConfig.DELAY_AFTER_CLICK_MS)
        } else {
            scheduleRetry()
        }
    }

    // â”€â”€ BÆ°á»›c 3: MÃ n hÃ¬nh thÃ´ng tin KH + hÃ³a Ä‘Æ¡n â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
    // Kiá»ƒm tra xem popup "KÃ­ch hoáº¡t tá»± Ä‘á»™ng" cÃ³ xuáº¥t hiá»‡n khÃ´ng, xá»­ lÃ½ trÆ°á»›c.
    // Náº¿u khÃ´ng cÃ³ popup thÃ¬ báº¥m "Tiáº¿p tá»¥c".
    private fun handleInvoiceScreen(root: AccessibilityNodeInfo) {
        // Kiá»ƒm tra popup
        val popupNodes = root.findAccessibilityNodeInfosByText(AppConfig.TEXT_AUTO_PAY_POPUP)
        if (!popupNodes.isNullOrEmpty()) {
            popupNodes.forEach { it.recycle() }
            Log.d(tag, "PhÃ¡t hiá»‡n popup kÃ­ch hoáº¡t tá»± Ä‘á»™ng â†’ dismiss")
            state = PaymentState.DISMISS_AUTO_PAY
            handler.post(pollRunnable)
            return
        }

        // KhÃ´ng cÃ³ popup â†’ báº¥m Tiáº¿p tá»¥c
        if (clickByText(root, AppConfig.TEXT_BTN_TIEP_TUC)) {
            Log.d(tag, "Clicked Tiáº¿p tá»¥c 2 (invoice screen)")
            broadcastStatus(currentIndex + 1, mkhList.size, mkhList[currentIndex], "\u0110ang x\u00e1c nh\u1eadn h\u00f3a \u0111\u01a1n\u2026")
            retryCount = 0
            state      = PaymentState.CLICK_XAC_NHAN
            handler.postDelayed(pollRunnable, AppConfig.DELAY_AFTER_CLICK_MS)
        } else {
            scheduleRetry()
        }
    }

    // â”€â”€ BÆ°á»›c 3b: ÄÃ³ng popup "KÃ­ch hoáº¡t thanh toÃ¡n tá»± Ä‘á»™ng" â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
    private fun handleDismissAutoPayPopup(root: AccessibilityNodeInfo) {
        if (clickByText(root, AppConfig.TEXT_BTN_SKIP_AUTO)) {
            Log.d(tag, "Dismissed auto-pay popup")
            retryCount = 0
            state      = PaymentState.CLICK_TIEP_TUC_2
            handler.postDelayed(pollRunnable, AppConfig.DELAY_AFTER_CLICK_MS)
        } else {
            scheduleRetry()
        }
    }

    // â”€â”€ BÆ°á»›c 3c: Báº¥m "Tiáº¿p tá»¥c" sau khi Ä‘Ã£ dismiss popup â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
    private fun handleTiepTuc2(root: AccessibilityNodeInfo) {
        if (clickByText(root, AppConfig.TEXT_BTN_TIEP_TUC)) {
            Log.d(tag, "Clicked Tiáº¿p tá»¥c sau popup")
            broadcastStatus(currentIndex + 1, mkhList.size, mkhList[currentIndex], "\u0110ang x\u00e1c nh\u1eadn\u2026")
            retryCount = 0
            state      = PaymentState.CLICK_XAC_NHAN
            handler.postDelayed(pollRunnable, AppConfig.DELAY_AFTER_CLICK_MS)
        } else {
            scheduleRetry()
        }
    }

    // â”€â”€ BÆ°á»›c 4: MÃ n hÃ¬nh XÃ¡c nháº­n â†’ báº¥m "XÃ¡c nháº­n" â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
    private fun handleXacNhan(root: AccessibilityNodeInfo) {
        // Äáº£m báº£o Ä‘ang á»Ÿ mÃ n hÃ¬nh XÃ¡c nháº­n (title)
        val titleNodes = root.findAccessibilityNodeInfosByText(AppConfig.TEXT_CONFIRM_SCREEN)
        if (titleNodes.isNullOrEmpty()) {
            scheduleRetry(); return
        }
        titleNodes.forEach { it.recycle() }

        if (clickByText(root, AppConfig.TEXT_BTN_XAC_NHAN)) {
            Log.d(tag, "Clicked XÃ¡c nháº­n")
            broadcastStatus(currentIndex + 1, mkhList.size, mkhList[currentIndex], "\u0110ang nh\u1eadp PIN Smart OTP\u2026")
            retryCount   = 0
            pinCharIndex = 0
            state        = PaymentState.INPUT_SMART_OTP_PIN
            handler.postDelayed(pollRunnable, AppConfig.DELAY_AFTER_CLICK_MS)
        } else {
            scheduleRetry()
        }
    }

    // â”€â”€ BÆ°á»›c 5: Nháº­p PIN Smart OTP (6 chá»¯ sá»‘) â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
    // BÃ n phÃ­m lÃ  custom grid, má»—i Ã´ sá»‘ lÃ  1 node vá»›i text = chá»¯ sá»‘ Ä‘Ã³.
    private fun handleSmartOtpPin(root: AccessibilityNodeInfo) {
        val pin = AppConfig.smartOtpPin
        if (pin.length != 6 || !pin.all { it.isDigit() }) {
            Log.e(tag, "PIN khÃ´ng há»£p lá»‡: '$pin' â€“ dá»«ng")
            stopAutomation()
            broadcastStatus(currentIndex, mkhList.size, mkhList[currentIndex],
                "\u26d4 PIN Smart OTP ch\u01b0a h\u1ee3p l\u1ec7. Vui l\u00f2ng nh\u1eadp 6 ch\u1eef s\u1ed1 r\u1ed3i th\u1eed l\u1ea1i.")
            return
        }

        // Kiá»ƒm tra Ä‘ang á»Ÿ mÃ n hÃ¬nh Smart OTP
        val otpTitleNodes = root.findAccessibilityNodeInfosByText(AppConfig.TEXT_SMART_OTP_TITLE)
        if (otpTitleNodes.isNullOrEmpty()) {
            // CÃ³ thá»ƒ Ä‘Ã£ vÆ°á»£t qua mÃ n hÃ¬nh nÃ y (giao dá»‹ch nhá» khÃ´ng cáº§n OTP PIN)
            Log.d(tag, "KhÃ´ng tháº¥y mÃ n hÃ¬nh Smart OTP â†’ thá»­ WAIT_SUCCESS")
            state = PaymentState.WAIT_SUCCESS
            handler.post(pollRunnable)
            return
        }
        otpTitleNodes.forEach { it.recycle() }

        if (pinCharIndex >= pin.length) {
            // ÄÃ£ nháº­p xong PIN â†’ chá» chuyá»ƒn mÃ n hÃ¬nh
            retryCount   = 0
            state        = PaymentState.CLICK_XAC_NHAN_GD
            handler.postDelayed(pollRunnable, AppConfig.DELAY_AFTER_CLICK_MS)
            return
        }

        val digit = pin[pinCharIndex].toString()
        if (clickByText(root, digit)) {
            Log.d(tag, "PIN char $pinCharIndex: $digit")
            pinCharIndex++
            // Nháº­p nhanh tá»«ng sá»‘: delay ngáº¯n
            handler.postDelayed(pollRunnable, 300L)
        } else {
            scheduleRetry()
        }
    }

    // â”€â”€ BÆ°á»›c 6: OTP nÃ¢ng cao â†’ báº¥m "XÃ¡c nháº­n giao dá»‹ch" â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
    private fun handleXacNhanGiaoDich(root: AccessibilityNodeInfo) {
        // MÃ n hÃ¬nh "XÃ¡c nháº­n OTP nÃ¢ng cao"
        val advNodes = root.findAccessibilityNodeInfosByText(AppConfig.TEXT_OTP_ADV_SCREEN)
        if (!advNodes.isNullOrEmpty()) {
            advNodes.forEach { it.recycle() }
            if (clickByText(root, AppConfig.TEXT_BTN_XAC_NHAN_GD)) {
                Log.d(tag, "Clicked XÃ¡c nháº­n giao dá»‹ch")
                broadcastStatus(currentIndex + 1, mkhList.size, mkhList[currentIndex], "\u0110ang ch\u1edd k\u1ebft qu\u1ea3\u2026")
                retryCount = 0
                state      = PaymentState.WAIT_SUCCESS
                handler.postDelayed(pollRunnable, AppConfig.DELAY_AFTER_CLICK_MS)
            } else {
                scheduleRetry()
            }
            return
        }

        // Náº¿u khÃ´ng cÃ³ mÃ n hÃ¬nh OTP nÃ¢ng cao â†’ cÃ³ thá»ƒ Ä‘Ã£ ThÃ nh cÃ´ng
        state = PaymentState.WAIT_SUCCESS
        handler.post(pollRunnable)
    }

    // â”€â”€ BÆ°á»›c 7: Chá» mÃ n hÃ¬nh "ThÃ nh cÃ´ng" â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
    private fun handleWaitSuccess(root: AccessibilityNodeInfo) {
        val successNodes = root.findAccessibilityNodeInfosByText(AppConfig.TEXT_SUCCESS)
        if (successNodes.isNullOrEmpty()) {
            Log.d(tag, "ChÆ°a tháº¥y ThÃ nh cÃ´ng")
            scheduleRetry(); return
        }
        successNodes.forEach { it.recycle() }

        Log.d(tag, "\u2713 Th\u00e0nh c\u00f4ng MKH: ${mkhList[currentIndex]}")
        broadcastStatus(currentIndex + 1, mkhList.size, mkhList[currentIndex],
            "\u2713 Th\u00e0nh c\u00f4ng: ${mkhList[currentIndex]}")

        retryCount = 0
        state      = PaymentState.CLICK_NEW_TX
        handler.postDelayed(pollRunnable, 1_000L)
    }

    // â”€â”€ BÆ°á»›c 8: Báº¥m "Giao dá»‹ch má»›i" â†’ chuyá»ƒn sang MKH tiáº¿p theo â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
    private fun handleNewTransaction(root: AccessibilityNodeInfo) {
        if (clickByText(root, AppConfig.TEXT_BTN_NEW_TX)) {
            currentIndex++
            prefs.edit().putInt(AppConfig.KEY_CURRENT_INDEX, currentIndex).apply()
            retryCount   = 0
            pinCharIndex = 0

            Log.d(tag, "Giao dá»‹ch má»›i. Next: $currentIndex / ${mkhList.size}")

            if (currentIndex >= mkhList.size) {
                state = PaymentState.DONE
                prefs.edit()
                    .putBoolean(AppConfig.KEY_IS_RUNNING, false)
                    .putInt(AppConfig.KEY_CURRENT_INDEX, 0)
                    .apply()
                broadcastStatus(currentIndex, mkhList.size, "", "\u2705 Ho\u00e0n t\u1ea5t ${mkhList.size} MKH!")
            } else {
                state = PaymentState.SELECT_ELEC_TYPE
                handler.postDelayed(pollRunnable, AppConfig.DELAY_AFTER_CLICK_MS)
            }
        } else {
            scheduleRetry()
        }
    }

    // â”€â”€ Retry logic â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

    private fun scheduleRetry() {
        retryCount++
        Log.d(tag, "Retry $retryCount / ${AppConfig.MAX_RETRY} [state=$state]")

        if (retryCount > AppConfig.MAX_RETRY) {
            val skipped = if (currentIndex < mkhList.size) mkhList[currentIndex] else "?"
            Log.w(tag, "Bá» qua MKH: $skipped")
            broadcastStatus(currentIndex + 1, mkhList.size, skipped,
                "\u26a0 B\u1ecf qua: $skipped (h\u1ebft l\u1ea7n th\u1eed t\u1ea1i $state)")

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
                broadcastStatus(currentIndex, mkhList.size, "", "Hoàn tất (có lỗi)")
            } else {
                state = PaymentState.SELECT_ELEC_TYPE
                handler.postDelayed(pollRunnable, AppConfig.DELAY_AFTER_CLICK_MS)
            }
        } else {
            handler.postDelayed(pollRunnable, AppConfig.POLL_INTERVAL_MS)
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

        // Æ¯u tiÃªn node clickable, fallback vá» node Ä‘áº§u tiÃªn
        val target = nodes.firstOrNull { it.isClickable }
            ?: findClickableParent(nodes[0])
            ?: nodes[0]

        val success = target.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        nodes.forEach { it.recycle() }
        return success
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

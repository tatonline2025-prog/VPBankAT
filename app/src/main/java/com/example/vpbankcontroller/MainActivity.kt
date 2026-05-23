package com.example.vpbat

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.util.Xml
import android.widget.Button
import android.widget.EditText
import android.widget.ProgressBar
import android.widget.ScrollView
import androidx.appcompat.widget.SwitchCompat
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.widget.doAfterTextChanged
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import org.json.JSONArray
import org.xmlpull.v1.XmlPullParser
import java.io.File
import java.text.Normalizer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipInputStream

class MainActivity : AppCompatActivity() {

    private lateinit var etMkhList: EditText
    private lateinit var etVpbankPackage: EditText
    private lateinit var etPin: EditText
    private lateinit var etSmartOtpPin: EditText
    private lateinit var switchStickCredentials: SwitchCompat
    private lateinit var btnStart: Button
    private lateinit var btnStop: Button
    private lateinit var btnOpenApp: Button
    private lateinit var btnAutoAll: Button
    private lateinit var btnImportFile: Button
    private lateinit var tvSecurityStatus: TextView
    private lateinit var tvEventLog: TextView
    private lateinit var tvStatus: TextView
    private lateinit var progressBar: ProgressBar
    private lateinit var tvProgress: TextView

    private lateinit var prefs: SharedPreferences
    private var pendingAutoAllResume = false
    private var isActivityVisible = false
    private var isProgrammaticPackageUpdate = false
    private val eventLogLines = mutableListOf<String>()
    private var latestAutomationLogPreview = ""
    private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
    private val retryHandler = Handler(Looper.getMainLooper())

    private data class VpbankCandidate(
        val score: Int,
        val packageName: String,
        val label: String
    )

    // ── File picker (TXT / CSV) ──────────────────────────────────────────────────
    private val filePickerLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri != null) {
            importFileFromUri(uri)
            if (pendingAutoAllResume) {
                runAutoAllFlow(fromAutoResume = true)
            }
        } else {
            pendingAutoAllResume = false
        }
    }

    private val progressReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val current = intent.getIntExtra(AppConfig.EXTRA_CURRENT, 0)
            val total   = intent.getIntExtra(AppConfig.EXTRA_TOTAL, 0)
            val status  = intent.getStringExtra(AppConfig.EXTRA_STATUS) ?: ""

            tvStatus.text        = status
            tvProgress.text      = "$current / $total"
            progressBar.max      = if (total > 0) total else 1
            progressBar.progress = current
            addEventLog("Progress: $status ($current/$total)")
            loadLatestAutomationLogPreview()

            val finished = status.startsWith("\u2705") ||
                status.startsWith("\u0110\u00e3 d\u1eebng") ||
                status.startsWith("Ho\u00e0n t\u1ea5t")
            btnStart.isEnabled = finished
            btnStop.isEnabled  = !finished
        }
    }

    private val debugLogReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val line = intent.getStringExtra(AppConfig.EXTRA_LOG_LINE)?.trim().orEmpty()
            if (line.isBlank()) return

            latestAutomationLogPreview = buildString {
                append("AUTO LOG REALTIME")
                append("\n")
                append(line)
            }
            refreshEventLogPanel()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        prefs = getSharedPreferences(AppConfig.PREFS_NAME, Context.MODE_PRIVATE)

        etMkhList        = findViewById(R.id.et_mkh_list)
        etVpbankPackage  = findViewById(R.id.et_vpbank_package)
        etPin            = findViewById(R.id.et_pin)
        etSmartOtpPin    = findViewById(R.id.et_smart_otp_pin)
        switchStickCredentials = findViewById(R.id.switch_stick_credentials)
        btnStart         = findViewById(R.id.btn_start)
        btnStop          = findViewById(R.id.btn_stop)
        btnOpenApp       = findViewById(R.id.btn_open_app)
        btnAutoAll       = findViewById(R.id.btn_auto_all)
        btnImportFile    = findViewById(R.id.btn_import_file)
        tvSecurityStatus = findViewById(R.id.tv_security_status)
        tvEventLog       = findViewById(R.id.tv_event_log)
        tvStatus         = findViewById(R.id.tv_status)
        progressBar      = findViewById(R.id.progress_bar)
        tvProgress       = findViewById(R.id.tv_progress)

        addEventLog("App opened")

        etVpbankPackage.doAfterTextChanged { editable ->
            if (isProgrammaticPackageUpdate) return@doAfterTextChanged
            val pkg = editable?.toString()?.trim().orEmpty()
            if (pkg.isNotEmpty()) {
                persistVpbankPackage(pkg, "manual-edit")
            }
        }

        switchStickCredentials.isChecked =
            prefs.getBoolean(AppConfig.KEY_STICK_CREDENTIALS, true)

        val defaultPin = "222333"
        val defaultSmartOtp = "222222"
        val savedPin = prefs.getString(AppConfig.KEY_PIN_VALUE, null)
        val savedSmartOtp = prefs.getString(AppConfig.KEY_SMART_OTP_VALUE, null)

        etPin.setText(if (savedPin.isNullOrBlank()) defaultPin else savedPin)
        etSmartOtpPin.setText(if (savedSmartOtp.isNullOrBlank()) defaultSmartOtp else savedSmartOtp)

        if (savedPin.isNullOrBlank() || savedSmartOtp.isNullOrBlank()) {
            saveCredentialDraft()
        }

        switchStickCredentials.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean(AppConfig.KEY_STICK_CREDENTIALS, isChecked).apply()
            if (isChecked) {
                saveCredentialDraft()
                addEventLog("Stick ON: saved PIN + Smart OTP")
            } else {
                addEventLog("Stick OFF")
            }
        }

        etPin.doAfterTextChanged {
            if (switchStickCredentials.isChecked) {
                saveCredentialDraft()
            }
        }

        etSmartOtpPin.doAfterTextChanged {
            if (switchStickCredentials.isChecked) {
                saveCredentialDraft()
            }
        }

        etVpbankPackage.setText(
            prefs.getString(AppConfig.KEY_VPBANK_PACKAGE, AppConfig.vpbankPackage)
        )
        restoreSavedMkhDraft()

        // If stored package is missing or no longer installed, auto-detect VPBank app.
        val initialPkg = etVpbankPackage.text.toString().trim()
        val isSelfPackage = initialPkg == packageName ||
            initialPkg.contains("vpbankcontroller") ||
            initialPkg.contains("vpbat")
        if (initialPkg.isEmpty() || !isPackageInstalled(initialPkg) || isSelfPackage) {
            detectAndFillVpbankPackage(showToast = false)
        }

        btnStop.isEnabled = false
        updateSecurityStatusBadge()

        // Start automation.
        btnStart.setOnClickListener {
            startAutomationFromUi()
        }

        // Stop automation.
        btnStop.setOnClickListener {
            AppConfig.smartOtpPin = ""
            pendingAutoAllResume = false
            prefs.edit()
                .putBoolean(AppConfig.KEY_IS_RUNNING, false)
                .putInt(AppConfig.KEY_CURRENT_INDEX, 0)
                .apply()
            LocalBroadcastManager.getInstance(this)
                .sendBroadcast(Intent(AppConfig.ACTION_STOP))
            btnStart.isEnabled = true
            btnStop.isEnabled  = false
            progressBar.progress = 0
            tvProgress.text = "0 / 0"
            setMainStatus("\u0110\u00e3 d\u1eebng - reset về bước 1")
        }

        // Scan and open VPBank app with visible status updates.
        btnOpenApp.setOnClickListener {
            setMainStatus("\u0110ang scan app VPBank tr\u00ean m\u00e1y\u2026")
            val packageToOpen = resolveVpbankPackageForLaunch(forceScan = true) ?: return@setOnClickListener
            launchApp(packageToOpen, fromAction = "Open VPBank")
        }

        // One-click flow: detect VPBank package, validate inputs, open app, then start.
        btnAutoAll.setOnClickListener {
            pendingAutoAllResume = true
            runAutoAllFlow(fromAutoResume = false)
        }

        // ── Import file TXT / CSV ─────────────────────────────────────────────
        btnImportFile.setOnClickListener {
            filePickerLauncher.launch("*/*")
        }
    }

    override fun onResume() {
        super.onResume()
        isActivityVisible = true
        updateSecurityStatusBadge()
        loadLatestAutomationLogPreview()

        if (pendingAutoAllResume) {
            addEventLog("Resume Auto All after returning to app")
            runAutoAllFlow(fromAutoResume = true)
        }

        LocalBroadcastManager.getInstance(this).registerReceiver(
            progressReceiver,
            IntentFilter(AppConfig.ACTION_PROGRESS)
        )
        LocalBroadcastManager.getInstance(this).registerReceiver(
            debugLogReceiver,
            IntentFilter(AppConfig.ACTION_DEBUG_LOG)
        )
    }

    override fun onPause() {
        super.onPause()
        isActivityVisible = false
        LocalBroadcastManager.getInstance(this).unregisterReceiver(progressReceiver)
        LocalBroadcastManager.getInstance(this).unregisterReceiver(debugLogReceiver)
    }

    override fun onDestroy() {
        super.onDestroy()
        // Xoa PIN khoi memory khi app dong.
        AppConfig.smartOtpPin = ""
    }

    // â”€â”€ Helpers â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

    private fun launchApp(packageName: String, fromAction: String = "Launch") {
        launchAppWithRetry(packageName, fromAction, attempt = 0)
    }

    private fun launchAppWithRetry(packageName: String, fromAction: String, attempt: Int) {
        val launch = packageManager.getLaunchIntentForPackage(packageName)
            ?: packageManager.queryIntentActivities(
                Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER),
                0
            ).firstOrNull { it.activityInfo?.packageName == packageName }
                ?.let { info ->
                    Intent(Intent.ACTION_MAIN).apply {
                        addCategory(Intent.CATEGORY_LAUNCHER)
                        setClassName(packageName, info.activityInfo.name)
                    }
                }

        if (launch != null) {
            launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            launch.addFlags(Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
            launch.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
            startActivity(launch)
            setMainStatus("\u0110ang m\u1edf app: $packageName")
            addEventLog("$fromAction -> launch request sent to $packageName (attempt ${attempt + 1})")

            if (attempt < 2) {
                retryHandler.postDelayed({
                    if (isActivityVisible) {
                        addEventLog("Foreground ch\u01b0a \u0111\u1ed5i sau 1.2s, th\u1eed m\u1edf l\u1ea1i")
                        launchAppWithRetry(packageName, fromAction, attempt + 1)
                    }
                }, 1200L)
            } else {
                retryHandler.postDelayed({
                    if (isActivityVisible) {
                        setMainStatus("\u0110\u00e3 g\u1eedi l\u1ec7nh m\u1edf 3 l\u1ea7n, ch\u01b0a r\u1eddi m\u00e0n h\u00ecnh hi\u1ec7n t\u1ea1i")
                    }
                }, 1200L)
            }
        } else {
            val msg = "Kh\u00f4ng t\u00ecm th\u1ea5y app: $packageName"
            Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
            setMainStatus(msg)
            addEventLog("$fromAction -> launch intent null for $packageName")
        }
    }

    private fun startAutomationFromUi() {
        if (!ensureAccessibilityEnabled()) return
        if (!ensureBankOpenPreconditions()) return

        val pin = etSmartOtpPin.text.toString().trim()
        if (pin.length != 6 || !pin.all { it.isDigit() }) {
            Toast.makeText(this, "PIN Smart OTP ph\u1ea3i \u0111\u00fang 6 ch\u1eef s\u1ed1", Toast.LENGTH_SHORT).show()
            setMainStatus("PIN Smart OTP ch\u01b0a h\u1ee3p l\u1ec7")
            etSmartOtpPin.requestFocus()
            return
        }

        val mkhRaw = etMkhList.text.toString().trim()
        if (mkhRaw.isEmpty()) {
            Toast.makeText(this, "Nh\u1eadp danh s\u00e1ch MKH", Toast.LENGTH_SHORT).show()
            setMainStatus("Ch\u01b0a c\u00f3 danh s\u00e1ch MKH")
            etMkhList.requestFocus()
            return
        }

        val vpbankPkg = resolveVpbankPackageForLaunch() ?: return
        val mkhLines = mkhRaw.lines().map { it.trim() }.filter { it.isNotEmpty() }
        val jsonArr = JSONArray(mkhLines)
        val startIndex = resolveResumeStartIndex(mkhLines)

        AppConfig.smartOtpPin = pin
        AppConfig.vpbankPackage = vpbankPkg

        prefs.edit()
            .putString(AppConfig.KEY_MKH_DRAFT, mkhRaw)
            .putString(AppConfig.KEY_MKH_LIST, jsonArr.toString())
            .putInt(AppConfig.KEY_CURRENT_INDEX, startIndex)
            .putBoolean(AppConfig.KEY_IS_RUNNING, true)
            .putString(AppConfig.KEY_VPBANK_PACKAGE, vpbankPkg)
            .apply()

        btnStart.isEnabled = false
        btnStop.isEnabled = true
        setMainStatus(if (startIndex > 0) {
            "\u0110ang kh\u1edfi \u0111\u1ed9ng\u2026 (resume ${startIndex + 1}/${mkhLines.size})"
        } else {
            "\u0110ang kh\u1edfi \u0111\u1ed9ng\u2026"
        })
        addEventLog("Nh\u1eafc nh\u1edf: Kh\u00f4ng t\u1eaft m\u00e0n h\u00ecnh trong khi Auto \u0111ang ch\u1ea1y")
        setMainStatus("\u26a0 Kh\u00f4ng t\u1eaft m\u00e0n h\u00ecnh trong khi Auto \u0111ang ch\u1ea1y!")

        launchApp(vpbankPkg, fromAction = "Start automation")
        LocalBroadcastManager.getInstance(this)
            .sendBroadcast(Intent(AppConfig.ACTION_START))
        pendingAutoAllResume = false
    }

    private fun runAutoAllFlow(fromAutoResume: Boolean) {
        addEventLog("Auto All running${if (fromAutoResume) " (resume)" else ""}")
        if (!ensureAccessibilityEnabled()) {
            if (fromAutoResume) {
                setMainStatus("\u0110ang ch\u1edd b\u1eadt Accessibility Service\u2026")
            }
            return
        }

        if (etMkhList.text.toString().trim().isEmpty()) {
            if (!fromAutoResume) {
                Toast.makeText(
                    this,
                    "Ch\u01b0a c\u00f3 danh s\u00e1ch MKH. H\u00e3y ch\u1ecdn file TXT/CSV ho\u1eb7c d\u00e1n danh s\u00e1ch.",
                    Toast.LENGTH_LONG
                ).show()
                setMainStatus("\u0110ang ch\u1edd b\u1ea1n ch\u1ecdn file MKH")
                filePickerLauncher.launch("*/*")
            }
            return
        }

        startAutomationFromUi()
    }

    private fun ensureAccessibilityEnabled(): Boolean {
        // Service đã thực sự connect (onServiceConnected đã chạy) → OK
        if (BillPaymentAccessibilityService.isConnected) return true

        // Service có trong settings nhưng chưa bind (MIUI chưa gọi onServiceConnected)
        if (isAccessibilityServiceEnabled()) {
            Toast.makeText(
                this,
                "Dịch vụ Trợ năng đã bật và đang kết nối. Chờ 1-2 giây rồi bấm Bắt đầu lại.",
                Toast.LENGTH_LONG
            ).show()
            addEventLog("Accessibility in settings but not connected")
            setMainStatus("Trợ năng đang kết nối - vui lòng thử lại sau 1-2 giây")
            return false
        }

        Toast.makeText(
            this,
            "Chưa bật Trợ năng. Vào Cài đặt > Trợ năng > VPBat > Bật dịch vụ.",
            Toast.LENGTH_LONG
        ).show()
        addEventLog("Accessibility not enabled")
        setMainStatus("Thiếu quyền Trợ năng - đang mở Cài đặt")
        startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        return false
    }

    private fun ensureBankOpenPreconditions(): Boolean {
        val usbDebugOn = isUsbDebugEnabled()
        val developerOn = isDeveloperOptionsEnabled()
        if (!usbDebugOn && !developerOn) return true

        val enabledFlags = mutableListOf<String>()
        if (developerOn) enabledFlags.add("Developer options")
        if (usbDebugOn) enabledFlags.add("USB Debugging")

        AlertDialog.Builder(this)
            .setTitle("Y\u00eau c\u1ea7u tr\u01b0\u1edbc khi m\u1edf VPBank")
            .setMessage(
                "VPBank c\u00f3 th\u1ec3 kh\u00f4ng cho m\u1edf khi \u0111ang b\u1eadt: ${enabledFlags.joinToString(", ")}.\n\n" +
                    "L\u00e0m theo th\u1ee9 t\u1ef1:\n" +
                    "1) B\u1ea5m 'M\u1edf c\u00e0i \u0111\u1eb7t'.\n" +
                    "2) Trong T\u00f9y ch\u1ecdn nh\u00e0 ph\u00e1t tri\u1ec3n: t\u1eaft USB Debugging.\n" +
                    "3) T\u1eaft Developer options (n\u1ebfu VPBank v\u1eabn ch\u1eb7n).\n" +
                    "4) Quay l\u1ea1i app n\u00e0y, b\u1ea5m Auto All \u0111\u1ec3 ti\u1ebfp t\u1ee5c."
            )
            .setPositiveButton("M\u1edf c\u00e0i \u0111\u1eb7t") { _, _ ->
                openDeveloperSettings()
            }
            .setNegativeButton("\u0110\u00f3ng", null)
            .show()

        setMainStatus("Ch\u1edd t\u1eaft Developer options/USB Debugging\u2026")
        addEventLog("Security blocked: ${enabledFlags.joinToString(", ")}")

        return false
    }

    private fun openDeveloperSettings() {
        val devIntent = Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)
        try {
            startActivity(devIntent)
            addEventLog("Opened developer settings")
        } catch (_: Exception) {
            startActivity(Intent(Settings.ACTION_SETTINGS))
            addEventLog("Fallback opened system settings")
        }
    }

    private fun isUsbDebugEnabled(): Boolean {
        return runCatching {
            Settings.Global.getInt(contentResolver, Settings.Global.ADB_ENABLED, 0) == 1
        }.getOrDefault(false)
    }

    private fun isDeveloperOptionsEnabled(): Boolean {
        return runCatching {
            Settings.Global.getInt(contentResolver, Settings.Global.DEVELOPMENT_SETTINGS_ENABLED, 0) == 1
        }.getOrDefault(false)
    }

    private fun resolveResumeStartIndex(currentMkhLines: List<String>): Int {
        if (currentMkhLines.isEmpty()) return 0

        val previousRaw = prefs.getString(AppConfig.KEY_MKH_LIST, "[]") ?: "[]"
        val previousList = parseJsonArrayToList(previousRaw)
        val savedIndex = prefs.getInt(AppConfig.KEY_CURRENT_INDEX, 0)

        val canResume = previousList == currentMkhLines && savedIndex in 1 until currentMkhLines.size
        return if (canResume) savedIndex else 0
    }

    private fun parseJsonArrayToList(json: String): List<String> {
        return runCatching {
            val arr = JSONArray(json)
            (0 until arr.length()).map { arr.getString(it) }
        }.getOrDefault(emptyList())
    }

    private fun restoreSavedMkhDraft() {
        val draft = prefs.getString(AppConfig.KEY_MKH_DRAFT, "")?.trim().orEmpty()
        if (draft.isNotEmpty() && etMkhList.text.toString().trim().isEmpty()) {
            etMkhList.setText(draft)
            addEventLog("Restored saved MKH draft")
        }
    }

    private fun saveCredentialDraft() {
        prefs.edit()
            .putString(AppConfig.KEY_PIN_VALUE, etPin.text.toString().trim())
            .putString(AppConfig.KEY_SMART_OTP_VALUE, etSmartOtpPin.text.toString().trim())
            .apply()
    }

    private fun updateSecurityStatusBadge() {
        val usbDebugOn = isUsbDebugEnabled()
        val developerOn = isDeveloperOptionsEnabled()
        if (!usbDebugOn && !developerOn) {
            tvSecurityStatus.text = "Security: Dev OFF, USB Debug OFF \u2705"
            tvSecurityStatus.setTextColor(Color.parseColor("#2E7D32"))
            return
        }

        val parts = mutableListOf<String>()
        parts.add("Dev " + if (developerOn) "ON" else "OFF")
        parts.add("USB Debug " + if (usbDebugOn) "ON" else "OFF")
        tvSecurityStatus.text = "Security: ${parts.joinToString(", ")} \u26a0"
        tvSecurityStatus.setTextColor(Color.parseColor("#C62828"))
    }

    private fun resolveVpbankPackageForLaunch(forceScan: Boolean = false): String? {
        if (forceScan) {
            val scanned = detectAndFillVpbankPackage(showToast = true)
            if (scanned != null) return scanned
        }

        val typedPackage = etVpbankPackage.text.toString().trim()
        if (typedPackage.isNotEmpty() && isPackageInstalled(typedPackage)) {
            persistVpbankPackage(typedPackage, "typed-valid")
            addEventLog("Using package from input: $typedPackage")
            return typedPackage
        }

        val detected = detectAndFillVpbankPackage(showToast = true)
        if (detected == null) {
            Toast.makeText(
                this,
                "Kh\u00f4ng t\u1ef1 detect \u0111\u01b0\u1ee3c app VPBank. Vui l\u00f2ng nh\u1eadp package app th\u1ee7 c\u00f4ng.",
                Toast.LENGTH_LONG
            ).show()
            setMainStatus("Scan VPBank th\u1ea5t b\u1ea1i")
            addEventLog("No VPBank candidate found")
        }
        return detected
    }

    private fun detectAndFillVpbankPackage(showToast: Boolean): String? {
        val candidates = detectVpbankCandidates()
        val best = candidates.maxByOrNull { it.score }
        val detected = best?.packageName

        if (candidates.isNotEmpty()) {
            val preview = candidates
                .sortedByDescending { it.score }
                .take(3)
                .joinToString(" | ") { "${it.label} (${it.packageName})" }
            addEventLog("Scan candidates: $preview")
        }

        if (detected != null) {
            isProgrammaticPackageUpdate = true
            etVpbankPackage.setText(detected)
            isProgrammaticPackageUpdate = false
            persistVpbankPackage(detected, "auto-detect")
            setMainStatus("Detect VPBank: ${best?.label ?: "Unknown"} -> $detected")
            if (showToast) {
                Toast.makeText(this, "\u0110\u00e3 t\u1ef1 detect app VPBank: $detected", Toast.LENGTH_LONG).show()
            }
            addEventLog("Detected + saved VPBank package: $detected")
        } else {
            setMainStatus("Kh\u00f4ng t\u00ecm th\u1ea5y app VPBank tr\u00ean launcher")
        }
        return detected
    }

    private fun persistVpbankPackage(pkg: String, source: String) {
        AppConfig.vpbankPackage = pkg
        prefs.edit().putString(AppConfig.KEY_VPBANK_PACKAGE, pkg).commit()
        addEventLog("Saved package [$source]: $pkg")
    }

    private fun detectVpbankCandidates(): List<VpbankCandidate> {
        val launcherIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val launchableApps = packageManager.queryIntentActivities(launcherIntent, 0)

        val selfPackage = this.packageName
        val byPackage = linkedMapOf<String, VpbankCandidate>()

        launchableApps.orEmpty().forEach { info ->
            val packageName = info.activityInfo?.packageName ?: return@forEach
            if (packageName == selfPackage ||
                packageName.contains("vpbankcontroller") ||
                packageName.contains("vpbat")) return@forEach
            val appLabel = info.loadLabel(packageManager)?.toString().orEmpty()
            val score = scoreVpbankCandidate(packageName, appLabel)
            if (score <= 0) return@forEach
            byPackage[packageName] = VpbankCandidate(score, packageName, appLabel)
        }

        // Fallback: include installed packages that can be launched but may not appear in launcher query.
        packageManager.getInstalledPackages(0).forEach { pkgInfo ->
            val packageName = pkgInfo.packageName
            if (packageName == selfPackage ||
                packageName.contains("vpbankcontroller") ||
                packageName.contains("vpbat")) return@forEach
            if (packageManager.getLaunchIntentForPackage(packageName) == null) return@forEach

            val appLabel = pkgInfo.applicationInfo?.loadLabel(packageManager)?.toString().orEmpty()
            val score = scoreVpbankCandidate(packageName, appLabel)
            if (score <= 0) return@forEach

            val existing = byPackage[packageName]
            if (existing == null || score > existing.score) {
                byPackage[packageName] = VpbankCandidate(score, packageName, appLabel)
            }
        }

        val candidates = byPackage.values.toList()

        candidates.forEach {
            Log.d("VpbankDetect", "Candidate score=${it.score}, pkg=${it.packageName}, label=${it.label}")
        }
        return candidates
    }

    private fun scoreVpbankCandidate(packageName: String, label: String): Int {
        val pkg = packageName.lowercase(Locale.ROOT)
        val app = label.lowercase(Locale.ROOT)
        val appNormalized = normalizeForMatch(label)

        var score = 0
        if (pkg == "com.vnpay.vpbankonline") score += 200
        if (pkg.contains("vpbank")) score += 120
        if (app.contains("vpbank")) score += 100
        if (appNormalized.contains("vpbank")) score += 100
        if (app.contains("vp bank")) score += 40
        if (appNormalized.contains("vp bank")) score += 40
        if (app.contains("ngan hang viet nam thinh vuong")) score += 35
        if (appNormalized.contains("ngan hang viet nam thinh vuong")) score += 45
        if (app.contains("neo")) score += 30
        if (appNormalized.contains("neo")) score += 30
        if (pkg.contains("vnpay")) score += 10
        if (app.contains("bank")) score += 5
        if (appNormalized.contains("bank")) score += 5
        return score
    }

    private fun normalizeForMatch(value: String): String {
        val lower = value.lowercase(Locale.ROOT)
        val decomposed = Normalizer.normalize(lower, Normalizer.Form.NFD)
        return decomposed
            .replace("đ", "d")
            .replace("\\p{M}+".toRegex(), "")
            .replace("[^a-z0-9 ]".toRegex(), " ")
            .replace("\\s+".toRegex(), " ")
            .trim()
    }

    private fun isPackageInstalled(packageName: String): Boolean {
        if (packageName.isBlank()) return false
        return try {
            packageManager.getPackageInfo(packageName, 0)
            true
        } catch (_: Exception) {
            false
        }
    }

    // ── Import file TXT / CSV / XLSX helpers ───────────────────────────────────
    private fun importFileFromUri(uri: Uri) {
        try {
            val mimeType = contentResolver.getType(uri) ?: ""
            val fileName = uri.lastPathSegment ?: ""
            val isXlsx   = mimeType.contains("spreadsheet") ||
                           mimeType.contains("excel") ||
                           fileName.endsWith(".xlsx", ignoreCase = true) ||
                           fileName.endsWith(".xls",  ignoreCase = true)

            val rawLines: List<String> = if (isXlsx) {
                parseXlsx(uri)
            } else {
                val text = contentResolver.openInputStream(uri)?.bufferedReader()?.readText()
                    ?: run {
                        Toast.makeText(this, "Kh\u00f4ng \u0111\u1ecdc \u0111\u01b0\u1ee3c file", Toast.LENGTH_SHORT).show()
                        return
                    }
                parseMkhFromText(text)
            }

            // Lọc chỉ lấy MKH bắt đầu bằng PB hoặc PE
            val pbList  = rawLines.filter { it.uppercase().startsWith("PB") }
            val peList  = rawLines.filter { it.uppercase().startsWith("PE") }
            val unknown = rawLines.filter {
                !it.uppercase().startsWith("PB") && !it.uppercase().startsWith("PE")
            }
            // Nhóm PB trước, PE sau để giảm số lần chuyển màn hình loại điện
            val lines = pbList + peList

            if (lines.isEmpty()) {
                Toast.makeText(
                    this,
                    "Kh\u00f4ng t\u00ecm th\u1ea5y MKH h\u1ee3p l\u1ec7 (PB.../PE...) trong file",
                    Toast.LENGTH_LONG
                ).show()
                return
            }

            etMkhList.setText(lines.joinToString("\n"))
            prefs.edit().putString(AppConfig.KEY_MKH_DRAFT, lines.joinToString("\n")).apply()
            val msg = "\u0110\u00e3 import ${lines.size} MKH " +
                      "(PB: ${pbList.size}, PE: ${peList.size}" +
                      if (unknown.isNotEmpty()) ", b\u1ecf qua ${unknown.size} d\u00f2ng l\u1ea1)" else ")"
            Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
            setMainStatus("$msg")
            addEventLog("Imported MKH from file")

        } catch (e: Exception) {
            Toast.makeText(this, "L\u1ed7i \u0111\u1ecdc file: ${e.message}", Toast.LENGTH_LONG).show()
            Log.e("Import", "importFileFromUri error", e)
            setMainStatus("L\u1ed7i import file")
            addEventLog("Import error: ${e.message}")
        }
    }

    private fun setMainStatus(message: String) {
        tvStatus.text = message
        addEventLog(message)
    }

    private fun addEventLog(message: String) {
        val line = "${timeFormat.format(Date())}  $message"
        eventLogLines.add(line)
        while (eventLogLines.size > 25) {
            eventLogLines.removeAt(0)
        }
        refreshEventLogPanel()
    }

    private fun loadLatestAutomationLogPreview() {
        val baseDir = getExternalFilesDir(null) ?: filesDir
        val logDir = File(baseDir, "logs")
        val latestLog = logDir.listFiles()
            ?.filter { it.isFile && it.name.startsWith("vpbat_") && it.extension.equals("log", ignoreCase = true) }
            ?.maxByOrNull { it.lastModified() }

        latestAutomationLogPreview = if (latestLog == null) {
            "AUTO LOG: chưa có file log"
        } else {
            val tail = runCatching { latestLog.readLines().takeLast(12) }.getOrDefault(emptyList())
            buildString {
                append("AUTO LOG: ${latestLog.name}")
                if (tail.isNotEmpty()) {
                    append("\n")
                    append(tail.joinToString("\n"))
                }
            }
        }
        refreshEventLogPanel()
    }

    private fun refreshEventLogPanel() {
        val sections = mutableListOf<String>()
        if (eventLogLines.isNotEmpty()) {
            sections.add(eventLogLines.joinToString("\n"))
        }
        if (latestAutomationLogPreview.isNotBlank()) {
            sections.add(latestAutomationLogPreview)
        }
        tvEventLog.text = sections.joinToString("\n\n")
    }

    /**
     * Parse TXT / CSV: mỗi dòng một MKH hoặc lấy cột đầu tiên nếu CSV.
     */
    private fun parseMkhFromText(text: String): List<String> {
        return text.lines()
            .map { line -> line.split(',', ';').first().trim().trim('"') }
            .filter { it.isNotEmpty() && it.any { ch -> ch.isLetterOrDigit() } }
    }

    /**
     * Parse XLSX (Office Open XML) không cần thư viện ngoài.
     * Đọc cột A của sheet1 → danh sách MKH.
     */
    private fun parseXlsx(uri: Uri): List<String> {
        val sharedStrings = mutableListOf<String>()
        val columnAValues = mutableListOf<String>()

        val entries = mutableMapOf<String, ByteArray>()
        contentResolver.openInputStream(uri)?.use { inputStream ->
            ZipInputStream(inputStream).use { zis ->
                var entry = zis.nextEntry
                while (entry != null) {
                    if (entry.name == "xl/sharedStrings.xml" ||
                        entry.name == "xl/worksheets/sheet1.xml") {
                        entries[entry.name] = zis.readBytes()
                    }
                    entry = zis.nextEntry
                }
            }
        }

        // Parse sharedStrings.xml
        entries["xl/sharedStrings.xml"]?.let { bytes ->
            val parser = Xml.newPullParser()
            parser.setInput(bytes.inputStream(), "UTF-8")
            var inT = false
            var event = parser.eventType
            while (event != XmlPullParser.END_DOCUMENT) {
                when (event) {
                    XmlPullParser.START_TAG -> inT = parser.name == "t"
                    XmlPullParser.TEXT      -> if (inT) sharedStrings.add(parser.text ?: "")
                    XmlPullParser.END_TAG   -> if (parser.name == "t") inT = false
                }
                event = parser.next()
            }
        }

        // Parse sheet1.xml — lấy cột A
        entries["xl/worksheets/sheet1.xml"]?.let { bytes ->
            val parser = Xml.newPullParser()
            parser.setInput(bytes.inputStream(), "UTF-8")
            var isColA    = false
            var cellType  = ""
            var inV       = false
            var event = parser.eventType
            while (event != XmlPullParser.END_DOCUMENT) {
                when (event) {
                    XmlPullParser.START_TAG -> when (parser.name) {
                        "c" -> {
                            val ref = parser.getAttributeValue(null, "r") ?: ""
                            isColA   = ref.takeWhile { it.isLetter() } == "A"
                            cellType = parser.getAttributeValue(null, "t") ?: ""
                            inV      = false
                        }
                        "v" -> if (isColA) inV = true
                    }
                    XmlPullParser.TEXT -> if (inV && isColA) {
                        val raw = parser.text ?: ""
                        val value = if (cellType == "s") {
                            sharedStrings.getOrElse(raw.toIntOrNull() ?: -1) { "" }
                        } else raw
                        if (value.isNotEmpty()) columnAValues.add(value.trim())
                    }
                    XmlPullParser.END_TAG -> when (parser.name) {
                        "c" -> { isColA = false; cellType = ""; inV = false }
                        "v" -> inV = false
                    }
                }
                event = parser.next()
            }
        }

        return columnAValues
    }

    private fun isAccessibilityServiceEnabled(): Boolean {
        // MIUI/HyperOS keeps ACCESSIBILITY_ENABLED = 0 even for enabled third-party services.
        // Only check the service list, not the global flag.
        val expected = ComponentName(this, BillPaymentAccessibilityService::class.java)
        val expectedClass = BillPaymentAccessibilityService::class.java.name
        val expectedShortClass = ".${BillPaymentAccessibilityService::class.java.simpleName}"

        val enabled = Settings.Secure.getString(
            contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false

        return enabled
            .split(":")
            .asSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .any { raw ->
                val parsed = ComponentName.unflattenFromString(raw)
                if (parsed != null) {
                    val samePackage = parsed.packageName.equals(expected.packageName, ignoreCase = true)
                    val sameClass = parsed.className.equals(expected.className, ignoreCase = true) ||
                        parsed.className.equals(expectedClass, ignoreCase = true) ||
                        parsed.className.equals("${expected.packageName}$expectedShortClass", ignoreCase = true)
                    samePackage && sameClass
                } else {
                    // Fallback for malformed OEM strings that cannot be parsed by ComponentName.
                    raw.equals("${expected.packageName}/$expectedClass", ignoreCase = true) ||
                        raw.equals("${expected.packageName}/$expectedShortClass", ignoreCase = true)
                }
            }
    }
}

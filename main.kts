import java.io.File
import java.security.MessageDigest
import java.time.LocalDateTime
import java.util.Properties

// === ANSI Colors ===
val R = "\u001B[0m" // Reset
val RED = "\u001B[31m"
val GRN = "\u001B[32m"
val YEL = "\u001B[33m"
val BLU = "\u001B[34m"
val MAG = "\u001B[35m"
val CYN = "\u001B[36m"
val WHT = "\u001B[37m"
val BOLD = "\u001B[1m"
val DIM = "\u001B[2m"

data class RootResult(
        val success: Boolean,
        val exitCode: Int,
        val output: String,
        val error: String
)

data class StatusReport(
        val packageName: String,
        val status: String,
        val pid: String,
        val focused: Boolean,
        val uiHit: Boolean,
        val crashHit: Boolean,
        val fileLogHit: Boolean,
        val sessionCookie: Boolean,
        val userId: String,
        val gameId: String,
        val window: String,
        val activity: String,
        val username: String = "",
        val presenceType: Int = -1,
        val inGameSession: Boolean = false
)

val configFile = File("rejoin-config.properties")
val tabFiles = listOf("output.txt", "tabs.txt", "delta-tabs.txt")

// V7: Avoid 'by lazy' lambda (yGuard renames synthetic method -> LambdaConversionException)
fun detectSqlite3(): String {
    val paths =
            listOf(
                    "/system/bin/sqlite3",
                    "/system/xbin/sqlite3",
                    "/data/data/com.termux/files/usr/bin/sqlite3",
                    "/usr/bin/sqlite3",
                    "/vendor/bin/sqlite3"
            )
    for (p in paths) {
        val result = root("test -f $p && echo OK")
        if (result.output.trim() == "OK") {
            println("${GRN}[OK] sqlite3 found: $p${R}")
            return p
        }
    }
    val whichResult = root("which sqlite3")
    if (whichResult.output.isNotBlank()) {
        println("${GRN}[OK] sqlite3 found: ${whichResult.output.trim()}${R}")
        return whichResult.output.trim()
    }
    println("${YEL}[!] sqlite3 khong tim thay duong dan, dung mac dinh 'sqlite3'${R}")
    return "sqlite3"
}
var SQLITE3_CACHE: String? = null
fun getSqlite3(): String {
    if (SQLITE3_CACHE == null) SQLITE3_CACHE = detectSqlite3()
    return SQLITE3_CACHE!!
}

// === Per-package heartbeat file name (fix multi-cloudphone bug) ===
fun heartbeatFileForPkg(pkg: String): String {
    val sanitized = pkg.replace(".", "_")
    return "roblox_status_${sanitized}.txt"
}

// === All possible heartbeat paths for a package ===
fun heartbeatPathsForPkg(pkg: String): List<String> {
    val hbFile = heartbeatFileForPkg(pkg)
    return listOf(
            "/sdcard/Delta/Workspace/$hbFile",
            "/sdcard/Android/data/$pkg/files/delta/workspace/$hbFile",
            "/sdcard/Android/data/$pkg/files/*/workspace/$hbFile"
    )
}

// === Read heartbeat status for a specific package ===
fun readHeartbeatForPkg(pkg: String): String {
    for (p in heartbeatPathsForPkg(pkg)) {
        val content = rootOut("cat $p 2>/dev/null | tail -n 1").trim()
        if (content.isNotBlank()) return content
    }
    // FALLBACK: try old generic filename for backward compat
    val fallback = rootOut("cat /sdcard/Delta/Workspace/roblox_status.txt 2>/dev/null | tail -n 1").trim()
    return fallback
}

// === Clear heartbeat files for a package ===
fun clearHeartbeatForPkg(pkg: String) {
    for (p in heartbeatPathsForPkg(pkg)) {
        root("rm -f $p 2>/dev/null")
    }
    // Also clear old generic file
    root("rm -f /sdcard/Delta/Workspace/roblox_status.txt 2>/dev/null")
}

fun shellQuote(value: String): String {
    return "'" + value.replace("'", "'\"'\"'") + "'"
}

// BAT BUOC dung su -c

fun root(command: String): RootResult {
    return try {
        val process = ProcessBuilder("su", "-c", command).start()
        val output = process.inputStream.bufferedReader().readText().trim()
        val error = process.errorStream.bufferedReader().readText().trim()
        // Bug #15 fix: them timeout 30s de tranh treo vinh vien
        val finished = process.waitFor(30, java.util.concurrent.TimeUnit.SECONDS)
        if (!finished) {
            process.destroyForcibly()
            return RootResult(false, -1, "", "Timeout after 30s")
        }
        val exitCode = process.exitValue()
        RootResult(exitCode == 0, exitCode, output, error)
    } catch (e: Exception) {
        RootResult(false, -1, "", e.message ?: "Unknown error")
    }
}

fun rootOut(command: String): String {
    val result = root(command)
    return if (result.output.isNotBlank()) result.output else result.error
}

fun hasAny(text: String, words: List<String>): Boolean {
    return words.any { text.contains(it, ignoreCase = true) }
}

fun okMark(ok: Boolean): String {
    return if (ok) "${GRN}[OK]${R}" else "${RED}[FAIL]${R}"
}

fun hasVngPopup(pkg: String): Boolean {
    // Check logcat: khi Roblox mo nhung co popup VNG, logcat se co:
    // - "onAppReady: Home" (app o home screen)
    // - "LUA_HOME_PAGE_LOADED" (trang chu da load)
    // - KHONG co "onGameStarting" hoac "onGameStarted"
    val pid = rootOut("pidof $pkg").trim()
    if (pid.isBlank()) return false

    val homeLog =
            rootOut(
                            "logcat -d -t 200 --pid=$pid | grep -iE 'onAppReady.*Home|LUA_HOME_PAGE_LOADED' | tail -1"
                    )
                    .trim()
    val gameLog =
            rootOut(
                            "logcat -d -t 200 --pid=$pid | grep -iE 'onGameStarting|onGameStarted' | tail -1"
                    )
                    .trim()

    // Co home log nhung KHONG co game log = dang o home screen (co the co popup VNG)
    return homeLog.isNotBlank() && gameLog.isBlank()
}

fun hasWhiteScreenStuck(pkg: String, stuckThresholdSec: Long = 60): Boolean {
    // Detect: Roblox dang foreground nhung khong co bat ky game log nao sau N giay
    // (white screen, loading mai, hoac app crash khong thoat)
    val pid = rootOut("pidof $pkg").trim()
    if (pid.isBlank()) return false

    // Bug #5 fix: Dung /proc thay vi 'ps -o etimes=' (khong tuong thich Android)
    val uptimeStr = rootOut("cat /proc/$pid/stat 2>/dev/null | awk '{print \$22}'").trim()
    val systemUptime = rootOut("cat /proc/uptime 2>/dev/null | awk '{print \$1}'").trim()
    val uptimeTicks = uptimeStr.toLongOrNull() ?: 0L
    val sysUpSec = systemUptime.toDoubleOrNull() ?: 0.0
    val clkTck = 100L // Linux default
    val elapsed =
            if (uptimeTicks > 0 && sysUpSec > 0) {
                (sysUpSec - (uptimeTicks.toDouble() / clkTck)).toLong()
            } else 0L
    if (elapsed < stuckThresholdSec) return false // App moi chay, chua du thoi gian

    // Check: KHONG co bat ky game signal nao
    val gameLog =
            rootOut(
                            "logcat -d -t 500 --pid=$pid | grep -iE 'onGameStarted|Replicator created|ExperienceSession|PlaceLauncher.*Game|WebSocketTraceError|DataModelPatchConfigurer|FunctionMarshal|AssetProvider' | grep -v 'disconnect' | tail -1"
                    )
                    .trim()
    val homeLog =
            rootOut(
                            "logcat -d -t 200 --pid=$pid | grep -iE 'onAppReady|LUA_HOME_PAGE_LOADED' | tail -1"
                    )
                    .trim()

    // App chay > N giay nhung KHONG co game log VA KHONG co home log = stuck/white screen
    return gameLog.isBlank() && homeLog.isBlank()
}

fun sha256Short(value: String): String {
    if (value.isBlank()) return "(empty)"
    val digest =
            MessageDigest.getInstance("SHA-256")
                    .digest(value.toByteArray(Charsets.UTF_8))
                    .joinToString("") { "%02x".format(it) }
    return digest.take(16)
}

fun prompt(label: String): String {
    print("$label: ")
    return readLine().orEmpty().trim()
}

fun pause() {
    println()
    println("Nhan Enter de tiep tuc...")
    readLine()
}

fun loadConfig(): Properties {
    val props = Properties()
    if (configFile.exists()) {
        configFile.inputStream().use { props.load(it) }
    }
    // Set defaults neu chua co
    if (props.getProperty("package_prefixes").isNullOrBlank())
            props.setProperty("package_prefixes", "roblox,delta")
    if (props.getProperty("delay_seconds").isNullOrBlank()) props.setProperty("delay_seconds", "3")
    if (props.getProperty("status_method").isNullOrBlank())
            props.setProperty("status_method", "combined")
    if (props.getProperty("auto_change_package").isNullOrBlank())
            props.setProperty("auto_change_package", "false")
    if (props.getProperty("auto_block").isNullOrBlank()) props.setProperty("auto_block", "false")
    if (props.getProperty("force_stop").isNullOrBlank()) props.setProperty("force_stop", "true")
    if (props.getProperty("join_method").isNullOrBlank())
            props.setProperty("join_method", "deeplink_package")
    if (props.getProperty("max_retry_kill").isNullOrBlank())
            props.setProperty("max_retry_kill", "5")
    if (props.getProperty("auto_bypass_key").isNullOrBlank())
            props.setProperty("auto_bypass_key", "false")
    if (props.getProperty("delta_key_url").isNullOrBlank())
            props.setProperty("delta_key_url", "")
    if (props.getProperty("bypass_server_url").isNullOrBlank())
            props.setProperty("bypass_server_url", "https://server1-production-5005.up.railway.app/bypass?url=")
    if (props.getProperty("game_session_wait_seconds").isNullOrBlank())
            props.setProperty("game_session_wait_seconds", "30")
    // Neu file chua ton tai -> tao file voi defaults
    if (!configFile.exists()) {
        try {
            configFile.parentFile?.mkdirs()
            configFile.outputStream().use { props.store(it, "rejoin tool config - auto generated") }
            println("${GRN}-> Da tao file config mac dinh: ${configFile.name}${R}")
        } catch (e: Exception) {
            println("${YEL}-> Warning: Khong tao duoc config file: ${e.message}${R}")
        }
    }
    // Fix file permissions neu can
    if (configFile.exists() && !configFile.canWrite()) {
        root("chmod 666 ${shellQuote(configFile.absolutePath)}")
    }
    return props
}

fun saveConfig(props: Properties) {
    try {
        // Luon ghi qua temp file + su de tranh moi van de permission
        val tmpFile = File.createTempFile("rejoin_cfg_", ".tmp")
        tmpFile.outputStream().use { props.store(it, "rejoin tool config") }
        val absPath = configFile.absolutePath
        // Copy qua su, fix permission + owner
        val result = root("cp ${shellQuote(tmpFile.absolutePath)} ${shellQuote(absPath)}")
        tmpFile.delete()
        if (!result.success) {
            // Fallback: thu ghi truc tiep
            configFile.parentFile?.mkdirs()
            configFile.outputStream().use { props.store(it, "rejoin tool config") }
        }
    } catch (e: Exception) {
        println("${RED}-> Loi ghi config: ${e.message}${R}")
    }
}

fun parseCsv(value: String?): List<String> {
    return value.orEmpty().split(",").map { it.trim() }.filter { it.isNotBlank() }
}

fun selectedPackages(props: Properties): List<String> {
    return parseCsv(props.getProperty("selected_packages"))
}

fun checkRoot(): Boolean {
    val result = root("id")
    if (!result.success) {
        println("${RED}Khong co root/su.${R}")
        println(result.error.ifBlank { result.output })
        return false
    }
    println("${GRN}[OK] Root: su binary${R}")
    return true
}

fun isRobloxPackage(pkg: String): Boolean {
    // Check 1: Activity dac trung cua Roblox
    val activityCheck =
            rootOut(
                    "dumpsys package $pkg | grep -iE 'ActivityNativeMain|RobloxSplash|com\\.roblox\\.client\\.Activity' | head -1"
            )
    if (activityCheck.isNotBlank()) return true
    // Check 2: Folder dac trung chi Roblox moi co
    val folderCheck =
            rootOut(
                    "test -d /data/data/$pkg/app_assets/content && echo ROBLOX || test -d /data/data/$pkg/files/ota_rbxm_decompressed_cache && echo ROBLOX"
            )
    if (folderCheck.contains("ROBLOX")) return true
    return false
}

fun candidatePackages(props: Properties): List<String> {
    val prefixes = parseCsv(props.getProperty("package_prefixes"))
    val output = rootOut("pm list packages")
    val allPkgs =
            output.lineSequence()
                    .map { it.removePrefix("package:").trim() }
                    .filter { it.isNotBlank() }
                    .toList()

    // 1) Tim theo prefix (cach cu)
    val byPrefix =
            allPkgs
                    .filter { pkg ->
                        prefixes.any { prefix -> pkg.contains(prefix, ignoreCase = true) }
                    }
                    .toMutableSet()

    // 2) Tim theo Activity + Folder (bat clone doi package name)
    val thirdParty =
            rootOut("pm list packages -3")
                    .lineSequence()
                    .map { it.removePrefix("package:").trim() }
                    .filter { it.isNotBlank() && it !in byPrefix }
                    .toList()

    for (pkg in thirdParty) {
        if (isRobloxPackage(pkg)) {
            byPrefix.add(pkg)
        }
    }

    return byPrefix.sortedBy { (if (it == "com.roblox.client") "0" else "1") + it }
}

fun robloxFileLogSignal(pkg: String): Boolean {
    val dirs =
            listOf(
                            "/data/data/$pkg/cache",
                            "/data/user/0/$pkg/cache",
                            "/sdcard/Android/data/$pkg/files/logs"
                    )
                    .joinToString(" ") { shellQuote(it) }

    val command =
            "find $dirs -type f -mmin -10 2>/dev/null | head -n 10 | while read f; do tail -n 100 \"\${'$'}f\" 2>/dev/null; done | grep -iE 'kicked|disconnect|lost connection|same account|error code|fatal|crash|exception' | tail -n 20"

    return rootOut(command).isNotBlank()
}

fun robloxDebugText(pkg: String): String {
    val dirs =
            listOf(
                            "/data/data/$pkg/cache",
                            "/data/user/0/$pkg/cache",
                            "/sdcard/Android/data/$pkg/files/logs"
                    )
                    .joinToString(" ") { shellQuote(it) }

    val fileTextCommand =
            "find $dirs -type f -mmin -10 2>/dev/null | head -n 10 | while read f; do tail -n 120 \"\${'$'}f\" 2>/dev/null; done | grep -iE 'userid|user_id|placeid|place_id|gameid|game_id|universeid|kicked|disconnect|lost connection|same account|error code|fatal|crash|exception' | tail -n 120"

    val logcatText =
            rootOut(
                    "logcat -d -t 800 | grep -iE 'roblox|$pkg|userid|user_id|placeid|place_id|gameid|game_id|universeid|kicked|disconnect|lost connection|same account|error code|fatal|crash|exception' | tail -n 160"
            )
    val uiDump = rootOut("cat /sdcard/roblox_status_dump.xml 2>/dev/null")
    val fileText = rootOut(fileTextCommand)
    return listOf(logcatText, fileText, uiDump).joinToString("\n")
}

fun extractFirst(text: String, patterns: List<String>): String {
    for (pattern in patterns) {
        val match = Regex(pattern, setOf(RegexOption.IGNORE_CASE)).find(text)
        val value = match?.groups?.get(1)?.value
        if (!value.isNullOrBlank()) return value
    }
    return ""
}

fun sessionCookieExists(pkg: String): Boolean {
    val db = "/data/data/$pkg/app_webview/Default/Cookies"
    // Method 1: Try sqlite3 (fastest, most accurate)
    val query =
            "SELECT COUNT(*) FROM cookies WHERE host_key LIKE '%roblox.com%' AND name='.ROBLOSECURITY' AND (LENGTH(value) > 0 OR LENGTH(encrypted_value) > 0);"
    val sqlResult = rootOut("${getSqlite3()} -batch ${shellQuote(db)} ${shellQuote(query)} 2>/dev/null")
            .trim()
            .toIntOrNull()
    if (sqlResult != null) return sqlResult > 0

    // Method 2: FALLBACK - read raw DB file with strings (khi khong co sqlite3)
    val rawCheck = rootOut("cat ${shellQuote(db)} 2>/dev/null | strings 2>/dev/null | grep -i 'ROBLOSECURITY' | head -1")
    return rawCheck.contains("ROBLOSECURITY", ignoreCase = true)
}

fun userIdFromLogs(text: String): String {
    return extractFirst(
            text,
            listOf(
                    """user[_ -]?id["'=:\s]+([0-9]{3,})""",
                    """"userId"\s*:\s*([0-9]{3,})""",
                    """userid=([0-9]{3,})""",
                    """UserId[^0-9]+([0-9]{3,})"""
            )
    )
}

fun gameIdFromLogs(text: String): String {
    return extractFirst(
            text,
            listOf(
                    """place[_ -]?id["'=:\s]+([0-9]{3,})""",
                    """"placeId"\s*:\s*([0-9]{3,})""",
                    """roblox://placeId=([0-9]{3,})""",
                    """game[_ -]?id["'=:\s]+([0-9a-fA-F-]{8,})""",
                    """"gameId"\s*:\s*"?( [0-9a-fA-F-]{8,})"?""".replace(" ", "")
            )
    )
}

fun getUserIdFromCookieDb(pkg: String): String {
    val db = "/data/data/$pkg/app_webview/Default/Cookies"
    // Method 1: sqlite3
    val query =
            "SELECT value FROM cookies WHERE name IN ('RBXEventTrackerV2', 'GuestData', '.RBXID', '.ROBLOSECURITY');"
    val result = rootOut("${getSqlite3()} -batch ${shellQuote(db)} ${shellQuote(query)} 2>/dev/null")

    var text = result
    // Method 2: FALLBACK - read raw DB if sqlite3 failed
    if (text.isBlank()) {
        text = rootOut("cat ${shellQuote(db)} 2>/dev/null | strings 2>/dev/null | grep -iE 'rbxuid|UserID' | head -5").trim()
    }

    val rbxuidMatch = Regex("""rbxuid=([0-9]+)""", RegexOption.IGNORE_CASE).find(text)
    if (rbxuidMatch != null) return rbxuidMatch.groups[1]?.value ?: ""

    val userIdMatch = Regex("""(UserID=)([0-9-]+)""", RegexOption.IGNORE_CASE).find(text)
    if (userIdMatch != null) return userIdMatch.groups[2]?.value ?: ""

    return ""
}

fun getUserIdFromSharedPrefs(pkg: String): String {
    // prefs.xml co userid_long (stable, khong doi)
    val prefsResult = rootOut("cat /data/data/$pkg/shared_prefs/prefs.xml 2>/dev/null")
    val longMatch =
            Regex("""name="userid_long"\s+value="([0-9]+)"""", RegexOption.IGNORE_CASE)
                    .find(prefsResult)
    if (longMatch != null) return longMatch.groups[1]?.value ?: ""
    // Fallback: v2.player.xml
    val v2Result = rootOut("cat /data/data/$pkg/shared_prefs/${pkg}.v2.player.xml 2>/dev/null")
    val match = Regex("""userId["\s>:]+([0-9]{4,})""", RegexOption.IGNORE_CASE).find(v2Result)
    return match?.groups?.get(1)?.value ?: ""
}

fun getPlaceIdFromIntent(pkg: String): String {
    val intentData =
            rootOut("dumpsys activity activities | grep -i 'roblox://placeId=' | grep $pkg")
    val match = Regex("""placeId=([0-9]+)""", RegexOption.IGNORE_CASE).find(intentData)
    return match?.groups?.get(1)?.value ?: ""
}

fun getRawCookieFromDb(pkg: String): String {
    val db = "/data/data/$pkg/app_webview/Default/Cookies"
    // Method 1: sqlite3
    val query = "SELECT value FROM cookies WHERE name='.ROBLOSECURITY';"
    val result = rootOut("${getSqlite3()} -batch ${shellQuote(db)} ${shellQuote(query)} 2>/dev/null").trim()
    if (result.contains("WARNING:-DO-NOT-SHARE-THIS", ignoreCase = true)) return result

    // Method 2: FALLBACK - extract from raw DB file (khi khong co sqlite3)
    val rawResult = rootOut("cat ${shellQuote(db)} 2>/dev/null | strings 2>/dev/null | grep 'WARNING:-DO-NOT-SHARE-THIS' | head -1").trim()
    // Cookie trong raw DB co format: _|WARNING:-DO-NOT-SHARE-THIS...|
    val match = Regex("""(_\|WARNING:-DO-NOT-SHARE-THIS[^|]*\|[^/]*)""").find(rawResult)
    return match?.value ?: ""
}

fun checkStatus(pkg: String): StatusReport {
    val packagePath = rootOut("pm path $pkg")
    if (packagePath.isBlank() || packagePath.contains("not found", ignoreCase = true)) {
        return StatusReport(
                pkg,
                "NOT_INSTALLED",
                "",
                false,
                false,
                false,
                false,
                false,
                "",
                "",
                "",
                ""
        )
    }

    var pid = rootOut("pidof $pkg").trim()
    if (pid.isBlank()) {
        pid = rootOut("pgrep -f $pkg | head -n 1").trim()
    }
    val window =
            rootOut(
                    "dumpsys window | grep -E 'mCurrentFocus|mFocusedApp|mFocusedWindow' | grep $pkg"
            )
    // Loai bo mRootProcess va ActivityRecord vi no chua ca app dang chay ngam hoac da bi an di
    val activity =
            rootOut(
                    "dumpsys activity activities | grep -E 'mResumedActivity|ResumedActivity' | grep $pkg"
            )
    val focused = window.contains(pkg) || activity.contains(pkg)

    // UIAutomator VO DUNG voi Roblox (render trong SurfaceView/OpenGL)
    // Bo de tang toc check status
    val uiHit = false

    // --- SMART LOGCAT: tang buffer len 1000 dong de khong bi mat log kick ---
    // --- READ LOGS UNCONDITIONALLY ---
    val recentLog =
            rootOut(
                    "logcat -d -t 500 | grep -iE '$pkg|am_crash|FATAL EXCEPTION|has died|Force finishing' | tail -n 30"
            )
    val rawCrashHit =
            recentLog.contains(pkg, ignoreCase = true) &&
                    hasAny(
                            recentLog,
                            listOf("am_crash", "fatal exception", "has died", "force finishing")
                    )
    val crashHit = rawCrashHit && pid.isBlank()

    var logcatKickHit = false
    // Pattern CHINH XAC cua Roblox kick
    val kickPatterns =
            "You have been kicked|Lost connection with reason|Sending disconnect with reason|Disconnection Notification|same account launched|Connection lost|Teleport failed|server.?shut"

    // === CHI CHECK LOGCAT KHI PID TON TAI ===
    // Neu pid blank thi khong can check logcat kick, vi game da chet roi
    if (pid.isNotBlank()) {
        // Lay timestamp hien tai de loc log cu (chi xet log trong 5 phut gan nhat)
        // logcat -d -t 300 = 300 dong (co the it hon neu logcat ngan)
        // Dung --pid de chi check log cua dung process nay
        val kickLog = rootOut("logcat -d -t 500 --pid=$pid | grep -iE '$kickPatterns' | tail -n 5")
        if (kickLog.isNotBlank()) {
            // Co log kick NHUNG phai dam bao la log RECENT (khong phai log cu truoc khi clear)
            // Lay dong log moi nhat co timestamp
            val lastLine = kickLog.lines().lastOrNull { it.isNotBlank() } ?: ""
            // Parse minute tu dong log: format '04-30 00:52:20.xxx'
            val logMinMatch = Regex("""\d{2}-\d{2} (\d{2}):(\d{2}):""").find(lastLine)
            if (logMinMatch != null) {
                val logHour = logMinMatch.groupValues[1].toIntOrNull() ?: -1
                val logMin = logMinMatch.groupValues[2].toIntOrNull() ?: -1
                val nowMin = java.time.LocalTime.now().minute
                val nowHour = java.time.LocalTime.now().hour
                // Bug #7 fix: handle midnight wrap-around
                val diffMin = ((nowHour * 60 + nowMin) - (logHour * 60 + logMin) + 1440) % 1440
                // Chi tinh la kick neu log xay ra trong vong 5 phut gan nhat
                if (diffMin in 0..5) {
                    logcatKickHit = true
                }
            } else {
                // Neu khong parse duoc timestamp, bo qua (an toan hon)
                logcatKickHit = false
            }
        }
        // REMOVED: heartbeat-only detection (V4 - gay false positive, kill tab dang online)
        // Thay the bang lua heartbeat V4 + API cross-check (ben duoi)
    }

    val fileLogHit = robloxFileLogSignal(pkg)

    // --- DELTA CRASH FILE CHECK (METHOD MOI) ---
    // Khi Delta crash truoc khi exec script, roblox_status.txt khong ton tai
    // Nhung Delta ghi crash log vao /sdcard/Delta/Crashes/
    var deltaCrashRecent = false
    var deltaCrashDetail = ""
    try {
        val latestCrashInfo = rootOut("ls -t /sdcard/Delta/Crashes/ 2>/dev/null | head -1").trim()
        if (latestCrashInfo.isNotBlank()) {
            val crashContent =
                    rootOut("cat /sdcard/Delta/Crashes/$latestCrashInfo 2>/dev/null | head -15")
                            .trim()
            val tsMatch = Regex("""Timestamp \(Unix\):\s*(\d+)""").find(crashContent)
            if (tsMatch != null) {
                val crashTime = tsMatch.groupValues[1].toLongOrNull() ?: 0
                val nowEpoch = System.currentTimeMillis() / 1000
                val diffSec = nowEpoch - crashTime
                if (diffSec < 180) { // Crash trong vong 3 phut
                    deltaCrashRecent = true
                    val scriptsRan = !crashContent.contains("Scripts Ran: false")
                    val actionMatch = Regex("""Last Action:\s*(.+)""").find(crashContent)
                    val lastAction = actionMatch?.groupValues?.get(1)?.trim() ?: "unknown"
                    deltaCrashDetail =
                            "Delta crash ${diffSec}s ago, ScriptsRan=$scriptsRan, LastAction=$lastAction"
                    println("${RED}-> DELTA CRASH DETECTED: $deltaCrashDetail${R}")
                }
            }
        }
    } catch (_: Exception) {}

    // --- LOGCAT KICK CHECK KHI PID BLANK (app da chet) ---
    // Truoc day chi check khi pid ton tai, nhung khi app crash + Lua chua chay
    // thi can check logcat toan bo de tim signal kick
    var logcatKickWhenDead = false
    if (pid.isBlank()) {
        val deadKickLog =
                rootOut(
                                "logcat -d -t 300 | grep -iE 'kicked|disconnect.*reason|lost connection|same account|Connection lost' | grep -i '${pkg.replace(".", "\\\\.")}\\|roblox' | tail -n 3"
                        )
                        .trim()
        if (deadKickLog.isNotBlank()) {
            // Verify timestamp trong 5 phut
            val lastLine = deadKickLog.lines().lastOrNull { it.isNotBlank() } ?: ""
            val logMinMatch = Regex("""\d{2}-\d{2} (\d{2}):(\d{2}):""").find(lastLine)
            if (logMinMatch != null) {
                val logHour = logMinMatch.groupValues[1].toIntOrNull() ?: -1
                val logMin = logMinMatch.groupValues[2].toIntOrNull() ?: -1
                val nowMin = java.time.LocalTime.now().minute
                val nowHour = java.time.LocalTime.now().hour
                // Bug #7 fix: handle midnight wrap-around
                val diffMin = ((nowHour * 60 + nowMin) - (logHour * 60 + logMin) + 1440) % 1440
                if (diffMin in 0..5) {
                    logcatKickWhenDead = true
                    println("${RED}-> LOGCAT KICK (app dead): ${lastLine.take(100)}${R}")
                }
            }
        }
    }

    // --- LUA EXECUTOR (DELTA) WORKSPACE CHECK ---
    // V6: Per-package heartbeat file (fix multi-cloudphone bug)
    var workspaceStatus = readHeartbeatForPkg(pkg)

    var luaKickHit = false
    var luaHeartbeatStale = false
    var luaRejoining = false
    var luaGameStatus = "" // "loading" or "ingame" from V4 heartbeat
    if (workspaceStatus.startsWith("kicked", ignoreCase = true)) {
        luaKickHit = true
        // Xoa file de khong loop
        clearHeartbeatForPkg(pkg)
    } else if (workspaceStatus.startsWith("rejoining", ignoreCase = true)) {
        // Lua script dang tu rejoin bang TeleportService
        luaRejoining = true
        println("${YEL}-> Lua script dang tu rejoin (TeleportService)...${R}")
    } else if (workspaceStatus.startsWith("alive:", ignoreCase = true)) {
        // V4 format: alive:<timestamp>:<status>:<placeId>:<jobId>
        val parts = workspaceStatus.removePrefix("alive:").split(":")
        val tsStr = parts.getOrNull(0)?.trim() ?: ""
        luaGameStatus = parts.getOrNull(1)?.trim() ?: ""
        val luaTime = tsStr.toLongOrNull()
        if (luaTime != null) {
            val nowEpoch = System.currentTimeMillis() / 1000
            val diff = nowEpoch - luaTime
            if (diff > 45) {
                // Heartbeat qua cu (> 45s, V5 interval=5s) => game da die hoac bi kick
                // NHUNG can confirm bang API truoc khi ket luan
                luaHeartbeatStale = true
            }
        }
    }

    // --- IDENTITY: userId tu shared_prefs (stable) ---
    val sessionCookie = sessionCookieExists(pkg)
    var userId = getUserIdFromSharedPrefs(pkg)
    if (userId.isBlank()) userId = getUserIdFromCookieDb(pkg)

    // --- GAME ID: tu logcat gan nhat ---
    val gameLog =
            rootOut(
                    "logcat -d -t 200 | grep -iE 'placeId|place_id|ExperienceSession|GameManager|PlaceLauncher' | grep '$pkg\\|roblox' | tail -n 10"
            )
    var gameId =
            extractFirst(
                    gameLog,
                    listOf(
                            """placeId\s*=\s*([0-9]{5,})""",
                            """placeId[\"'=:\s]+([0-9]{5,})""",
                            """place_id[\"'=:\s]+([0-9]{5,})"""
                    )
            )
    if (gameId.isBlank()) gameId = getPlaceIdFromIntent(pkg)

    // --- CHECK GAME SESSION: Roblox co dang o trong game khong? ---
    var inGameSession = false
    // V7.3: Remove 'focused' requirement - on cloudphone only 1 app focused at a time
    // Other running game instances were wrongly reported as InGame:NO
    if (pid.isNotBlank()) {
        // V7: Expanded patterns - original onGameStarted/Replicator get pushed out of
        // tiny 256KB logcat buffer by asset error spam. Add continuous game signals:
        // WebSocketTraceError (game networking), DataModelPatchConfigurer (game config),
        // FunctionMarshal (Lua execution), AssetProvider (asset loading = game active)
        val gameSessionLog =
                rootOut(
                        "logcat -d -t 500 --pid=$pid | grep -iE 'FLog::Network.*Replicator|onGameStarting|onGameLoaded|ExperienceSession|PlaceLauncher.*Game|Rendering.*started|WebSocketTraceError|DataModelPatchConfigurer|FunctionMarshal|AssetProvider' | grep -v 'disconnect' | tail -n 3"
                )
        inGameSession = gameSessionLog.isNotBlank()

        // V5 FALLBACK: Neu logcat bi clear (logcat -c) thi khong co game log
        // -> Dung process uptime + intent placeId + KHONG co home/kick log de xac dinh
        if (!inGameSession) {
            // Check intent co placeId khong
            val intentLog =
                    rootOut(
                                    "dumpsys activity activities | grep '$pkg' | grep -i 'placeId' | tail -1"
                            )
                            .trim()
            val hasPlaceIntent = intentLog.contains("placeId", ignoreCase = true)

            // Check process uptime (starttime tu /proc)
            val uptimeStr = rootOut("cat /proc/$pid/stat 2>/dev/null | awk '{print \$22}'").trim()
            val systemUptime = rootOut("cat /proc/uptime 2>/dev/null | awk '{print \$1}'").trim()
            val uptimeTicks = uptimeStr.toLongOrNull() ?: 0L
            val sysUpSec = systemUptime.toDoubleOrNull() ?: 0.0
            val clkTck = 100L // Linux default
            val processAgeSec =
                    if (uptimeTicks > 0 && sysUpSec > 0) {
                        (sysUpSec - (uptimeTicks.toDouble() / clkTck)).toLong()
                    } else 0L

            // Check KHONG co home/kick log (= game khong bi kick, khong o home)
            val badLog =
                    rootOut(
                                    "logcat -d -t 200 --pid=$pid | grep -iE 'onAppReady.*Home|LUA_HOME_PAGE_LOADED|kicked|disconnect.*reason|lost connection' | tail -1"
                            )
                            .trim()

            // Process chay > 60s + co placeId intent + khong co bad log = InGame
            if (hasPlaceIntent && processAgeSec > 60 && badLog.isBlank()) {
                inGameSession = true
                println(
                        "${DIM}-> inGameSession=true (fallback: uptime=${processAgeSec}s, placeId intent, no bad log)${R}"
                )
            }
        }
    }

    // --- ROBLOX PRESENCE API CHECK (cross-verify moi tin hieu) ---
    // 0=Offline, 1=Online(Web), 2=InGame, 3=InStudio
    var apiPresence = -1
    var apiKickHit = false
    if (userId.isNotBlank() && pid.isNotBlank()) {
        val pt = fetchPresenceType(userId)
        if (pt != null) {
            apiPresence = pt
            // API confirm: neu API bao InGame(2) thi KHONG kick du logcat/heartbeat bao gi
            if (pt == 2) {
                // API bao dang InGame -> override cac tin hieu sai
                if (logcatKickHit) {
                    println("${GRN}-> API confirm InGame, bo qua logcat kick (co the log cu)${R}")
                    logcatKickHit = false
                }
                if (luaHeartbeatStale) {
                    println(
                            "${GRN}-> API confirm InGame, bo qua heartbeat stale (Delta co the bi crash rieng)${R}"
                    )
                    luaHeartbeatStale = false
                }
            } else if (pt != 2 && inGameSession && !luaKickHit && !logcatKickHit) {
                // API bao KHONG InGame nhung logcat bao co game session -> bi kick
                apiKickHit = true
            }
        }
    }

    // --- USERNAME tu API ---
    val username = if (userId.isNotBlank()) fetchUsername(userId) ?: "" else ""

    // --- STATUS LOGIC V5 ---
    // Thu tu uu tien (SMART + DELTA CRASH):
    // 1. Lua kick -> kick (100% chinh xac)
    // 2. Lua heartbeat stale + API confirm NOT InGame -> kick
    // 3. pid blank + Delta crash recent -> CRASHED_RECENTLY (NEW!)
    // 4. pid blank + logcat kick khi app dead -> KICKED_OR_DISCONNECTED (NEW!)
    // 5. pid blank + crash logcat -> CRASHED_RECENTLY
    // 6. pid blank -> NOT_RUNNING_OR_EXITED (se duoc rejoin boi autoRejoin)
    // 7. pid + logcatKickHit (da duoc API verify) -> kick
    // 8. Lua heartbeat active + status=loading -> LOADING_GAME (KHONG KILL!)
    // 9. pid + focused + inGameSession -> FOREGROUND (dang choi game)
    // 9b. pid + focused + KHONG inGameSession -> FOREGROUND_NO_GAME
    // 10. pid + apiKickHit -> kick
    // 11. pid + khong focused -> RUNNING_BACKGROUND
    val status =
            when {
                luaRejoining -> "LUA_REJOINING"
                luaKickHit -> "KICKED_OR_DISCONNECTED"
                luaHeartbeatStale && pid.isNotBlank() -> "KICKED_OR_DISCONNECTED"
                // V5: Delta crash file moi (<180s) -> CRASHED (du Lua chua chay)
                pid.isBlank() && deltaCrashRecent -> "CRASHED_RECENTLY"
                // V5: Logcat kick khi app da chet
                pid.isBlank() && logcatKickWhenDead -> "KICKED_OR_DISCONNECTED"
                pid.isBlank() && crashHit -> "CRASHED_RECENTLY"
                pid.isBlank() -> "NOT_RUNNING_OR_EXITED"
                pid.isNotBlank() && logcatKickHit -> "KICKED_OR_DISCONNECTED"
                // V4: Lua bao dang loading -> KHONG KILL, doi game load xong
                pid.isNotBlank() && luaGameStatus == "loading" -> "LOADING_GAME"
                // V7.4: inGameSession from logcat OR lua -> FOREGROUND regardless of focus
                // On cloudphone only 1 app is focused, but both can be in-game
                pid.isNotBlank() && (inGameSession || luaGameStatus == "ingame") -> "FOREGROUND"
                pid.isNotBlank() && focused && !inGameSession -> "FOREGROUND_NO_GAME"
                pid.isNotBlank() && apiKickHit -> "KICKED_OR_DISCONNECTED"
                pid.isNotBlank() -> "RUNNING_BACKGROUND"
                else -> "NOT_RUNNING_OR_EXITED"
            }

    return StatusReport(
            pkg,
            status,
            pid,
            focused,
            uiHit,
            crashHit,
            fileLogHit,
            sessionCookie,
            userId,
            gameId,
            window,
            activity,
            username,
            apiPresence,
            inGameSession
    )
}

fun checkStatusFast(pkg: String): StatusReport {
    val packagePath = rootOut("pm path $pkg")
    if (packagePath.isBlank() || packagePath.contains("not found", ignoreCase = true)) {
        return StatusReport(
                pkg,
                "NOT_INSTALLED",
                "",
                false,
                false,
                false,
                false,
                false,
                "",
                "",
                "",
                ""
        )
    }

    var pid = rootOut("pidof $pkg").trim()
    if (pid.isBlank()) {
        pid = rootOut("pgrep -f $pkg | head -n 1").trim()
    }
    val window =
            rootOut(
                    "dumpsys window | grep -E 'mCurrentFocus|mFocusedApp|mFocusedWindow' | grep $pkg"
            )
    // Loai bo mRootProcess va ActivityRecord de kiem tra chinh xac app co dang tren man hinh hay
    // khong
    val activity =
            rootOut(
                    "dumpsys activity activities | grep -E 'mResumedActivity|ResumedActivity' | grep $pkg"
            )
    val focused = window.contains(pkg) || activity.contains(pkg)

    val status =
            when {
                focused && pid.isNotBlank() -> "FOREGROUND"
                pid.isNotBlank() -> "RUNNING_BACKGROUND"
                else -> "NOT_RUNNING_OR_EXITED"
            }

    var uId = ""
    var pId = ""
    if (focused) {
        pId = getPlaceIdFromIntent(pkg)
    }

    return StatusReport(
            pkg,
            status,
            pid,
            focused,
            false,
            false,
            false,
            false,
            uId,
            pId,
            window,
            activity
    )
}

// === BATCHED STATUS CHECK V6: 1 su command thay vi 15+ ===
// Gop tat ca shell commands thanh 1 script, parse output theo delimiter
fun checkStatusBatched(pkg: String): StatusReport {
    // 1 lenh su duy nhat, gop tat ca checks
    val batchScript =
            """
        echo '===PID==='
        pidof $pkg 2>/dev/null || echo ''
        echo '===WINDOW==='
        dumpsys window 2>/dev/null | grep -E 'mCurrentFocus|mFocusedApp|mFocusedWindow' | grep '$pkg' || echo ''
        echo '===ACTIVITY==='
        dumpsys activity activities 2>/dev/null | grep -E 'mResumedActivity|ResumedActivity' | grep '$pkg' || echo ''
        echo '===LUA_STATUS==='
        cat /sdcard/Delta/Workspace/${heartbeatFileForPkg(pkg)} 2>/dev/null | tail -n 1 || cat /sdcard/Delta/Workspace/roblox_status.txt 2>/dev/null | tail -n 1 || cat /sdcard/Android/data/$pkg/files/delta/workspace/${heartbeatFileForPkg(pkg)} 2>/dev/null | tail -n 1 || echo ''
        echo '===DELTA_CRASH==='
        CFILE=${'$'}(ls -t /sdcard/Delta/Crashes/ 2>/dev/null | head -1)
        if [ -n "${'$'}CFILE" ]; then cat "/sdcard/Delta/Crashes/${'$'}CFILE" 2>/dev/null | head -15; fi
        echo '===USERID==='
        cat /data/data/$pkg/shared_prefs/prefs.xml 2>/dev/null | grep 'userid_long' | head -1 || echo ''
        echo '===LOGCAT_PID==='
        PID=${'$'}(pidof $pkg 2>/dev/null)
        if [ -n "${'$'}PID" ]; then
            logcat -d -t 500 --pid=${'$'}PID 2>/dev/null | grep -iE 'kicked|Lost connection|disconnect.*reason|same account|Connection lost|Teleport failed|server.?shut|onGameStarted|onGameLoaded|Replicator created|ExperienceSession|PlaceLauncher.*Game|DataModelPatchConfigurer|FunctionMarshal|placeId|am_crash|FATAL EXCEPTION|has died|Force finishing' | tail -n 30
        else
            echo ''
        fi
        echo '===LOGCAT_DEAD==='
        if [ -z "${'$'}PID" ]; then
            logcat -d -t 300 2>/dev/null | grep -iE 'kicked|disconnect.*reason|lost connection|same account|Connection lost' | grep -i '${pkg.replace(".", "\\\\.")}\|roblox' | tail -n 3
        else
            echo ''
        fi
    """.trimIndent()

    val rawOutput = rootOut(batchScript)

    // Parse sections
    fun section(name: String): String {
        val marker = "===${name}==="
        val idx = rawOutput.indexOf(marker)
        if (idx < 0) return ""
        val start = idx + marker.length
        val nextMarker = rawOutput.indexOf("===", start + 1)
        val end = if (nextMarker > start) nextMarker else rawOutput.length
        return rawOutput.substring(start, end).trim()
    }

    var pid = section("PID").lines().firstOrNull()?.trim().orEmpty()
    val window = section("WINDOW")
    val activity = section("ACTIVITY")
    val focused = window.contains(pkg) || activity.contains(pkg)
    val workspaceStatus = section("LUA_STATUS")
    val deltaCrashRaw = section("DELTA_CRASH")
    val userIdRaw = section("USERID")
    val logcatPidData = section("LOGCAT_PID")
    val logcatDeadData = section("LOGCAT_DEAD")

    // Parse userId from prefs XML
    var userId = ""
    val uidMatch = Regex("""value="([0-9]+)"""").find(userIdRaw)
    if (uidMatch != null) userId = uidMatch.groupValues[1]

    // Parse Lua status
    var luaKickHit = false
    var luaHeartbeatStale = false
    var luaRejoining = false
    var luaGameStatus = ""
    if (workspaceStatus.startsWith("kicked", ignoreCase = true)) {
        luaKickHit = true
        // Xoa file de khong loop
        clearHeartbeatForPkg(pkg)
    } else if (workspaceStatus.startsWith("rejoining", ignoreCase = true)) {
        luaRejoining = true
    } else if (workspaceStatus.startsWith("alive:", ignoreCase = true)) {
        val parts = workspaceStatus.removePrefix("alive:").split(":")
        val tsStr = parts.getOrNull(0)?.trim() ?: ""
        luaGameStatus = parts.getOrNull(1)?.trim() ?: ""
        val luaTime = tsStr.toLongOrNull()
        if (luaTime != null) {
            val diff = System.currentTimeMillis() / 1000 - luaTime
            if (diff > 45) { // V5: 45s thay vi 90s
                luaHeartbeatStale = true
            }
        }
    }

    // Parse Delta crash
    var deltaCrashRecent = false
    if (deltaCrashRaw.isNotBlank()) {
        val tsMatch = Regex("""Timestamp \(Unix\):\s*(\d+)""").find(deltaCrashRaw)
        if (tsMatch != null) {
            val crashTime = tsMatch.groupValues[1].toLongOrNull() ?: 0
            val diffSec = System.currentTimeMillis() / 1000 - crashTime
            if (diffSec < 180) deltaCrashRecent = true
        }
    }

    // === CHI GOI LOGCAT KHI CAN (khong phai moi vong) ===
    // Logcat chi can khi: pid ton tai VA chua co Lua signal
    var logcatKickHit = false
    var logcatKickWhenDead = false
    var crashHit = false
    var inGameSession = false
    var gameId = ""

    if (pid.isNotBlank() && !luaKickHit && !luaRejoining) {
        // V6: Logcat da duoc gop vao batch script, dung data da co
        val logcatAll = logcatPidData

        // Parse kick
        val kickPatterns =
                listOf(
                        "kicked",
                        "Lost connection",
                        "disconnect",
                        "same account",
                        "Connection lost",
                        "Teleport failed"
                )
        val kickLines =
                logcatAll.lines().filter { line ->
                    kickPatterns.any { line.contains(it, ignoreCase = true) }
                }
        if (kickLines.isNotEmpty()) {
            val lastLine = kickLines.last()
            val logMinMatch = Regex("""\d{2}-\d{2} (\d{2}):(\d{2}):""").find(lastLine)
            if (logMinMatch != null) {
                val logHour = logMinMatch.groupValues[1].toIntOrNull() ?: -1
                val logMin = logMinMatch.groupValues[2].toIntOrNull() ?: -1
                val nowMin = java.time.LocalTime.now().minute
                val nowHour = java.time.LocalTime.now().hour
                val diffMin = ((nowHour * 60 + nowMin) - (logHour * 60 + logMin) + 1440) % 1440
                if (diffMin in 0..5) logcatKickHit = true
            }
        }

        // Parse game session
        val gameLines =
                logcatAll.lines().filter { line ->
                    listOf(
                                    "onGameStarted",
                                    "onGameLoaded",
                                    "Replicator created",
                                    "ExperienceSession",
                                    "PlaceLauncher",
                                    "DataModelPatchConfigurer",
                                    "FunctionMarshal"
                            )
                            .any { line.contains(it, ignoreCase = true) } &&
                            !line.contains("disconnect", ignoreCase = true)
                }
        inGameSession = gameLines.isNotEmpty()

        // Parse crash
        crashHit =
                logcatAll.contains(pkg, ignoreCase = true) &&
                        hasAny(
                                logcatAll,
                                listOf("am_crash", "fatal exception", "has died", "force finishing")
                        )
        if (crashHit && pid.isNotBlank()) crashHit = false // crash chi khi pid blank

        // Parse placeId
        gameId =
                extractFirst(
                        logcatAll,
                        listOf("""placeId\s*=\s*([0-9]{5,})""", """placeId[\"'=:\s]+([0-9]{5,})""")
                )
    } else if (pid.isBlank() && !luaKickHit) {
        // App dead: dung logcat dead da gop trong batch
        val deadLog = logcatDeadData
        if (deadLog.isNotBlank()) {
            val lastLine = deadLog.lines().lastOrNull { it.isNotBlank() } ?: ""
            val logMinMatch = Regex("""\d{2}-\d{2} (\d{2}):(\d{2}):""").find(lastLine)
            if (logMinMatch != null) {
                val logHour = logMinMatch.groupValues[1].toIntOrNull() ?: -1
                val logMin = logMinMatch.groupValues[2].toIntOrNull() ?: -1
                val nowMin = java.time.LocalTime.now().minute
                val nowHour = java.time.LocalTime.now().hour
                val diffMin = ((nowHour * 60 + nowMin) - (logHour * 60 + logMin) + 1440) % 1440
                if (diffMin in 0..5) logcatKickWhenDead = true
            }
        }
        // Crash check khi dead (V6: dung logcat dead da batch)
        crashHit =
                logcatDeadData.contains(pkg, ignoreCase = true) &&
                        hasAny(logcatDeadData, listOf("am_crash", "fatal exception", "has died"))
    }

    // Fallback inGameSession khi logcat bi clear
    // V7.4: Remove focused requirement - on cloudphone only 1 app focused
    if (!inGameSession && pid.isNotBlank() && !luaKickHit && !logcatKickHit) {
        // Lua bao ingame = inGameSession
        if (luaGameStatus == "ingame" && !luaHeartbeatStale) {
            inGameSession = true
        }
    }

    // STATUS LOGIC (same as V5 but faster)
    val status =
            when {
                luaRejoining -> "LUA_REJOINING"
                luaKickHit -> "KICKED_OR_DISCONNECTED"
                luaHeartbeatStale && pid.isNotBlank() -> "KICKED_OR_DISCONNECTED"
                pid.isBlank() && deltaCrashRecent -> "CRASHED_RECENTLY"
                pid.isBlank() && logcatKickWhenDead -> "KICKED_OR_DISCONNECTED"
                pid.isBlank() && crashHit -> "CRASHED_RECENTLY"
                pid.isBlank() -> "NOT_RUNNING_OR_EXITED"
                pid.isNotBlank() && logcatKickHit -> "KICKED_OR_DISCONNECTED"
                pid.isNotBlank() && luaGameStatus == "loading" -> "LOADING_GAME"
                // V7.4: Remove focused requirement for cloudphone multi-tab
                pid.isNotBlank() && (inGameSession || luaGameStatus == "ingame") -> "FOREGROUND"
                pid.isNotBlank() && focused && !inGameSession -> "FOREGROUND_NO_GAME"
                pid.isNotBlank() -> "RUNNING_BACKGROUND"
                else -> "NOT_RUNNING_OR_EXITED"
            }

    return StatusReport(
            pkg,
            status,
            pid,
            focused,
            false,
            crashHit,
            false,
            false,
            userId,
            gameId,
            window,
            activity,
            "",
            -1,
            inGameSession
    )
}

fun printStatus(pkg: String) {
    val report = checkStatus(pkg)
    println("Package: ${report.packageName}")
    println("Status: ${report.status}")
    println("PID: ${if (report.pid.isBlank()) "(none)" else report.pid}")
    println("Focused: ${report.focused}")
    println("InGameSession: ${report.inGameSession}")
    println("UI keyword: ${report.uiHit}")
    println("Crash logcat: ${report.crashHit}")
    println("File log keyword: ${report.fileLogHit}")
    println("Session cookie metadata: ${report.sessionCookie}")
    println("UserId from log/UI: ${report.userId.ifBlank { "(not found)" }}")
    println("GameId/placeId from log/UI: ${report.gameId.ifBlank { "(not found)" }}")
}

data class TabEntry(val label: String, val packageName: String, val expectedPlaceId: String)

fun parseTabLine(line: String): TabEntry? {
    val clean = line.trim()
    if (clean.isBlank() || clean.startsWith("#")) return null
    val parts = clean.split("|", ",", " ", "\t").map { it.trim() }.filter { it.isNotBlank() }
    if (parts.isEmpty()) return null

    val packageIndex = parts.indexOfFirst { it.contains(".") && !it.all { ch -> ch.isDigit() } }
    if (packageIndex < 0) return null

    val pkg = parts[packageIndex]
    val placeId = parts.firstOrNull { it.all { ch -> ch.isDigit() } }.orEmpty()
    val label = parts.firstOrNull { it != pkg && it != placeId } ?: pkg
    return TabEntry(label, pkg, placeId)
}

fun loadTabsFromFile(props: Properties): List<TabEntry> {
    val defaultPlaceId = props.getProperty("place_id").orEmpty()
    val selectedPkgs = selectedPackages(props)

    // 1) Neu co selected_packages trong config -> luon dung no (uu tien config)
    if (selectedPkgs.isNotEmpty()) {
        val tabs =
                selectedPkgs.mapIndexed { index, pkg ->
                    TabEntry("tab${index + 1}", pkg, defaultPlaceId)
                }
        // Tu dong cap nhat output.txt de dong bo
        val targetFile = File(props.getProperty("tabs_file").orEmpty().ifBlank { "output.txt" })
        val content = buildString {
            appendLine("# label|package|placeId (auto-generated from selected_packages)")
            for (tab in tabs) {
                appendLine("${tab.label}|${tab.packageName}|${tab.expectedPlaceId}")
            }
        }
        targetFile.writeText(content)
        return tabs
    }

    // 2) Fallback: doc tu file tabs neu co
    val configuredFile = props.getProperty("tabs_file").orEmpty()
    val candidates = (listOf(configuredFile).filter { it.isNotBlank() } + tabFiles).distinct()
    val file = candidates.map { File(it) }.firstOrNull { it.exists() && it.isFile }
    if (file != null) {
        val tabs = file.readLines().mapNotNull { parseTabLine(it) }
        if (tabs.isNotEmpty()) return tabs
    }

    // 3) Fallback cuoi: scan packages + tao file mau
    val fallbackPackages = candidatePackages(props).ifEmpty { listOf("com.roblox.client") }

    val generatedTabs =
            fallbackPackages.mapIndexed { index, pkg ->
                TabEntry("tab${index + 1}", pkg, defaultPlaceId)
            }

    val targetFile = File(configuredFile.ifBlank { "output.txt" })
    val content = buildString {
        appendLine("# label|package|placeId")
        appendLine("# Sua placeId neu can, moi dong la mot app/tab.")
        for (tab in generatedTabs) {
            appendLine("${tab.label}|${tab.packageName}|${tab.expectedPlaceId}")
        }
    }
    targetFile.writeText(content)
    println("Da tao file tab mau: ${targetFile.path}")

    return generatedTabs
}

fun checkAllTabs(props: Properties) {
    val tabs = loadTabsFromFile(props)
    if (tabs.isEmpty()) {
        println("Khong co tab nao trong output.txt/tabs.txt/config.")
        return
    }

    println("=== Check Tabs ===")
    for ((index, tab) in tabs.withIndex()) {
        val report = checkStatus(tab.packageName)
        val installedOk = report.status != "NOT_INSTALLED"
        val runningOk = report.status == "FOREGROUND"
        val inGame = report.inGameSession
        val placeOk = tab.expectedPlaceId.isBlank() || report.gameId == tab.expectedPlaceId
        val userOk = report.userId.isNotBlank()

        println("[${index + 1}] ${tab.label} (${tab.packageName})")
        println("  ${okMark(installedOk)} installed/status: ${report.status}")
        println("  ${okMark(inGame)} inGameSession: $inGame")
        println("  ${okMark(report.sessionCookie)} session cookie metadata")
        println("  ${okMark(userOk)} userId: ${report.userId.ifBlank { "(not found)" }}")
        println(
                "  ${okMark(placeOk)} gameId/placeId: ${report.gameId.ifBlank { "(not found)" }} expected=${tab.expectedPlaceId.ifBlank { "(none)" }}"
        )
        println(
                "  ${okMark(runningOk)} running: pid=${report.pid.ifBlank { "(none)" }} focused=${report.focused}"
        )
        println()
    }
}

fun fetchCsrfToken(cookie: String): String? {
    if (cookie.isBlank()) return null
    val conn =
            java.net.URL("https://auth.roblox.com/v3/logout").openConnection() as
                    java.net.HttpURLConnection
    conn.requestMethod = "POST"
    conn.setRequestProperty("Cookie", ".ROBLOSECURITY=$cookie")
    conn.setRequestProperty("Content-Length", "0")
    try {
        conn.responseCode
        return conn.getHeaderField("x-csrf-token")
    } catch (e: Exception) {
        return null
    } finally {
        conn.disconnect()
    }
}

fun fetchAuthTicket(cookie: String, csrfToken: String, placeId: String): String? {
    if (cookie.isBlank() || csrfToken.isBlank()) return null
    val conn =
            java.net.URL("https://auth.roblox.com/v1/authentication-ticket").openConnection() as
                    java.net.HttpURLConnection
    conn.requestMethod = "POST"
    conn.doOutput = true
    conn.setRequestProperty("Cookie", ".ROBLOSECURITY=$cookie")
    conn.setRequestProperty("X-CSRF-TOKEN", csrfToken)
    conn.setRequestProperty("Referer", "https://www.roblox.com/games/$placeId/")
    conn.setRequestProperty("RBXAuthenticationNegotiation", "1")
    conn.setRequestProperty("Content-Type", "application/json")

    try {
        conn.outputStream.use { it.write("{}".toByteArray()) }
        val code = conn.responseCode
        if (code in 200..299) {
            return conn.getHeaderField("rbx-authentication-ticket")
        }
    } catch (e: Exception) {} finally {
        conn.disconnect()
    }
    return null
}

fun sendWebhook(
        url: String,
        title: String,
        description: String,
        color: Int = 0x3498DB,
        fields: String = "[]"
) {
    if (url.isBlank()) return
    try {
        val conn = java.net.URL(url).openConnection() as java.net.HttpURLConnection
        conn.requestMethod = "POST"
        conn.doOutput = true
        conn.setRequestProperty("Content-Type", "application/json")
        val safeTitle = title.replace("\"", "\\\"").replace("\n", "\\n")
        val safeDesc = description.replace("\"", "\\\"").replace("\n", "\\n")
        val json =
                """
            {
              "embeds": [{
                "title": "$safeTitle",
                "description": "$safeDesc",
                "color": $color,
                "fields": $fields
              }]
            }
        """.trimIndent()
        conn.outputStream.use { it.write(json.toByteArray()) }
        conn.inputStream.use { it.readBytes() }
    } catch (_: Exception) {}
}

// --- ROBLOX PRESENCE API: check trang thai user tren server ---
// Return: 0=Offline, 1=Online(Web), 2=InGame, 3=InStudio, null=error
fun fetchPresenceType(userId: String): Int? {
    if (userId.isBlank()) return null
    try {
        val conn =
                java.net.URL("https://presence.roblox.com/v1/presence/users").openConnection() as
                        java.net.HttpURLConnection
        conn.requestMethod = "POST"
        conn.doOutput = true
        conn.setRequestProperty("Content-Type", "application/json")
        conn.connectTimeout = 5000
        conn.readTimeout = 5000
        conn.outputStream.use { it.write("""{"userIds":[$userId]}""".toByteArray()) }
        if (conn.responseCode == 200) {
            val resp = conn.inputStream.bufferedReader().readText()
            val match = Regex(""""userPresenceType"\s*:\s*(\d+)""").find(resp)
            return match?.groupValues?.get(1)?.toIntOrNull()
        }
        conn.disconnect()
    } catch (_: Exception) {}
    return null
}

// --- ROBLOX USER API: lay username tu userId ---
fun fetchUsername(userId: String): String? {
    if (userId.isBlank()) return null
    try {
        val conn =
                java.net.URL("https://users.roblox.com/v1/users/$userId").openConnection() as
                        java.net.HttpURLConnection
        conn.requestMethod = "GET"
        conn.connectTimeout = 5000
        conn.readTimeout = 5000
        if (conn.responseCode == 200) {
            val resp = conn.inputStream.bufferedReader().readText()
            val match = Regex(""""name"\s*:\s*"([^"]+)""").find(resp)
            return match?.groupValues?.get(1)
        }
        conn.disconnect()
    } catch (_: Exception) {}
    return null
}

fun ensureLuaKickDetector(pkg: String) {
    // Tim thu muc Autoexecute cua Delta
    val autoExecPaths =
            listOf("/sdcard/Delta/Autoexecute", "/sdcard/Android/data/$pkg/files/delta/autoexec")
    val autoExecDir =
            autoExecPaths.firstOrNull { path ->
                rootOut("test -d $path && echo yes").trim() == "yes"
            }
    if (autoExecDir == null) {
        // Khong co Delta, bo qua
        return
    }

    // V6: Per-package script name + per-package heartbeat file
    val hbFile = heartbeatFileForPkg(pkg)
    val sanitizedPkg = pkg.replace(".", "_")
    val scriptPath = "$autoExecDir/kick_detect_${sanitizedPkg}.lua"
    val currentVersion = "VERSION:6:$sanitizedPkg"

    // Check version: chi update khi file chua ton tai hoac version cu
    val existingContent = rootOut("head -5 $scriptPath 2>/dev/null").trim()
    if (existingContent.contains(currentVersion)) {
        println("${GRN}-> kick_detect V6 ($pkg) da ton tai, bo qua deploy.${R}")
        return
    }

    // Xoa script cu (V5 generic) de tranh conflict
    root("rm -f $autoExecDir/kick_detect.lua 2>/dev/null")

    println("${GRN}-> Deploy kick_detect V6 (PER-PKG HEARTBEAT) cho $pkg vao $autoExecDir${R}")
    val luaScript =
            """-- kick_detect.lua V6 - Per-package heartbeat + AUTO REJOIN
-- Tu dong tao boi rejoin tool
-- VERSION:6:${sanitizedPkg}
-- Package: $pkg

local HEARTBEAT_FILE = "$hbFile"
local HEARTBEAT_INTERVAL = 5
local kicked = false
local gameLoaded = false
local REJOIN_DELAY = 2

local placeId = game.PlaceId
local jobId = game.JobId

local function doRejoin()
    pcall(function()
        writefile(HEARTBEAT_FILE, "rejoining:" .. tostring(os.time()) .. ":" .. tostring(placeId) .. ":" .. tostring(jobId))
    end)
    task.wait(REJOIN_DELAY)
    pcall(function()
        local TPS = game:GetService("TeleportService")
        TPS:Teleport(placeId, game:GetService("Players").LocalPlayer)
    end)
    task.wait(5)
    pcall(function()
        local TPS = game:GetService("TeleportService")
        if jobId and jobId ~= "" then
            TPS:TeleportToPlaceInstance(placeId, jobId, game:GetService("Players").LocalPlayer)
        else
            TPS:Teleport(placeId, game:GetService("Players").LocalPlayer)
        end
    end)
end

local function writeKick(reason)
    if kicked then return end
    kicked = true
    pcall(function()
        writefile(HEARTBEAT_FILE, "kicked:" .. tostring(os.time()) .. ":" .. tostring(reason))
    end)
    task.spawn(function()
        doRejoin()
    end)
end

-- Heartbeat loop: V6 - 5s interval
task.spawn(function()
    while task.wait(HEARTBEAT_INTERVAL) do
        if kicked then break end
        pcall(function()
            local status = gameLoaded and "ingame" or "loading"
            writefile(HEARTBEAT_FILE, "alive:" .. tostring(os.time()) .. ":" .. status .. ":" .. tostring(placeId) .. ":" .. tostring(jobId))
        end)
    end
end)

-- Wait for game to fully load
task.spawn(function()
    pcall(function()
        if not game:IsLoaded() then
            game.Loaded:Wait()
        end
        task.wait(3)
        gameLoaded = true
        pcall(function()
            writefile(HEARTBEAT_FILE, "alive:" .. tostring(os.time()) .. ":ingame:" .. tostring(placeId) .. ":" .. tostring(jobId))
        end)
    end)
end)

-- 1. ErrorMessageChanged
pcall(function()
    game:GetService("GuiService").ErrorMessageChanged:Connect(function(msg)
        if msg and msg ~= "" then writeKick("ErrorMessage:" .. msg) end
    end)
end)

-- 2. NetworkClient.ConnectionFailed
pcall(function()
    local nc = game:FindFirstChildOfClass("NetworkClient")
    if nc then
        nc.ConnectionFailed:Connect(function(peer, code, reason)
            writeKick("ConnectionFailed:" .. tostring(reason))
        end)
    end
end)

-- 3. LocalPlayer removed
pcall(function()
    local lp = game:GetService("Players").LocalPlayer
    if lp then
        lp.AncestryChanged:Connect(function(_, parent)
            if parent == nil then writeKick("PlayerRemoved") end
        end)
    end
end)

-- 4. CoreGui error prompt
pcall(function()
    local cg = game:GetService("CoreGui")
    local rpg = cg:FindFirstChild("RobloxPromptGui")
    if rpg then
        rpg.DescendantAdded:Connect(function(desc)
            if desc:IsA("TextLabel") then
                task.wait(0.3)
                local t = (desc.Text or ""):lower()
                if t:find("kicked") or t:find("disconnect") or t:find("error") or t:find("lost connection") or t:find("same account") then
                    writeKick("CoreGui:" .. desc.Text:sub(1,100))
                end
            end
        end)
    end
end)

-- 5. Teleport failure
pcall(function()
    game:GetService("TeleportService").TeleportInitFailed:Connect(function(p, r, msg)
        writeKick("TeleportFailed:" .. tostring(msg))
    end)
end)

-- 6. NetworkClient gone (polling)
task.spawn(function()
    task.wait(20)
    while not kicked do
        pcall(function()
            if game:FindFirstChildOfClass("NetworkClient") == nil then
                writeKick("NetworkClientGone")
            end
        end)
        task.wait(3)
    end
end)

pcall(function()
    local status = gameLoaded and "ingame" or "loading"
    writefile(HEARTBEAT_FILE, "alive:" .. tostring(os.time()) .. ":" .. status .. ":" .. tostring(placeId) .. ":" .. tostring(jobId))
end)
"""

    // Ghi file bang echo + su
    val tmpPath = "/sdcard/.kick_detect_tmp.lua"
    root("cat > $tmpPath << 'LUAEOF'\n$luaScript\nLUAEOF")
    root("cp $tmpPath $scriptPath")
    root("rm -f $tmpPath")

    // Verify
    val ok = rootOut("test -f $scriptPath && echo yes").trim() == "yes"
    println(
            if (ok) "${GRN}-> kick_detect V6 ($pkg) da deploy thanh cong!${R}"
            else "${RED}-> FAIL deploy kick_detect V6 ($pkg)${R}"
    )
}

// === VIP SERVER LINK SUPPORT ===

fun parseVipLink(url: String): String? {
    // Parse: https://www.roblox.com/share?code=XXX&type=Server
    // Or direct code: 166171aa1ea1834eaf91a87bb7d9d551
    val codeRegex = Regex("[?&]code=([a-zA-Z0-9]+)")
    val match = codeRegex.find(url)
    if (match != null) return match.groupValues[1]
    // If it's just a raw code (hex string)
    if (url.matches(Regex("^[a-zA-Z0-9]{16,}$"))) return url
    return null
}

fun startVipServer(pkg: String, shareCode: String, cfgProps: Properties = loadConfig()) {
    ensureLuaKickDetector(pkg)

    // Clear old lua status
    clearHeartbeatForPkg(pkg)

    // LUON force-stop truoc de deeplink hoat dong (fix rejoin bug)
    root("am force-stop $pkg")
    Thread.sleep(2000)

    root("rm -rf /sdcard/Android/data/$pkg/files/logs/* /data/data/$pkg/cache/*")

    // VIP server deeplink
    val link = "roblox://navigation/share_links?code=$shareCode&type=Server"
    println("-> [VIP] Share link deeplink: code=${shareCode.take(12)}...")
    val res =
            root(
                    "am start --activity-clear-task -a android.intent.action.VIEW -d ${shellQuote(link)} -p $pkg"
            )
    if (res.success) {
        val autoBypass = cfgProps.getProperty("auto_bypass_key", "false").toBoolean()
        if (autoBypass) {
            deltaAutoBypassFlow(pkg, cfgProps)
        }
        println("=== XONG: Da gui VIP Server Rejoin cho $pkg ===")
    } else {
        println("${RED}VIP deeplink failed, fallback to normal startPlace${R}")
    }
}

// === PER-PACKAGE CONFIG HELPERS ===

fun getPlaceIdForPackage(pkg: String, props: Properties): String {
    // Priority: per-package > global > empty
    return props.getProperty("place_id.$pkg").orEmpty().ifBlank {
        props.getProperty("place_id", "")
    }
}

fun getVipCodeForPackage(pkg: String, props: Properties): String {
    // Priority: per-package > global > empty
    return props.getProperty("vip_server_code.$pkg").orEmpty().ifBlank {
        props.getProperty("vip_server_code", "")
    }
}

// === DELTA KEY BYPASS SYSTEM ===

// === PIXEL CHECK HELPERS FOR DELTA BYPASS (AUTO-SCAN, SPLIT-SCREEN SAFE) ===
data class RGBA(val r: Int, val g: Int, val b: Int, val a: Int)

fun getScreenRes(): Pair<Int, Int> {
    val wm = rootOut("wm size")
    val sz = Regex("""(\d+)x(\d+)""").findAll(wm).lastOrNull()
    val pw = sz?.groupValues?.get(1)?.toInt() ?: 1080
    val ph = sz?.groupValues?.get(2)?.toInt() ?: 2220
    val rot = Regex("""(\d)""").find(rootOut("dumpsys input | grep SurfaceOrientation | head -1"))
        ?.groupValues?.get(1)?.toIntOrNull() ?: 0
    val land = rot == 1 || rot == 3
    val w = if (land) maxOf(pw, ph) else minOf(pw, ph)
    val h = if (land) minOf(pw, ph) else maxOf(pw, ph)
    return Pair(w, h)
}

// Fallback % positions (chi dung khi scan that bai)
fun btnContinue(w: Int, h: Int) = Pair(w * 876 / 1000, h * 580 / 1000)
fun btnReceiveKey(w: Int, h: Int) = Pair(w * 876 / 1000, h * 648 / 1000)
fun btnEnterKey(w: Int, h: Int) = Pair(w * 876 / 1000, h * 426 / 1000)

fun screencapRaw(rawPath: String = "/sdcard/delta_check.raw"): Pair<Int, Int> {
    root("screencap $rawPath")
    val f = java.io.File(rawPath)
    if (!f.exists()) return Pair(0, 0)
    return try {
        val raf = java.io.RandomAccessFile(f, "r")
        fun readInt32(): Int {
            val b0 = raf.read(); val b1 = raf.read()
            val b2 = raf.read(); val b3 = raf.read()
            return b0 or (b1 shl 8) or (b2 shl 16) or (b3 shl 24)
        }
        val w = readInt32(); val h = readInt32()
        raf.close()
        Pair(w, h)
    } catch (e: Exception) { Pair(0, 0) }
}

fun getPixel(rawPath: String, x: Int, y: Int, imgW: Int): RGBA {
    val f = java.io.File(rawPath)
    if (!f.exists() || imgW <= 0) return RGBA(0,0,0,0)
    return try {
        val raf = java.io.RandomAccessFile(f, "r")
        val offset = 12L + (y.toLong() * imgW + x) * 4
        if (offset < raf.length() - 4) {
            raf.seek(offset)
            val r = raf.read(); val g = raf.read(); val b = raf.read(); val a = raf.read()
            raf.close()
            RGBA(r, g, b, a)
        } else { raf.close(); RGBA(0,0,0,0) }
    } catch(e: Exception) { RGBA(0,0,0,0) }
}

// === AUTO-SCAN: Quet toan bo screenshot tim nut Delta (ho tro split-screen) ===
// Tra ve Triple(continueXY, enterKeyXY, receiveKeyXY) hoac null neu khong tim thay
data class DeltaButtons(val contX: Int, val contY: Int, val enterX: Int, val enterY: Int, val recvX: Int, val recvY: Int)

// Lay bounds cua so cua package cu the tu dumpsys window
// Tra ve (left, top, right, bottom) hoac null
data class WinBounds(val left: Int, val top: Int, val right: Int, val bottom: Int)

fun getWindowBounds(pkg: String): WinBounds? {
    // Tim ActivityNativeMain window (cua so game chinh)
    val dump = rootOut("dumpsys window windows")
    // Tim dong chua package + ActivityNativeMain
    val lines = dump.lines()
    for (i in lines.indices) {
        if (lines[i].contains("$pkg/com.roblox.client.ActivityNativeMain") && lines[i].contains("Window #")) {
            // Tim mFrame trong 5 dong tiep theo
            for (j in i+1 until minOf(i+15, lines.size)) {
                val m = Regex("""\bmFrame=\[(\d+),(\d+)\]\[(\d+),(\d+)\]""").find(lines[j])
                if (m != null) {
                    val l = m.groupValues[1].toInt()
                    val t = m.groupValues[2].toInt()
                    val r = m.groupValues[3].toInt()
                    val b = m.groupValues[4].toInt()
                    if (r - l > 100 && b - t > 100) { // Cua so phai lon hon 100x100
                        return WinBounds(l, t, r, b)
                    }
                }
            }
        }
    }
    // Fallback: tim bat ky window nao cua package co kich thuoc lon
    for (i in lines.indices) {
        if (lines[i].contains(pkg) && lines[i].contains("Window #")) {
            for (j in i+1 until minOf(i+15, lines.size)) {
                val m = Regex("""\bmFrame=\[(\d+),(\d+)\]\[(\d+),(\d+)\]""").find(lines[j])
                if (m != null) {
                    val l = m.groupValues[1].toInt()
                    val t = m.groupValues[2].toInt()
                    val r = m.groupValues[3].toInt()
                    val b = m.groupValues[4].toInt()
                    if (r - l > 300 && b - t > 300) {
                        return WinBounds(l, t, r, b)
                    }
                }
            }
        }
    }
    return null
}

// Lay bounds CHINH XAC cua surfaceview (game content, KHONG BAO GOM title bar)
// Su dung uiautomator dump de lay bounds cua surfaceview
fun getSurfaceViewBounds(pkg: String): WinBounds? {
    return try {
        val xml = rootOut("uiautomator dump /dev/tty 2>/dev/null")
        // Tim surfaceview cua package
        val pattern = Regex("""resource-id="$pkg:id/surfaceview"[^>]*?bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]""")
        val m = pattern.find(xml)
        if (m != null) {
            val l = m.groupValues[1].toInt(); val t = m.groupValues[2].toInt()
            val r = m.groupValues[3].toInt(); val b = m.groupValues[4].toInt()
            if (r - l > 100 && b - t > 100) {
                println("${DIM}    [BOUNDS] SurfaceView $pkg: [$l,$t][$r,$b] (${r-l}x${b-t})${R}")
                return WinBounds(l, t, r, b)
            }
        }
        // Fallback: bounds co the nam truoc resource-id
        val pattern2 = Regex("""bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"[^>]*?resource-id="$pkg:id/surfaceview""")
        val m2 = pattern2.find(xml)
        if (m2 != null) {
            val l = m2.groupValues[1].toInt(); val t = m2.groupValues[2].toInt()
            val r = m2.groupValues[3].toInt(); val b = m2.groupValues[4].toInt()
            if (r - l > 100 && b - t > 100) {
                println("${DIM}    [BOUNDS] SurfaceView $pkg: [$l,$t][$r,$b] (${r-l}x${b-t})${R}")
                return WinBounds(l, t, r, b)
            }
        }
        // Fallback2: tim bat ky surfaceview nao cua zam.delt hoac roblox
        val pattern3 = Regex("""package="$pkg"[^>]*?bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"[^>]*?resource-id="[^"]*surfaceview""")
        val m3 = pattern3.find(xml)
        if (m3 != null) {
            val l = m3.groupValues[1].toInt(); val t = m3.groupValues[2].toInt()
            val r = m3.groupValues[3].toInt(); val b = m3.groupValues[4].toInt()
            if (r - l > 100 && b - t > 100) {
                println("${DIM}    [BOUNDS] SurfaceView (pkg match) $pkg: [$l,$t][$r,$b]${R}")
                return WinBounds(l, t, r, b)
            }
        }
        null
    } catch (e: Exception) {
        println("${DIM}    [BOUNDS] uiautomator failed: ${e.message}${R}")
        null
    }
}

// Lay bounds game content: uu tien surfaceview, fallback mFrame - title bar offset
fun getGameContentBounds(pkg: String): WinBounds? {
    // Uu tien: surfaceview (chinh xac, khong bao gom title bar)
    val sv = getSurfaceViewBounds(pkg)
    if (sv != null) return sv
    // Fallback: mFrame - tru di title bar height (~56px tren freeform LDPlayer)
    val mf = getWindowBounds(pkg) ?: return null
    val titleBarHeight = 56 // Freeform window title bar = ~56px
    val adjustedTop = mf.top + titleBarHeight
    if (mf.bottom - adjustedTop > 100) {
        println("${DIM}    [BOUNDS] mFrame fallback (adjusted -${titleBarHeight}px title): [${mf.left},$adjustedTop][${mf.right},${mf.bottom}]${R}")
        return WinBounds(mf.left, adjustedTop, mf.right, mf.bottom)
    }
    return mf
}

fun scanDeltaButtons(pkg: String = "", rawPath: String = "/sdcard/delta_check.raw"): DeltaButtons? {
    root("screencap $rawPath")
    val f = java.io.File(rawPath)
    if (!f.exists() || f.length() < 16) return null
    
    val bounds = if (pkg.isNotBlank()) getGameContentBounds(pkg) else null
    if (bounds != null) {
        println("${DIM}    [SCAN] Game bounds $pkg: [${bounds.left},${bounds.top}][${bounds.right},${bounds.bottom}] (${bounds.right-bounds.left}x${bounds.bottom-bounds.top})${R}")
    }
    
    // === METHOD 1: OCR voi tesseract (chinh xac nhat) ===
    val ocrResult = scanDeltaButtonsOCR(pkg, bounds)
    if (ocrResult != null) return ocrResult
    
    // === METHOD 2: Pixel scan (fallback) ===
    return scanDeltaButtonsPixel(rawPath, bounds)
}

// OCR scan: dung tesseract de tim text "Continue", "Receive", "Enter" trong screenshot
fun scanDeltaButtonsOCR(pkg: String, bounds: WinBounds?): DeltaButtons? {
    return try {
        val tesseract = "/data/data/com.termux/files/usr/bin/tesseract"
        if (!java.io.File(tesseract).exists()) {
            println("${DIM}    [OCR] tesseract not installed, skip OCR${R}")
            return null
        }
        
        // Screenshot PNG
        root("screencap -p /sdcard/delta_ocr.png")
        
        // Crop vao surfaceview bounds (neu co) de tang do chinh xac
        val cropPng = if (bounds != null) {
            val w = bounds.right - bounds.left; val h = bounds.bottom - bounds.top
            val convert = "/data/data/com.termux/files/usr/bin/convert"
            if (java.io.File(convert).exists()) {
                root("$convert /sdcard/delta_ocr.png -crop ${w}x${h}+${bounds.left}+${bounds.top} /sdcard/delta_crop.png")
                "/sdcard/delta_crop.png"
            } else {
                "/sdcard/delta_ocr.png"
            }
        } else "/sdcard/delta_ocr.png"
        
        // Chay tesseract voi TSV output de lay vi tri text
        val tsvPath = "/sdcard/delta_ocr_out"
        root("PATH=/data/data/com.termux/files/usr/bin:${'$'}PATH LD_LIBRARY_PATH=/data/data/com.termux/files/usr/lib $tesseract $cropPng $tsvPath --psm 6 tsv 2>/dev/null")
        
        val tsvFile = java.io.File("${tsvPath}.tsv")
        if (!tsvFile.exists()) {
            println("${DIM}    [OCR] tesseract output not found${R}")
            return null
        }
        
        val tsv = tsvFile.readText()
        println("${DIM}    [OCR] TSV lines: ${tsv.lines().size}${R}")
        
        // Parse TSV: columns = level conf text left top width height
        // Tim "Continue", "Receive", "Key", "Enter"
        var contX = -1; var contY = -1
        var recvX = -1; var recvY = -1
        var enterX = -1; var enterY = -1
        
        val offsetX = bounds?.left ?: 0
        val offsetY = bounds?.top ?: 0
        
        for (line in tsv.lines()) {
            val parts = line.split("\t")
            if (parts.size < 12) continue
            val text = parts[11].trim()
            if (text.isBlank()) continue
            
            val left = parts[6].toIntOrNull() ?: continue
            val top = parts[7].toIntOrNull() ?: continue
            val w = parts[8].toIntOrNull() ?: continue
            val h = parts[9].toIntOrNull() ?: continue
            val cx = offsetX + left + w / 2
            val cy = offsetY + top + h / 2
            
            when {
                text.contains("Continue", ignoreCase = true) -> { contX = cx; contY = cy }
                text.contains("Receive", ignoreCase = true) -> { recvX = cx; recvY = cy }
                text.contains("Enter", ignoreCase = true) && enterX < 0 -> { enterX = cx; enterY = cy }
            }
        }
        
        if (contX > 0 && recvX > 0) {
            // Dung center X cua Continue cho tat ca (cung cot)
            if (enterX < 0) { enterX = contX; enterY = contY - 40 }
            println("${DIM}    [OCR] FOUND: Enter($enterX,$enterY) Cont($contX,$contY) Recv($recvX,$recvY)${R}")
            return DeltaButtons(contX, contY, enterX, enterY, recvX, recvY)
        }
        
        println("${DIM}    [OCR] Text not found (cont=$contX recv=$recvX enter=$enterX)${R}")
        null
    } catch (e: Exception) {
        println("${DIM}    [OCR] Error: ${e.message}${R}")
        null
    }
}

// Pixel scan: tim dark panel roi tinh proportional positions
fun scanDeltaButtonsPixel(rawPath: String, bounds: WinBounds?): DeltaButtons? {
    return try {
        val f = java.io.File(rawPath)
        val bytes = f.readBytes()
        if (bytes.size < 16) return null
        fun i32(off: Int): Int = (bytes[off].toInt() and 0xFF) or
            ((bytes[off+1].toInt() and 0xFF) shl 8) or
            ((bytes[off+2].toInt() and 0xFF) shl 16) or
            ((bytes[off+3].toInt() and 0xFF) shl 24)
        val imgW = i32(0); val imgH = i32(4)
        if (imgW <= 0 || imgH <= 0) return null
        val dataOff = 12
        fun px(x: Int, y: Int): Triple<Int, Int, Int> {
            if (x < 0 || y < 0 || x >= imgW || y >= imgH) return Triple(0, 0, 0)
            val off = dataOff + (y * imgW + x) * 4
            if (off + 3 >= bytes.size) return Triple(0, 0, 0)
            return Triple(bytes[off].toInt() and 0xFF, bytes[off+1].toInt() and 0xFF, bytes[off+2].toInt() and 0xFF)
        }

        val sL = bounds?.left ?: 0
        val sT = bounds?.top ?: 0
        val sR = minOf(bounds?.right ?: imgW, imgW)
        val sB = minOf(bounds?.bottom ?: imgH, imgH)
        val step = 2

        // === STRATEGY: Tim Continue button bang TEAL BORDER cluster ===
        // Continue button co vien teal (B-R > 35, G > 130) tao thanh vung rong >80px
        // Tim Y co nhieu teal nhat + width > 80px
        var bestY = -1; var bestCount = 0; var bestCX = -1
        for (y in sT until sB step step) {
            var tealCount = 0; var tealMinX = imgW; var tealMaxX = 0
            for (x in sL until sR step step) {
                val (r, g, b) = px(x, y)
                if (b - r > 35 && g > 130) {
                    tealCount++
                    if (x < tealMinX) tealMinX = x
                    if (x > tealMaxX) tealMaxX = x
                }
            }
            val tealW = tealMaxX - tealMinX
            // Continue button: teal band rong 80-300px, count > 30
            if (tealCount > 30 && tealW in 80..350) {
                // Kiem tra co phai la button (khong phai title bar)
                // Button co dark background (~30px above)
                val cx = (tealMinX + tealMaxX) / 2
                val (abR, abG, abB) = px(cx, y - 40)
                val hasDarkAbove = abR < 50 && abG < 55
                if (hasDarkAbove && tealCount > bestCount) {
                    bestCount = tealCount
                    bestY = y
                    bestCX = cx
                }
            }
        }

        if (bestY < 0 || bestCX < 0) {
            println("${DIM}    [PIXEL] Continue teal border NOT found${R}")
            return null
        }
        
        // Tim full range cua Continue button (Y range voi teal count > 20)
        var contTop = bestY; var contBot = bestY
        for (y in bestY downTo sT step step) {
            var tc = 0
            for (x in sL until sR step step) {
                val (r, g, b) = px(x, y)
                if (b - r > 35 && g > 130) tc++
            }
            if (tc < 20) break
            contTop = y
        }
        for (y in bestY until sB step step) {
            var tc = 0
            for (x in sL until sR step step) {
                val (r, g, b) = px(x, y)
                if (b - r > 35 && g > 130) tc++
            }
            if (tc < 20) break
            contBot = y
        }
        
        val contY = (contTop + contBot) / 2
        val contH = contBot - contTop
        println("${DIM}    [PIXEL] Continue: Y=$contTop-$contBot (h=$contH, center=$contY, cx=$bestCX, peak=$bestCount)${R}")
        
        // Enter Key textbox: ngay tren Continue button
        // Textbox border cung co teal nhe, o ~contH phia tren
        val enterKeyY = contTop - contH * 60 / 100  // Center of textbox
        
        // Receive Key: ngay duoi Continue button
        // Tim bang cach scan teal cluster tiep theo phia duoi
        var recvY = -1
        for (y in contBot + 10 until sB step step) {
            val (r, g, b) = px(bestCX, y)
            // Receive Key text: bright on dark bg, or has golden/brown tone
            if (r > 80 && g > 80 && (r + g + b) > 250) {
                // Check pixel phia tren la dark (gap giua Continue va Receive)
                val (ar, ag, _) = px(bestCX, y - 8)
                if (ar < 65 && ag < 70) {
                    recvY = y
                    break
                }
            }
        }
        if (recvY < 0) {
            recvY = contBot + contH * 40 / 100
            println("${DIM}    [PIXEL] ReceiveKey fallback Y=$recvY${R}")
        }

        println("${DIM}    [PIXEL] RESULT: Enter($bestCX,$enterKeyY) Cont($bestCX,$contY) Recv($bestCX,$recvY)${R}")
        DeltaButtons(bestCX, contY, bestCX, enterKeyY, bestCX, recvY)
    } catch (e: Exception) {
        println("${RED}    [PIXEL] Error: ${e.message}${R}")
        null
    }
}

// === isWelcomeBackVisible: dung scanDeltaButtons voi pkg de chi scan trong cua so game ===
fun isWelcomeBackVisible(pkg: String = ""): Boolean {
    val btns = scanDeltaButtons(pkg)
    if (btns != null) {
        println("${DIM}    [SCAN] Panel Delta DETECTED! Continue(${btns.contX},${btns.contY}) Enter(${btns.enterX},${btns.enterY}) Recv(${btns.recvX},${btns.recvY})${R}")
        return true
    }
    return false
}

fun waitForWelcomeBack(maxWait: Int = 60, pkg: String = ""): Boolean {
    println("${CYN}-> [AUTO BYPASS] Doi 'Welcome Back' panel hien len (max ${maxWait}s)...${R}")
    val start = System.currentTimeMillis()
    while (System.currentTimeMillis() - start < maxWait * 1000L) {
        if (isWelcomeBackVisible(pkg)) return true
        Thread.sleep(5000)
    }
    return false
}

fun getLinkFromActivityOrLogcat(): String? {
    val dump = rootOut("dumpsys activity activities | grep -i 'dat=http' | grep -iE 'platorelay|platoboost|lootlabs' | tail -1")
    val m = Regex("""dat=(https?://[^\s}]+)""").find(dump)
    if (m != null) return m.groupValues[1]
    
    val log = rootOut("logcat -d -t 500 | grep -iE 'platorelay|platoboost|lootlabs' | tail -3").replace("\\u003d", "=").replace("\\u0026", "&")
    Regex("""https://auth\.platorelay\.com/a\?d[=\\u003d]([A-Za-z0-9+/=_-]+)""").find(log)?.let { return "https://auth.platorelay.com/a?d=${it.groupValues[1]}" }
    Regex("""https://gateway\.platoboost\.com/[^\s"'\\]+""").find(log)?.let { return it.value }
    Regex("""https://lootlabs\.gg/[^\s"'\\]+""").find(log)?.let { return it.value }
    return null
}

fun bypassDeltaKey(props: Properties = loadConfig(), pkg: String = "com.roblox.client"): String? {
    var keyUrl = props.getProperty("delta_key_url", "").trim()
    val bypassServerUrl = props.getProperty("bypass_server_url", "https://a312b723544c-16430169196050188809.ngrok-free.app/bypass?url=").trim()

    if (keyUrl.isBlank()) {
        println("${RED}--> [BYPASS] delta_key_url chua set, khong the bypass.${R}")
        return null
    }

    println("${CYN}--> [BYPASS] Dang bypass Delta key...${R}")
    println("${DIM}    URL: ${keyUrl.take(80)}...${R}")
    println("${DIM}    Server: $bypassServerUrl${R}")

    try {
        val encodedUrl = java.net.URLEncoder.encode(keyUrl, "UTF-8")
        val fullUrl = "$bypassServerUrl$encodedUrl"
        val conn = java.net.URL(fullUrl).openConnection() as java.net.HttpURLConnection
        conn.requestMethod = "GET"
        conn.connectTimeout = 15000
        conn.readTimeout = 120000 // Bypass can 60-120s
        conn.setRequestProperty("User-Agent", "Mozilla/5.0")
        conn.setRequestProperty("ngrok-skip-browser-warning", "true")

        val responseCode = conn.responseCode
        if (responseCode != 200) {
            println("${RED}--> [BYPASS] Server tra ve HTTP $responseCode${R}")
            conn.disconnect()
            return null
        }

        val body = conn.inputStream.bufferedReader().readText().trim()
        conn.disconnect()

        println("${DIM}    Response: ${body.take(200)}${R}")

        var key: String? = null
        val keyMatch = Regex(""""key"\s*:\s*"([^"]+)"""").find(body)
        if (keyMatch != null) {
            key = keyMatch.groupValues[1]
        } else if (body.startsWith("FREE_") || body.startsWith("KEY_") || body.length in 20..100) {
            key = body.lines().firstOrNull()?.trim()
        } else if (body.startsWith("{")) {
            val resMatch = Regex("""["']?(?:key|result|data)["']?\s*[:=]\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE).find(body)
            if (resMatch != null) key = resMatch.groupValues[1]
        }

        if (key.isNullOrBlank()) {
            println("${RED}--> [BYPASS] Khong parse duoc key tu response.${R}")
            return null
        }

        println("${GRN}--> [BYPASS] Da lay duoc key: ${key.take(40)}...${R}")
        return key
    } catch (e: Exception) {
        println("${RED}--> [BYPASS] Loi: ${e.message}${R}")
        return null
    }
}

fun deltaAutoBypassFlow(pkg: String, props: Properties) {
    // === STEP 0: Bring app to foreground ===
    println("${CYN}-> [AUTO BYPASS] Bring $pkg to foreground...${R}")
    root("am start -n $pkg/com.roblox.client.ActivityNativeMain 2>/dev/null")
    Thread.sleep(3000)
    
    // Doi game load truoc khi bat dau scan
    println("${CYN}-> [AUTO BYPASS] Doi 12s cho game load truoc khi scan...${R}")
    Thread.sleep(12000)
    
    if (!waitForWelcomeBack(60, pkg)) {
        println("${YEL}-> [AUTO BYPASS] Khong thay bang Delta, bo qua bypass.${R}")
        return
    }
    
    println("${GRN}-> [AUTO BYPASS] Phat hien bang Delta! Bat dau tu dong lay link...${R}")
    
    // === STEP 1: Xac dinh toa do cac nut ===
    // Strategy: scanDeltaButtons (pixel) -> window bounds fallback -> screen fallback
    val btns = scanDeltaButtons(pkg)
    val rX: Int; val rY: Int; val eX: Int; val eY: Int; val cX: Int; val cY: Int
    var dismissX = 400; var dismissY = 400 // Default dismiss keyboard
    
    if (btns != null) {
        rX = btns.recvX; rY = btns.recvY
        eX = btns.enterX; eY = btns.enterY
        cX = btns.contX; cY = btns.contY
        // Dismiss keyboard: tap vao game area (phia trai cua so, giua Y)
        val bounds = getWindowBounds(pkg)
        if (bounds != null) {
            dismissX = bounds.left + (bounds.right - bounds.left) / 4
            dismissY = bounds.top + (bounds.bottom - bounds.top) / 2
        }
        println("${CYN}-> [AUTO BYPASS] SCAN OK: Recv($rX,$rY) Enter($eX,$eY) Cont($cX,$cY)${R}")
    } else {
        // Fallback: tinh toa do tu GAME CONTENT bounds (surfaceview, khong bao gom title bar)
        val bounds = getGameContentBounds(pkg)
        if (bounds != null) {
            val winW = bounds.right - bounds.left
            val winH = bounds.bottom - bounds.top
            println("${YEL}-> [AUTO BYPASS] Game content bounds: [${bounds.left},${bounds.top}][${bounds.right},${bounds.bottom}] = ${winW}x${winH}${R}")
            
            // Delta panel nam ben phai cua so game
            // Panel chiem khoang 30% width ben phai, centered theo Y
            // Toa do TUONG DOI trong cua so game (da do tu screenshot thuc te):
            //   - Panel center X: khoang 65-70% width cua window
            //   - Enter Key textbox: Y khoang 25% height cua window (tu top cua game render, khong tinh title)
            //   - Continue button:   Y khoang 33% height
            //   - Receive Key:       Y khoang 40% height
            // Nhung voi freeform window nho (611x648), panel scale khac:
            //   - X center cua panel: khoang 15-16% tu right edge = 84-85% tu left
            //   - Enter Key Y: khoang 16% H
            //   - Continue Y: khoang 25% H  
            //   - Receive Key Y: khoang 33% H
            
            // Dung proportional calculation co dieu chinh theo kich thuoc cua so
            val isSmallWindow = winW < 800 || winH < 800
            if (isSmallWindow) {
                // Cua so nho (freeform): da do pixel chinh xac tu ADB debug grid scan
                // Panel center X = 53% width, buttons Y = 21/30/34% height
                val pcX = bounds.left + winW * 53 / 100
                eX = pcX; eY = bounds.top + winH * 21 / 100   // Enter Key textbox
                cX = pcX; cY = bounds.top + winH * 30 / 100   // Continue button
                rX = pcX; rY = bounds.top + winH * 34 / 100   // Receive Key
            } else {
                // Cua so lon / full screen: dung % goc da verify tren 2220x1080
                eX = bounds.left + winW * 876 / 1000; eY = bounds.top + winH * 426 / 1000
                cX = bounds.left + winW * 876 / 1000; cY = bounds.top + winH * 580 / 1000
                rX = bounds.left + winW * 876 / 1000; rY = bounds.top + winH * 648 / 1000
            }
            dismissX = bounds.left + winW / 4
            dismissY = bounds.top + winH / 2
            println("${YEL}-> [AUTO BYPASS] Fallback WINDOW: Recv($rX,$rY) Enter($eX,$eY) Cont($cX,$cY) Dismiss($dismissX,$dismissY)${R}")
        } else {
            // Fallback cuoi: full screen
            val (w, h) = getScreenRes()
            val rv = btnReceiveKey(w, h); rX = rv.first; rY = rv.second
            val ev = btnEnterKey(w, h); eX = ev.first; eY = ev.second
            val cv = btnContinue(w, h); cX = cv.first; cY = cv.second
            println("${YEL}-> [AUTO BYPASS] Fallback SCREEN(%): Recv($rX,$rY) Enter($eX,$eY) Cont($cX,$cY)${R}")
        }
    }
    
    // === STEP 2: Tap Receive Key de mo link ===
    println("${CYN}-> [AUTO BYPASS] Tap Receive Key ($rX, $rY)...${R}")
    // Clear logcat truoc de bat link moi
    root("logcat -b main -c 2>/dev/null")
    root("am force-stop com.android.chrome 2>/dev/null")
    Thread.sleep(500)
    root("input tap $rX $rY")
    Thread.sleep(4000)
    
    // === STEP 3: Bat link tu activity/logcat ===
    var link = getLinkFromActivityOrLogcat()
    if (link == null) {
        // Retry: co the Chrome chua mo xong
        println("${YEL}-> [AUTO BYPASS] Link chua bat duoc, retry...${R}")
        for (i in 1..5) {
            Thread.sleep(2000)
            link = getLinkFromActivityOrLogcat()
            if (link != null) break
        }
    }
    
    if (link == null) {
        println("${RED}-> [AUTO BYPASS] Khong the bat link! Thu tap lai Receive Key...${R}")
        // Kill Chrome, quay lai Delta, tap lai
        root("am force-stop com.android.chrome 2>/dev/null")
        Thread.sleep(1000)
        root("am start -n $pkg/com.roblox.client.ActivityNativeMain 2>/dev/null")
        Thread.sleep(2000)
        root("input tap $rX $rY")
        Thread.sleep(5000)
        link = getLinkFromActivityOrLogcat()
    }
    
    if (link == null) {
        println("${RED}-> [AUTO BYPASS] THAT BAI bat link sau 2 lan thu!${R}")
        return
    }
    
    // Kill Chrome va quay lai Delta truoc khi bypass
    root("am force-stop com.android.chrome 2>/dev/null")
    Thread.sleep(500)
    root("am start -n $pkg/com.roblox.client.ActivityNativeMain 2>/dev/null")
    Thread.sleep(2000)
    
    println("${CYN}-> [AUTO BYPASS] URL: ${link.take(60)}...${R}")
    props.setProperty("delta_key_url", link)
    try { saveConfig(props) } catch(e: Exception){}
    
    // === STEP 4: Bypass key qua server ===
    val key = bypassDeltaKey(props, pkg)
    if (key == null) {
        println("${RED}-> [AUTO BYPASS] That bai! Server khong tra ve key.${R}")
        return
    }
    
    // === STEP 5: Nhap key vao Delta ===
    println("${GRN}-> [AUTO BYPASS] Key: $key. Dang nhap...${R}")
    
    // Bring Delta to front truoc khi nhap
    root("am start -n $pkg/com.roblox.client.ActivityNativeMain 2>/dev/null")
    Thread.sleep(1500)
    
    // Tap vao textbox Enter Key
    root("input tap $eX $eY")
    Thread.sleep(800)
    
    // Clear text cu
    root("input keyevent " + (1..30).joinToString(" ") { "67" })
    Thread.sleep(300)
    
    // Nhap key
    root("input text '${key.replace("'", "'\\''")}'")
    Thread.sleep(800)
    
    // Dismiss keyboard: tap vao game area (TRONG cua so game, phia trai)
    root("input tap $dismissX $dismissY")
    Thread.sleep(500)
    root("input tap $dismissX $dismissY")
    Thread.sleep(800)
    
    // === STEP 6: Tap Continue de redeem ===
    root("input tap $cX $cY")
    println("${GRN}-> [AUTO BYPASS] Tap Continue ($cX, $cY). Doi executor...${R}")
    Thread.sleep(3000)
    
    // Verify: check xem Chrome co mo khong (= tap sai nut)
    val focusAfter = rootOut("dumpsys window | grep mCurrentFocus")
    if (focusAfter.contains("chrome", ignoreCase = true)) {
        println("${RED}-> [AUTO BYPASS] WARN: Chrome mo = tap sai vi tri Continue!${R}")
        root("am force-stop com.android.chrome 2>/dev/null")
        Thread.sleep(500)
        root("am start -n $pkg/com.roblox.client.ActivityNativeMain 2>/dev/null")
    } else {
        println("${GRN}-> [AUTO BYPASS] XONG! Executor dang khoi dong.${R}")
    }
    Thread.sleep(2000)
}


fun startPlace(
        pkg: String,
        placeId: String,
        method: String = "deeplink_package",
        cfgProps: Properties = loadConfig(),
        jobId: String = ""
) {
    // --- AUTO DEPLOY LUA KICK DETECTOR ---
    ensureLuaKickDetector(pkg)

    val autoBypass = cfgProps.getProperty("auto_bypass_key", "false").toBoolean()
    if (autoBypass) {
        println("${CYN}-> [REJOIN] Auto Bypass Mode duoc bat cho $pkg...${R}")
    }

    // --- CLEAR LUA STATUS FILE CU ---
    clearHeartbeatForPkg(pkg)

    // --- FORCE STOP TRUOC (LUON LUON, bat buoc de deeplink hoat dong) ---
    root("am force-stop $pkg")
    Thread.sleep(2000)

    // Bug #4 fix: KHONG clear logcat o day - gay mat game session log -> kill loop
    // Bug #3 fix: Gop cache cleanup thanh 1 lenh, xoa duplicate
    root("rm -rf /sdcard/Android/data/$pkg/files/logs/* /data/data/$pkg/cache/*")

    var launched = false

    // === METHOD 1: experiences/start deeplink (NHANH NHAT) ===
    if (!launched) {
        val link =
                "roblox://experiences/start?placeId=$placeId" +
                        (if (jobId.isNotBlank()) "&gameInstanceId=${urlEncode(jobId)}" else "")
        println("-> [M1] Experiences deeplink...")
        val res =
                root(
                        "am start --activity-clear-task -a android.intent.action.VIEW -d ${shellQuote(link)} -p $pkg"
                )
        if (res.success) launched = true
    }

    // === SAU KHI LAUNCH: Khong dismiss popup VNG nua ===
    // Neu co popup VNG, autoRejoin se detect FOREGROUND_NO_GAME roi kill + rejoin lai

    // === METHOD 2: Auth Ticket (neu co cookie) ===
    if (!launched || method == "auth_ticket") {
        var cookie = getRawCookieFromDb(pkg)
        if (cookie.isBlank()) {
            val f = File("cookiefolder/cookie.txt")
            cookie = if (f.exists()) f.readText(Charsets.UTF_8).trim() else ""
        }
        if (cookie.isNotBlank()) {
            val csrf = fetchCsrfToken(cookie)
            if (!csrf.isNullOrBlank()) {
                val ticket = fetchAuthTicket(cookie, csrf, placeId)
                if (!ticket.isNullOrBlank()) {
                    // Android format: dung roblox:// KHONG dung roblox-player:
                    val ticketLink =
                            "roblox://experiences/start?placeId=$placeId&ticket=$ticket" +
                                    (if (jobId.isNotBlank()) "&gameInstanceId=${urlEncode(jobId)}"
                                    else "")
                    println("-> [M2] Auth Ticket deeplink...")
                    val res =
                            root(
                                    "am start --activity-clear-task -a android.intent.action.VIEW -d ${shellQuote(ticketLink)} -p $pkg"
                            )
                    if (res.success) launched = true
                }
            }
        }
    }

    // === METHOD 3: Legacy placeId deeplink ===
    if (!launched) {
        val link = "roblox://placeId=$placeId"
        println("-> [M3] Legacy deeplink...")
        val res =
                root(
                        "am start --activity-clear-task -a android.intent.action.VIEW -d ${shellQuote(link)} -p $pkg"
                )
        if (res.success) launched = true
    }

    if (!launched) {
        println("-> [M4] Direct Activity launch...")
        root(
                "am start -n $pkg/.startup.ActivitySplash -a android.intent.action.MAIN -c android.intent.category.LAUNCHER"
        )
        launched = true
    }

    println("=== XONG: Da gui lenh Rejoin cho $pkg ===")
    
    if (autoBypass) {
        deltaAutoBypassFlow(pkg, cfgProps)
    }
}

fun verifyJoin(
        pkg: String,
        expectedPlaceId: String,
        waitSeconds: Long,
        deepVerify: Boolean = false
) {
    if (waitSeconds > 0) Thread.sleep(waitSeconds * 1000L)

    val fast = checkStatusFast(pkg)
    println(
            "Verify fast: ${fast.status}, pid=${fast.pid.ifBlank { "(none)" }}, focused=${fast.focused}"
    )

    val text =
            if (deepVerify) {
                robloxDebugText(pkg)
            } else {
                rootOut(
                        "logcat -d -t 180 | grep -iE 'roblox|$pkg|placeid|place_id|gameid|game_id|ExperienceSession|onGameStarting' | tail -n 80"
                )
            }
    val foundPlace = gameIdFromLogs(text)
    if (foundPlace.isNotBlank()) {
        val ok = expectedPlaceId.isBlank() || foundPlace == expectedPlaceId
        println(
                "${okMark(ok)} log/UI placeId=$foundPlace expected=${expectedPlaceId.ifBlank { "(none)" }}"
        )
    } else {
        println("${okMark(false)} recent log placeId not found")
    }

    val blackHint =
            rootOut(
                    "logcat -d -t 120 | grep -iE 'roblox|surface|black|render|vulkan|opengl|egl|fatal|exception|timeout' | tail -n 40"
            )
    if (blackHint.isNotBlank()) {
        println("Recent render/log hint:")
        println(blackHint)
    }
}

fun openPackage(pkg: String) {
    println("${CYN}Dang mo $pkg...${R}")
    // Dung am start thay vi monkey de tranh log rac
    val result =
            root(
                    "am start -a android.intent.action.MAIN -c android.intent.category.LAUNCHER -p $pkg"
            )
    if (result.success || result.output.contains("Starting:", ignoreCase = true)) {
        println("${GRN}Da mo $pkg thanh cong!${R}")
    } else {
        // Fallback: dung monkey
        val fallback = root("monkey -p $pkg -c android.intent.category.LAUNCHER 1 2>/dev/null")
        println(
                if (fallback.success) "${GRN}Da mo $pkg thanh cong! (monkey)${R}"
                else "${RED}Loi mo $pkg${R}"
        )
    }
}

fun fixCookieDb(pkg: String) {
    val db = "/data/data/$pkg/app_webview/Default/Cookies"
    val appData = "/data/data/$pkg"
    val commands =
            listOf(
                    "am force-stop $pkg",
                    "test -f ${shellQuote(db)}",
                    "test -f ${getSqlite3()} && ${getSqlite3()} ${shellQuote(db)} 'PRAGMA wal_checkpoint(TRUNCATE);' || true",
                    "rm -f ${shellQuote(db + "-journal")} ${shellQuote(db + "-wal")} ${shellQuote(db + "-shm")}",
                    "APP_USER=${'$'}(stat -c '%U' ${shellQuote(appData)}) && APP_GROUP=${'$'}(stat -c '%G' ${shellQuote(appData)}) && chown ${'$'}APP_USER:${'$'}APP_GROUP ${shellQuote(db)}",
                    "chmod 660 ${shellQuote(db)}",
                    "command -v restorecon >/dev/null && restorecon ${shellQuote(db)} || true"
            )

    for (cmd in commands) {
        val result = root(cmd)
        println("${if (result.success) "OK" else "WARN"}: $cmd")
        if (result.output.isNotBlank()) println(result.output)
        if (result.error.isNotBlank()) println(result.error)
    }
}

fun logoutRoblox(pkg: String) {
    val db = "/data/data/$pkg/app_webview/Default/Cookies"
    root("am force-stop $pkg")
    val result =
            root(
                    "${getSqlite3()} ${shellQuote(db)} \"DELETE FROM cookies WHERE host_key LIKE '%roblox.com%';\""
            )
    println(
            if (result.success) "Logout cookie done for $pkg"
            else result.error.ifBlank { result.output }
    )
}

fun exportCookieRedacted(pkg: String) {
    val db = "/data/data/$pkg/app_webview/Default/Cookies"
    val outputFile = File("exported_${pkg.replace(".", "_")}_redacted.md")
    val query =
            "SELECT COALESCE(host_key,''), COALESCE(name,''), COALESCE(path,''), COALESCE(CAST(expires_utc AS TEXT),''), COALESCE(CAST(LENGTH(value) AS TEXT),'0'), COALESCE(CAST(LENGTH(encrypted_value) AS TEXT),'0'), COALESCE(value,'') FROM cookies ORDER BY host_key, name;"
    val rows = rootOut("${getSqlite3()} -batch -separator '|' ${shellQuote(db)} ${shellQuote(query)}")

    println("=== Export Cookie Metadata ===")
    println("Package: $pkg")
    println("DB: $db")
    println("Output: ${outputFile.path}")
    println("Raw cookie value: NOT PRINTED")
    println()

    val md = buildString {
        appendLine("# Export Cookie Redacted")
        appendLine()
        appendLine("- Package: `$pkg`")
        appendLine("- DB: `$db`")
        appendLine("- Time: `${LocalDateTime.now()}`")
        appendLine("- Raw cookie value: `NOT PRINTED`")
        appendLine()
        appendLine("| Host | Name | Path | Expires | Value | Encrypted | SHA-256 fingerprint |")
        appendLine("|---|---|---|---:|---:|---:|---:|")
        rows.lineSequence().filter { it.isNotBlank() }.forEach { line ->
            val p = line.split("|")
            val host = p.getOrElse(0) { "" }
            val name = p.getOrElse(1) { "" }
            val path = p.getOrElse(2) { "" }
            val expires = p.getOrElse(3) { "" }
            val valueLength = p.getOrElse(4) { "0" }
            val encryptedLength = p.getOrElse(5) { "0" }
            val fingerprint = sha256Short(p.getOrElse(6) { "" })

            println(
                    "- $host $name path=$path value=REDACTED($valueLength chars) encrypted=REDACTED($encryptedLength bytes) fingerprint=$fingerprint"
            )
            appendLine(
                    "| $host | $name | $path | $expires | REDACTED ($valueLength chars) | REDACTED ($encryptedLength bytes) | $fingerprint |"
            )
        }
    }

    outputFile.writeText(md)
    println()
    println("Exported redacted metadata: ${outputFile.path}")
}

fun checkSelectedCookies(props: Properties) {
    val tabs = loadTabsFromFile(props)
    if (tabs.isEmpty()) {
        println("Khong co package/tab nao de check.")
        return
    }

    println("=== Login Cookie Tab / Session Cookie Tools ===")
    println("Mode: Check metadata cookie.")
    println()
    for ((index, tab) in tabs.withIndex()) {
        val pkg = tab.packageName
        val db = "/data/data/$pkg/app_webview/Default/Cookies"
        val hasSession = sessionCookieExists(pkg)
        println("[${index + 1}] ${tab.label} ($pkg)")
        println("  DB: $db")
        println("  ${okMark(hasSession)} .ROBLOSECURITY metadata")
        println()
    }
}

fun insertRawCookie(pkg: String, mycookie: String) {
    val db = "/data/data/$pkg/app_webview/Default/Cookies"
    val dbDir = "/data/data/$pkg/app_webview/Default"
    val appData = "/data/data/$pkg"
    val tmpSql = "/sdcard/.cookie_insert.sql"

    // Check package exists
    val pkgCheck = root("pm path $pkg")
    if (!pkgCheck.success) {
        println("SKIP: Package $pkg khong ton tai.")
        return
    }

    // Force stop truoc
    root("am force-stop $pkg")
    Thread.sleep(500)

    // === AUTO CREATE COOKIE DB NEU CHUA CO ===
    val dbExists = rootOut("test -f $db && echo yes || echo no").trim()
    if (dbExists != "yes") {
        println("${YEL}-> Cookie DB chua ton tai. Mo Roblox de tao DB...${R}")
        // Mo Roblox de no tu tao cac file can thiet
        openPackage(pkg)
        println("${CYN}-> Doi 8s cho Roblox khoi tao...${R}")
        Thread.sleep(8000)
        // Force stop lai
        root("am force-stop $pkg")
        Thread.sleep(1000)

        // Check lai sau khi mo app
        val dbExists2 = rootOut("test -f $db && echo yes || echo no").trim()
        if (dbExists2 != "yes") {
            println("${YEL}-> DB van chua co. Tu dong tao bang sqlite3...${R}")
            root("mkdir -p $dbDir")
            // Ghi SQL tao table vao tmp file
            val createSQL =
                    """CREATE TABLE IF NOT EXISTS cookies (creation_utc INTEGER NOT NULL, host_key TEXT NOT NULL DEFAULT '', top_frame_site_key TEXT NOT NULL DEFAULT '', name TEXT NOT NULL DEFAULT '', value TEXT NOT NULL DEFAULT '', encrypted_value BLOB NOT NULL DEFAULT X'', path TEXT NOT NULL DEFAULT '', expires_utc INTEGER NOT NULL DEFAULT 0, is_secure INTEGER NOT NULL DEFAULT 0, is_httponly INTEGER NOT NULL DEFAULT 0, last_access_utc INTEGER NOT NULL DEFAULT 0, has_expires INTEGER NOT NULL DEFAULT 1, is_persistent INTEGER NOT NULL DEFAULT 1, priority INTEGER NOT NULL DEFAULT 1, samesite INTEGER NOT NULL DEFAULT -1, source_scheme INTEGER NOT NULL DEFAULT 0, source_port INTEGER NOT NULL DEFAULT -1, last_update_utc INTEGER NOT NULL DEFAULT 0);
CREATE TABLE IF NOT EXISTS meta (key TEXT NOT NULL UNIQUE PRIMARY KEY, value TEXT NOT NULL);
INSERT OR IGNORE INTO meta VALUES ('version','20');
INSERT OR IGNORE INTO meta VALUES ('last_compatible_version','20');"""
            // Ghi file SQL
            File("/sdcard/.cookie_create.sql").also {
                try {
                    it.writeText(createSQL)
                } catch (_: Exception) {
                    root("echo ${shellQuote(createSQL)} > /sdcard/.cookie_create.sql")
                }
            }
            val createResult = root("${getSqlite3()} $db < /sdcard/.cookie_create.sql")
            root("rm -f /sdcard/.cookie_create.sql")
            if (createResult.success) {
                println("${GRN}-> Da tao Cookie DB moi thanh cong!${R}")
            } else {
                println(
                        "${RED}-> LOI tao DB: ${createResult.error.ifBlank { createResult.output }}${R}"
                )
                return
            }
            // Fix ownership
            root("chown -R $(stat -c '%U' $appData):$(stat -c '%G' $appData) $dbDir")
            root("chmod 660 $db")
            root("restorecon -R $dbDir 2>/dev/null || true")
        } else {
            println("${GRN}-> Roblox da tao Cookie DB thanh cong!${R}")
        }
    }

    val colInfo = rootOut("${getSqlite3()} $db \"PRAGMA table_info(cookies);\"")
    val colNames =
            colInfo.lines().mapNotNull { line ->
                val parts = line.split("|")
                if (parts.size >= 2) parts[1].trim() else null
            }
    println("${DIM}-> DB schema: ${colNames.size} columns${R}")

    val safeCookie = mycookie.replace("'", "''")

    // Tao SQL INSERT dong (dynamic) dua tren dung cac cot cua DB (Bulletproof cho moi ban Chromium)
    val cols = mutableListOf<String>()
    val vals = mutableListOf<String>()
    for (col in colNames) {
        cols.add(col)
        when (col) {
            "creation_utc" -> vals.add("13421673867034526")
            "host_key" -> vals.add("'.roblox.com'")
            "top_frame_site_key" -> vals.add("''")
            "name" -> vals.add("'.ROBLOSECURITY'")
            "value" -> vals.add("'$safeCookie'")
            "encrypted_value" -> vals.add("X''")
            "path" -> vals.add("'/'")
            "expires_utc" -> vals.add("13456233867034526")
            "is_secure", "secure" -> vals.add("1")
            "is_httponly", "httponly" -> vals.add("1")
            "last_access_utc" -> vals.add("13421673867034526")
            "has_expires" -> vals.add("1")
            "is_persistent", "persistent" -> vals.add("1")
            "priority" -> vals.add("1")
            "samesite" -> vals.add("-1")
            "source_scheme" -> vals.add("2")
            "source_port" -> vals.add("443")
            "last_update_utc" -> vals.add("13421673867053634")
            else -> vals.add("''")
        }
    }

    val sqlContent =
            "DELETE FROM cookies WHERE name='.ROBLOSECURITY';\n" +
                    "INSERT OR REPLACE INTO cookies (${cols.joinToString(", ")}) VALUES (${vals.joinToString(", ")});"

    println("${CYN}Dang chen cookie cho $pkg...${R}")

    // Force stop truoc khi thao tac DB
    root("am force-stop $pkg")
    Thread.sleep(500)

    // Xoa WAL/journal TRUOC khi ghi
    root("rm -f ${db}-journal ${db}-wal ${db}-shm")

    // Ghi SQL file
    try {
        File(tmpSql.removePrefix("/sdcard/").let { "/sdcard/$it" }).writeText(sqlContent)
    } catch (_: Exception) {
        // Fallback: ghi qua root
        root("cat > $tmpSql << 'SQLEOF'\n$sqlContent\nSQLEOF")
    }

    // Chay SQL tu file
    val insertResult = root("${getSqlite3()} $db < $tmpSql")
    root("rm -f $tmpSql")

    if (!insertResult.success) {
        println("${RED}FAIL insert: ${insertResult.error.ifBlank { insertResult.output }}${R}")
        println("${YEL}-> Thu method 2: truc tiep tung lenh...${R}")
        root("${getSqlite3()} $db \"DELETE FROM cookies WHERE name='.ROBLOSECURITY';\"")
        val fb =
                root(
                        "${getSqlite3()} $db \"INSERT OR REPLACE INTO cookies (${cols.joinToString(", ")}) VALUES (${vals.joinToString(", ")});\""
                )
        if (!fb.success) {
            println("${RED}FAIL fallback: ${fb.error.ifBlank { fb.output }}${R}")
        }
    }

    // VACUUM de merge WAL vao DB chinh (QUAN TRONG - fix cookie khong hien)
    root("${getSqlite3()} $db \"PRAGMA journal_mode=DELETE;\"")
    root("${getSqlite3()} $db \"VACUUM;\"")

    // Xoa WAL/journal SAU khi VACUUM
    root("rm -f ${db}-journal ${db}-wal ${db}-shm")

    // Fix ownership + permissions cho TAT CA file lien quan
    root("chown $(stat -c '%U' $appData):$(stat -c '%G' $appData) $db 2>/dev/null")
    root("chown $(stat -c '%U' $appData):$(stat -c '%G' $appData) ${db}-journal 2>/dev/null")
    root("chown $(stat -c '%U' $appData):$(stat -c '%G' $appData) ${db}-wal 2>/dev/null")
    root("chown $(stat -c '%U' $appData):$(stat -c '%G' $appData) ${db}-shm 2>/dev/null")
    val dbDirPath = "/data/data/$pkg/app_webview/Default"
    root("chown -R $(stat -c '%U' $appData):$(stat -c '%G' $appData) $dbDirPath 2>/dev/null")
    root("chmod 660 $db")
    root("chmod 770 $dbDirPath")

    // Fix SELinux context cho TAT CA file trong thu muc
    root("restorecon -R $dbDirPath 2>/dev/null || true")
    root("restorecon $db 2>/dev/null || true")

    // Verify
    val verify =
            rootOut(
                    "${getSqlite3()} $db \"SELECT COUNT(*) FROM cookies WHERE name='.ROBLOSECURITY' AND LENGTH(value) > 0;\""
            )
    val count = verify.trim().toIntOrNull() ?: 0
    if (count > 0) {
        println("${GRN}Chen cookie xong cho $pkg. (verified: $count row)${R}")
        // Tu dong mo Roblox sau khi inject thanh cong
        println("${CYN}-> Dang mo Roblox ($pkg)...${R}")
        Thread.sleep(500)
        openPackage(pkg)
        println("${GRN}-> Roblox da mo. Doi 5-10s de cookie duoc load...${R}")
    } else {
        println("${RED}FAIL: Cookie khong duoc chen cho $pkg!${R}")
    }
}

fun getDeltaAutoExecPath(pkg: String = "com.roblox.client"): String {
    return "/sdcard/Android/data/$pkg/files/delta/autoexec"
}

fun setupAutoExec(pkg: String = "com.roblox.client") {
    println("\n=== Them Script vao Auto Execute (Delta) ===")
    val autoExecDir = getDeltaAutoExecPath(pkg)
    root("mkdir -p $autoExecDir")

    val defaultPath = "/sdcard/Download/script.lua"
    print("Nhap duong dan den file script cua ban (Mac dinh: $defaultPath): ")
    var sourcePath = readLine()?.trim() ?: ""
    if (sourcePath.isBlank()) sourcePath = defaultPath

    val checkExist = rootOut("ls '$sourcePath' 2>/dev/null").trim()
    if (checkExist.isBlank() || checkExist.contains("No such file")) {
        println(
                "${RED}Loi: Khong tim thay file $sourcePath. Hay tai script vao muc Download truoc!${R}"
        )
        return
    }

    print("Dat ten file luu trong autoexec (Vi du: autofarm.lua): ")
    var destName = readLine()?.trim() ?: "script.lua"
    if (!destName.endsWith(".lua")) destName += ".lua"

    val destPath = "$autoExecDir/$destName"
    root("cp '$sourcePath' '$destPath'")
    println("${GRN}Da copy script vao: $destPath${R}")
}

fun deleteAutoExec(pkg: String = "com.roblox.client") {
    println("\n=== Xoa Script trong Auto Execute (Delta) ===")
    val autoExecDir = getDeltaAutoExecPath(pkg)

    val filesListStr = rootOut("ls -1 $autoExecDir 2>/dev/null").trim()
    if (filesListStr.isBlank() || filesListStr.contains("No such file")) {
        println("${YEL}Thu muc autoexec hien dang trong hoac chua duoc tao!${R}")
        return
    }

    val files = filesListStr.lines().filter { it.endsWith(".lua") || it.endsWith(".txt") }
    if (files.isEmpty()) {
        println("${YEL}Khong co file script nao trong autoexec!${R}")
        return
    }

    println("Danh sach cac file hien co:")
    for ((index, file) in files.withIndex()) {
        println("${CYN}[${index + 1}]${R} $file")
    }
    println("${RED}[0]${R} Xoa TAT CA file")
    println("[C] Huy thao tac")

    print("Chon so tuong ung de xoa: ")
    val choice = readLine()?.trim() ?: "c"

    when {
        choice.equals("c", ignoreCase = true) -> return
        choice == "0" -> {
            root("rm -f $autoExecDir/*")
            println("${GRN}Da xoa toan bo script trong autoexec!${R}")
        }
        else -> {
            val idx = choice.toIntOrNull()?.minus(1) ?: -1
            if (idx in files.indices) {
                val targetFile = files[idx]
                root("rm -f '$autoExecDir/$targetFile'")
                println("${GRN}Da xoa file: $targetFile${R}")
            } else {
                println("${RED}Lua chon khong hop le!${R}")
            }
        }
    }
}

fun autoExecTab(props: Properties) {
    while (true) {
        val packages = selectedPackages(props).ifEmpty { listOf("com.roblox.client") }

        println("\n=== Quan ly Auto Execute (Delta) ===")
        println("${YEL}Packages da chon:${R}")
        packages.forEachIndexed { i, pkg ->
            val autoExecDir = getDeltaAutoExecPath(pkg)
            val fileCount = rootOut("ls $autoExecDir 2>/dev/null | wc -l").trim()
            println("  ${GRN}[${i+1}]${R} $pkg | Scripts: $fileCount")
        }
        println()
        println("${MAG}[A]${R} Them script cho ${BOLD}TAT CA${R} packages")
        println("${RED}[D]${R} Xoa script khoi ${BOLD}TAT CA${R} packages")
        println("${CYN}Hoac chon so [1-${packages.size}] de quan ly tung package${R}")
        println("${BLU}[0]${R} Quay lai")
        val pkgInput = prompt("Chon").trim()

        when (pkgInput.lowercase()) {
            "0" -> return
            "a" -> {
                // Add script to ALL packages at once
                println("\n${CYN}=== Them script cho TAT CA ${packages.size} packages ===${R}")
                for (pkg in packages) {
                    println("-> Deploy cho $pkg...")
                    setupAutoExec(pkg)
                }
                println("${GRN}Da them script cho ${packages.size} packages!${R}")
            }
            "d" -> {
                // Delete script from ALL packages at once
                println("\n${RED}=== Xoa script khoi TAT CA ${packages.size} packages ===${R}")
                for (pkg in packages) {
                    println("-> Xoa cho $pkg...")
                    deleteAutoExec(pkg)
                }
                println("${GRN}Da xoa script khoi ${packages.size} packages!${R}")
            }
            else -> {
                val pkgIdx = pkgInput.toIntOrNull()?.minus(1) ?: continue
                val pkg = packages.getOrNull(pkgIdx) ?: continue

                println("\n${CYN}Package: $pkg${R}")
                println("${GRN}[1]${R} Them script .lua vao Auto Execute")
                println("${RED}[2]${R} Xoa script .lua trong Auto Execute")
                println("${BLU}[3]${R} Quay lai")

                when (prompt("Chon chuc nang")) {
                    "1" -> setupAutoExec(pkg)
                    "2" -> deleteAutoExec(pkg)
                    "3", "0", "c" -> continue
                    else -> println("${RED}Lua chon khong hop le!${R}")
                }
            }
        }

        println()
        println("Nhan Enter de tiep tuc...")
        readLine()
    }
}

fun pickOnePackage(props: Properties, label: String = "Chon package"): String? {
    val packages = selectedPackages(props).ifEmpty { listOf("com.roblox.client") }
    if (packages.size == 1) return packages.first()
    println("${CYN}$label:${R}")
    packages.forEachIndexed { i, pkg -> println("  ${GRN}[${i+1}]${R} $pkg") }
    println("  ${BLU}[0]${R} Tat ca")
    val input = prompt("Chon so").trim()
    if (input == "0") return null // null = all
    val idx = input.toIntOrNull()?.minus(1) ?: return packages.first()
    return packages.getOrNull(idx) ?: packages.first()
}

fun loginCookieTab(props: Properties) {
    while (true) {
        val packages = selectedPackages(props).ifEmpty { listOf("com.roblox.client") }

        println("\n=== Login Cookie Tab / Session Cookie Tools ===")
        println("${YEL}Packages da chon:${R}")
        packages.forEachIndexed { i, pkg ->
            val hasCookie = sessionCookieExists(pkg)
            val userId = getUserIdFromSharedPrefs(pkg).ifBlank { "?" }
            println(
                    "  ${GRN}[${i+1}]${R} $pkg | Cookie:${if(hasCookie) "${GRN}OK${R}" else "${RED}NO${R}"} | UID:$userId"
            )
        }
        println()
        println("[1] Check session cookie metadata")
        println("[2] Fix login cookie DB")
        println("[3] Export cookie metadata redacted")
        println("[4] Open Roblox app")
        println("${MAG}[5]${R} Insert raw cookie (Login) -> chon package")
        println("${RED}[6]${R} Remove cookie (Logout tung package)")
        println("[7] Back")
        println()

        when (prompt("Cookie tab")) {
            "1" -> {
                val pkg = pickOnePackage(props, "Check cookie cho package nao")
                if (pkg == null)
                        packages.forEach {
                            println("--- $it ---")
                            printCookieInfo(it)
                        }
                else printCookieInfo(pkg)
            }
            "2" -> {
                val pkg = pickOnePackage(props, "Fix cookie DB cho package nao")
                if (pkg == null) packages.forEach { fixCookieDb(it) } else fixCookieDb(pkg)
            }
            "3" -> {
                val pkg = pickOnePackage(props, "Export cookie cho package nao")
                if (pkg == null) packages.forEach { exportCookieRedacted(it) }
                else exportCookieRedacted(pkg)
            }
            "4" -> {
                val pkg = pickOnePackage(props, "Mo app nao")
                if (pkg == null) packages.forEach { openPackage(it) } else openPackage(pkg)
            }
            "5" -> {
                val pkg = pickOnePackage(props, "Insert cookie vao package nao")
                val targets = if (pkg == null) packages else listOf(pkg)
                val cookie =
                        prompt("Nhap cookie (.ROBLOSECURITY) cho ${targets.joinToString(", ")}")
                if (cookie.isNotBlank()) {
                    File("cookiefolder").mkdirs()
                    File("cookiefolder/cookie.txt").writeText(cookie)
                    targets.forEach { insertRawCookie(it, cookie) }
                }
            }
            "6" -> {
                // Remove cookie per package
                val pkg = pickOnePackage(props, "Remove cookie cua package nao")
                val targets = if (pkg == null) packages else listOf(pkg)
                for (t in targets) {
                    println("${YEL}Dang xoa cookie cua $t ...${R}")
                    val db = "/data/data/$t/app_webview/Default/Cookies"
                    // Xoa .ROBLOSECURITY + session cookies
                    root(
                            "${getSqlite3()} ${shellQuote(db)} \"DELETE FROM cookies WHERE name IN ('.ROBLOSECURITY', '.RBXID', 'RBXEventTrackerV2', 'GuestData');\""
                    )
                    // Xoa shared_prefs userid
                    root("rm -f /data/data/$t/shared_prefs/${t}.v2.player.xml 2>/dev/null")
                    // Force stop app
                    root("am force-stop $t")
                    println("${GRN}[OK] Da xoa cookie + force-stop $t${R}")
                }
                println("${GRN}Xong! App se yeu cau login lai khi mo.${R}")
            }
            "7" -> return
            else -> println("Lua chon khong hop le.")
        }

        println()
        println("Nhan Enter de quay lai cookie tab...")
        readLine()
        println()
    }
}

fun printCookieInfo(pkg: String) {
    val db = "/data/data/$pkg/app_webview/Default/Cookies"
    val hasSession = sessionCookieExists(pkg)
    val userId = getUserIdFromSharedPrefs(pkg).ifBlank { getUserIdFromCookieDb(pkg) }
    println("  Package: $pkg")
    println("  DB: $db")
    println("  ${okMark(hasSession)} .ROBLOSECURITY metadata")
    println("  UserID: ${userId.ifBlank { "(not found)" }}")
    println()
}

fun printMenu(props: Properties) {
    val selected = selectedPackages(props).joinToString(", ").ifBlank { "(none)" }
    val placeId = props.getProperty("place_id", "(none)")
    val joinMethod = props.getProperty("join_method", "deeplink_package")
    val vipCode = props.getProperty("vip_server_code", "").ifBlank { "(none)" }
    println()
    println("${BOLD}${CYN}+================================================+${R}")
    println(
            "${BOLD}${CYN}|${R}  ${BOLD}${MAG}* Pluto Rejoin ${R}${DIM}v2.1${R}  ${BOLD}${CYN}|${R}"
    )
    println("${BOLD}${CYN}+================================================+${R}")
    println("${BOLD}${CYN}|${R} ${YEL}Package:${R} $selected")
    println("${BOLD}${CYN}|${R} ${YEL}PlaceID:${R} $placeId")
    println(
            "${BOLD}${CYN}|${R} ${YEL}VIP:${R}     ${if (vipCode == "(none)") vipCode else vipCode.take(16) + "..."}"
    )
    println("${BOLD}${CYN}|${R} ${YEL}Method:${R}  $joinMethod")
    println("${BOLD}${CYN}+================================================+${R}")
    println("${BOLD}${CYN}|${R} ${GRN}[1]${R} ${BOLD}Auto Rejoin${R}")
    println("${BOLD}${CYN}|${R} ${BLU}[2]${R} Select package")
    println("${BOLD}${CYN}|${R} ${BLU}[3]${R} List selected packages")
    println("${BOLD}${CYN}|${R} ${BLU}[4]${R} Auto-select package")
    println("${BOLD}${CYN}|${R} ${BLU}[5]${R} Select rejoin method")
    println("${BOLD}${CYN}|${R} ${BLU}[6]${R} Set up webhook")
    println("${BOLD}${CYN}|${R} ${MAG}[7]${R} Login via cookie")
    println("${BOLD}${CYN}|${R} ${BLU}[8]${R} Select status check method")
    println("${BOLD}${CYN}|${R} ${BLU}[9]${R} Open all roblox tabs")
    println("${BOLD}${CYN}|${R} ${BLU}[10]${R} Toggle auto-change account")
    println("${BOLD}${CYN}|${R} ${BLU}[11]${R} Delay settings")
    println("${BOLD}${CYN}|${R} ${RED}[12]${R} Logout roblox")
    println("${BOLD}${CYN}|${R} ${BLU}[13]${R} Fix login cookie")
    println("${BOLD}${CYN}|${R} ${BLU}[14]${R} Export cookie")
    println("${BOLD}${CYN}|${R} ${BLU}[15]${R} Edit package prefix")
    println("${BOLD}${CYN}|${R} ${BLU}[16]${R} Toggle auto block")
    println("${BOLD}${CYN}|${R} ${DIM}[17]${R} Config tool")
    println("${BOLD}${CYN}|${R} ${MAG}[18]${R} Quan ly Auto Execute (Delta)")
    println("${BOLD}${CYN}|${R} ${GRN}[19]${R} Scan & Auto-add Roblox packages")
    println("${BOLD}${CYN}|${R} ${RED}[20]${R} Remove package")
    println("${BOLD}${CYN}|${R} ${YEL}[21]${R} Set Place ID")
    println("${BOLD}${CYN}|${R} ${CYN}[22]${R} Fix Lag / Toi uu")
    println("${BOLD}${CYN}|${R} ${YEL}[23]${R} Set thoi gian check (NoGame/WhiteScreen)")
    println("${BOLD}${CYN}+================================================+${R}")
    println("${BOLD}${CYN}|${R} ${GRN}[24]${R} ${BOLD}Join VIP Server${R}")
    println("${BOLD}${CYN}|${R} ${MAG}[25]${R} Set PlaceID tung package")
    println("${BOLD}${CYN}|${R} ${CYN}[26]${R} Debug Status (chi tiet)")
    println("${BOLD}${CYN}|${R} ${GRN}[27]${R} ${BOLD}Bypass Delta Key${R}")
    println("${BOLD}${CYN}|${R} ${BLU}[28]${R} Set Delta Key URL")
    println("${BOLD}${CYN}|${R} ${BLU}[29]${R} Toggle auto bypass key")
    println("${BOLD}${CYN}+================================================+${R}")
    println("${BOLD}${CYN}|${R} ${RED}[0]${R}  ${RED}Exit${R}")
    println("${BOLD}${CYN}+================================================+${R}")
}

// === [24] JOIN VIP SERVER ===

fun joinVipServerMenu(props: Properties) {
    val packages = selectedPackages(props).ifEmpty { listOf("com.roblox.client") }
    val currentCode = props.getProperty("vip_server_code", "")

    println("\n${BOLD}${CYN}=== Join VIP Server ===${R}")
    if (currentCode.isNotBlank()) {
        println("${YEL}VIP code hien tai:${R} ${currentCode.take(20)}...")
    }
    println()
    println("${GRN}[1]${R} Set VIP Server link/code")
    println("${GRN}[2]${R} Set VIP code cho tung package")
    println("${MAG}[3]${R} Join VIP Server ngay (tat ca packages)")
    println("${MAG}[4]${R} Join VIP Server cho 1 package")
    println("${RED}[5]${R} Xoa VIP code")
    println("${BLU}[0]${R} Quay lai")

    when (prompt("Chon")) {
        "1" -> {
            println("Nhap VIP Server link hoac code:")
            println("${DIM}Vi du: https://www.roblox.com/share?code=166171aa...&type=Server${R}")
            println("${DIM}Hoac: 166171aa1ea1834eaf91a87bb7d9d551${R}")
            val input = prompt("Link/Code")
            val code = parseVipLink(input)
            if (code != null) {
                props.setProperty("vip_server_code", code)
                saveConfig(props)
                println("${GRN}Da luu VIP code: ${code.take(20)}...${R}")
            } else {
                println("${RED}Khong parse duoc code tu input!${R}")
            }
        }
        "2" -> {
            println("\n${CYN}Set VIP code cho tung package:${R}")
            packages.forEachIndexed { i, pkg ->
                val pkgCode = getVipCodeForPackage(pkg, props)
                println("  ${GRN}[${i+1}]${R} $pkg | VIP: ${pkgCode.take(16).ifBlank { "(none)" }}")
            }
            val idx = prompt("Chon package (so)").trim().toIntOrNull()?.minus(1) ?: return
            val pkg = packages.getOrNull(idx) ?: return
            println("Nhap VIP link/code cho $pkg:")
            val input = prompt("Link/Code")
            val code = parseVipLink(input)
            if (code != null) {
                props.setProperty("vip_server_code.$pkg", code)
                saveConfig(props)
                println("${GRN}Da luu VIP code cho $pkg${R}")
            } else {
                println("${RED}Khong parse duoc code!${R}")
            }
        }
        "3" -> {
            val code =
                    currentCode.ifBlank {
                        println("Chua co VIP code! Nhap ngay:")
                        val input = prompt("Link/Code")
                        parseVipLink(input) ?: ""
                    }
            if (code.isBlank()) {
                println("${RED}Khong co VIP code!${R}")
                return
            }
            for (pkg in packages) {
                val pkgCode = getVipCodeForPackage(pkg, props).ifBlank { code }
                println("-> Joining VIP for $pkg...")
                startVipServer(pkg, pkgCode, props)
            }
        }
        "4" -> {
            val pkg = pickOnePackage(props, "Join VIP cho package nao") ?: return
            val code = getVipCodeForPackage(pkg, props).ifBlank { currentCode }
            if (code.isBlank()) {
                println("${RED}Chua co VIP code cho $pkg!${R}")
                return
            }
            startVipServer(pkg, code, props)
        }
        "5" -> {
            props.remove("vip_server_code")
            for (pkg in packages) props.remove("vip_server_code.$pkg")
            saveConfig(props)
            println("${GRN}Da xoa tat ca VIP codes.${R}")
        }
        "0" -> return
    }
}

// === [25] SET PLACEID PER PACKAGE ===

fun setPlaceIdPerPackageMenu(props: Properties) {
    val packages = selectedPackages(props).ifEmpty { listOf("com.roblox.client") }
    val globalPlaceId = props.getProperty("place_id", "")

    println("\n${BOLD}${CYN}=== Set PlaceID tung package ===${R}")
    println("${YEL}Global PlaceID:${R} ${globalPlaceId.ifBlank { "(chua set)" }}")
    println()
    packages.forEachIndexed { i, pkg ->
        val pkgPlaceId = props.getProperty("place_id.$pkg", "")
        val effective = pkgPlaceId.ifBlank { globalPlaceId }
        val source = if (pkgPlaceId.isNotBlank()) "${GRN}(custom)${R}" else "${DIM}(global)${R}"
        println("  ${GRN}[${i+1}]${R} $pkg | PlaceID: ${effective.ifBlank { "(none)" }} $source")
    }
    println()
    println("${GRN}[0]${R} Set Global PlaceID (cho tat ca)")
    println("${BLU}[B]${R} Quay lai")

    val input = prompt("Chon package (so) hoac 0 cho global").trim()

    when (input.lowercase()) {
        "b" -> return
        "0" -> {
            val newId = prompt("Nhap Global PlaceID moi")
            if (newId.isNotBlank() && newId.all { it.isDigit() }) {
                props.setProperty("place_id", newId.trim())
                saveConfig(props)
                println("${GRN}Da luu global place_id = $newId${R}")
            } else {
                println("${RED}PlaceID khong hop le!${R}")
            }
        }
        else -> {
            val idx = input.toIntOrNull()?.minus(1) ?: return
            val pkg = packages.getOrNull(idx) ?: return
            println("Nhap PlaceID cho $pkg (Enter de xoa custom, dung global):")
            val newId = prompt("PlaceID").trim()
            if (newId.isBlank()) {
                props.remove("place_id.$pkg")
                saveConfig(props)
                println("${GRN}Da xoa custom PlaceID cho $pkg (se dung global)${R}")
            } else if (newId.all { it.isDigit() }) {
                props.setProperty("place_id.$pkg", newId)
                saveConfig(props)
                println("${GRN}Da luu place_id.$pkg = $newId${R}")
            } else {
                println("${RED}PlaceID khong hop le!${R}")
            }
        }
    }
}

// === [26] DEBUG STATUS ===

fun debugStatusMenu(props: Properties) {
    val packages = selectedPackages(props).ifEmpty { listOf("com.roblox.client") }

    println("\n${BOLD}${CYN}+================================================+${R}")
    println(
            "${BOLD}${CYN}|${R}         ${BOLD}DEBUG STATUS (chi tiet)${R}          ${BOLD}${CYN}|${R}"
    )
    println("${BOLD}${CYN}+================================================+${R}")

    for (pkg in packages) {
        val report = checkStatus(pkg)

        println("\n${BOLD}${MAG}=== $pkg ===${R}")
        println("  ${YEL}PID:${R}        ${report.pid.ifBlank { "${RED}(not running)${R}" }}")
        println("  ${YEL}Status:${R}     ${colorStatus(report.status)}")
        println("  ${YEL}Focused:${R}    ${report.focused}")
        println(
                "  ${YEL}InGame:${R}     ${if (report.inGameSession) "${GRN}YES${R}" else "${RED}NO${R}"}"
        )
        println("  ${YEL}GameId:${R}     ${report.gameId.ifBlank { "(none)" }}")
        println("  ${YEL}UserId:${R}     ${report.userId.ifBlank { "(none)" }}")
        println(
                "  ${YEL}Cookie:${R}     ${if (report.sessionCookie) "${GRN}OK${R}" else "${RED}NO${R}"}"
        )

        // Extra debug info
        if (report.pid.isNotBlank()) {
            // Memory
            val memLine =
                    rootOut(
                                    "dumpsys meminfo ${report.pid} 2>/dev/null | grep 'TOTAL PSS' | head -1"
                            )
                            .trim()
            println("  ${YEL}Memory:${R}     ${memLine.ifBlank { "N/A" }}")

            // Process age
            val uptimeStr =
                    rootOut("cat /proc/${report.pid}/stat 2>/dev/null | awk '{print \$22}'").trim()
            val sysUptime = rootOut("cat /proc/uptime 2>/dev/null | awk '{print \$1}'").trim()
            try {
                val startTicks = uptimeStr.toLongOrNull() ?: 0L
                val sysUp = sysUptime.toDoubleOrNull() ?: 0.0
                val ageSec = sysUp - (startTicks.toDouble() / 100.0)
                println("  ${YEL}Uptime:${R}     ${ageSec.toLong()}s (${ageSec.toLong() / 60}m)")
            } catch (_: Exception) {}

            // VNG popup check
            val vng = hasVngPopup(pkg)
            println("  ${YEL}VNG Popup:${R}  ${if (vng) "${RED}YES${R}" else "${GRN}NO${R}"}")

            // White screen check
            val white = hasWhiteScreenStuck(pkg)
            println("  ${YEL}WhiteScr:${R}  ${if (white) "${RED}STUCK${R}" else "${GRN}OK${R}"}")

            // Last 5 logcat lines
            val lastLogs = rootOut("logcat -d -t 5 --pid=${report.pid} 2>/dev/null").trim()
            if (lastLogs.isNotBlank()) {
                println("  ${YEL}Last logs:${R}")
                lastLogs.lines().take(5).forEach { println("    ${DIM}$it${R}") }
            }

            // Kick signals
            val kickLog =
                    rootOut(
                                    "logcat -d -t 500 --pid=${report.pid} 2>/dev/null | grep -iE 'kicked|Lost connection|disconnect.*reason|same account' | tail -3"
                            )
                            .trim()
            if (kickLog.isNotBlank()) {
                println("  ${RED}Kick signals:${R}")
                kickLog.lines().forEach { println("    ${RED}$it${R}") }
            }
        }

        // Per-package config
        val pkgPlaceId = getPlaceIdForPackage(pkg, props)
        val pkgVipCode = getVipCodeForPackage(pkg, props)
        println("  ${CYN}Config:${R}")
        println("    PlaceID: ${pkgPlaceId.ifBlank { "(none)" }}")
        println("    VIP:     ${pkgVipCode.take(16).ifBlank { "(none)" }}")
    }

    // System info
    println("\n${BOLD}${CYN}=== SYSTEM ===${R}")
    val memTotal = rootOut("cat /proc/meminfo | head -3").trim()
    println("  $memTotal")
    val cpuLoad = rootOut("cat /proc/loadavg 2>/dev/null").trim()
    println("  ${YEL}CPU Load:${R} $cpuLoad")
    val battery =
            rootOut("dumpsys battery 2>/dev/null | grep -E 'level|status|temperature' | head -3")
                    .trim()
    println("  ${YEL}Battery:${R}")
    battery.lines().forEach { println("    $it") }
}

fun colorStatus(status: String): String {
    return when (status) {
        "FOREGROUND" -> "${GRN}$status${R}"
        "FOREGROUND_NO_GAME" -> "${YEL}$status${R}"
        "KICKED_OR_DISCONNECTED" -> "${RED}$status${R}"
        "NOT_RUNNING_OR_EXITED" -> "${RED}$status${R}"
        "NOT_INSTALLED" -> "${RED}$status${R}"
        "CRASHED_RECENTLY" -> "${RED}$status${R}"
        else -> status
    }
}

fun fixLagMenu(props: Properties) {
    while (true) {
        val packages = selectedPackages(props).ifEmpty { listOf("com.roblox.client") }
        println("\n${BOLD}=== Fix Lag / Toi Uu ===${R}")
        println("${GRN}[1]${R} Toi uu hieu suat (tat animation, GPU render...)")
        println("${GRN}[2]${R} Xoa cache Roblox")
        println("${GRN}[3]${R} Kill background apps (giam RAM)")
        println("${GRN}[4]${R} Toi uu pin (giam drain battery)")
        println("${GRN}[5]${R} Ha do phan giai (giam lag nang)")
        println("${GRN}[6]${R} Khoi phuc do phan giai goc")
        println("${GRN}[7]${R} ALL-IN-ONE (1+2+3+4)")
        println("${BLU}[0]${R} Quay lai")

        when (prompt("Chon")) {
            "1" -> {
                println("${CYN}Dang toi uu hieu suat...${R}")
                // Tat animation he thong
                root("settings put global window_animation_scale 0")
                root("settings put global transition_animation_scale 0")
                root("settings put global animator_duration_scale 0")
                // GPU rendering
                root("settings put global force_hw_accel 1")
                root("setprop debug.hwui.renderer opengl")
                // Giam overdraw
                root("setprop debug.hwui.overdraw false")
                // Tat HW overlays de GPU xu ly
                root("settings put system show_touches 0")
                println("${GRN}Da toi uu hieu suat! Animation=0, GPU accelerated.${R}")
            }
            "2" -> {
                println("${CYN}Dang xoa cache Roblox...${R}")
                for (pkg in packages) {
                    root("rm -rf /data/data/$pkg/cache/*")
                    root("rm -rf /data/data/$pkg/code_cache/*")
                    root("rm -rf /sdcard/Android/data/$pkg/cache/*")
                    root("rm -rf /data/data/$pkg/app_webview/Default/GPUCache/*")
                    root("rm -rf /data/data/$pkg/files/ota_rbxm_decompressed_cache/*")
                    println("  ${GRN}Cleared cache: $pkg${R}")
                }
                // System cache
                root("sync && echo 3 > /proc/sys/vm/drop_caches")
                println("${GRN}Da xoa cache!${R}")
            }
            "3" -> {
                println("${CYN}Dang kill background apps...${R}")
                // Kill cac app nang khong can thiet
                val killList =
                        listOf(
                                "com.google.android.gms",
                                "com.google.android.googlequicksearchbox",
                                "com.android.chrome",
                                "com.google.android.youtube",
                                "com.facebook.katana",
                                "com.facebook.orca"
                        )
                for (app in killList) {
                    root("am force-stop $app 2>/dev/null")
                }
                // Free RAM
                root("sync && echo 3 > /proc/sys/vm/drop_caches")
                val memInfo = rootOut("cat /proc/meminfo | head -3")
                println("${GRN}Da kill background apps!${R}")
                println("${DIM}$memInfo${R}")
            }
            "4" -> {
                println("${CYN}Dang toi uu pin...${R}")
                // Giam do sang
                root("settings put system screen_brightness 50")
                // Tat adaptive brightness
                root("settings put system screen_brightness_mode 0")
                // Tang thoi gian tat man hinhbanj
                root("settings put system screen_off_timeout 600000")
                // Tat vibrate
                root("settings put system haptic_feedback_enabled 0")
                // Tat sync tu dong
                root("settings put global auto_sync 0")
                // Tat location
                root("settings put secure location_providers_allowed -gps,-network")
                println("${GRN}Da toi uu pin! Brightness=50, auto-sync=OFF, GPS=OFF.${R}")
                println("${YEL}Luu y: GPS va sync da tat, bat lai khi can.${R}")
            }
            "5" -> {
                println("${CYN}Ha do phan giai...${R}")
                val currentRes = rootOut("wm size")
                println("${DIM}Hien tai: $currentRes${R}")
                println("[1] 720x1280 (thap)")
                println("[2] 540x960 (rat thap)")
                println("[3] Nhap tu chinh")
                when (prompt("Chon")) {
                    "1" -> root("wm size 720x1280")
                    "2" -> root("wm size 540x960")
                    "3" -> {
                        val res = prompt("Nhap WxH (vi du: 600x1024)")
                        if (res.contains("x")) root("wm size $res")
                    }
                }
                root("wm density 240")
                println("${GRN}Da ha do phan giai! Game se muot hon.${R}")
            }
            "6" -> {
                root("wm size reset")
                root("wm density reset")
                println("${GRN}Da khoi phuc do phan giai goc.${R}")
            }
            "7" -> {
                println("${CYN}=== ALL-IN-ONE Optimization ===${R}")
                // 1. Animation
                root("settings put global window_animation_scale 0")
                root("settings put global transition_animation_scale 0")
                root("settings put global animator_duration_scale 0")
                root("settings put global force_hw_accel 1")
                println("  ${GRN}[1/4] Animation OFF, GPU ON${R}")
                // 2. Cache
                for (pkg in packages) {
                    root(
                            "rm -rf /data/data/$pkg/cache/* /data/data/$pkg/code_cache/* /sdcard/Android/data/$pkg/cache/*"
                    )
                }
                root("sync && echo 3 > /proc/sys/vm/drop_caches")
                println("  ${GRN}[2/4] Cache cleared${R}")
                // 3. Kill bg
                val killList =
                        listOf(
                                "com.google.android.gms",
                                "com.google.android.googlequicksearchbox",
                                "com.android.chrome"
                        )
                killList.forEach { root("am force-stop $it 2>/dev/null") }
                println("  ${GRN}[3/4] Background apps killed${R}")
                // 4. Battery
                root("settings put system screen_brightness 50")
                root("settings put system screen_brightness_mode 0")
                root("settings put system haptic_feedback_enabled 0")
                println("  ${GRN}[4/4] Battery optimized${R}")
                println("\n${GRN}${BOLD}ALL-IN-ONE DONE! Game se muot hon nhieu.${R}")
            }
            "0" -> return
            else -> println("${RED}Lua chon khong hop le.${R}")
        }
        pause()
    }
}

fun autoRejoin(props: Properties) {
    val tabs = loadTabsFromFile(props)
    if (tabs.isEmpty()) {
        println("Khong co tab nao trong output.txt/tabs.txt/config.")
        return
    }

    val fallbackPlaceId =
            props.getProperty("place_id").orEmpty().ifBlank { prompt("Nhap placeId mac dinh") }
    if (fallbackPlaceId.isNotBlank() && !fallbackPlaceId.all { it.isDigit() }) {
        println("PlaceId mac dinh khong hop le.")
        return
    }

    if (fallbackPlaceId.isNotBlank()) {
        props.setProperty("place_id", fallbackPlaceId)
        saveConfig(props)
    }

    val delay = props.getProperty("delay_seconds").toLongOrNull() ?: 3L
    val autoBlock = props.getProperty("auto_block").toBoolean()
    val statusMethod = props.getProperty("status_method") ?: "combined"
    val joinMethod = props.getProperty("join_method") ?: "deeplink_package"
    val maxRetryKill = props.getProperty("max_retry_kill", "5").toIntOrNull() ?: 5
    // Thoi gian doi sau rejoin truoc khi check game da vao chua (seconds)
    val joinVerifySeconds = props.getProperty("join_verify_seconds", "15").toLongOrNull() ?: 15L
    // Thoi gian cho them de xac nhan game session sau khi app foreground (seconds)
    val gameSessionWaitSeconds =
            props.getProperty("game_session_wait_seconds", "30").toLongOrNull() ?: 30L
    // V5: Thoi gian toi da cho FOREGROUND_NO_GAME truoc khi kill (mac dinh 30s)
    val noGameTimeoutMs =
            (props.getProperty("no_game_timeout_seconds", "15").toLongOrNull() ?: 15L) * 1000L
    // V5: Thoi gian white screen stuck truoc khi kill (mac dinh 30s)
    val whiteScreenTimeoutSec =
            props.getProperty("white_screen_timeout_seconds", "30").toLongOrNull() ?: 30L

    println("Auto rejoin dang chay bang su cho ${tabs.size} app/tab. Ctrl+C de dung.")
    println("Mode: $statusMethod | Max retry kill: $maxRetryKill")

    println("\n=== Initial Status Check ===")
    val initialReports = mutableListOf<String>()
    for ((index, tab) in tabs.withIndex()) {
        val report = checkStatus(tab.packageName)
        val installedOk = report.status != "NOT_INSTALLED"
        val runningOk = report.status == "FOREGROUND"
        val inGame = report.inGameSession
        val userOk = report.userId.isNotBlank()
        val placeOk = tab.expectedPlaceId.isBlank() || report.gameId == tab.expectedPlaceId

        println("[${index + 1}] ${tab.label} (${tab.packageName})")
        println("  ${okMark(installedOk)} installed/status: ${report.status}")
        println("  ${okMark(inGame)} inGameSession: $inGame")
        println("  ${okMark(report.sessionCookie)} session cookie metadata")
        println("  ${okMark(userOk)} userId: ${report.userId.ifBlank { "(not found)" }}")
        println(
                "  ${okMark(placeOk)} gameId/placeId: ${report.gameId.ifBlank { "(not found)" }} expected=${tab.expectedPlaceId.ifBlank { "(none)" }}"
        )
        println(
                "  ${okMark(runningOk)} running: pid=${report.pid.ifBlank { "(none)" }} focused=${report.focused}"
        )

        initialReports.add(
                "[${index+1}] ${tab.label} | UID:${report.userId.ifBlank{"?"}} | GID:${report.gameId.ifBlank{"?"}} | Status:${report.status} | InGame:$inGame"
        )
    }
    println("============================\n")

    val webhookUrl = props.getProperty("webhook_url").orEmpty()
    if (webhookUrl.isNotBlank() && initialReports.isNotEmpty()) {
        val desc = "${tabs.size} tabs dang duoc giam sat\n\n" + initialReports.joinToString("\n")
        sendWebhook(webhookUrl, ">> Bat dau Auto Rejoin", desc, 0x3498DB)
    }

    // V6: KHONG clear logcat toan bo (gay mat game session log cua cac package khac)
    // Dung --pid filtering thay the
    println("${GRN}V6: Su dung per-pid logcat filtering (khong clear logcat toan bo).${R}")

    val lastRejoinTime = mutableMapOf<String, Long>()
    val rejoinCooldownMs =
            (props.getProperty("rejoin_cooldown_seconds", "30").toLongOrNull() ?: 30L) * 1000L
    // Dem so lan retry kill cho tung tab (reset khi vao game thanh cong)
    val retryKillCount = mutableMapOf<String, Int>()
    // V4: Thoi diem bat dau phat hien FOREGROUND_NO_GAME (de tinh timeout 90s)
    val foregroundNoGameFirstSeen = mutableMapOf<String, Long>()
    // V6: Cache username + userId de khong goi API moi vong
    val cachedUsername = mutableMapOf<String, String>()
    val cachedUserId = mutableMapOf<String, String>()
    println(
            "Rejoin cooldown: ${rejoinCooldownMs / 1000}s | NoGame timeout: ${noGameTimeoutMs / 1000}s | Delay: ${delay}s"
    )

    while (true) {
        for (tab in tabs) {
            val pkg = tab.packageName
            // Per-package placeId: priority tab.expectedPlaceId > per-package config > global
            val targetPlaceId =
                    tab.expectedPlaceId.ifBlank {
                        getPlaceIdForPackage(pkg, props).ifBlank { fallbackPlaceId }
                    }
            // Per-package VIP code
            val vipCode = getVipCodeForPackage(pkg, props)

            // === V6 FAST-PATH: Check Lua heartbeat file truoc (per-package, ~0.3s) ===
            // Neu game OK -> skip tat ca heavy checks
            val luaQuickCheck = readHeartbeatForPkg(pkg)
            var fastPathSkip = false
            if (luaQuickCheck.startsWith("alive:", ignoreCase = true)) {
                val luaParts = luaQuickCheck.removePrefix("alive:").split(":")
                val luaTs = luaParts.getOrNull(0)?.trim()?.toLongOrNull()
                val luaStatus = luaParts.getOrNull(1)?.trim() ?: ""
                if (luaTs != null && luaStatus == "ingame") {
                    val luaAge = System.currentTimeMillis() / 1000 - luaTs
                    if (luaAge < 45) {
                        // Game dang chay tot, Lua heartbeat fresh -> SKIP tat ca
                        val uid = cachedUserId[pkg] ?: "?"
                        val uname = cachedUsername[pkg] ?: "?"
                        println(
                                "[${LocalDateTime.now()}] ${tab.label} ($pkg) | User:$uname($uid) | FAST:OK (lua=${luaAge}s ago) -> FOREGROUND"
                        )
                        retryKillCount[pkg] = 0
                        foregroundNoGameFirstSeen.remove(pkg)
                        fastPathSkip = true
                    }
                }
            }
            if (fastPathSkip) continue

            // === FULL CHECK: Chi chay khi fast-path khong xac nhan duoc ===
            val report = checkStatusBatched(pkg)

            // Cache userId + username (chi fetch 1 lan)
            if (report.userId.isNotBlank() && cachedUserId[pkg].isNullOrBlank()) {
                cachedUserId[pkg] = report.userId
                val uname = fetchUsername(report.userId)
                if (!uname.isNullOrBlank()) cachedUsername[pkg] = uname
            }

            val userText = cachedUserId[pkg]?.ifBlank { report.userId.ifBlank { "?" } } ?: "?"
            val nameText = cachedUsername[pkg]?.ifBlank { "?" } ?: "?"
            val gameText = report.gameId.ifBlank { "?" }
            val inGameText = if (report.inGameSession) "InGame:YES" else "InGame:NO"

            println(
                    "[${LocalDateTime.now()}] ${tab.label} ($pkg) | User:$nameText($userText) | GID:$gameText | $inGameText -> ${report.status} (target=${targetPlaceId.ifBlank { "(none)" }})"
            )

            // === V6: PER-PACKAGE LOGCAT DEBUG ===
            if (report.pid.isNotBlank()) {
                // V7: Expanded patterns for game detection in tiny buffer
                val pkgLogSnippet = rootOut("logcat -d -t 200 --pid=${report.pid} 2>/dev/null | grep -iE 'kicked|disconnect|lost connection|same account|onGameStarted|Replicator|WebSocketTraceError|DataModelPatchConfigurer|FunctionMarshal' | tail -n 3").trim()
                if (pkgLogSnippet.isNotBlank()) {
                    println("  ${DIM}[LOGCAT --pid=${report.pid}]:${R}")
                    pkgLogSnippet.lines().take(3).forEach { println("    ${DIM}$it${R}") }
                }
            }

            // === RESET retry count + noGame timer khi da vao game thanh cong ===
            if (report.status == "FOREGROUND" && report.inGameSession) {
                if ((retryKillCount[pkg] ?: 0) > 0) {
                    println("${GRN}-> ${tab.label}: Da vao game thanh cong! Reset retry count.${R}")
                }
                retryKillCount[pkg] = 0
                foregroundNoGameFirstSeen.remove(pkg)
            }

            // === V4: LOADING_GAME: Lua bao game dang load -> doi, KHONG KILL ===
            if (report.status == "LOADING_GAME") {
                println(
                        "${CYN}-> ${tab.label}: Game dang LOADING (Lua heartbeat active). Doi game load xong...${R}"
                )
                foregroundNoGameFirstSeen.remove(pkg) // Reset timer vi game dang load
                continue
            }

            // === V5: FOREGROUND_NO_GAME: check VNG popup NGAY, roi deep check sau 90s ===
            if (report.status == "FOREGROUND_NO_GAME" && targetPlaceId.isNotBlank()) {
                val now2 = System.currentTimeMillis()
                val firstSeen = foregroundNoGameFirstSeen.getOrPut(pkg) { now2 }
                val elapsedMs = now2 - firstSeen
                val remainingS = ((noGameTimeoutMs - elapsedMs) / 1000).coerceAtLeast(0)

                // === V5: VNG POPUP EARLY DETECT: kill ngay, KHONG doi 90s ===
                val vngEarly = hasVngPopup(pkg)
                val whiteScreenEarly = hasWhiteScreenStuck(pkg, whiteScreenTimeoutSec)
                if (vngEarly || whiteScreenEarly) {
                    val earlyReason =
                            if (vngEarly) "VNG popup"
                            else "White screen stuck >${whiteScreenTimeoutSec}s"
                    println(
                            "${RED}-> ${tab.label}: $earlyReason DETECTED! KILL NGAY (khong doi 90s)${R}"
                    )
                    foregroundNoGameFirstSeen.remove(pkg)
                    val currentRetry = retryKillCount[pkg] ?: 0
                    if (currentRetry < maxRetryKill) {
                        val attempt = currentRetry + 1
                        retryKillCount[pkg] = attempt
                        root("am force-stop $pkg")
                        Thread.sleep(2000)
                        // V6: KHONG clear logcat toan bo
                        clearHeartbeatForPkg(pkg)
                        if (vipCode.isNotBlank()) {
                            startVipServer(pkg, vipCode, props)
                        } else {
                            startPlace(
                                    pkg,
                                    targetPlaceId,
                                    joinMethod,
                                    props,
                                    props.getProperty("job_id", "")
                            )
                        }
                        println("-> Rejoin sent ($earlyReason). Doi ${joinVerifySeconds}s...")
                        if (webhookUrl.isNotBlank()) {
                            sendWebhook(
                                    webhookUrl,
                                    "<> VNG/WhiteScreen Rejoin",
                                    "**${tab.label}** ($pkg)\nLan thu: $attempt/$maxRetryKill\nLy do: $earlyReason",
                                    0xF39C12
                            )
                        }
                        lastRejoinTime[pkg] = System.currentTimeMillis()
                        for (i in 1..(joinVerifySeconds / 2)) {
                            Thread.sleep(2000)
                            val currentPid = rootOut("pidof $pkg").trim()
                            if (currentPid.isNotBlank() &&
                                            rootOut(
                                                            "logcat -d -t 200 --pid=$currentPid | grep -iE 'onGameStarted|Replicator created|WebSocketTraceError|DataModelPatchConfigurer|FunctionMarshal'"
                                                    )
                                                    .isNotBlank()
                            ) {
                                println("${GRN}-> Game load xong!${R}")
                                break
                            }
                        }
                        // V5: KHONG clear logcat o day de giu game session log
                    } else {
                        println("${RED}-> ${tab.label}: DA THU $maxRetryKill lan. Bo qua.${R}")
                        retryKillCount[pkg] = 0
                    }
                    continue
                }

                // === CHUA HET 90s: thu thap data, KHONG KILL ===
                if (elapsedMs < noGameTimeoutMs) {
                    println(
                            "${YEL}-> ${tab.label}: FOREGROUND_NO_GAME da ${elapsedMs/1000}s. Thu thap data... (${remainingS}s con lai truoc deep check)${R}"
                    )
                    continue
                }

                // === HET 90s: DEEP CHECK tat ca 3 method ===
                println(
                        "${CYN}-> ${tab.label}: FOREGROUND_NO_GAME da ${noGameTimeoutMs/1000}s. DEEP CHECK 3 method...${R}"
                )

                // METHOD 1: Lua script heartbeat
                var luaData = readHeartbeatForPkg(pkg)
                val luaAlive = luaData.startsWith("alive:", ignoreCase = true)
                val luaIngame = luaData.contains(":ingame:", ignoreCase = true)
                val luaKicked = luaData.startsWith("kicked", ignoreCase = true)
                var luaStale = false
                if (luaAlive) {
                    val luaTs =
                            luaData.removePrefix("alive:").split(":").firstOrNull()?.toLongOrNull()
                    if (luaTs != null && (System.currentTimeMillis() / 1000 - luaTs) > 45)
                            luaStale = true
                }
                val m1Ok = luaAlive && luaIngame && !luaStale && !luaKicked
                val m1Problem = luaKicked || luaStale || (!luaAlive && luaData.isBlank())

                // METHOD 2: Logcat
                val pid2 = rootOut("pidof $pkg").trim()
                var m2Ok = false
                var m2Problem = false
                if (pid2.isNotBlank()) {
                    // V7: Expanded patterns to survive tiny logcat buffer
                    val gameLog2 =
                            rootOut(
                                    "logcat -d -t 500 --pid=$pid2 | grep -iE 'onGameStarted|Replicator created|ExperienceSession|WebSocketTraceError|DataModelPatchConfigurer|FunctionMarshal|AssetProvider' | grep -v 'disconnect' | tail -n 3"
                            )
                    val kickLog2 =
                            rootOut(
                                    "logcat -d -t 500 --pid=$pid2 | grep -iE 'kicked|disconnect|lost connection|same account' | tail -n 3"
                            )
                    m2Ok = gameLog2.isNotBlank() && kickLog2.isBlank()
                    m2Problem = kickLog2.isNotBlank() || (gameLog2.isBlank() && pid2.isNotBlank())
                } else {
                    m2Problem = true // PID mat = crash
                }

                // METHOD 3: API Presence
                val userId2 = report.userId.ifBlank { getUserIdFromSharedPrefs(pkg) }
                var m3Ok = false
                var m3Problem = false
                if (userId2.isNotBlank()) {
                    val pt = fetchPresenceType(userId2)
                    if (pt != null) {
                        m3Ok = (pt == 2) // InGame
                        m3Problem =
                                (pt == 0 || pt == 1) // Offline hoac Online(Web) = khong trong game
                    }
                }

                println(
                        "  ${if(m1Ok) "${GRN}[OK]" else if(m1Problem) "${RED}[FAIL]" else "${YEL}[???]"}${R} Method 1 (Script): lua=${luaData.take(60).ifBlank{"(none)"}}"
                )
                // Method 2: show logcat evidence (V7 expanded patterns)
                val m2GameEvidence = if (pid2.isNotBlank()) rootOut("logcat -d -t 300 --pid=$pid2 | grep -iE 'onGameStarted|Replicator created|WebSocketTraceError|DataModelPatchConfigurer|FunctionMarshal' | tail -n 2").trim() else ""
                val m2KickEvidence = if (pid2.isNotBlank()) rootOut("logcat -d -t 300 --pid=$pid2 | grep -iE 'kicked|disconnect|lost connection|same account' | tail -n 2").trim() else ""
                println(
                        "  ${if(m2Ok) "${GRN}[OK]" else if(m2Problem) "${RED}[FAIL]" else "${YEL}[???]"}${R} Method 2 (Logcat --pid=$pid2): game=${if(m2GameEvidence.isNotBlank()) "YES" else "NO"} kick=${if(m2KickEvidence.isNotBlank()) "YES" else "NO"}"
                )
                if (m2KickEvidence.isNotBlank()) {
                    println("    ${DIM}Kick evidence: ${m2KickEvidence.lines().lastOrNull()?.take(120) ?: ""}${R}")
                }
                println(
                        "  ${if(m3Ok) "${GRN}[OK]" else if(m3Problem) "${RED}[FAIL]" else "${YEL}[???]"}${R} Method 3 (API): presenceType=${report.presenceType} userId=${userId2.ifBlank{"?"}}"
                )

                // === BAT KY method nao bao OK -> KHONG KILL ===
                if (m1Ok || m3Ok) {
                    println("${GRN}-> DEEP CHECK: Co method bao game OK. KHONG KILL.${R}")
                    foregroundNoGameFirstSeen.remove(pkg)
                    continue
                }

                // === TAT CA method deu bao problem -> KILL + REJOIN ===
                if (m1Problem && m2Problem && m3Problem) {
                    foregroundNoGameFirstSeen.remove(pkg)
                    val currentRetry = retryKillCount[pkg] ?: 0
                    if (currentRetry < maxRetryKill) {
                        val attempt = currentRetry + 1
                        retryKillCount[pkg] = attempt

                        val vngDetected = hasVngPopup(pkg)
                        val reason = buildString {
                            if (vngDetected) append("VNG popup")
                            if (luaKicked) append(if (isNotEmpty()) " + " else "")
                            if (luaKicked) append("Lua:kicked")
                            if (luaStale) append(if (isNotEmpty()) " + " else "")
                            if (luaStale) append("Lua:stale")
                            if (m2Problem) append(if (isNotEmpty()) " + " else "")
                            if (m2Problem) append("Logcat:problem")
                            if (m3Problem) append(if (isNotEmpty()) " + " else "")
                            if (m3Problem) append("API:not_ingame")
                            if (isEmpty()) append("All methods confirm problem")
                        }
                        println(
                                "${RED}-> ${tab.label}: DEEP CHECK CONFIRM co van de ($reason). KILL va REJOIN ($attempt/$maxRetryKill)${R}"
                        )

                        root("am force-stop $pkg")
                        Thread.sleep(2000)
                        // V6: Clear per-package heartbeat
                        clearHeartbeatForPkg(pkg)

                        if (vipCode.isNotBlank()) {
                            startVipServer(pkg, vipCode, props)
                        } else {
                            startPlace(
                                    pkg,
                                    targetPlaceId,
                                    joinMethod,
                                    props,
                                    props.getProperty("job_id", "")
                            )
                        }
                        println("-> Rejoin sent. Doi ${joinVerifySeconds}s cho game load...")
                        if (webhookUrl.isNotBlank()) {
                            sendWebhook(
                                    webhookUrl,
                                    "<> Deep Check Rejoin",
                                    "**${tab.label}** ($pkg)\nLan thu: $attempt / $maxRetryKill\nLy do: $reason",
                                    0xF39C12
                            )
                        }
                        lastRejoinTime[pkg] = System.currentTimeMillis()

                        for (i in 1..(joinVerifySeconds / 2)) {
                            Thread.sleep(2000)
                            val currentPid = rootOut("pidof $pkg").trim()
                            if (currentPid.isNotBlank() &&
                                            rootOut(
                                                            "logcat -d -t 200 --pid=$currentPid | grep -iE 'onGameStarted|Replicator created|WebSocketTraceError|DataModelPatchConfigurer|FunctionMarshal'"
                                                    )
                                                    .isNotBlank()
                            ) {
                                println("${GRN}-> Game load xong!${R}")
                                break
                            }
                        }
                        // V5: KHONG clear logcat de giu game session log cho lan check tiep
                    } else {
                        println("${RED}-> ${tab.label}: DA THU $maxRetryKill lan. Bo qua.${R}")
                        if (webhookUrl.isNotBlank()) {
                            sendWebhook(
                                    webhookUrl,
                                    "[X] That Bai Rejoin",
                                    "**${tab.label}** ($pkg)\nDa thu $maxRetryKill lan. Can kiem tra thu cong!",
                                    0xE74C3C
                            )
                        }
                        retryKillCount[pkg] = 0
                    }
                } else {
                    // Khong du data de quyet dinh -> doi them
                    println("${YEL}-> DEEP CHECK: Khong du data. Doi them 1 vong nua...${R}")
                }
                continue
            }

            // === LUA DANG TU REJOIN: doi Lua lam xong, khong can thiep ===
            if (report.status == "LUA_REJOINING") {
                println(
                        "${YEL}-> ${tab.label}: Lua script dang tu rejoin bang TeleportService. Doi 15s...${R}"
                )
                Thread.sleep(15000)
                // Check lai: neu sau 15s lua van chua rejoin duoc thi main.kts se xu ly
                val recheck = readHeartbeatForPkg(pkg)
                if (recheck.startsWith("rejoining", ignoreCase = true)) {
                    println(
                            "${RED}-> Lua rejoin FAIL (van dang rejoining). Main.kts se kill + rejoin...${R}"
                    )
                    clearHeartbeatForPkg(pkg)
                    // De cho vong lap ke tiep se bat KICKED_OR_DISCONNECTED
                } else if (recheck.startsWith("alive", ignoreCase = true)) {
                    println("${GRN}-> Lua rejoin THANH CONG! Game dang chay lai.${R}")
                }
                continue
            }

            val shouldRejoin =
                    when (report.status) {
                        "NOT_RUNNING_OR_EXITED",
                        "KICKED_OR_DISCONNECTED",
                        "CRASHED_RECENTLY",
                        "RUNNING_BACKGROUND" -> true
                        else -> false
                    }

            // V5: Khi bi KICKED hoac CRASHED, PHAI rejoin bat ke autoBlock vi app da die/kicked
            val isKickedOrCrashed =
                    report.status in listOf("KICKED_OR_DISCONNECTED", "CRASHED_RECENTLY")

            val now = System.currentTimeMillis()
            val lastRejoin = lastRejoinTime[pkg] ?: 0L
            val inCooldown = (now - lastRejoin) < rejoinCooldownMs

            if (shouldRejoin &&
                            targetPlaceId.isNotBlank() &&
                            (isKickedOrCrashed || !(autoBlock && report.focused)) &&
                            !inCooldown
            ) {
                // V5: Force stop truoc khi rejoin neu app van dang chay (kicked nhung chua thoat)
                if (report.pid.isNotBlank()) {
                    println("${YEL}-> Force-stop $pkg truoc khi rejoin (pid=${report.pid})${R}")
                    root("am force-stop $pkg")
                    Thread.sleep(1000)
                }
                // V5: Clear Delta crash files cu de khong loop
                root("rm -f /sdcard/Delta/Crashes/*.txt 2>/dev/null")

                // Reset retry count khi rejoin tu trang thai NOT_RUNNING/KICKED (khac voi
                // FOREGROUND_NO_GAME)
                retryKillCount[pkg] = 0

                if (vipCode.isNotBlank()) {
                    startVipServer(pkg, vipCode, props)
                } else {
                    startPlace(
                            pkg,
                            targetPlaceId,
                            joinMethod,
                            props,
                            props.getProperty("job_id", "")
                    )
                }
                val msg =
                        "Dang Rejoin ${tab.label} ($pkg) | UID: $userText | GID: $gameText | Ly do: ${report.status}"
                println("-> $msg")
                if (webhookUrl.isNotBlank()) {
                    val fields =
                            """[
                        {"name": "Trang thai", "value": "${report.status}", "inline": true},
                        {"name": "User ID", "value": "$userText", "inline": true},
                        {"name": "Game ID", "value": "$gameText", "inline": true},
                        {"name": "Place ID", "value": "$targetPlaceId", "inline": true}
                    ]"""
                    sendWebhook(
                            webhookUrl,
                            ">> Dang Rejoin Game",
                            "**${tab.label}** ($pkg)",
                            0x2ECC71,
                            fields
                    )
                }
                // Ghi nhan thoi diem rejoin
                lastRejoinTime[pkg] = System.currentTimeMillis()
                // QUAN TRONG: Doi game load xong truoc khi check lai (smart wait)
                println("-> Doi toi da ${joinVerifySeconds}s cho game load...")
                for (i in 1..(joinVerifySeconds / 2)) {
                    Thread.sleep(2000)
                    if (hasVngPopup(pkg)) {
                        println("${YEL}-> Phat hien VNG popup som! Ngat wait de kill lai...${R}")
                        break
                    }
                    val currentPid = rootOut("pidof $pkg").trim()
                    if (currentPid.isNotBlank() &&
                                    rootOut(
                                                    "logcat -d -t 200 --pid=$currentPid | grep -iE 'onGameStarted|Replicator created|WebSocketTraceError|DataModelPatchConfigurer|FunctionMarshal'"
                                            )
                                            .isNotBlank()
                    ) {
                        println("${GRN}-> Game load xong som!${R}")
                        break
                    }
                }
                // V6: KHONG clear logcat toan bo (pha huy game session log cua package khac)
                val postPid = rootOut("pidof $pkg").trim()
                if (postPid.isNotBlank() &&
                                rootOut(
                                                "logcat -d -t 200 --pid=$postPid | grep -iE 'onGameStarted|Replicator created|WebSocketTraceError|DataModelPatchConfigurer|FunctionMarshal'"
                                        )
                                        .isNotBlank()
                ) {
                    // V6: Chi clear heartbeat file, giu logcat nguyen
                    clearHeartbeatForPkg(pkg)
                    println("-> Game load xong, clear heartbeat file.")
                } else {
                    println("${YEL}-> Game chua load xong, giu logcat de debug.${R}")
                }
            } else if (shouldRejoin && inCooldown) {
                val remaining = (rejoinCooldownMs - (now - lastRejoin)) / 1000
                println("${YEL}-> COOLDOWN: Doi them ${remaining}s truoc khi rejoin lai $pkg${R}")
            }
        }

        Thread.sleep(delay * 1000L)
    }
}

// === AUTO DEPENDENCY CHECK (giong Python try/except) ===
fun ensureDependencies() {
    // Map: ten binary -> ten package can install
    val deps =
            mapOf(
                    "sqlite3" to "sqlite",
                    "curl" to "curl",
                    "wget" to "wget",
                    "grep" to "grep",
                    "stat" to "coreutils",
                    "ps" to "procps"
            )

    val missing = mutableListOf<String>()

    for ((bin, pkg) in deps) {
        // Bug #8 + #9 fix: 'command' la shell builtin, ProcessBuilder khong chay duoc
        // Dung root("which ...") de check ca trong root context
        val check =
                try {
                    // Thu Termux truoc
                    val p = ProcessBuilder("which", bin).start()
                    val out = p.inputStream.bufferedReader().readText().trim()
                    p.waitFor(5, java.util.concurrent.TimeUnit.SECONDS)
                    if (out.isNotBlank()) true
                    else {
                        // Fallback: check qua root (binary co the o /system/bin)
                        val rootCheck =
                                root("which $bin 2>/dev/null || command -v $bin 2>/dev/null")
                        rootCheck.output.isNotBlank()
                    }
                } catch (_: Exception) {
                    // Fallback cuoi: check qua root
                    val rootCheck = root("which $bin 2>/dev/null")
                    rootCheck.output.isNotBlank()
                }

        if (!check) {
            println("${YEL}[!] Khong tim thay '$bin' -> can cai dat package '$pkg'${R}")
            missing.add(pkg)
        }
    }

    if (missing.isNotEmpty()) {
        val uniquePkgs = missing.distinct().joinToString(" ")
        println("${CYN}[*] Dang tu dong cai dat: $uniquePkgs ...${R}")
        println("${DIM}    (pkg update && pkg install $uniquePkgs -y)${R}")

        // Update truoc
        try {
            val update = ProcessBuilder("pkg", "update", "-y").inheritIO().start()
            update.waitFor()
        } catch (_: Exception) {}

        // Install
        try {
            val install =
                    ProcessBuilder(*("pkg install $uniquePkgs -y".split(" ").toTypedArray()))
                            .inheritIO()
                            .start()
            val exitCode = install.waitFor()
            if (exitCode == 0) {
                println("${GRN}[OK] Da cai dat thanh cong: $uniquePkgs${R}")
            } else {
                println(
                        "${RED}[!] Cai dat co loi (exit=$exitCode). Thu chay thu cong: pkg install $uniquePkgs -y${R}"
                )
            }
        } catch (e: Exception) {
            println("${RED}[!] Loi khi chay pkg install: ${e.message}${R}")
            println("${YEL}    Thu chay thu cong: pkg install $uniquePkgs -y${R}")
        }
    } else {
        println("${GRN}[OK] Tat ca dependencies da san sang.${R}")
    }
}

// === KHOI DONG MULTI-WINDOW / SPLIT SCREEN ===
fun enableMultiWindow() {
    val check1 = rootOut("settings get global enable_freeform_support").trim()
    val check2 = rootOut("settings get global force_resizable_activities").trim()

    if (check1 != "1" || check2 != "1") {
        println("${CYN}[*] Dang bat tinh nang Multi-Window (Freeform / Split Screen)...${R}")
        root("settings put global enable_freeform_support 1")
        root("settings put global force_resizable_activities 1")
        println("${GRN}[OK] Da bat Multi-Window support!${R}")
        println(
                "${YEL}[!] LUU Y: Ban can KHOI DONG LAI (Restart) may ao/dien thoai de ap dung!${R}"
        )
        println(
                "${DIM}Sau khi restart, ban co the nhan giu bieu tuong app trong trinh da nhiem de chon 'Split Screen' hoac 'Freeform'.${R}"
        )
    }
}

// --- ADVANCED ENGINE HELPERS ---
// Bug #1 fix: Di chuyen len TRUOC while(true) de .kts co the goi duoc
fun urlEncode(value: String): String = java.net.URLEncoder.encode(value, "UTF-8")

fun fetchDynamicValue(initialData: String, props: java.util.Properties): String? {
    val url = props.getProperty("challenge_url", "").trim()
    if (url.isBlank()) return null

    println("-> Dang fetch dynamic value tu challenge_url...")
    return try {
        val conn1 = java.net.URL(url).openConnection() as java.net.HttpURLConnection
        conn1.requestMethod = "POST"
        conn1.doOutput = true
        conn1.setRequestProperty("X-Initial-Data", initialData)
        conn1.setRequestProperty("Content-Length", "0")
        conn1.outputStream.use { it.write(ByteArray(0)) }

        val challenge = if (conn1.responseCode == 403) conn1.getHeaderField("x-challenge") else null
        conn1.disconnect()

        if (challenge.isNullOrBlank()) return null

        val conn2 = java.net.URL(url).openConnection() as java.net.HttpURLConnection
        conn2.requestMethod = "POST"
        conn2.doOutput = true
        conn2.setRequestProperty("X-Initial-Data", initialData)
        conn2.setRequestProperty("x-challenge", challenge)
        conn2.setRequestProperty("Content-Length", "0")
        conn2.outputStream.use { it.write(ByteArray(0)) }

        val result =
                if (conn2.responseCode in 200..299) conn2.getHeaderField("x-result-value") else null
        conn2.disconnect()
        result
    } catch (e: Exception) {
        null
    }
}

if (!checkRoot()) kotlin.system.exitProcess(1)

ensureDependencies()

enableMultiWindow()


// --- PLUTO REJOIN WEB-CONTROLLED LOOP ---
println("================================================")
println("* Pluto Rejoin VIP (Web Control) *")
println("================================================")

val props = loadConfig()
var webServerUrl = props.getProperty("web_server_url", "")
if (webServerUrl.isBlank()) {
    webServerUrl = prompt("Nhap Web Server URL (VD: http://147.135.213.131:20376)").trim()
    if (webServerUrl.isBlank()) webServerUrl = "http://147.135.213.131:20376"
    props.setProperty("web_server_url", webServerUrl)
    saveConfig(props)
}

val plutoKey = prompt("Vui long nhap Key de ket noi").trim()
if (plutoKey.isBlank()) {
    println("Key khong duoc de trong!")
    kotlin.system.exitProcess(1)
}
val deviceName = prompt("Ten thiet bi nay (VD: May 1)").trim()

var deviceId = ""
var pollInterval = 30000L
try {
    val authUrl = java.net.URL("$webServerUrl/api/login")
    val conn = authUrl.openConnection() as java.net.HttpURLConnection
    conn.requestMethod = "POST"
    conn.setRequestProperty("Content-Type", "application/json")
    conn.doOutput = true
    val payload = "{\"key\":\"$plutoKey\",\"deviceName\":\"$deviceName\"}"
    conn.outputStream.write(payload.toByteArray())
    
    if (conn.responseCode == 200) {
        val resp = conn.inputStream.bufferedReader().readText()
        if (resp.contains("\"success\":true")) {
            val idMatch = "\"deviceId\":\"([^\"]+)\"".toRegex().find(resp)
            if (idMatch != null) deviceId = idMatch.groupValues[1]
            val intMatch = "\"interval\":([0-9]+)".toRegex().find(resp)
            if (intMatch != null) pollInterval = intMatch.groupValues[1].toLong()
            println("[OK] Da ket noi VIP! Device ID: $deviceId")
            println("Da ket noi, vui long khong dong cai nay de server luon giao tiep voi cai nay.")
        } else {
            println("Key khong hop le.")
            kotlin.system.exitProcess(1)
        }
    } else {
        println("Sai Key hoac qua so lan thu. Code: ${conn.responseCode}")
        kotlin.system.exitProcess(1)
    }
} catch (e: Exception) {
    println("Loi ket noi server: ${e.message}")
    kotlin.system.exitProcess(1)
}

var lastScreenTime = 0L
while (true) {
    try {
        val pkgs = selectedPackages(props)
        val tabsOnline = pkgs.size
        
        val syncUrl = java.net.URL("$webServerUrl/api/sync")
        val conn = syncUrl.openConnection() as java.net.HttpURLConnection
        conn.requestMethod = "POST"
        conn.setRequestProperty("Content-Type", "application/json")
        conn.doOutput = true
        val payload = "{\"deviceId\":\"$deviceId\",\"uptime\":\"VIP Active\",\"tabsOnline\":$tabsOnline,\"tabsOffline\":0,\"cookies\":1,\"changedAccs\":0}"
        conn.outputStream.write(payload.toByteArray())
        
        if (conn.responseCode == 200) {
            val resp = conn.inputStream.bufferedReader().readText()
            val intMatch = "\"interval\":([0-9]+)".toRegex().find(resp)
            if (intMatch != null) pollInterval = intMatch.groupValues[1].toLong()
            
            if (resp.contains("\"command\":\"ACTION_START\"")) {
                println(">>> Nhan lenh Web: ACTION_START (Mo tool)")
            }
            if (resp.contains("\"command\":\"ACTION_STOP\"")) {
                println(">>> Nhan lenh Web: ACTION_STOP (Tat tool)")
                pkgs.forEach { root("am force-stop $it") }
            }
            // Add other commands here when they are implemented
        }
        
        val now = System.currentTimeMillis()
        if (now - lastScreenTime > pollInterval) {
            lastScreenTime = now
            val screenFile = java.io.File("/sdcard/screen.png")
            root("screencap -p /sdcard/screen.png")
            if (screenFile.exists()) {
                val boundary = "WebKitFormBoundary" + System.currentTimeMillis()
                val uploadUrl = java.net.URL("$webServerUrl/api/upload_screen")
                val uploadConn = uploadUrl.openConnection() as java.net.HttpURLConnection
                uploadConn.requestMethod = "POST"
                uploadConn.setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
                uploadConn.doOutput = true
                
                val out = uploadConn.outputStream
                out.write("--$boundary\r\n".toByteArray())
                out.write("Content-Disposition: form-data; name=\"deviceId\"\r\n\r\n".toByteArray())
                out.write("$deviceId\r\n".toByteArray())
                out.write("--$boundary\r\n".toByteArray())
                out.write("Content-Disposition: form-data; name=\"screenshot\"; filename=\"screen.png\"\r\n".toByteArray())
                out.write("Content-Type: image/png\r\n\r\n".toByteArray())
                out.write(screenFile.readBytes())
                out.write("\r\n--$boundary--\r\n".toByteArray())
                out.flush()
                uploadConn.responseCode
                out.close()
            }
        }
        
    } catch (e: Exception) {
        // Silent catch for background loop
    }
    
    Thread.sleep(3000)
}

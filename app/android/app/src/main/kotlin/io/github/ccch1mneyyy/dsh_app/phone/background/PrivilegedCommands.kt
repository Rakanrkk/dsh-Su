package io.github.ccch1mneyyy.dsh_app.phone.background

/** The shell commands the ADB and root paths run: fixed text plus the APK paths ApplicationInfo gives. */
object PrivilegedCommands {
    fun appProcess(sourceDir: String, splitSourceDirs: List<String> = emptyList()): String {
        val paths = listOf(sourceDir) + splitSourceDirs
        require(paths.all { it.startsWith('/') && '\u0000' !in it && ':' !in it })
        return "CLASSPATH=${quote(paths.joinToString(":"))} exec /system/bin/app_process /system/bin ${PrivilegedDisplayMain::class.java.name}"
    }

    private fun quote(value: String): String = "'" + value.replace("'", "'\\''") + "'"
}

/** The managers start their default shell; the fixed app_process command goes through stdin. */
internal object RootCommands {
    val suEntries = listOf("su", "/system/bin/su", "/debug_ramdisk/su")
    fun shell(): List<List<String>> = suEntries.map { listOf(it) }
}

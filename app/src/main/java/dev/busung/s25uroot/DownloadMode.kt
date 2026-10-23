package dev.busung.s25uroot

import android.app.KeyguardManager
import android.content.Context
import android.provider.Settings

/**
 * Whether Samsung's Download Mode (Odin) is reachable, and from which of the three independent
 * doors - mirrors the live check DirtyFrag's own "Odin" pill makes, read from that APK this
 * session, so this project's own escape-hatch discipline (verify Download Mode before anything
 * privileged) is a status line instead of something done by hand each time.
 *
 * Three-valued for the same reason [KernelSuStatus] is: the module check needs root, and a phone
 * that has not run root yet this boot cannot be told "blocked" on the strength of two doors when
 * the third was never looked at. [Unverified] is that distinction kept rather than thrown away.
 */
internal enum class DownloadModeStatus {
    /** At least one door is open: no lockscreen credential, Maintenance Mode, or BackDownload. */
    Reachable,

    /** Every door was checked, including the module, and none is open. */
    Blocked,

    /** The two doors this app can always check are both closed, and the third needs root this
     *  boot does not have yet - so this is not the same claim as [Blocked]. */
    Unverified,
}

/**
 * The three doors, read individually, so a popup can say which one is open rather than just a
 * colour. Each is nullable where the reading itself can fail or need something this boot may not
 * have: [backDownloadActive] is null with no root to ask, and [maintenanceModeActive] is null if
 * the platform ever stops exposing the setting this reads.
 */
internal data class DownloadModeReading(
    val status: DownloadModeStatus,
    val lockscreenSecured: Boolean,
    val maintenanceModeActive: Boolean?,
    val backDownloadActive: Boolean?,
)

internal object DownloadMode {
    /**
     * Samsung's own key for whether Maintenance Mode is the account currently running. Read with
     * [Settings.Global] because that is where this project's one other Samsung-specific read
     * ([dev.busung.s25uroot.AdbPairing]'s wireless-debugging flags) already looks.
     *
     * Confirmed on a live phone (not in Maintenance Mode): the key reads as absent, not present
     * with value 0 - Samsung's own convention for a setting like this is to write it only when the
     * feature is turned on, not to carry a permanent off value. [Settings.Global.getInt] with no
     * default throws [android.provider.Settings.SettingNotFoundException] for an absent key, which
     * this read used to fold into "could not tell" along with every other failure - so every phone
     * not currently in Maintenance Mode read as unknown instead of correctly reading as off. The
     * default-value overload below reads an absent key as off, which is what it actually means here;
     * a key that does exist (on or off) still reads its real value either way.
     */
    private const val MAINTENANCE_MODE_SETTING = "sec_maintenance_mode"

    /** The module BackDownload installs, and the two files that mean "present but switched off". */
    private const val BACKDOWNLOAD_MODULE_ID = "dmc_at_zygisk"
    private const val MODULES_DIR = "/data/adb/modules"

    /**
     * Reads what can be read without root: the lockscreen and Maintenance Mode. Safe to call from
     * the main thread - both are local reads, no shell and no network.
     */
    fun readWithoutRoot(context: Context): Pair<Boolean, Boolean?> {
        val lockscreenSecured = context.getSystemService(KeyguardManager::class.java)
            ?.isDeviceSecure
            ?: true // Unreadable reads as secured: the closed door is the safe guess, not the open one.
        val maintenanceModeActive = runCatching {
            Settings.Global.getInt(context.contentResolver, MAINTENANCE_MODE_SETTING, 0)
        }.map { it == 1 }.getOrNull()
        return lockscreenSecured to maintenanceModeActive
    }

    /**
     * Whether BackDownload is installed and switched on, by the same two files KernelSU's own
     * module list uses for "present but disabled" - a directory with neither `disable` nor
     * `remove` next to it. Null when no root shell could run the check at all, which [status]
     * turns into [DownloadModeStatus.Unverified] rather than a door read as closed.
     */
    private fun backDownloadActive(): Boolean? {
        val dir = "$MODULES_DIR/$BACKDOWNLOAD_MODULE_ID"
        val command = "[ -d $dir ] && [ ! -e $dir/disable ] && [ ! -e $dir/remove ] && echo 1 || echo 0"
        val result = KernelSuRuntime.rootShell(command) ?: return null
        return result.output.trim().endsWith("1")
    }

    /**
     * The full reading: the two free doors read now, the module door read only when a run has
     * root to ask with - off the main thread, since [backDownloadActive] may start a shell.
     */
    fun read(context: Context): DownloadModeReading {
        val (lockscreenSecured, maintenanceModeActive) = readWithoutRoot(context)
        if (!lockscreenSecured || maintenanceModeActive == true) {
            // Already open by a free door - the module is worth knowing for the popup, but reading
            // it first would only block an answer this doesn't need to give.
            val backDownload = runCatching { backDownloadActive() }.getOrNull()
            return DownloadModeReading(DownloadModeStatus.Reachable, lockscreenSecured, maintenanceModeActive, backDownload)
        }
        val backDownloadActive = runCatching { backDownloadActive() }.getOrNull()
        val status = when (backDownloadActive) {
            true -> DownloadModeStatus.Reachable
            false -> DownloadModeStatus.Blocked
            null -> DownloadModeStatus.Unverified
        }
        return DownloadModeReading(status, lockscreenSecured, maintenanceModeActive, backDownloadActive)
    }
}

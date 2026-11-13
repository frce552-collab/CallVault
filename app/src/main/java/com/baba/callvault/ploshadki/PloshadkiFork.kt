/*
 * ABP Call — Ploshadki.kz fork of CallVault (https://github.com/madkongo/CallVault),
 * itself a fork of ShizuCallRecorder. GPL-3.0-or-later with the Section 7 terms in LICENSE.
 */
package com.baba.callvault.ploshadki

/**
 * Fork-wide switches for ABP Call. One place, so every behaviour that differs from upstream
 * CallVault is visible here and easy to diff against upstream when syncing.
 */
object PloshadkiFork {
    /**
     * Upstream self-update from GitHub (madkongo/CallVault releases) is off: it would offer a
     * different app (other package id, brand and signing key) to our managers.
     */
    const val UPSTREAM_UPDATES_ENABLED = false

    /**
     * ABP Call updates itself from OUR GitHub Releases (owner decisions 2026-11-09/10):
     * upstream release → fork sync → owner merge → ploshadki-release.yml publishes a signed
     * [UPDATE_APK_ASSET] → phones pick it up (daily check, silent install over ADB).
     * The update must be signed with the same key as the installed app (see ApkVerifier).
     */
    const val UPDATES_ENABLED = true

    /**
     * GitHub "owner/repo" whose latest (non-prerelease) release is the update source. PRIVATE repo
     * (owner decision 2026-11-10): the APK is not public; phones read it with the read-only token
     * BuildConfig.UPDATE_READ_TOKEN that only ploshadki-release.yml bakes in.
     */
    const val UPDATE_REPO = "frce552-collab/abp-call-releases"

    /** Release asset name the updater downloads. ploshadki-release.yml uploads exactly this. */
    const val UPDATE_APK_ASSET = "ABP-Call.apk"

    /**
     * Expiry date (ISO yyyy-MM-dd) of the read-only key for [UPDATE_REPO] (secret RELEASES_READ_TOKEN, baked into the
     * APK as BuildConfig.UPDATE_READ_TOKEN). After it, phones get no updates until a release with a new key is installed.
     * Blank = not known yet. The scheduled workflow ploshadki-key-reminder.yml reads THIS line and opens an Issue
     * [UPDATE_KEY_REMIND_DAYS] days before; it also reads the real expiry from GitHub and flags a mismatch.
     */
    // TODO(owner): дата окончания ключа abp-call-read, например "2027-05-13". Пока не дана владельцем.
    const val UPDATE_KEY_EXPIRES = ""

    /** How many days before [UPDATE_KEY_EXPIRES] the owner is reminded (Issue + e-mail from GitHub). */
    const val UPDATE_KEY_REMIND_DAYS = 14L

    /**
     * Phone notice "updates no longer arrive" — ONLY once the key has expired (by [UPDATE_KEY_EXPIRES] or GitHub answering
     * 401 to the update check). Never before: the manager cannot do anything about a key that is still valid.
     */
    const val UPDATE_KEY_EXPIRED_NOTICE = true

    /** Upstream donation (Ko-fi/PayPal) and community (Telegram) links are not shown. */
    const val UPSTREAM_SUPPORT_LINKS_ENABLED = false

    /**
     * App calls are recorded ONLY for these packages: WhatsApp Business (owner decision 2026-10-21, #161) and
     * ordinary WhatsApp (owner decision 13.11.2026: managers talk to clients through it too; it replaces the
     * #161 rule "never personal WhatsApp"). Both are captured the same way — the call audio of the app's own
     * uid. Any other app (Telegram, Signal, …) and an unidentified app are never recorded (fail-closed).
     */
    const val WHATSAPP_BUSINESS_PACKAGE = "com.whatsapp.w4b"
    const val WHATSAPP_PACKAGE = "com.whatsapp"
    val RECORDABLE_VOIP_PACKAGES: Set<String> = setOf(WHATSAPP_BUSINESS_PACKAGE, WHATSAPP_PACKAGE)

    /**
     * Automatic short summary of every ABP call on the phone (owner decision 11.11.2026: «включить»).
     * After the on-device transcript is DONE, the on-device summariser runs once and its text travels
     * with the transcript to the server (air.wa-capture.transcript.v1 `summary`). The summary model
     * (about 2.6 GB) is downloaded on Wi-Fi only, once. Phones with too little memory send the
     * transcript without a summary. See [AutoSummaryPlan].
     */
    const val AUTO_SUMMARY_ENABLED = true

    /** Phones below this total RAM never summarise in the background (the model needs about 3.5 GB). */
    const val AUTO_SUMMARY_MIN_TOTAL_RAM_BYTES = 5_000_000_000L

    /** How long the transcript waits for its summary before it is sent without one. */
    const val AUTO_SUMMARY_WAIT_MINUTES = 40L

    /**
     * Self-healing recorder (owner request 12.11.2026, field report CallVault 2.4.9 on Xiaomi HyperOS: the helper
     * died after a day idle and the app waited for a tap and Wi-Fi).
     *
     * Off-Wi-Fi recording (the loopback listener inside adbd) is ON by default: once armed — needs Wi-Fi and
     * Wireless debugging once per boot — the helper can be relaunched over 127.0.0.1 with no Wi-Fi at all, as
     * long as USB debugging stays on. Upstream keeps it opt-in; for company phones it is the only way to
     * recover without a person. The listener is RSA-gated (only this app's paired key is accepted).
     */
    const val OFFLINE_RECORDING_BY_DEFAULT = true

    /** Liveness check from an alarm that fires in Doze (the 60 s watchdog stops in deep sleep). */
    const val LIVENESS_ALARM_MINUTES = 5L

    /** After this long down with recovery failing, a loud ongoing notification asks the manager to tap. */
    const val RECORDER_DOWN_ALERT_MINUTES = 10L

    /**
     * Reboot recovery (owner request 12.11.2026, field report CallVault 2.4.9, Xiaomi HyperOS).
     *
     * A reboot forgets "the user switched Wireless debugging off". That mark was made in the previous boot — and
     * a phone shutting down (HyperOS switches the switch off on the way down, while Wi-Fi still reads connected)
     * looks exactly like a tap. Left standing it made the keep-alive stand down after every reboot
     * (ReadinessNotice.WD_OFF_BY_USER) and refused the Wireless-debugging write, so recording never came back
     * without a person. A switch-off the user makes during this boot is still respected.
     */
    const val REBOOT_FORGETS_WD_OFF_BY_USER = true

    /**
     * If the recorder dies right after CallVault itself switched Wireless debugging off, keep it on for the rest
     * of the boot. Upstream switches it off only while USB debugging holds adbd up — measured safe on OnePlus /
     * Samsung, never measured on HyperOS. If a ROM does restart adbd on that write, this stops the loop after one
     * death instead of repeating it on every relaunch.
     */
    const val KEEP_WD_ON_IF_DROP_KILLED_RECORDER = true

    /**
     * A Wi-Fi join (or Wireless debugging switched on) while a relaunch is already running is not dropped: the
     * relaunch that started without Wi-Fi fails, and one more is made straight after it, instead of waiting for
     * the next watchdog tick or the Doze-batched alarm (up to ~10 min).
     */
    const val RETRY_RELAUNCH_REFUSED_WHILE_BUSY = true

    /** Window after our own Wireless-debugging switch-off in which a recorder death is put down to it. */
    const val WD_DROP_DEATH_WINDOW_MS = 10_000L

    /**
     * Owner decision 12.11.2026: an update is installed only by an administrator. The Update button (Home banner and
     * the "update available" popup) asks for the administrator PIN first; the daily check only tells the manager
     * "version X is available, the administrator will install it". The PIN is set on the phone when the app is set up
     * and stored only as a salted PBKDF2 hash ([AdminPin]).
     */
    const val ADMIN_PIN_FOR_UPDATES = false // owner 12.11 (later): «PIN не надо» — we decide which releases go out.

    /**
     * Owner decision 12.11.2026: phones install OUR main-channel (Latest) releases by themselves. Only releases we
     * promoted after the one-phone check reach them (new builds are pilot/pre-release). Unattended install happens
     * only in [AUTO_INSTALL_FROM_HOUR]–[AUTO_INSTALL_UNTIL_HOUR] Almaty time, on unmetered Wi-Fi and charging, never
     * during a call (CallInProgressGate), silently through the recorder's shell; never a dialog.
     */
    const val AUTO_INSTALL_UPDATES = true
    const val AUTO_INSTALL_FROM_HOUR = 20
    const val AUTO_INSTALL_UNTIL_HOUR = 8
    const val AUTO_INSTALL_ZONE = "Asia/Almaty"

    /**
     * Owner request 13.11.2026 ("как у CallVault"): the "update available" notification says "ABP Call update
     * available" and carries an **Update** button; the Home banner and popup have the same button. Tapped → installs at
     * once (never during a call: it waits for the call to end). Not tapped → the night auto-install above. See
     * [AbpUpdatePrompt].
     */
    const val UPDATE_NOW_BUTTON = true

    /**
     * Owner request 13.11.2026: after an update, check that recording came back; if not, the loud "Recording stopped"
     * ([AbpRecorderDownAlert]). Runs [POST_UPDATE_CHECK_MINUTES] after the replace. See [AbpPostUpdateCheck].
     */
    const val POST_UPDATE_CHECK = true
    const val POST_UPDATE_CHECK_MINUTES = 3L

    /**
     * CRM status file (owner decision 12.11.2026, variant "A": through Google Drive, no HTTP). The phone overwrites ONE
     * `abp-call-status.json` in the Drive folder the recordings go to — every [STATUS_FILE_INTERVAL_MINUTES] and on
     * every change of recorder state — with the recorder state, its reason and the last 24 h of calls that have no
     * recording. If Drive will not overwrite, one `abp-call-status-YYYYMMDD-HH.json` per hour. See [AbpStatusFile].
     */
    const val STATUS_FILE_ENABLED = true

    const val STATUS_FILE_INTERVAL_MINUTES = 10L

    /**
     * Setup wizard step "Phone check" (owner request 12.11.2026): USB debugging; on Xiaomi/HyperOS "USB debugging
     * (Security settings)" and Autostart; "Keep Wireless debugging on for recording"; battery "No restrictions";
     * big system updates only after the test-phone check (owner 13.11: security updates are NOT blocked). Read where Android allows, otherwise the settings screen opens and the person
     * marks it done. See [PhoneCheckState]. Off-Wi-Fi recording stays on the reliability step just before it.
     */
    const val WIZARD_PHONE_CHECKS = true
}

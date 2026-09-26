package tickguard.runner

import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.deleteIfExists
import kotlin.io.path.exists
import kotlin.io.path.readLines
import kotlin.io.path.writeText

/** Which sleeves the page switched to trade live every day, kept across restarts. */
internal interface DailySwitch {
    fun load(): Set<String>

    fun save(sleeves: Set<String>)
}

/** Kept nowhere: for tests of everything but the switch. */
internal object NoDailySwitch : DailySwitch {
    override fun load(): Set<String> = emptySet()

    override fun save(sleeves: Set<String>) = Unit
}

/**
 * The switch as a file on the data volume, one sleeve id per line, beside the
 * placement journal: it outlives the container, and a person can read it,
 * or delete it to switch daily orders off by hand.
 */
internal class DailySwitchFile(
    private val path: Path,
) : DailySwitch {
    companion object {
        /** The file's name in the journal's directory, which the journal leaves alone. */
        const val NAME = "daily-live"
    }

    /** Unreadable reads as off: a switch that cannot be read must not be taken as on. */
    override fun load(): Set<String> =
        try {
            if (path.exists()) {
                path
                    .readLines()
                    .map { it.trim() }
                    .filter { it.isNotEmpty() }
                    .toSet()
            } else {
                emptySet()
            }
        } catch (unreadable: IOException) {
            log.error("daily switch unreadable, taken as off: {}", unreadable.message)
            emptySet()
        }

    override fun save(sleeves: Set<String>) {
        if (sleeves.isEmpty()) {
            path.deleteIfExists()
        } else {
            path.parent?.let { Files.createDirectories(it) }
            path.writeText(sleeves.sorted().joinToString("\n", postfix = "\n"))
        }
    }
}

private val log: Logger = LoggerFactory.getLogger(DailySwitchFile::class.java)

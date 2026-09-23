package tickguard.tools

import java.nio.file.Files
import java.nio.file.Path

/**
 * The `.env` file the original's scripts read with `node --env-file`, merged
 * the way Node merges it: a variable already set in the environment wins over
 * the file, so a one-off override on the command line needs no edit.
 *
 * Only for operator entry points. The service itself takes its configuration
 * from the environment it is started with.
 */
fun environment(
    file: Path = Path.of(".env"),
    process: Map<String, String> = System.getenv(),
): Map<String, String> = parseDotEnv(if (Files.exists(file)) Files.readString(file) else "") + process

/** `KEY=value` lines; `#` comments and blank lines skipped; one pair of surrounding quotes removed. */
fun parseDotEnv(text: String): Map<String, String> =
    text
        .lineSequence()
        .map { it.trim() }
        .filter { it.isNotEmpty() && !it.startsWith("#") && '=' in it }
        .associate { line ->
            val key =
                line
                    .substringBefore('=')
                    .trim()
                    .removePrefix("export ")
                    .trim()
            key to unquote(line.substringAfter('=').trim())
        }

private fun unquote(value: String): String {
    val quoted = value.length >= 2 && value.first() == value.last() && value.first() in "\"'"
    return if (quoted) value.substring(1, value.length - 1) else value
}

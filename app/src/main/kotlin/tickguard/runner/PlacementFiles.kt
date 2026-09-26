package tickguard.runner

import tickguard.execution.OrderRequest
import tickguard.execution.PlacementJournal
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.deleteIfExists
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name
import kotlin.io.path.writeText

/**
 * The placement journal as one file per order, named by its client order id,
 * in a directory on the data volume. A file is the one thing still writable
 * when the database is not, and a person can read and remove it by hand.
 */
internal class PlacementFiles(
    directory: String,
) : PlacementJournal {
    private val dir: Path = Path.of(directory).also { Files.createDirectories(it) }

    override fun begin(request: OrderRequest) {
        dir.resolve(request.clientOrderId).writeText("${request.sleeve} ${request.side} ${request.symbol}\n")
    }

    override fun done(request: OrderRequest) {
        dir.resolve(request.clientOrderId).deleteIfExists()
    }

    /** Only order entries: every client order id starts with `tg-`, and the daily switch lives here too. */
    override fun pending(): List<String> =
        dir
            .listDirectoryEntries()
            .map {
                it.name
            }.filter { it.startsWith("tg-") }
            .sorted()
}

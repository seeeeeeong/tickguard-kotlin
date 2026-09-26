package tickguard.execution

/**
 * Orders about to be sent, written down before the request and crossed out
 * once the order's sleeve is recorded. Toss's order records carry no client
 * order id, so an order accepted but never tagged cannot be traced back to
 * its sleeve; an entry left here after a crash or a failed tag write is the
 * only trace of it, and the executor refuses to send anything while one is.
 */
interface PlacementJournal {
    /** Written before [request] is sent. Throws if it cannot be: then nothing is sent. */
    fun begin(request: OrderRequest)

    /** The order was refused, or accepted and tagged: nothing is unaccounted for. */
    fun done(request: OrderRequest)

    /** Client order ids begun and never done. */
    fun pending(): List<String>
}

/** No journal: for tests and tools that never place an order. */
object NoJournal : PlacementJournal {
    override fun begin(request: OrderRequest) = Unit

    override fun done(request: OrderRequest) = Unit

    override fun pending(): List<String> = emptyList()
}

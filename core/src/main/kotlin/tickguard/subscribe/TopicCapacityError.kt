package tickguard.subscribe

/**
 * Refusing the add is better than sending an oversized declaration. The server
 * answers that with `too-many-topics` and keeps the *previous* subscription
 * set, so the caller would see no error and no change — the worst combination.
 */
class TopicCapacityError(
    val requested: Int,
    val capacity: Int = MAX_TOPICS_PER_CONNECTION,
) : IllegalStateException(
        "Subscribing would need $requested topics but a connection holds $capacity. " +
            "Drop topics or shard them across connections.",
    )

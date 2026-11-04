package com.nexa.app.core.swarm

object SwarmConstants {
    const val CHUNK_SIZE = 64 * 1024 // 64 KB per chunk
    const val MAX_CACHE_BYTES = 2L * 1024L * 1024L * 1024L // 2 GB LRU ceiling
    const val PRUNE_TARGET_BYTES = 1536L * 1024L * 1024L   // 1.5 GB target upon prune
    const val BACKPRESSURE_BUFFER_CEILING = 1024L * 1024L  // 1 MB buffer ceiling
}

package com.dreamdisplays.platform.server.storage

import com.dreamdisplays.api.display.data.DisplayCustomDataLimits
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.util.Base64

/** Stores a display's addon data in one text column as JSON of key to Base64 value. */
internal object CustomDataCodec {
    private val logger = LoggerFactory.getLogger(CustomDataCodec::class.java)
    private val serializer = MapSerializer(String.serializer(), String.serializer())

    /** The column value for [data], or `null` when there is nothing to store. */
    fun encode(data: Map<String, ByteArray>): String? {
        if (data.isEmpty()) return null
        val encoded = data.mapValues { Base64.getEncoder().encodeToString(it.value) }
        return Json.encodeToString(serializer, encoded)
    }

    /** The data in [column]; anything unreadable or over the limits is dropped rather than failing the load. */
    fun decode(column: String?): Map<String, ByteArray> {
        if (column.isNullOrBlank()) return emptyMap()
        return runCatching {
            Json.decodeFromString(serializer, column).mapValues { Base64.getDecoder().decode(it.value) }
        }.onFailure { logger.warn("Dropping unreadable display addon data.", it) }
            .getOrNull()
            ?.takeIf { DisplayCustomDataLimits.isValid(it) }
            ?: emptyMap()
    }
}

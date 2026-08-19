package com.dreamdisplayx.core.protocol.common

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals

class SchemaDriftTest {
    /**
     * The committed .proto artifact must structurally match the schema derived from the packet
     * classes. Comments and formatting are ignored, so hand-written comments are allowed.
     */
    @Test
    fun committedSchemaIsUpToDate() {
        val committed = File("src/main/proto/dreamdisplayx.proto")
        assertEquals(
            normalizeProtoSchema(generateProtoSchema()),
            normalizeProtoSchema(if (committed.exists()) committed.readText() else ""),
            "dreamdisplayx.proto is out of date; regenerate with ./gradlew :protocol:generateProto",
        )
    }
}

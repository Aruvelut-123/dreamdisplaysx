package com.dreamdisplayx.core.protocol.common

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals

class SchemaDriftTest {
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

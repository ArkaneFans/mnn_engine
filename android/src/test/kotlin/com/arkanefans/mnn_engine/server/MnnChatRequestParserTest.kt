package com.arkanefans.mnn_engine.server

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class MnnChatRequestParserTest {
    @Test
    fun parsesSupportedChatParameters() {
        val request = MnnChatRequestParser.parse(
            """{
              "model":"local/qwen",
              "messages":[
                {"role":"system","content":"be concise"},
                {"role":"user","content":"hello"}
              ],
              "stream":true,
              "temperature":0.3,
              "top_p":0.8,
              "max_tokens":64,
              "n":1
            }""",
        )

        assertEquals("local/qwen", request.model)
        assertEquals(true, request.stream)
        assertEquals(0.3, request.temperature)
        assertEquals(0.8, request.topP)
        assertEquals(64, request.maxTokens)
        assertEquals(2, request.messages.size)
    }

    @Test
    fun rejectsMultipleChoices() {
        val error = assertFailsWith<IllegalArgumentException> {
            MnnChatRequestParser.parse(
                """{"messages":[{"role":"user","content":"hello"}],"n":2}""",
            )
        }
        assertTrue(error.message.orEmpty().contains("n must be 1"))
        assertFailsWith<IllegalArgumentException> {
            MnnChatRequestParser.parse(
                """{"messages":[{"role":"user","content":"hello"}],"n":1.5}""",
            )
        }
    }

    @Test
    fun rejectsUnsupportedTools() {
        val error = assertFailsWith<IllegalArgumentException> {
            MnnChatRequestParser.parse(
                """{"messages":[{"role":"user","content":"hello"}],"tools":[]}""",
            )
        }
        assertTrue(error.message.orEmpty().contains("tools is not supported"))
    }

    @Test
    fun rejectsOutOfRangeParameters() {
        assertFailsWith<IllegalArgumentException> {
            MnnChatRequestParser.parse(
                """{"messages":[{"role":"user","content":"hello"}],"temperature":3}""",
            )
        }
        assertFailsWith<IllegalArgumentException> {
            MnnChatRequestParser.parse(
                """{"messages":[{"role":"user","content":"hello"}],"max_tokens":0}""",
            )
        }
        assertFailsWith<IllegalArgumentException> {
            MnnChatRequestParser.parse(
                """{"messages":[{"role":"user","content":""}]}""",
            )
        }
        assertFailsWith<IllegalArgumentException> {
            MnnChatRequestParser.parse(
                """{"messages":[{"role":"user","content":"hello"}],"temperature":"0.7"}""",
            )
        }
        assertFailsWith<IllegalArgumentException> {
            MnnChatRequestParser.parse(
                """{"messages":[{"role":"user","content":"hello"}],"stream":"true"}""",
            )
        }
    }
}

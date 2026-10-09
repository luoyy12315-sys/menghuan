package com.menghuan.app

import com.menghuan.app.llm.ToolCallAccumulator
import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * tool_calls 流式分片聚合的单测（P3-1，纯 JVM）。
 *
 * 协议事实来源：OpenAI function-calling 指南（只有首个分片带 id/name，
 * 后续分片只带 arguments 增量）+ langchain4j / openai-kotlin 的聚合逻辑。
 */
class ToolCallAccumulatorTest {

    @Test
    fun `单调用分片聚合成完整工具调用`() {
        val acc = ToolCallAccumulator()
        acc.accept(JSONArray("""[{"index":0,"id":"call_abc","type":"function","function":{"name":"get_weather","arguments":""}}]"""))
        acc.accept(JSONArray("""[{"index":0,"function":{"arguments":"{\"location\":"}}]"""))
        acc.accept(JSONArray("""[{"index":0,"function":{"arguments":"\"Tokyo\"}"}}]"""))
        val result = acc.result()
        assertEquals(1, result.size)
        assertEquals("call_abc", result[0].id)
        assertEquals("get_weather", result[0].name)
        assertEquals("{\"location\":\"Tokyo\"}", result[0].argumentsJson)
    }

    @Test
    fun `并行双调用按 index 分槽 参数互不污染`() {
        // 共用一个全局缓冲是经典错误：两个并行调用的 arguments 分片交错到达时会拼成坏 JSON
        val acc = ToolCallAccumulator()
        acc.accept(JSONArray("""[{"index":0,"id":"call_1","function":{"name":"f1","arguments":"{\"a\":"}}]"""))
        acc.accept(JSONArray("""[{"index":1,"id":"call_2","function":{"name":"f2","arguments":"{\"b\":"}}]"""))
        acc.accept(JSONArray("""[{"index":0,"function":{"arguments":"1}"}}]"""))
        acc.accept(JSONArray("""[{"index":1,"function":{"arguments":"2}"}}]"""))
        val result = acc.result()
        assertEquals(2, result.size)
        assertEquals("{\"a\":1}", result.first { it.name == "f1" }.argumentsJson)
        assertEquals("{\"b\":2}", result.first { it.name == "f2" }.argumentsJson)
    }

    @Test
    fun `id 与 name 被网关切片时按追加聚合`() {
        // 官方只切 arguments，但部分兼容网关连 name/id 都切片——追加写法两种都吃得下
        val acc = ToolCallAccumulator()
        acc.accept(JSONArray("""[{"index":0,"id":"call_","function":{"name":"get_","arguments":""}}]"""))
        acc.accept(JSONArray("""[{"index":0,"id":"xyz","function":{"name":"time","arguments":"{}"}}]"""))
        val result = acc.result()
        assertEquals("call_xyz", result[0].id)
        assertEquals("get_time", result[0].name)
    }

    @Test
    fun `JSON null 字段不聚合出字面量 null`() {
        // org.json 会把 JSON null 读成 JSONObject.NULL，若误当字符串追加就出 "null" 垃圾
        val acc = ToolCallAccumulator()
        acc.accept(JSONArray("""[{"index":0,"id":"call_a","function":{"name":"f","arguments":null}}]"""))
        acc.accept(JSONArray("""[{"index":0,"id":null,"function":{"name":null,"arguments":"{}"}}]"""))
        val result = acc.result()
        assertEquals("call_a", result[0].id)
        assertEquals("f", result[0].name)
        assertEquals("{}", result[0].argumentsJson)
    }

    @Test
    fun `index 缺省时按数组位置分槽`() {
        val acc = ToolCallAccumulator()
        acc.accept(JSONArray("""[{"id":"call_1","function":{"name":"f1","arguments":"{}"}}]"""))
        val result = acc.result()
        assertEquals("call_1", result[0].id)
    }

    @Test
    fun `id 全程缺失时补占位 id`() {
        // 回灌协议要求 tool_call_id 对得上：id 缺失也不能交空串
        val acc = ToolCallAccumulator()
        acc.accept(JSONArray("""[{"index":2,"function":{"name":"f","arguments":"{}"}}]"""))
        assertEquals("call_2", acc.result()[0].id)
    }

    @Test
    fun `空聚合器 isEmpty 且结果为空`() {
        val acc = ToolCallAccumulator()
        assertTrue(acc.isEmpty)
        assertTrue(acc.result().isEmpty())
    }
}

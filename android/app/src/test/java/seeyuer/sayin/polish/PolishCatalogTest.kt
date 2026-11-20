package seeyuer.sayin.polish

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PolishCatalogTest {
    private val json = JSONObject(
        """
        {
          "deepseek": {
            "models": {
              "deepseek-flash": {
                "id": "deepseek-flash",
                "name": "DeepSeek Flash",
                "modalities": {"output": ["text"]},
                "reasoning": true,
                "cost": {"input": 0.15, "output": 0.6, "cache_read": 0.003}
              },
              "old-model": {"id": "old-model", "name": "Old", "status": "deprecated", "modalities": {"output": ["text"]}}
            }
          },
          "alibaba-cn": {
            "models": {
              "qwen3.5-flash": {"id": "qwen3.5-flash", "name": "Qwen Flash", "modalities": {"output": ["text"]}}
            }
          },
          "openai": {
            "models": {
              "gpt-6-luna": {"id": "gpt-6-luna", "name": "GPT-6 Luna", "modalities": {"output": ["text"]}}
            }
          },
          "anthropic": {
            "models": {
              "claude-haiku-5-5": {"id": "claude-haiku-5-5", "name": "Claude Haiku 5.5", "modalities": {"output": ["text"]}}
            }
          }
        }
        """.trimIndent()
    )

    @Test fun `filters deprecated and maps official providers`() {
        val catalog = PolishCatalog.parse(json, "sha", "fixture", "now")
        val deepseek = catalog.modelsFor(PolishProvider.DeepSeek)
        assertEquals(1, deepseek.size)
        assertEquals("deepseek-flash", deepseek.single().id)
        assertEquals(0.15, deepseek.single().inputUsdPerMillion!!, 0.0001)
        assertEquals("qwen3.5-flash", catalog.modelsFor(PolishProvider.Qwen).single().id)
        assertEquals("gpt-6-luna", catalog.modelsFor(PolishProvider.OpenAi).single().id)
        assertEquals("claude-haiku-5-5", catalog.modelsFor(PolishProvider.Anthropic).single().id)
        assertTrue(catalog.modelsFor(PolishProvider.Custom).isEmpty())
    }

    @Test fun `text output filter excludes image only models`() {
        val local = JSONObject("""{"deepseek":{"models":{"image":{"id":"image","name":"Image","modalities":{"output":["image"]}},"text":{"id":"text","name":"Text","modalities":{"output":["text"]}}}}}""")
        val catalog = PolishCatalog.parse(local, "sha", "fixture", "now")
        assertEquals(listOf("text"), catalog.modelsFor(PolishProvider.DeepSeek).map { it.id })
    }
}

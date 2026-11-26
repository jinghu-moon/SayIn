package seeyuer.sayin.asr

import org.junit.Assert.assertEquals
import org.junit.Test

class LocalItnTest {
    @Test fun usesExplicitFormatsFromTheVoiceCorpusAndIsIdempotent() {
        val examples = mapOf(
            "会议定在二零二六年十一月二十四号下午三点半。" to "会议定在2026年11月24号下午3点半。",
            "这个项目是两千零二十六年启动的。" to "这个项目是2026年启动的。",
            "十二点零五分" to "12点05分",
            "晚上十点十五分我会到家。" to "晚上10点15分我会到家。",
            "增长了百分之二十" to "增长了20%",
            "通过率只有百分之五点五。" to "通过率只有5.5%。",
            "水位上升了零点五米。" to "水位上升了0.5米。",
            "停止说话后等两秒半。" to "停止说话后等2秒半。",
            "静音二点五秒后结束。" to "静音2.5秒后结束。",
            "可在设置选择二、二点五或三秒。" to "可在设置选择2、2.5 或 3 秒。",
            "可选2、2.5或三秒。" to "可选2、2.5 或 3 秒。",
            "实际用时二十二点五三秒。" to "实际用时22.53秒。",
        )
        for ((input, expected) in examples) {
            assertEquals(input, expected, LocalItn.normalize(input))
            assertEquals(input, expected, LocalItn.normalize(expected))
        }
    }

    @Test fun preservesTechnicalTextAndAmbiguousPhrases() {
        for (input in listOf(
            "我有三点意见，三点一线，近万一有问题。",
            "他十分感谢你，我用了三个GPU，去四川。",
            "https://example.com/百分之二十/三点半，`三点半`，v1.2.3，/tmp/三月五号。",
            "1000%，百分之一分之三，二月三十日，二十四点整",
            "二二点五三秒",
            "格式是2.5 秒。",
            "选项是第二秒和第三秒。",
            "`等待二点五秒`、v2.5.3、https://example.com/二点五秒",
        )) {
            assertEquals(input, LocalItn.normalize(input))
        }
    }
}


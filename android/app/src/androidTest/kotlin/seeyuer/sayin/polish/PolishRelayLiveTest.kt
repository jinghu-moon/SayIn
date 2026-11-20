package seeyuer.sayin.polish

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Opt-in live relay check; credentials come from instrumentation arguments, never source control. */
@RunWith(AndroidJUnit4::class)
class PolishRelayLiveTest {
    @Test fun relayModelListConnectionAndPolish() {
        val args = InstrumentationRegistry.getArguments()
        val key = args.getString("relayKey").orEmpty()
        assumeTrue("relayKey not supplied", key.isNotBlank())
        val config = PolishConfig(
            provider = PolishProvider.Custom,
            protocol = PolishProtocol.Chat,
            baseUrl = args.getString("relayUrl") ?: "https://api.shuaiapi.com/v1",
            model = args.getString("relayModel") ?: "gpt-6.2-sol",
            key = key,
            auth = PolishAuth.Bearer,
            apiVersion = "",
            maxTokensField = PolishMaxTokensField.MaxTokens,
            thinking = PolishThinking.None,
        )
        val list = PolishModelListClient(config).fetch()
        assertTrue("model list failed: ${list.error}", list.error == null)
        assertTrue("model missing: ${config.model}", config.model in list.models)

        val test = PolishClient(config).test()
        assertTrue("connection failed: $test", test is PolishCallResult.Success)

        if (args.getString("relayPolish") == "true") {
            val result = PolishClient(config).call(PolishMode.Text, text = "um this is a test of the ai polish feature")
            assertTrue("polish failed: $result", result is PolishCallResult.Success)
            assertNotNull((result as PolishCallResult.Success).text)
        }
    }
}

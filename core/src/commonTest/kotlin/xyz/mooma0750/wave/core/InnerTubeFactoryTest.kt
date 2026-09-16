package xyz.mooma0750.wave.core

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respondOk
import kotlin.test.Test
import kotlin.test.assertSame

class InnerTubeFactoryTest {
    @Test
    fun createsInnerTubeOnSuppliedClient() {
        val httpClient = HttpClient(MockEngine { respondOk() })
        val innerTube = createInnerTube(httpClient)

        assertSame(httpClient, innerTube.httpClient)

        innerTube.close()
        httpClient.close()
    }
}

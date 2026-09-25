package xyz.mooma0750.wave.core

import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/** テストの fixture（匿名で取った応答、`resources/fixtures/<name>.json`）。 */
internal object Fixtures {
    fun text(name: String): String = requireNotNull(Fixtures::class.java.getResource("/fixtures/$name.json")) { "fixture $name" }.readText()

    fun json(name: String): JsonObject = Json.parseToJsonElement(text(name)) as JsonObject
}

/** MockEngine の JSON の応答（200）。 */
internal fun MockRequestHandleScope.jsonResponse(body: String): HttpResponseData = respond(body, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))

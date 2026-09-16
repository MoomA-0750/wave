package xyz.mooma0750.wave.core

import com.metrolist.innertubex.InnerTube
import io.ktor.client.HttpClient

/** innertubex の [InnerTube] を作る唯一の入口。M8 で WaveSession に置き換える。 */
fun createInnerTube(httpClient: HttpClient): InnerTube = InnerTube(httpClient = httpClient)

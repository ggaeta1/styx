/**
 * Copyright (C) 2013-2017 Expedia Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.hotels.styx.plugins

import com.github.tomakehurst.wiremock.client.WireMock._
import com.hotels.styx._
import com.hotels.styx.support.api.BlockingObservables.stringResponse
import com.hotels.styx.api.HttpInterceptor.Chain
import com.hotels.styx.api.HttpRequest.Builder.get
import com.hotels.styx.api.{HttpRequest, HttpResponse}
import com.hotels.styx.support.backends.FakeHttpServer
import com.hotels.styx.support.server.UrlMatchingStrategies._
import com.hotels.styx.support.configuration.{HttpBackend, Origins, StyxConfig}
import io.netty.handler.codec.http.HttpHeaders.Names._
import io.netty.handler.codec.http.HttpHeaders.Values._
import org.scalatest.{FunSpec, ShouldMatchers}
import rx.Observable
import rx.schedulers.Schedulers

import scala.concurrent.duration._

class AsyncPluginResponseSpec extends FunSpec
  with StyxProxySpec
  with StyxClientSupplier
  with ShouldMatchers {
  val mockServer = FakeHttpServer.HttpStartupConfig().start()

  override val styxConfig = StyxConfig(plugins = List("asyncDelayPlugin" -> new AsyncContentDelayPlugin()))

  override protected def beforeAll(): Unit = {
    super.beforeAll()
    mockServer.start()

    styxServer.setBackends(
      "/foobar" -> HttpBackend("appOne", Origins(mockServer), responseTimeout = 5.seconds)
    )
  }

  override protected def afterAll(): Unit = {
    mockServer.stop()
    super.afterAll()
  }

  describe("Styx as a plugin container") {
    it("Proxies requests when plugin processes response headers asynchronously on a separate thread pool") {
      mockServer.stub(urlStartingWith("/foobar"), aResponse
        .withStatus(200)
        .withHeader(TRANSFER_ENCODING, CHUNKED)
        .withBody("I should be here!")
      )

      val request = get(styxServer.routerURL("/foobar"))
        .addHeader("Content-Length", "0")
        .build()

      val response = stringResponse(client.sendRequest(request))

      mockServer.verify(1, getRequestedFor(urlStartingWith("/foobar")))
      response.body() should be ("I should be here!")
    }
  }
}

import rx.lang.scala.ImplicitFunctionConversions._

class AsyncContentDelayPlugin extends PluginAdapter {
  override def intercept(request: HttpRequest, chain: Chain): rx.Observable[HttpResponse] = {
    chain.proceed(request)
      .observeOn(Schedulers.computation())
      .flatMap((response: HttpResponse) => {
        Thread.sleep(1000)
        Observable.just(response)
      })

  }
}

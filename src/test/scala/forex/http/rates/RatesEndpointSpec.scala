package forex.http.rates

import java.time.OffsetDateTime
import java.util.concurrent.TimeUnit

import cats.effect.IO
import cats.implicits._
import forex.domain.Currency._
import forex.domain.{Currency, Price, Rate, Timestamp}
import forex.http.rates.ExternalProtocol.ExternalGetApiResponse
import forex.http.rates.Protocol.GetApiRequest
import forex.services.RatesCache
import org.http4s._
import org.http4s.client.Client
import org.scalatest.matchers.should.Matchers
import org.scalatest.prop.TableDrivenPropertyChecks
import org.scalatest.propspec.AnyPropSpec

import scala.concurrent.duration.Duration

class RatesEndpointSpec extends AnyPropSpec with TableDrivenPropertyChecks with Matchers with RatesSpecContext {

  import forex.http._

  property("Get rates - happy path") {
    // GIVEN
    val validCurrencyRateRequests =
      Table(
        "apiRequest",
        GetApiRequest(from = EUR, to = USD),
        GetApiRequest(from = USD, to = AUD),
        GetApiRequest(from = AUD, to = CAD),
        GetApiRequest(from = CAD, to = CHF),
        GetApiRequest(from = CHF, to = GBP),
        GetApiRequest(from = GBP, to = NZD),
        GetApiRequest(from = NZD, to = JPY),
        GetApiRequest(from = JPY, to = SGD)
      )

    forAll(validCurrencyRateRequests) { apiRequest: GetApiRequest =>
      // GIVEN
      val httpApp: HttpApp[IO] = buildHttpApp()
      val uri = uri"/rates"
        .withQueryParam("from", apiRequest.from)
        .withQueryParam("to", apiRequest.to)
      val httpApiRequest = IO.pure(Request[IO](method = GET, uri = uri))

      // WHEN
      val result = (for {
        request <- httpApiRequest
        response <- httpApp.run(request)
      } yield {
        response
      }).unsafeRunSync

      result.status should be(Ok)

      // THEN
      val resultResponse =
        result.attemptAs[TestGetApiResponse]
        .value
        .unsafeRunSync()
        .right.get

      Currency.fromString(resultResponse.from) should be (apiRequest.from)
      Currency.fromString(resultResponse.to) should be (apiRequest.to)

    }
  }

  property("Get rates - 400(BadRequest) due to unsupported currency") {
    // GIVEN
    val currencyNotSupportedRequest =
      Table(
        "apiRequest",
        ("EUR", "UAH"),
        ("UAH", "USD"),
        ("PLN", "GBP")
      )

    forAll(currencyNotSupportedRequest) { apiRequest =>
      // GIVEN
      val httpApp: HttpApp[IO] = buildHttpApp()
      val uri = uri"/rates"
        .withQueryParam("from", apiRequest._1)
        .withQueryParam("to", apiRequest._2)
      val httpApiRequest = IO.pure(Request[IO](method = GET, uri = uri))

      // WHEN
      val result = (for {
        request <- httpApiRequest
        response <- httpApp.run(request)
      } yield {
        response
      }).unsafeRunSync

      // THEN
      result.status should be(BadRequest)

      val resultResponse =
        result.attemptAs[TestGetApiErrorResponse]
          .value
          .unsafeRunSync()
          .right.get

      resultResponse.errors.size should be (1)
      resultResponse.errors.head should include ("Exchange rate for")
      resultResponse.errors.head should include ("is not supported")
    }
  }

  property("Get rates - 400(BadRequest) due to both currencies are unsupported") {
    // GIVEN
    val currencyNotSupportedRequest =
      Table(
        "apiRequest",
        ("PLN", "UAH"),
        ("UAH", "HUF"),
        ("HUF", "RON")
      )

    forAll(currencyNotSupportedRequest) { apiRequest =>
      // GIVEN
      val httpApp: HttpApp[IO] = buildHttpApp()
      val uri = uri"/rates"
        .withQueryParam("from", apiRequest._1)
        .withQueryParam("to", apiRequest._2)
      val httpApiRequest = IO.pure(Request[IO](method = GET, uri = uri))

      // WHEN
      val result = (for {
        request <- httpApiRequest
        response <- httpApp.run(request)
      } yield {
        response
      }).unsafeRunSync

      // THEN
      result.status should be(BadRequest)

      val resultResponse =
        result.attemptAs[TestGetApiErrorResponse]
          .value
          .unsafeRunSync()
          .right.get

      resultResponse.errors.size should be (2)
      resultResponse.errors.head should include ("Exchange rate for")
      resultResponse.errors.head should include ("is not supported")
      resultResponse.errors.tail.head should include ("Exchange rate for")
      resultResponse.errors.tail.head should include ("is not supported")
    }
  }

  property("Get rates - 429(TooManyRequests) due to API quota reached") {
    // GIVEN
    val currencyNotSupportedRequest =
      Table(
        "apiRequest",
        GetApiRequest(from = GBP, to = NZD),
        GetApiRequest(from = NZD, to = JPY),
        GetApiRequest(from = JPY, to = SGD)
      )

    forAll(currencyNotSupportedRequest) { apiRequest =>
      // GIVEN
      val httpApp: HttpApp[IO] = buildHttpAppWithThrottlingLimit(throttlingLimit = 1)
      val uri = uri"/rates"
        .withQueryParam("from", apiRequest.from)
        .withQueryParam("to", apiRequest.to)
      val httpApiRequest = IO.pure(Request[IO](method = GET, uri = uri))

      // WHEN
      // Executing 1st request (permitted)
      (for {
        request <- httpApiRequest
        response <- httpApp.run(request)
      } yield {
        response
      }).unsafeRunSync

      // Executing 2nd request (not permitted due to quota)
      val result = (for {
        request <- httpApiRequest
        response <- httpApp.run(request)
      } yield {
        response
      }).unsafeRunSync

      // THEN
      result.status should be(TooManyRequests)

      val resultResponse = result.attemptAs[TestGetApiErrorResponse]
        .value
        .unsafeRunSync()
        .right.get

      resultResponse.errors.size should be (1)
      resultResponse.errors.head should be ("Reached maximum rate of incoming requests")
    }
  }

  // This is working if executing as a single test
  // But failing when executing the whole suite via `sbt test` or via IntelliJ Idea
  ignore("Get rates - 404(NotFound) due to external service empty answer") {
    // GIVEN
    val exchangeRateNotFoundRequest =
      Table(
        "apiRequest",
        GetApiRequest(from = GBP, to = NZD)
      )

    // Build mock remote HttpApp answering with empty array
    val remoteMockHttpApp = HttpApp[IO](_ => Response[IO](Ok).withEntity(List[ExternalGetApiResponse]()).pure[IO])
    val mockHttpClient: Client[IO] = Client.fromHttpApp(remoteMockHttpApp)

    forAll(exchangeRateNotFoundRequest) { apiRequest =>
      // GIVEN
      val httpApp: HttpApp[IO] = buildHttpAppWithHttpClient(mockHttpClient)
      val uri = uri"/rates"
        .withQueryParam("from", apiRequest.from)
        .withQueryParam("to", apiRequest.to)

      // WHEN
      val httpApiRequest = IO.pure(Request[IO](method = GET, uri = uri))
      val result = (for {
        request <- httpApiRequest
        response <- httpApp.run(request)
      } yield {
        response
      }).unsafeRunSync

      // THEN
      result.status should be(NotFound)

      val resultResponse =
        result.attemptAs[TestGetApiErrorResponse]
          .value
          .unsafeRunSync()
          .right.get

      resultResponse.errors.size should be (1)
      resultResponse.errors.head should be (s"Could not find exchange rate for ${apiRequest.from} / ${apiRequest.to}")
    }
  }

  // This is working if executing as a single test
  // But failing when executing the whole suite via `sbt test` or via IntelliJ Idea
  ignore("Get rate from cache if not older 5 minutes") {
    // GIVEN
    val validCurrencyRateRequests =
      Table(
        "apiRequest",
        GetApiRequest(from = EUR, to = USD),
        GetApiRequest(from = USD, to = AUD),
        GetApiRequest(from = AUD, to = CAD)
      )

    forAll(validCurrencyRateRequests) { apiRequest: GetApiRequest =>
      // GIVEN
      // Build a cache
      val ratesCache: RatesCache[IO] = RatesCache[IO](appConfig.ratesCache)

      // Create a Rate with up-to-date timestamp for writing to a cache
      val currentTimestamp = Timestamp(OffsetDateTime.now())
      val rate = Rate(
        pair = Rate.Pair(from = apiRequest.from, to = apiRequest.to),
        price = Price(0.5),
        timestamp = currentTimestamp
      )
      val from: String = rate.pair.from.show
      val to: String = rate.pair.to.show
      val cacheKeyStr = s"$from$to"

      val putToCacheCb: Either[Throwable, Unit] => Unit = _ => {
        println("Completed writing to cache")
      }

      // Write the Rate to cache
      // and await a bit
      ratesCache.put(cacheKeyStr, rate)
        .flatMap(_ => IO.sleep(Duration(3, TimeUnit.SECONDS)))
        .unsafeRunAsync(putToCacheCb)

      // Construct HttpApp with a given cache
      val httpApp: HttpApp[IO] = buildHttpAppWithCache(ratesCache = ratesCache)
      val uri = uri"/rates"
        .withQueryParam("from", apiRequest.from)
        .withQueryParam("to", apiRequest.to)
      val httpApiRequest = IO.pure(Request[IO](method = GET, uri = uri))

      // WHEN
      val result = (for {
        request <- httpApiRequest
        response <- httpApp.run(request)
      } yield {
        response
      }).flatMap(response => {
        // try to wait until caching happens
        IO.sleep(Duration(7, TimeUnit.SECONDS))
          .flatMap(_ => IO.pure(response))
      })
          .unsafeRunSync()

      // THEN
      result.status should be(Ok)

      val resultResponse =
        result.attemptAs[TestGetApiResponse]
          .value
          .unsafeRunSync()
          .right.get

      Currency.fromString(resultResponse.from) should be (apiRequest.from)
      Currency.fromString(resultResponse.to) should be (apiRequest.to)
      // Timestamp should be the same as given
      resultResponse.timestamp.value should be (currentTimestamp.value.toString)
    }
  }

  // This is working if executing as a single test
  // But failing when executing the whole suite via `sbt test` or via IntelliJ Idea
  ignore("Get up-to-date rate from a third party if the written to cache was cleared as outdated") {
    // GIVEN
    val validCurrencyRateRequests =
      Table(
        "apiRequest",
        GetApiRequest(from = EUR, to = USD)
      )

    forAll(validCurrencyRateRequests) { apiRequest: GetApiRequest =>
      // GIVEN
      // Build a cache
      val ratesCache: RatesCache[IO] = RatesCache[IO](appConfig.ratesCache)
      val cacheExpireSeconds = appConfig.ratesCache.expireSeconds

      // Create a Rate with outdated timestamp
      val initialRateTimestamp = Timestamp(OffsetDateTime.now().minusSeconds(cacheExpireSeconds + 60))
      val rate = Rate(
        pair = Rate.Pair(from = apiRequest.from, to = apiRequest.to),
        price = Price(0.5),
        timestamp = initialRateTimestamp
      )
      val from: String = rate.pair.from.show
      val to: String = rate.pair.to.show
      val cacheKeyStr = s"$from$to"

      val putToCacheCb: Either[Throwable, Unit] => Unit = _ => {
        println("Completed writing to cache")
      }

      // Write a Rate with outdated timestamp to cache
      // and await a bit
      ratesCache.put(cacheKeyStr, rate)
        .flatMap(_ => IO.sleep(Duration(15, TimeUnit.SECONDS)))
        .unsafeRunAsync(putToCacheCb)

      // Build HttpApp with a given cache
      val httpApp: HttpApp[IO] = buildHttpAppWithCache(ratesCache = ratesCache)
      val uri = uri"/rates"
        .withQueryParam("from", apiRequest.from)
        .withQueryParam("to", apiRequest.to)
      val httpApiRequest = IO.pure(Request[IO](method = GET, uri = uri))

      // WHEN
      val result = (for {
        request <- httpApiRequest
        response <- httpApp.run(request)
      } yield {
        response
      }).flatMap(response => {
        // try to wait until caching happens
        IO.sleep(Duration(15, TimeUnit.SECONDS))
          .flatMap(_ => IO.pure(response))
      })
        .unsafeRunSync()

      // THEN
      result.status should be(Ok)

      val resultResponse =
        result.attemptAs[TestGetApiResponse]
          .value
          .unsafeRunSync()
          .right.get

      Currency.fromString(resultResponse.from) should be (apiRequest.from)
      Currency.fromString(resultResponse.to) should be (apiRequest.to)

      val resultDateTime = OffsetDateTime.parse(resultResponse.timestamp.value)
      (resultDateTime.minusSeconds(cacheExpireSeconds).compareTo(initialRateTimestamp.value) > 0) should be (true)
    }
  }

  // This is working if executing as a single test
  // But failing when executing the whole suite via `sbt test` or via IntelliJ Idea
  ignore("Get rates - 429(TooManyRequests) due to third-party quota reached") {
    // GIVEN
    val currencyNotSupportedRequest =
      Table(
        "apiRequest",
        GetApiRequest(from = GBP, to = NZD),
        GetApiRequest(from = NZD, to = JPY),
        GetApiRequest(from = JPY, to = SGD)
      )

    forAll(currencyNotSupportedRequest) { apiRequest =>
      // GIVEN
      // Build a HttpApp with an external quota limit equals 1
      val httpApp: HttpApp[IO] = buildHttpAppWithExternalServiceLimit(externalServiceLimit = 1)
      val uri1 = uri"/rates"
        .withQueryParam("from", apiRequest.from)
        .withQueryParam("to", apiRequest.to)
      val httpApiRequest1 = IO.pure(Request[IO](method = GET, uri = uri1))

      // WHEN
      // Executing 1st request (permitted)
      (for {
        request <- httpApiRequest1
        response <- httpApp.run(request)
      } yield {
        response
      }).unsafeRunSync

      val uri2 = uri"/rates"
        .withQueryParam("from", apiRequest.to)
        .withQueryParam("to", apiRequest.from)

      // Executing 2nd request (not permitted due to quota)
      val httpApiRequest2 = IO.pure(Request[IO](method = GET, uri = uri2))
      val result = (for {
        request <- httpApiRequest2
        response <- httpApp.run(request)
      } yield {
        response
      }).unsafeRunSync

      // THEN
      result.status should be(TooManyRequests)

      val resultResponse =
        result.attemptAs[TestGetApiErrorResponse]
          .value
          .unsafeRunSync()
          .right.get

      resultResponse.errors.size should be (1)
      resultResponse.errors.head should be ("Reached maximum rate of requests towards External service")
    }
  }
}

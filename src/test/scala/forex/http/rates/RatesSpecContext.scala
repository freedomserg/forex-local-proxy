package forex.http.rates

import java.time.LocalDateTime
import java.util.concurrent.TimeUnit

import cats.effect.{ContextShift, IO, Timer}
import cats.implicits._
import forex.config._
import forex.domain.Currency
import forex.domain.Currency._
import forex.http.rates.ExternalProtocol.ExternalGetApiResponse
import forex.http.rates.client.OneFrameClient
import forex.http.rates.middleware.RateLimitMiddleware
import forex.programs.RatesProgram
import forex.services.{RatesCache, RatesService, RatesServices}
import io.circe.generic.semiauto.{deriveDecoder, deriveEncoder}
import io.circe.{Decoder, Encoder}
import io.github.resilience4j.ratelimiter.{RateLimiter, RateLimiterConfig}
import org.http4s.client.Client
import org.http4s.dsl.Http4sDsl
import org.http4s.implicits._
import org.http4s.{HttpApp, HttpRoutes, Method, QueryParamEncoder, Response}

import scala.concurrent.ExecutionContext.global
import scala.concurrent.duration.FiniteDuration

trait RatesSpecContext extends Http4sDsl[IO] {

  implicit val cs: ContextShift[IO] = IO.contextShift(global)
  implicit val timer: Timer[IO] = IO.timer(global)

  case class TestCurrencyPair(from: Currency, to: Currency)
  case class TestPriceValue(value: BigDecimal)
  case class TestTimestampValue(value: String)
  case class TestGetApiResponse(
                                 from: String,
                                 to: String,
                                 price: TestPriceValue,
                                 timestamp: TestTimestampValue
                               )
  case class TestGetApiErrorResponse(errors: Seq[String])

  import forex.domain.Currency.show
  import forex.http._
  implicit val currencyQueryParam: QueryParamEncoder[Currency] = QueryParamEncoder.fromShow[Currency]
  implicit val timestampValueDecoder: Decoder[TestTimestampValue] = deriveDecoder[TestTimestampValue]
  implicit val priceValueDecoder: Decoder[TestPriceValue] = deriveDecoder[TestPriceValue]
  implicit val getApiResponseDecoder: Decoder[TestGetApiResponse] = deriveDecoder[TestGetApiResponse]
  implicit val getApiErrorResponseDecoder: Decoder[TestGetApiErrorResponse] = deriveDecoder[TestGetApiErrorResponse]
  implicit val externalApiResponseEncoder: Encoder[ExternalGetApiResponse] = deriveEncoder[ExternalGetApiResponse]

  val currencies = Seq(AUD, CAD, CHF, EUR, GBP, NZD, JPY, SGD, USD)

  val httpConfig: HttpConfig = HttpConfig(host = "0.0.0.0", port = 8081, timeout = FiniteDuration(40, TimeUnit.SECONDS))
  val defaultExternalServiceConfig = ExternalRatesServiceConfig(
    host = "0.0.0.0",
    port = 8080,
    rateLimitRefreshPeriodSeconds = 10,
    limitForPeriod = 3,
    authTokenName = "token",
    authTokenValue = "test"
  )
  val ratesCacheConfig = RatesCacheConfig(size = 9, expireSeconds = 300)
  val defaultInternalThrottlingConfig = InternalThrottlingConfig(rateLimitRefreshPeriodSeconds = 60, limitForPeriod = 3)
  val appConfig: ApplicationConfig = ApplicationConfig(
    http = httpConfig,
    externalService = defaultExternalServiceConfig,
    ratesCache = ratesCacheConfig,
    throttling = defaultInternalThrottlingConfig
  )

  val defaultRemoteMockHttpApp = HttpApp[IO] {
    case r if r.method == Method.GET =>
      val currencyPairStr = r.uri.query.params.get("pair").get
      val from = Currency.fromString(currencyPairStr.substring(0, 3))
      val to = Currency.fromString(currencyPairStr.substring(3))
      TestCurrencyPair(from = from, to = to)
      val timeStamp = LocalDateTime.now().toString
      val response = ExternalGetApiResponse(from = from, to = to, price = BigDecimal(0.1), time_stamp = timeStamp)
      Response[IO](Ok).withEntity(List(response)).pure[IO]
  }
  val defaultMockHttpClient: Client[IO] = Client.fromHttpApp(defaultRemoteMockHttpApp)
  val defaultRatesCache: RatesCache[IO] = RatesCache[IO](appConfig.ratesCache)

  def buildHttpAppWithExternalServiceLimit(externalServiceLimit: Int): HttpApp[IO] = {
    val externalRatesServiceConfig = defaultExternalServiceConfig.copy(limitForPeriod = externalServiceLimit)
    buildHttpApp(externalRatesServiceConfig = Some(externalRatesServiceConfig))
  }

  def buildHttpAppWithHttpClient(client: Client[IO]): HttpApp[IO] = buildHttpApp(client = Some(client))

  def buildHttpAppWithCache(ratesCache: RatesCache[IO]): HttpApp[IO] = buildHttpApp(ratesCache = Some(ratesCache))

  def buildHttpAppWithThrottlingLimit(throttlingLimit: Int): HttpApp[IO] = {
    val throttlingConf = defaultInternalThrottlingConfig.copy(limitForPeriod = throttlingLimit)
    buildHttpApp(throttlingConfig = Some(throttlingConf))
  }

  def buildHttpApp(client: Option[Client[IO]] = None,
                   ratesCache: Option[RatesCache[IO]] = None,
                   externalRatesServiceConfig: Option[ExternalRatesServiceConfig] = None,
                   throttlingConfig: Option[InternalThrottlingConfig] = None): HttpApp[IO] = {
    val externalServiceConf = externalRatesServiceConfig.getOrElse(defaultExternalServiceConfig)
    val oneFrameClient: OneFrameClient[IO] = new OneFrameClient[IO](
      client.getOrElse(defaultMockHttpClient),
      externalServiceConf)

    val externalRatesRequestLimiterConfig: RateLimiterConfig = buildLimiterConfig(
      rateLimitRefreshPeriodSeconds = externalServiceConf.rateLimitRefreshPeriodSeconds,
      limitForPeriod = externalServiceConf.limitForPeriod)
    val externalRateLimiter: RateLimiter = RateLimiter.of("TestExtRatesServiceLimiter", externalRatesRequestLimiterConfig)

    val ratesService: RatesService[IO] = RatesServices.live[IO](
      ratesCache.getOrElse(defaultRatesCache),
      oneFrameClient, externalRateLimiter)

    val ratesProgram: RatesProgram[IO] = RatesProgram[IO](ratesService)

    val ratesHttpRoutes: HttpRoutes[IO] = new RatesHttpRoutes[IO](ratesProgram).routes

    val throttlingConf = throttlingConfig.getOrElse(defaultInternalThrottlingConfig)
    val incomingRatesRequestLimiterConfig: RateLimiterConfig =
      buildLimiterConfig(
        rateLimitRefreshPeriodSeconds = throttlingConf.rateLimitRefreshPeriodSeconds,
        limitForPeriod = throttlingConf.limitForPeriod)
    val incomingRateLimiter: RateLimiter = RateLimiter.of("IncomingRatesServiceLimiter", incomingRatesRequestLimiterConfig)

    val routesMiddleware: HttpRoutes[IO] => HttpRoutes[IO] = {
      { http: HttpRoutes[IO] =>
        RateLimitMiddleware(http, incomingRateLimiter)
      }
    }
    val httpApp: HttpApp[IO] = routesMiddleware(ratesHttpRoutes).orNotFound
    httpApp
  }

  private def buildLimiterConfig(rateLimitRefreshPeriodSeconds: Int, limitForPeriod: Int): RateLimiterConfig = {
    RateLimiterConfig.custom()
      .limitRefreshPeriod(java.time.Duration.ofSeconds(rateLimitRefreshPeriodSeconds))
      .limitForPeriod(limitForPeriod)
      .timeoutDuration(java.time.Duration.ofMillis(25))
      .build()
  }
}

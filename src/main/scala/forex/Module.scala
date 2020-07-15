package forex

import cats.effect.{Concurrent, Timer}
import forex.config.{ApplicationConfig, ExternalRatesServiceConfig, InternalThrottlingConfig}
import forex.http.rates.RatesHttpRoutes
import forex.http.rates.client.OneFrameClient
import forex.http.rates.middleware.RateLimitMiddleware
import forex.programs._
import forex.services._
import io.github.resilience4j.ratelimiter.{RateLimiter, RateLimiterConfig}
import org.http4s._
import org.http4s.client.Client
import org.http4s.implicits._
import org.http4s.server.middleware.{AutoSlash, Timeout}

class Module[F[_]: Concurrent: Timer](config: ApplicationConfig, httpClient: Client[F]) {

  val extRatesServiceConfig: ExternalRatesServiceConfig = config.externalService
  val externalRatesRequestLimiterConfig: RateLimiterConfig = buildLimiterConfig(
    rateLimitRefreshPeriodSeconds = extRatesServiceConfig.rateLimitRefreshPeriodSeconds,
    limitForPeriod = extRatesServiceConfig.limitForPeriod)
  val externalRateLimiter: RateLimiter = RateLimiter.of("ExtRatesServiceLimiter", externalRatesRequestLimiterConfig)

  private val oneFrameClient: OneFrameClient[F] = new OneFrameClient[F](httpClient, extRatesServiceConfig)

  private val ratesCache: RatesCache[F] = RatesCache[F](config.ratesCache)

  private val ratesService: RatesService[F] = RatesServices.live[F](ratesCache, oneFrameClient, externalRateLimiter)

  private val ratesProgram: RatesProgram[F] = RatesProgram[F](ratesService)

  private val ratesHttpRoutes: HttpRoutes[F] = new RatesHttpRoutes[F](ratesProgram).routes

  type PartialMiddleware = HttpRoutes[F] => HttpRoutes[F]
  type TotalMiddleware   = HttpApp[F] => HttpApp[F]


  val throttlingConfig: InternalThrottlingConfig = config.throttling
  val incomingRatesRequestLimiterConfig: RateLimiterConfig =
    buildLimiterConfig(
      rateLimitRefreshPeriodSeconds = throttlingConfig.rateLimitRefreshPeriodSeconds,
      limitForPeriod = throttlingConfig.limitForPeriod)
  val incomingRateLimiter: RateLimiter = RateLimiter.of("IncomingRatesServiceLimiter", incomingRatesRequestLimiterConfig)

  private val routesMiddleware: PartialMiddleware = {
    { http: HttpRoutes[F] =>
      AutoSlash(http)
    } andThen { http: HttpRoutes[F] =>
      RateLimitMiddleware(http, incomingRateLimiter)
    }
  }

  private val appMiddleware: TotalMiddleware = { http: HttpApp[F] =>
    Timeout(config.http.timeout)(http)
  }

  private val http: HttpRoutes[F] = ratesHttpRoutes

  val httpApp: HttpApp[F] = appMiddleware(routesMiddleware(http).orNotFound)

  private def buildLimiterConfig(rateLimitRefreshPeriodSeconds: Int, limitForPeriod: Int): RateLimiterConfig = {
    RateLimiterConfig.custom()
      .limitRefreshPeriod(java.time.Duration.ofSeconds(rateLimitRefreshPeriodSeconds))
      .limitForPeriod(limitForPeriod)
      .timeoutDuration(java.time.Duration.ofMillis(25))
      .build()
  }
}

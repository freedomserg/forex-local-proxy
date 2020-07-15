package forex.config

import scala.concurrent.duration.FiniteDuration

case class ApplicationConfig(
                              http: HttpConfig,
                              externalService: ExternalRatesServiceConfig,
                              ratesCache: RatesCacheConfig,
                              throttling: InternalThrottlingConfig
                            )

case class HttpConfig(
                       host: String,
                       port: Int,
                       timeout: FiniteDuration
                     )

case class ExternalRatesServiceConfig(
                                       host: String,
                                       port: Int,
                                       rateLimitRefreshPeriodSeconds: Int,
                                       limitForPeriod: Int,
                                       authTokenName: String,
                                       authTokenValue: String
                                     )

case class RatesCacheConfig(
                             size: Int,
                             expireSeconds: Int
                           )

case class InternalThrottlingConfig(
                                     rateLimitRefreshPeriodSeconds: Int,
                                     limitForPeriod: Int,
                                   )

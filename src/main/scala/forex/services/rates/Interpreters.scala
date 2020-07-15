package forex.services.rates

import cats.Applicative
import cats.effect.Sync
import forex.http.rates.client.OneFrameClient
import forex.services.cache.RatesCacheAlgebra
import forex.services.rates.interpreters._
import io.github.resilience4j.ratelimiter.RateLimiter

object Interpreters {
  def dummy[F[_] : Applicative](): Algebra[F] = new OneFrameDummy[F]()

  def live[F[_] : Sync](ratesCache: RatesCacheAlgebra[F],
                        ratesClient: OneFrameClient[F],
                        rateLimiter: RateLimiter): Algebra[F] =
    OneFrameLive[F](ratesCache, ratesClient, rateLimiter)
}

package forex.services.cache

import java.util.concurrent.TimeUnit

import cats.data.EitherT
import cats.effect.Async
import com.github.benmanes.caffeine.cache.Caffeine
import forex.config.RatesCacheConfig
import forex.domain.Rate
import forex.services.rates.errors.ServiceError
import scalacache.{Mode, _}
import scalacache.caffeine._

class OneFrameRatesCache[F[_] : Async](ratesCacheConfig: RatesCacheConfig) extends RatesCacheAlgebra[F] {

  val underlyingCache = Caffeine
    .newBuilder()
    .maximumSize(ratesCacheConfig.size.toLong)
    .expireAfterWrite(ratesCacheConfig.expireSeconds.toLong, TimeUnit.SECONDS)
    .build[String, Entry[Rate]]
  implicit val mode: Mode[F] = scalacache.CatsEffect.modes.async[F]
  val cache: Cache[Rate] = CaffeineCache(underlyingCache)

  override def get(key: String): F[Option[Rate]] = {
    cache.get[F](key)
  }

  override def put(key: String, value: Rate): F[Either[ServiceError, Rate]] = {
    EitherT.right[ServiceError](cache.put[F](key)(value))
      .map(_ => value)
      .value
  }
}

object OneFrameRatesCache {
  def apply[F[_] : Async](ratesCacheConfig: RatesCacheConfig): RatesCacheAlgebra[F] = new OneFrameRatesCache[F](ratesCacheConfig)
}

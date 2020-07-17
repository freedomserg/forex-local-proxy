package forex.services.cache

import cats.data.EitherT
import cats.effect.Async
import com.github.benmanes.caffeine.cache.{Caffeine, Expiry}
import forex.config.RatesCacheConfig
import forex.domain.Rate
import forex.services.rates.errors.ServiceError
import scalacache.caffeine._
import scalacache.{Mode, _}

class OneFrameRatesCache[F[_] : Async](ratesCacheConfig: RatesCacheConfig) extends RatesCacheAlgebra[F] {

  val expiry = new Expiry[String, Entry[Rate]]() {
    val expireSeconds = ratesCacheConfig.expireSeconds.toLong
    override def expireAfterCreate(key: String, value: Entry[Rate], currentTime: Long): Long = {
      value.value.timestamp.value.plusSeconds(expireSeconds)
        .getNano.toLong
    }

    override def expireAfterUpdate(key: String, value: Entry[Rate], currentTime: Long, currentDuration: Long): Long = {
      value.value.timestamp.value.plusSeconds(expireSeconds)
        .getNano.toLong
    }

    override def expireAfterRead(key: String, value: Entry[Rate], currentTime: Long, currentDuration: Long): Long = {
      value.value.timestamp.value.plusSeconds(expireSeconds)
        .getNano.toLong
    }
  }

  val underlyingCache = Caffeine
    .newBuilder()
    .maximumSize(ratesCacheConfig.size.toLong)
    .expireAfter(expiry)
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

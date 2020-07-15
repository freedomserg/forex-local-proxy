package forex.services.rates.interpreters

import java.time.{Clock, LocalDateTime, OffsetDateTime}

import cats.Monad
import cats.data.EitherT
import cats.implicits._
import forex.services.ratelimiter.GetRateRequestLimiter
import io.github.resilience4j.ratelimiter.RateLimiter
import forex.domain.{Price, Rate, Timestamp}
import forex.http.rates.ExternalProtocol.ExternalGetApiResponse
import forex.http.rates.client.OneFrameClient
import forex.services.cache.RatesCacheAlgebra
import forex.services.rates.Algebra
import forex.services.rates.errors.ServiceError.OneFrameLookupFailed
import forex.services.rates.errors._
import forex.services.ratelimiter.GetRateRequestLimiter._

import scala.util.{Failure, Success, Try}

class OneFrameLive[F[_] : Monad](ratesCache: RatesCacheAlgebra[F],
                                 ratesClient: OneFrameClient[F],
                                 rateLimiter: RateLimiter) extends Algebra[F] {

  override def get(pair: Rate.Pair): F[Either[ServiceError, Rate]] = {

    val from = pair.from.show
    val to = pair.to.show
    val cacheKeyStr = s"$from$to"

    val rateLimitedRateRequest: GetRateRequestLimiter[F, ExternalGetApiResponse, Rate.Pair] =
      limit[F, ExternalGetApiResponse, Rate.Pair](ratesClient.get, rateLimiter)

    ratesCache.get(cacheKeyStr).flatMap {
      case Some(r) => Either.right[ServiceError, Rate](r).pure[F]
      case None =>
        rateLimitedRateRequest(pair)
          .leftMap(toServiceError)
          .flatMap(apiResponseAsRate)
          .flatMap(writeToCache(_, cacheKeyStr))
          .value
    }
  }

  private def writeToCache(rate: Rate, key: String): EitherT[F, ServiceError, Rate] = {
    EitherT(ratesCache.put(key, rate))
  }

  private def apiResponseAsRate(apiResp: ExternalGetApiResponse): EitherT[F, ServiceError, Rate] = {
    val apiTimestampStr = apiResp.time_stamp.replace("Z", "")

    EitherT.fromEither(
      Try(LocalDateTime.parse(apiTimestampStr)) match {
        case Success(timeStampNoOffset) =>
          val offset = Clock.systemDefaultZone().getZone.getRules.getOffset(timeStampNoOffset)
          val timestampWithOffset = OffsetDateTime.of(timeStampNoOffset, offset)
          Either.right[ServiceError, Rate](
            Rate(
              pair = Rate.Pair(from = apiResp.from, to = apiResp.to),
              price = Price(apiResp.price),
              timestamp = Timestamp(timestampWithOffset)
            )
          )
        case Failure(e) =>
          Either.left[ServiceError, Rate](OneFrameLookupFailed(e.getMessage)) // TODO: use another specific error
      }
    )
  }

}

object OneFrameLive {
  def apply[F[_] : Monad](ratesCache: RatesCacheAlgebra[F],
                          ratesClient: OneFrameClient[F],
                          rateLimiter: RateLimiter): Algebra[F] =
    new OneFrameLive[F](ratesCache, ratesClient, rateLimiter)
}
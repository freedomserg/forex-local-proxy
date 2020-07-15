package forex.services.ratelimiter

import cats.Applicative
import cats.data.EitherT
import cats.implicits._
import forex.http.rates.client.errors.ClientError
import forex.http.rates.client.errors.ClientError.{RateLimitExceeded, RateRequestFailed}
import io.github.resilience4j.ratelimiter.{RateLimiter, RequestNotPermitted}

class GetRateRequestLimiter[F[_] : Applicative, R, T]
                                  (rateLimiter: RateLimiter,
                                   function1: T => EitherT[F, ClientError, R]) extends (T => EitherT[F, ClientError, R]) {

  def apply(t: T): EitherT[F, ClientError, R] =
    EitherT(
      try {
        RateLimiter.waitForPermission(rateLimiter)
        function1.apply(t).value
      } catch {
        case e: RequestNotPermitted => Either.left[ClientError, R](RateLimitExceeded(e.getMessage)).pure[F]
        case e: Throwable => Either.left[ClientError, R](RateRequestFailed(e.getMessage)).pure[F]
      }
    )
}

object GetRateRequestLimiter {
  def limit[F[_] : Applicative, A, B](function1: B => EitherT[F, ClientError, A], rateLimiter: RateLimiter): GetRateRequestLimiter[F, A, B] =
    new GetRateRequestLimiter[F, A, B](rateLimiter, function1)
}

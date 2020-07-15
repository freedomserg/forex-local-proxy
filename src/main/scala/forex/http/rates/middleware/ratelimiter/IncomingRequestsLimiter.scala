package forex.http.rates.middleware.ratelimiter

import cats.Monad
import cats.data.{Kleisli, OptionT}
import io.github.resilience4j.ratelimiter.{RateLimiter, RequestNotPermitted}
import org.http4s.dsl.Http4sDsl
import org.http4s.{Request, Response}

class IncomingRequestsLimiter[F[_] : Monad]
                                  (function1: Request[F] => OptionT[F, Response[F]],
                                   rateLimiter: RateLimiter) extends (Request[F] => OptionT[F, Response[F]]) {

  def apply(request: Request[F]): OptionT[F, Response[F]] = {
    val dsl = Http4sDsl[F]
    import dsl._
    try {
      RateLimiter.waitForPermission(rateLimiter)
      Kleisli(function1).apply(request)
    } catch {
      case _: RequestNotPermitted =>
        OptionT.liftF(TooManyRequests("Reached maximum rate of incoming requests."))
      case e: Throwable =>
        // TODO: add logging
      OptionT.liftF(InternalServerError("Unexpected server error."))
    }
  }
}

object IncomingRequestsLimiter {
  def limit[F[_] : Monad](function1: Request[F] => OptionT[F, Response[F]], rateLimiter: RateLimiter): IncomingRequestsLimiter[F] =
    new IncomingRequestsLimiter[F](function1, rateLimiter)
}



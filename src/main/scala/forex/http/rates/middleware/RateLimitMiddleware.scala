package forex.http.rates.middleware

import cats.data.Kleisli
import cats.effect.Concurrent
import forex.http.rates.middleware.ratelimiter.IncomingRequestsLimiter._
import forex.http.rates.middleware.ratelimiter.IncomingRequestsLimiter
import io.github.resilience4j.ratelimiter.RateLimiter
import org.http4s.{HttpRoutes, Request}

object RateLimitMiddleware {

  def apply[F[_]: Concurrent](service: HttpRoutes[F], rateLimiter: RateLimiter): HttpRoutes[F] = {
    val function = (request: Request[F]) => service(request)
    val rateLimitedService: IncomingRequestsLimiter[F] = limit[F](function, rateLimiter)
    Kleisli { request: Request[F] =>
      rateLimitedService(request)
    }
  }

}

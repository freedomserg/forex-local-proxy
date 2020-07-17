package forex.services.rates

import forex.http.rates.client.errors.ClientError
import forex.http.rates.client.errors.ClientError._
import forex.services.rates.errors.ServiceError._

object errors {

  sealed trait ServiceError
  object ServiceError {
    final case class OneFrameLookupFailed(msg: String) extends ServiceError
    final case class OneFrameQuotaReached(msg: String) extends ServiceError
    final case class OneFrameExchangeRateNotFound(msg: String) extends ServiceError
  }


  def toServiceError(error: ClientError): ServiceError = error match {
    case RateRequestFailed(msg) => OneFrameLookupFailed(msg)
    case RateLimitExceeded => OneFrameQuotaReached("Reached maximum rate of requests towards External service")
    case ExchangeRateNotFound(msg) => OneFrameExchangeRateNotFound(msg)
  }
}

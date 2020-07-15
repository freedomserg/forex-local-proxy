package forex.services.rates

import forex.http.rates.client.errors.ClientError
import forex.http.rates.client.errors.ClientError.{RateLimitExceeded, RateRequestFailed}
import forex.services.rates.errors.ServiceError.OneFrameLookupFailed

object errors {

  sealed trait ServiceError
  object ServiceError {
    final case class OneFrameLookupFailed(msg: String) extends ServiceError
  }


  def toServiceError(error: ClientError): ServiceError = error match {
    case RateRequestFailed(msg) => OneFrameLookupFailed(msg)
    case RateLimitExceeded(_) => OneFrameLookupFailed("Rate limit exceeded")
  }
}

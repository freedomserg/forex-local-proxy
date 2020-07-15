package forex.programs.rates

import forex.services.rates.errors.ServiceError
import forex.services.rates.errors.ServiceError.OneFrameLookupFailed

object errors {

  sealed trait Error {
    val msg: String
  }
  object Error {
    final case class GeneralError(msg: String) extends Error
    final case class RateLookupFailed(msg: String) extends Error
    final case class CurrencyNotSupported(msg: String) extends Error
  }

  def toProgramError(error: ServiceError): Error = error match {
    case OneFrameLookupFailed(msg) => Error.RateLookupFailed(msg)
  }
}

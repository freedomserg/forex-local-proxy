package forex.programs.rates

import forex.programs.rates.errors.Error._
import forex.services.rates.errors.ServiceError
import forex.services.rates.errors.ServiceError._

object errors {

  sealed trait Error {
    val msg: String
  }

  object Error {

    final case class GeneralProgramError(msg: String) extends Error

    final case class RateLookupFailed(msg: String) extends Error

    final case class CurrencyNotSupported(msg: String) extends Error

    final case class CurrencyRateNotFound(msg: String) extends Error

    final case class ProgramQuotaReached(msg: String) extends Error

  }

  def toProgramError(error: ServiceError): Error = error match {
    case OneFrameLookupFailed(msg) => RateLookupFailed(msg)
    case OneFrameExchangeRateNotFound(msg) => CurrencyRateNotFound(msg)
    case OneFrameQuotaReached(msg) => ProgramQuotaReached(msg)
  }
}

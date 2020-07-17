package forex.http.rates

import cats.implicits._
import forex.domain.Currency
import forex.programs.rates.errors.Error
import forex.programs.rates.errors.Error.{CurrencyNotSupported, GeneralProgramError}
import org.http4s.QueryParamDecoder
import org.http4s.dsl.impl.QueryParamDecoderMatcher

import scala.util.{Failure, Success, Try}

object QueryParams {

  private[http] implicit val currencyQueryParam: QueryParamDecoder[Either[Error, Currency]] = {
    QueryParamDecoder[String].map { currencyStr =>
      Try(Currency.fromString(currencyStr)) match {
        case Success(currency) => Either.right[Error, Currency](currency)
        case Failure(_: MatchError) =>
          Either.left[Error, Currency](CurrencyNotSupported(s"Exchange rate for $currencyStr is not supported"))
        case Failure(_: Throwable) =>
          Either.left[Error, Currency](GeneralProgramError("Unexpected internal error happened. Please, try again"))
      }
    }
  }

  object FromQueryParam extends QueryParamDecoderMatcher[Either[Error, Currency]]("from")
  object ToQueryParam extends QueryParamDecoderMatcher[Either[Error, Currency]]("to")

}

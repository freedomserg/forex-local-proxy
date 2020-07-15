package forex.http.rates

import forex.domain.Currency.show
import forex.domain.Rate.Pair
import forex.domain._
import io.circe._
import io.circe.generic.semiauto._

object CommonSerdes {
  implicit val currencyEncoder: Encoder[Currency] =
    Encoder.instance[Currency] { show.show _ andThen Json.fromString }
  implicit val pairEncoder: Encoder[Pair] = deriveEncoder[Pair]
  implicit val priceEncoder: Encoder[Price] = deriveEncoder[Price]
  implicit val timestampEncoder: Encoder[Timestamp] = deriveEncoder[Timestamp]
  implicit val rateEncoder: Encoder[Rate] = deriveEncoder[Rate]

  implicit val currencyDecoder: Decoder[Currency] =
    Decoder[String].emap{ s => Right(Currency.fromString(s)) }
  implicit val pairDecoder: Decoder[Pair] = deriveDecoder[Pair]
  implicit val priceDecoder: Decoder[Price] = deriveDecoder[Price]
  implicit val timestampDecoder: Decoder[Timestamp] = deriveDecoder[Timestamp]
  implicit val rateDecoder: Decoder[Rate] = deriveDecoder[Rate]
}

object Protocol {
  import forex.http.rates.CommonSerdes._

  final case class GetApiRequest(
      from: Currency,
      to: Currency
  )

  final case class GetApiResponse(
      from: Currency,
      to: Currency,
      price: Price,
      timestamp: Timestamp
  )

  final case class GetApiErrorResponse(errors: Seq[String])

  implicit val responseEncoder: Encoder[GetApiResponse] = deriveEncoder[GetApiResponse]
  implicit val errorResponseEncoder: Encoder[GetApiErrorResponse] = deriveEncoder[GetApiErrorResponse]

}

object ExternalProtocol {
  import forex.http.rates.CommonSerdes._

  final case class ExternalGetApiResponse(
                                           from: Currency,
                                           to: Currency,
                                           price: BigDecimal,
                                           time_stamp: String
                                 )

  implicit val responseDecoder: Decoder[ExternalGetApiResponse] = deriveDecoder[ExternalGetApiResponse]
}

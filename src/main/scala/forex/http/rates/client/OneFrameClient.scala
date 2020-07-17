package forex.http.rates.client

import cats.data.EitherT
import cats.effect.Sync
import cats.implicits._
import forex.config.ExternalRatesServiceConfig
import forex.domain.Rate
import forex.http.rates.ExternalProtocol.ExternalGetApiResponse
import forex.http.rates.client.errors.ClientError
import forex.http.rates.client.errors.ClientError.{ExchangeRateNotFound, RateRequestFailed}
import org.http4s.Method.GET
import org.http4s.Uri.{Authority, RegName, Scheme}
import org.http4s.client.Client
import org.http4s.client.dsl.Http4sClientDsl
import org.http4s.{Status, _}

class OneFrameClient[F[_] : Sync](httpClient: Client[F],
                                  externalRatesServiceConfig: ExternalRatesServiceConfig) extends Http4sClientDsl[F] {
  private val GET_RATES_PATH = "/rates"

  def get(pair: Rate.Pair): EitherT[F, ClientError, ExternalGetApiResponse] = {
    val host = externalRatesServiceConfig.host
    val port = externalRatesServiceConfig.port
    val tokenName = externalRatesServiceConfig.authTokenName
    val tokenValue = externalRatesServiceConfig.authTokenValue
    val pairValue = s"${pair.from}${pair.to}"

    val uri = Uri(
      scheme = Some(Scheme.http),
      authority = Some(Authority(host = RegName(host), port = Some(port))))
      .withPath(GET_RATES_PATH)
      .withQueryParam("pair", pairValue)
    val token = Header(tokenName, tokenValue)
    val headers = Headers.of(token)
    val request = Request[F](method = GET, uri = uri, headers = headers)


    EitherT {
      httpClient.fetch(Sync[F].delay(request)) {
        case Status.Successful(r) =>
          r.attemptAs[List[ExternalGetApiResponse]]
            .leftMap(e => RateRequestFailed(e.message))
            .flatMap { list =>
              EitherT.fromEither(
                list match {
                  case head :: _ => Either.right[ClientError, ExternalGetApiResponse](head)
                  case Nil =>
                    Either.left[ClientError, ExternalGetApiResponse](ExchangeRateNotFound(s"Could not find exchange rate for ${pair.from} / ${pair.to}"))
                }
              )
            }
            .value

        case r =>
          r.as[String]
            .map(b => Left(RateRequestFailed(s"Request failed with status ${r.status.code} and body $b")))
      }
    }
  }
}

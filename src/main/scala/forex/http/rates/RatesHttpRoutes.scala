package forex.http.rates

import cats.effect.Sync
import cats.implicits._
import forex.programs.RatesProgram
import forex.programs.rates.errors.Error
import forex.programs.rates.errors.Error._
import forex.programs.rates.{Protocol => RatesProgramProtocol}
import org.http4s.dsl.Http4sDsl
import org.http4s.server.Router
import org.http4s.{HttpRoutes, Response}

class RatesHttpRoutes[F[_] : Sync](rates: RatesProgram[F]) extends Http4sDsl[F] {

  import Converters._
  import Protocol._
  import QueryParams._

  private[http] val prefixPath = "/rates"

  private val httpRoutes: HttpRoutes[F] = HttpRoutes.of[F] {
    case GET -> Root :? FromQueryParam(from) +& ToQueryParam(to) =>
      (from, to) match {
        case (Right(f), Right(t)) =>
          rates.get(RatesProgramProtocol.GetRatesRequest(f, t))
            .flatMap {
              case Right(rate) => Ok(rate.asGetApiResponse)
              case Left(e: Error) => respondDependingOnSingleError(e)
            }
        case (Right(_), Left(e: Error)) => respondDependingOnSingleError(e)
        case (Left(e: Error), Right(_)) => respondDependingOnSingleError(e)
        case (Left(f: Error), Left(t: Error)) => respondDependingOnTwoErrors(f, t)
      }
  }

  private def respondDependingOnTwoErrors(e1: Error, e2: Error): F[Response[F]] = (e1, e2) match {
    case (GeneralError(_), GeneralError(_)) | (GeneralError(_), _) | (_, GeneralError(_)) =>
      InternalServerError(GetApiErrorResponse(Seq(e1.msg)))
    case _ => BadRequest(GetApiErrorResponse(Seq(e1.msg, e2.msg)))
  }

  private def respondDependingOnSingleError(e: Error): F[Response[F]] = e match {
    case GeneralError(msg) => InternalServerError(GetApiErrorResponse(Seq(msg)))
    case _ => BadRequest(GetApiErrorResponse(Seq(e.msg)))
  }

  val routes: HttpRoutes[F] = Router(
    prefixPath -> httpRoutes
  )

}

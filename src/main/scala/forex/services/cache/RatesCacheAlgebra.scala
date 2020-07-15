package forex.services.cache

import forex.domain.Rate
import forex.services.rates.errors.ServiceError

trait RatesCacheAlgebra[F[_]] {

  def get(key: String): F[Option[Rate]]

  def put(key: String, value: Rate): F[Either[ServiceError, Rate]]

}

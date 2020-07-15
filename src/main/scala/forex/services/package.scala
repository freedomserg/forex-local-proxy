package forex

package object services {
  type RatesService[F[_]] = rates.Algebra[F]
  final val RatesServices = rates.Interpreters

  type RatesCache[F[_]] = cache.RatesCacheAlgebra[F]
  final val RatesCache = cache.OneFrameRatesCache
}

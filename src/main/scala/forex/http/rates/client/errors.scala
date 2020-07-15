package forex.http.rates.client

object errors {

  sealed trait ClientError
  object ClientError {
    final case class RateRequestFailed(msg: String) extends ClientError
    final case class RateLimitExceeded(msg: String) extends ClientError
  }

}

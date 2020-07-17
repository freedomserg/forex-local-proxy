## Forex rates local proxy

Forex is an application that acts as a local proxy for getting exchange rates from third-party providers. Can be consumed 
by other internal services to ask the exchange rate between 2 given currencies and get back a rate that is not older 
than 5 minutes.

### REST API

#### GET /rates?from={currency_1}&to={currency_2}

1. `currency_1` and `currency_2` are required query parameters being currency codes, e.g. `/rates?from=USD&to=EUR`
2. Currently the application supports exchange rates between any of `AUD`, `CAD`, `CHF`, `EUR`, `GBP`, `NZD`, `JPY`, `SGD`, `USD`
3. in case of an error, API provides a response with a list of error messages
4. `application.conf` contains the following configs:

- of an external third-party to ask exchange rates from
- of an internal cache to keep exchange rates not older 5 minutes
- of rate limiting (throttling) of incoming requests  


##### Example

* request `curl 'localhost:8080/rates?from=USD&to=EUR'`
* response `{"from":"EUR","to":"USD","price":{"value":0.50871114858135455},"timestamp":{"value":"2020-07-15T12:48:08.418+03:00"}}`

* request with unsupported currency `curl 'localhost:8080/rates?from=USD&to=UAH'`
* response`{"errors":["Exchange rate for UAH is not supported"]}`

### Notes
1. App written with `Tagless Final pattern` in mind. 
2. To meet the requirement of calling rate-limited third-party API: 
    - there is `app.external-service` config in the `application.conf` where the one can configure `host`, `port`, 
    `rate-limit-refresh-period-seconds`, `limit-for-period`, `auth-token-name` (to be present in the HTTP header),
    `auth-token-value`
    - used [resilience4j](https://github.com/resilience4j/resilience4j) to get rate-limiting functionality and 
      its [RateLimiter](https://github.com/resilience4j/resilience4j#62-ratelimiter)
    - implemented `forex.services.ratelimiter.GetRateRequestLimiter` able to control a number of outbound requests through `RateLimiter` 
       and depending on the above rate-limiting configs.
3. To meet the requirement of providing back to the user an exchange rate which is not older 5 minutes and to decrease a number of
outbound requests towards third-party:
    - implemented `forex.services.cache.OneFrameRatesCache` class which able to keep rates that being removed automatically 
after an expiration period. Number of rates to keep and expiration are configured values in `application.cong`(`app.rates-cache`)
    - to have caching functionality under the hood, used [ScalaCache](https://cb372.github.io/scalacache/), its 
[Caffeine](https://cb372.github.io/scalacache/docs/modes.html#cats-effect-io-mode) implementation and 
[Cats-effect support](https://cb372.github.io/scalacache/docs/modes.html#cats-effect-io-mode).
4. To support an ability of rate-limiting own REST API:
    - implemented `forex.http.rates.middleware.ratelimiter.IncomingRequestsLimiter` able to control a number of inbound 
    requests through `resilience4j`'s  `RateLimiter` and depending on `app.throttling` configs in `application.conf`.
    - `IncomingRequestsLimiter` is composed in the application middleware. 
5. [Http4s Client](https://http4s.org/v0.20/client/) used for calling third-party via REST API.
6. Since there is no `one-frame` REST API specification and only known that `200 OK` response returned with an array of rates,
there are following implemented assumptions:
    - external API may return a successful response with the array of rates for a given currencies pair - 
       taken into account only the 1st rate in the array. Forex proxy returns `200 OK` with a rate.
    - external API may return a successful response with an empty array for a given currencies pair. Forex proxy 
       returns `404 NotFound` with an error message.
    - external API may return any unsuccessful response. Forex proxy returns `400 BadRequest` with an error message.
7. Since there is no specification of `one-frame` supported currencies:
    - for any unsupported by Forex app currency, it responds `400 BadRequest` with an error message.
    - in case `one-frame` returns an empty array for a given pair, Forex app responds `404 NotFound` with an error message. 
8. Due to a library versions conflict with `Http4s Client`, `fs2` was downgraded to version `1.0.5`.
9. Unit tests written in [Table-driven checks style](https://www.scalatest.org/user_guide/table_driven_property_checks). 
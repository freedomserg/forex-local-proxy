## Forex rates local proxy

Forex is a simple application that acts as a local proxy for getting exchange rates. Can be consumed by other internal services to ask the exchange rate between 2 given currencies and 
and get back a rate that is not older than 5 minutes. Supports 20.000 requests per day.

### REST API

#### GET /rates?from={currency_1}&to={currency_2}

1. `currency_1` and `currency_2` are required query parameters that are currency codes, e.g. `/rates?from=USD&to=EUR`
2. in case of an error, API provides a response with a list of error messages
3. `application.conf` contains the following configs:

- of an external third-party to ask exchange rates
- of an internal cache to keep exchange rates not older 5 minutes
- of rate limiting (throttling) of incoming requests  


##### Example

* request `curl 'localhost:8080/rates?from=USD&to=EUR'`
* response `{"from":"EUR","to":"USD","price":{"value":0.50871114858135455},"timestamp":{"value":"2020-07-15T12:48:08.418+03:00"}}`

* request with unsupported currency `curl 'localhost:8080/rates?from=USD&to=UAH'`
* response`{"errors":["Exchange rate for UAH is not supported"]}`

### Unit tests
* TODO
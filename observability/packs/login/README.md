# Social login

Import `social-login.json`, select the existing metrics, logs and traces data sources, and choose application/environment filters. No recording rules are required. Missing series do not imply healthy operation.

See the root `README.md` for instrumentation setup, delivery bounds, metric semantics and runbooks. Install the scoped `alerts.yml` only if you want warning alerts; do not also install the aggregate alerts.

Provider gauges distinguish disabled providers from enabled but idle providers. Expected authentication denials do not count as infrastructure errors.

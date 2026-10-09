# Social remote content

Import `social-remote-content.json`, select the existing metrics, logs and traces data sources, and choose application/environment filters. No recording rules are required. Missing series do not imply healthy operation.

See the root `README.md` for instrumentation setup, delivery bounds, metric semantics and runbooks. Install the scoped `alerts.yml` only if you want warning alerts; do not also install the aggregate alerts.

Queue panels describe distributions of reporting client snapshots, with freshness; they are not exact fleet totals.

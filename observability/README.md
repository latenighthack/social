# Social observability packs

Import `grafana/social-overview.json` and the feature dashboards you use. Select your Prometheus-compatible, Loki and Tempo data sources from the dashboard variables. No recording rules or custom Grafana plugins are required. Grafana 11.4 Classic JSON is the compatibility target. `manifest.json` identifies the report and metric contracts. `packs/<feature>/` also contains each dashboard, operating notes, compatibility manifest and scoped optional alerts. Choose either those scoped rules or the aggregate rules, to avoid duplicate alerts.

Server extensions register meters on their parent's `MeterRegistry`. Factories do this automatically. Direct hosts attach one `SocialServerTelemetry(registry, application, environment, openTelemetry)` using `.observedBy(telemetry)`. The host owns the registry, SDK, authentication policy and collector configuration.

Client feature provider interfaces inherit `SocialTelemetryProviders`. Override `socialTelemetry()` once in your component to return a `SocialClientTelemetry(serverUrl, socialPlatform(), headers, dedicatedHttpClient)`. Expose `Set<SocialFeatureDescriptor>` from the graph and call `telemetry.feature(it.name)` for each descriptor. Existing constructors and provider methods remain available; direct manager users call `.observedBy(telemetry)` before starting. Without this binding, features use a no-op sink. Configure the graph's shared transports once with `telemetry.instrumentRpcClient(rpcClient)` and `telemetry.instrumentHttpClient(contentHttpClient)`; these propagate W3C context and record bounded transport spans for every selected feature. HTTP 4xx denials are rejections. The HTTP adapter preserves the host client instance and ownership and rejects the dedicated exporter client to prevent recursion. The library never initializes a global SDK or subscribes to application Flows to collect telemetry.

Install `SocialReportRelay` under your host's authentication policy. Its required authorization callback must enforce the policy or rely on a host plugin that runs before routing. Pre-login telemetry needs an application attestation policy rather than requiring an account session. The default route is `/api/telemetry/social/v1/reports`. The host supplies server-side collector destinations and headers; client credentials are never forwarded. Keep the old client-spans compatibility route while previously shipped Fullhouse clients remain supported.

Client delivery is memory-only: 256 records, at most 32 records/64 KiB per batch, one-second batching and five-second attempts. A retry is allowed once for explicit 429/503 rejection before ingestion. Ambiguous acknowledgements are abandoned, not replayed. The relay acknowledges after recording metrics and bounded diagnostic enqueueing. Collector failure cannot cause metric replay. Processes can lose queued or in-progress telemetry; dropped client records and export failure/rejection counts are reported on a later successful upload without replaying those operations. Closing exporters and relays has a five-second drain bound.

Every enabled operation is traced, independent of metrics. Flow spans describe an existing collection's lifetime and do not change emission context or add subscriptions. Long-lived WebSocket spans finish when the connection closes; unrelated asynchronous work starts its own trace. Metric labels and diagnostic bodies never contain account/profile/room/message/content/device IDs, credentials, content, exception messages or debug dumps. Trace IDs are diagnostic fields, never metric labels.

## Metrics and interpretation

The authoritative contract is the parent's Prometheus exposition: `social_operations_total`, `social_operation_duration_seconds_bucket`, `social_events_total`, `social_transfer_bytes_sum`, `social_queue_depth_items_bucket`, `social_queue_age_seconds_bucket`, feature/provider availability and telemetry delivery counters. Durations and queue ages are seconds; transfers are bytes. Aggregate histogram buckets across replicas before calculating percentiles.

Client queue depth and oldest-age panels are distributions over reported snapshots, not exact current fleet totals. Offline clients contribute no current observations. A feature heartbeat is emitted every 30 seconds; missing reports can mean offline clients, no clients using that feature or a disabled exporter. Missing data does not establish health. Server feature availability is explicit; login provider availability reports both enabled and disabled providers even without requests.

Message `send` measures durable enqueueing; `attemptSend` measures the existing server acceptance path. Neither establishes receipt by another device. Retry and newly dead-lettered events are separate. Read receipts distinguish missing profiles/messages and redundant writes; typing distinguishes debounced signals and timeout observations within existing subscriptions. Multiple application observers can observe the same expiry. Expected login denials are `outcome="rejected"`, not unexpected errors.

## Fullhouse

Fullhouse supplies its shared registry and SDK, binds the KMP exporter in its Social component and inherits App Check on the report route. Its collector scrapes only Social meters from each server replica's internal `:8081/metrics`; the parallel OTLP registry excludes `social.*`. Set `SOCIAL_METRICS_TARGET` and `SOCIAL_METRICS_SECOND_TARGET` to actual replica endpoints for a clustered deployment. Do not point the collector at a load balancer and assume it scraped every replica.

Run `node tools/fh/sync-social-observability.mjs EXTRACTED_OBSERVABILITY_DIR [FEATURE ...]` in Fullhouse after updating the bundle. Its Grafana image provisions the selected dashboards. Loki's JSON `trace_id` derived field links to Tempo; enable the host's normal JSON/OTLP log and trace exporters. For other consumers, add the same derived field with your Tempo data-source UID.

## Unexpected errors

The optional rule warns at more than 5% unexpected errors with at least 100 observations in five minutes, sustained for ten minutes. Start with the outcome breakdown and correlated diagnostics. Confirm telemetry freshness and drop rates first. Expected denials, skipped writes and cancellations are excluded from the numerator. Tune volume, fraction and duration for your deployment.

## Dead letters

New dead-letter events mean a message exhausted the manager's existing retry policy. Inspect server acceptance failures, connectivity and authorization. Retry through the application's existing retry operation after fixing the cause. The rule does not assert recipient delivery.

## Telemetry delivery

Drops make client rates incomplete. Check freshness, queue pressure and connectivity. Diagnostic export failures indicate collector reachability, configuration or capacity problems; accepted metrics remain independent. Do not add persistent telemetry queues or block application requests to repair this signal.

## Build and validation

Run `python3 tools/generate-observability.py` to regenerate dashboard JSON and the manifest. `./gradlew observabilityBundle` creates the versioned ZIP. Run `promtool check rules observability/prometheus/alerts.yml` and, from its directory, `promtool test rules alerts.test.yml`. Each alert is optional and warning-only; absent integrations and offline clients never alert solely because they are absent.

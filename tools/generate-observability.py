#!/usr/bin/env python3
"""Rebuild portable Grafana 11.4 Classic dashboards from the Social metric contract."""
from pathlib import Path
import json
import sys
from urllib.parse import quote
ROOT = Path(sys.argv[1]) if len(sys.argv) > 1 else Path(__file__).resolve().parents[1] / 'observability'
ROOT.mkdir(parents=True, exist_ok=True)
(ROOT/'grafana').mkdir(exist_ok=True)
(ROOT/'prometheus').mkdir(exist_ok=True)
FEATURES=['runtime','account','profiles','login','rooms','messages','remote_content','contacts','typing','read_receipts','avatars','debug']
METRICS=['social_operations_total','social_operation_duration_seconds','social_events_total','social_transfer_bytes','social_queue_depth_items','social_queue_age_seconds','social_feature_enabled','social_client_feature_reports_total','social_client_last_report_timestamp_seconds','social_login_provider_enabled','social_telemetry_exports_total','social_telemetry_dropped_total']
DS={'type':'prometheus','uid':'${DS_PROMETHEUS}'}
def variable(name,query):
 return {'name':name,'label':name.replace('_',' ').title(),'type':'query','datasource':DS,'query':{'query':query,'refId':name},'refresh':1,'multi':True,'includeAll':True,'allValue':'.*','current':{'text':'All','value':'$__all'}}
VARIABLES=[{'name':'DS_PROMETHEUS','label':'Metrics','type':'datasource','query':'prometheus','current':{'text':'Mimir','value':'mimir'}},{'name':'DS_LOKI','label':'Logs','type':'datasource','query':'loki','current':{'text':'Loki','value':'loki'}},{'name':'DS_TEMPO','label':'Traces','type':'datasource','query':'tempo','current':{'text':'Tempo','value':'tempo'}}]+[
 variable('application','label_values({__name__=~"social_(feature_enabled|client_feature_reports_total)"}, application)'),
 variable('environment','label_values({application=~"$application",__name__=~"social_(feature_enabled|client_feature_reports_total)"}, environment)'),
 variable('side','label_values(social_operations_total{application=~"$application",environment=~"$environment"}, side)'),
 variable('platform','label_values(social_operations_total{application=~"$application",environment=~"$environment"}, platform)'),
 variable('provider','label_values({application=~"$application",__name__=~"social_(operations_total|login_provider_enabled)"}, provider)')]
def selector(feature,provider=True):
 s='application=~"$application",environment=~"$environment",side=~"$side",platform=~"$platform"'
 if feature:s+=f',feature=~"{feature}"'
 if provider:s+=',provider=~"$provider"'
 return '{'+s+'}'
def panel(n,title,expr,unit='short',kind='timeseries',desc=''):
 return {'id':n,'title':title,'type':kind,'gridPos':{'x':0 if n%2 else 12,'y':((n-1)//2)*8,'w':12,'h':8},'datasource':DS,'description':desc,
  'targets':[{'refId':'A','expr':expr,'legendFormat':'{{feature}} {{side}} {{operation}} {{result}}','range':kind=='timeseries','instant':kind=='stat'}],
  'fieldConfig':{'defaults':{'unit':unit,'links':[{'title':'Social logs and traces','url':'/d/social-'+('runtime' if feature=='.*' else feature.replace('_','-'))+'-v1?${application:queryparam}&${environment:queryparam}&${side:queryparam}&${platform:queryparam}&${provider:queryparam}'}]},'overrides':[]},
  'options':{'legend':{'displayMode':'table','placement':'bottom'}} if kind=='timeseries' else {'reduceOptions':{'calcs':['lastNotNull'],'values':False},'colorMode':'none'}}
for feature in ['.*']+FEATURES:
 slug='overview' if feature=='.*' else feature.replace('_','-')
 scoped='runtime|transport' if feature=='runtime' else feature
 op=selector(scoped); basic=selector(scoped,False)
 panels=[panel(1,'Operations per second',f'sum by (feature,side,operation) (rate(social_operations_total{op}[$__rate_interval]))','ops'),
 panel(2,'Unexpected error fraction',f'sum by (feature,side) (rate(social_operations_total{op[:-1]},outcome="error"}}[$__rate_interval])) / sum by (feature,side) (rate(social_operations_total{op}[$__rate_interval]))','percentunit',desc='Expected denials, cancellations and skipped writes are not unexpected errors. No data is not a health signal.'),
 panel(3,'Operation outcomes',f'sum by (feature,side,operation,result) (increase(social_operations_total{op}[$__range]))'),
 panel(4,'P95 operation latency',f'histogram_quantile(0.95, sum by (le,feature,side,operation) (rate(social_operation_duration_seconds_bucket{op}[$__rate_interval])))','s'),
 panel(5,'Server feature available',f'max by (feature) (social_feature_enabled{{application=~"$application",environment=~"$environment",feature=~"{scoped}"}})',kind='stat',desc='1 means a server extension is installed. A missing series does not say whether clients use this feature.'),
 panel(6,'Seconds since latest client report',f'time() - max by (feature,platform) (social_client_last_report_timestamp_seconds{basic})','s',kind='stat',desc='Heartbeat every 30 seconds while the client is alive. Missing reports can mean offline clients, a disabled exporter or no clients using this feature.'),
 panel(7,'Events, retries and dead letters',f'sum by (feature,side,operation,result) (rate(social_events_total{op}[$__rate_interval]))','ops'),
 panel(8,'P95 reported queue depth',f'histogram_quantile(0.95, sum by (le,feature,side) (rate(social_queue_depth_items_bucket{basic}[$__rate_interval])))',desc='Distribution over reporting client snapshots. This is not an exact fleet-wide current backlog.'),
 panel(9,'P95 reported oldest queue age',f'histogram_quantile(0.95, sum by (le,feature,side) (rate(social_queue_age_seconds_bucket{basic}[$__rate_interval])))','s')]
 if feature=='login':panels.append(panel(10,'Configured login providers','max by (provider) (social_login_provider_enabled{application=~"$application",environment=~"$environment",provider=~"$provider"})',kind='stat',desc='1 enabled, 0 disabled; independent of request traffic.'))
 elif feature in ['remote_content','avatars']:panels.append(panel(10,'Transferred bytes per second',f'sum by (feature,side,operation) (rate(social_transfer_bytes_sum{op}[$__rate_interval]))','Bps'))
 else:panels.append(panel(10,'Telemetry drops and export results','sum by (side,result) (rate({__name__=~"social_telemetry_(dropped|exports)_total",application=~"$application",environment=~"$environment"}[$__rate_interval]))','ops',desc='Client reports are best effort; compare this panel with client freshness before interpreting rates.'))
 panels.append({'id':11,'title':'Correlated diagnostic events','type':'logs','gridPos':{'x':0,'y':40,'w':24,'h':10},'datasource':{'type':'loki','uid':'${DS_LOKI}'},'targets':[{'refId':'A','expr':'{service_name=~".+"} | json | application=~"$application" | environment=~"$environment" | feature=~"'+scoped+'" | side=~"$side" | platform=~"$platform" | provider=~"$provider"'}], 'description':'Open a trace_id using the Loki-to-Tempo derived field configured in the pack provisioning example. Credentials and content are excluded.','options':{'showTime':True,'wrapLogMessage':True,'sortOrder':'Descending'}})
 tempo_url='/explore?schemaVersion=1&panes='+quote(json.dumps({'A':{'datasource':'${DS_TEMPO}','queries':[],'range':{'from':'now-1h','to':'now'}}}),safe='').replace('%24%7BDS_TEMPO%7D','${DS_TEMPO}')
 dashboard={'id':None,'uid':f'social-{slug}-v1','title':f'Social / {slug.replace("-"," ").title()}','tags':['social','social-'+slug],'schemaVersion':40,'version':1,'editable':True,'timezone':'browser','time':{'from':'now-1h','to':'now'},'refresh':'30s','templating':{'list':VARIABLES},'links':[{'title':'Installed Social dashboards','type':'dashboards','tags':['social'],'asDropdown':True,'includeVars':True,'keepTime':True},{'title':'Explore traces','type':'link','url':tempo_url}],'panels':panels}
 (ROOT/'grafana'/f'social-{slug}.json').write_text(json.dumps(dashboard,indent=2)+'\n')
manifest={'schema':1,'metricContract':1,'reportVersion':1,'grafana':'11.4.0','features':FEATURES,'metrics':METRICS,'dashboards':[p.name for p in sorted((ROOT/'grafana').glob('*.json'))], 'requiredDatasources':['prometheus','loki','tempo'],'clientDelivery':'bounded, memory-only, best effort','traceSampling':'all operations','packs':['packs/'+n.replace('_','-') for n in ['overview']+FEATURES]}
(ROOT/'manifest.json').write_text(json.dumps(manifest,indent=2)+'\n')

# Each pack is independently importable. Install either these scoped rules or the
# aggregate alerts.yml, so the same condition is never evaluated twice.
alerts = (ROOT/'prometheus'/'alerts.yml').read_text()
operation_rule = alerts[alerts.index('      - alert: SocialUnexpectedErrors'):alerts.index('      - alert: SocialNewDeadLetters')]
deadletter_rule = alerts[alerts.index('      - alert: SocialNewDeadLetters'):alerts.index('  - name: social.telemetry')]
telemetry_rules = alerts[alerts.index('  - name: social.telemetry'):]
for name in ['overview'] + FEATURES:
 slug = name.replace('_','-')
 pack = ROOT/'packs'/slug
 pack.mkdir(parents=True, exist_ok=True)
 (pack/f'social-{slug}.json').write_bytes((ROOT/'grafana'/f'social-{slug}.json').read_bytes())
 metadata = {'schema':1,'feature':name,'metricContract':1,'reportVersion':1,'grafana':'11.4.0','dashboardUid':f'social-{slug}-v1','datasources':['prometheus','loki','tempo']}
 (pack/'manifest.json').write_text(json.dumps(metadata,indent=2)+'\n')
 notes = f'# Social {name.replace("_"," ")}\n\nImport `social-{slug}.json`, select the existing metrics, logs and traces data sources, and choose application/environment filters. No recording rules are required. Missing series do not imply healthy operation.\n\n'
 notes += 'See the root `README.md` for instrumentation setup, delivery bounds, metric semantics and runbooks. Install the scoped `alerts.yml` only if you want warning alerts; do not also install the aggregate alerts.\n'
 if name in ['messages','remote_content']: notes += '\nQueue panels describe distributions of reporting client snapshots, with freshness; they are not exact fleet totals.\n'
 if name == 'messages': notes += '\nEnqueueing and server acceptance do not establish recipient delivery.\n'
 if name == 'typing': notes += '\nExpiry events count timeout observations within existing application subscriptions. Multiple observers can see the same expiry.\n'
 if name == 'login': notes += '\nProvider gauges distinguish disabled providers from enabled but idle providers. Expected authentication denials do not count as infrastructure errors.\n'
 (pack/'README.md').write_text(notes)
 if name == 'overview': rules=alerts
 elif name == 'runtime': rules='groups:\n'+telemetry_rules
 else:
  rule=operation_rule.replace('SocialUnexpectedErrors','Social'+''.join(word.title() for word in name.split('_'))+'UnexpectedErrors')
  rule=rule.replace('social_operations_total{outcome=',f'social_operations_total{{feature="{name}",outcome=').replace('social_operations_total[5m]',f'social_operations_total{{feature="{name}"}}[5m]')
  rules=f'groups:\n  - name: social.{name}\n    rules:\n'+rule+(deadletter_rule if name=='messages' else '')
 (pack/'alerts.yml').write_text(rules)

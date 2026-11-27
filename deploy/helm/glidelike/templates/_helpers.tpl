{{- define "glidelike.name" -}}
{{- default .Chart.Name .Values.nameOverride | trunc 63 | trimSuffix "-" }}
{{- end }}

{{- define "glidelike.fullname" -}}
{{- if .Values.fullnameOverride }}
{{- .Values.fullnameOverride | trunc 63 | trimSuffix "-" }}
{{- else }}
{{- $name := default .Chart.Name .Values.nameOverride }}
{{- if contains $name .Release.Name }}
{{- .Release.Name | trunc 63 | trimSuffix "-" }}
{{- else }}
{{- printf "%s-%s" .Release.Name $name | trunc 63 | trimSuffix "-" }}
{{- end }}
{{- end }}
{{- end }}

{{- define "glidelike.labels" -}}
helm.sh/chart: {{ printf "%s-%s" .Chart.Name .Chart.Version | replace "+" "_" }}
app.kubernetes.io/name: {{ include "glidelike.name" . }}
app.kubernetes.io/instance: {{ .Release.Name }}
app.kubernetes.io/version: {{ .Chart.AppVersion | quote }}
app.kubernetes.io/managed-by: {{ .Release.Service }}
app.kubernetes.io/part-of: glidelike
{{- end }}

{{/* Selector labels for one component (backend | frontend). */}}
{{- define "glidelike.selectorLabels" -}}
app.kubernetes.io/name: {{ include "glidelike.name" .root }}
app.kubernetes.io/instance: {{ .root.Release.Name }}
app.kubernetes.io/component: {{ .component }}
{{- end }}

{{- define "glidelike.backend.fullname" -}}{{ include "glidelike.fullname" . }}-backend{{- end }}
{{- define "glidelike.frontend.fullname" -}}{{ include "glidelike.fullname" . }}-frontend{{- end }}
{{- define "glidelike.appSecretName" -}}
{{- .Values.secrets.existingSecret | default (printf "%s-app" (include "glidelike.fullname" .)) }}
{{- end }}
{{- define "glidelike.dbSecretName" -}}
{{- .Values.postgres.existingSecret | default (printf "%s-db" (include "glidelike.fullname" .)) }}
{{- end }}
{{- define "glidelike.pgClusterName" -}}{{ include "glidelike.fullname" . }}-pg{{- end }}

{{/* Hostname of the read-write Postgres endpoint. */}}
{{- define "glidelike.dbHost" -}}
{{- if eq .Values.postgres.mode "cloudnativepg" -}}
{{ include "glidelike.pgClusterName" . }}-rw
{{- else if eq .Values.postgres.mode "external" -}}
{{ required "postgres.external.host is required when postgres.mode=external" .Values.postgres.external.host }}
{{- else -}}
{{ fail (printf "unknown postgres.mode %q (use cloudnativepg or external)" .Values.postgres.mode) }}
{{- end -}}
{{- end }}

{{- define "glidelike.dbPort" -}}
{{- if eq .Values.postgres.mode "external" }}{{ .Values.postgres.external.port }}{{ else }}5432{{ end }}
{{- end }}

{{- define "glidelike.jdbcUrl" -}}
jdbc:postgresql://{{ include "glidelike.dbHost" . }}:{{ include "glidelike.dbPort" . }}/{{ .Values.postgres.database }}
{{- if eq .Values.postgres.mode "external" }}?sslmode={{ .Values.postgres.external.sslMode }}{{ end }}
{{- end }}

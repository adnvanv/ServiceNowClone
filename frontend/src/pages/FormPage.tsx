import { useEffect, useMemo, useState } from 'react'
import { useNavigate, useParams } from 'react-router-dom'
import { api, ApiError, tableUrl } from '../api/client'
import type { RecordRow, TableMeta } from '../api/types'
import { displayText, isDisplayValue, rawValue } from '../api/types'
import FieldInput from '../components/FieldInput'

/**
 * The generic form view. Fields render by internal_type; mandatory fields
 * are marked and validated client-side (the server re-validates); fields the
 * user cannot read are hidden and fields they cannot write are read-only —
 * both driven by the ACL hints in the meta response.
 */
export default function FormPage() {
  const { table, sysId } = useParams<{ table: string; sysId: string }>()
  const isNew = !sysId
  const navigate = useNavigate()

  const [meta, setMeta] = useState<TableMeta | null>(null)
  const [record, setRecord] = useState<RecordRow | null>(null)
  const [values, setValues] = useState<Record<string, unknown>>({})
  const [refDisplays, setRefDisplays] = useState<Record<string, string | null>>({})
  const [dirty, setDirty] = useState<Set<string>>(new Set())
  const [error, setError] = useState<string | null>(null)
  const [missing, setMissing] = useState<Set<string>>(new Set())
  const [busy, setBusy] = useState(false)

  useEffect(() => {
    setError(null)
    setValues({})
    setRefDisplays({})
    setDirty(new Set())
    setRecord(null)
    api
      .get<TableMeta>(`/api/now/meta/table/${table}`)
      .then(({ result }) => {
        setMeta(result)
        if (isNew) {
          const defaults: Record<string, unknown> = {}
          for (const f of result.fields) {
            if (f.default_value !== null) {
              defaults[f.element] = f.type === 'boolean' ? f.default_value === 'true' : f.default_value
            }
          }
          setValues(defaults)
        }
      })
      .catch((e) => setError(e instanceof ApiError ? e.message : 'Failed to load metadata'))
  }, [table, sysId, isNew])

  useEffect(() => {
    if (isNew || !meta) return
    api
      .get<RecordRow>(`/api/now/table/${table}/${sysId}?sysparm_display_value=true`)
      .then(({ result }) => {
        setRecord(result)
        const vals: Record<string, unknown> = {}
        const displays: Record<string, string | null> = {}
        for (const [k, v] of Object.entries(result)) {
          vals[k] = rawValue(v)
          if (isDisplayValue(v)) displays[k] = v.display_value
        }
        setValues(vals)
        setRefDisplays(displays)
      })
      .catch((e) => setError(e instanceof ApiError ? e.message : 'Failed to load record'))
  }, [meta, table, sysId, isNew])

  const visibleFields = useMemo(() => (meta ? meta.fields.filter((f) => f.can_read) : []), [meta])
  const editable = isNew ? meta?.access.can_create : meta?.access.can_write

  function setField(element: string, value: unknown, refDisplay?: string | null) {
    setValues((v) => ({ ...v, [element]: value }))
    if (refDisplay !== undefined) setRefDisplays((d) => ({ ...d, [element]: refDisplay }))
    setDirty((d) => new Set(d).add(element))
    setMissing((m) => {
      if (!m.has(element)) return m
      const next = new Set(m)
      next.delete(element)
      return next
    })
  }

  async function save() {
    if (!meta) return
    // Client-side mandatory check; the server enforces it again.
    const missingNow = new Set<string>()
    for (const f of visibleFields) {
      if (!f.mandatory || !f.can_write) continue
      const v = values[f.element]
      if (v === null || v === undefined || (typeof v === 'string' && v.trim() === '')) {
        missingNow.add(f.element)
      }
    }
    setMissing(missingNow)
    if (missingNow.size > 0) {
      setError('Fill mandatory fields: ' + [...missingNow].join(', '))
      return
    }

    const payload: Record<string, unknown> = {}
    for (const f of visibleFields) {
      if (!f.can_write) continue
      if (isNew ? values[f.element] !== undefined : dirty.has(f.element)) {
        payload[f.element] = values[f.element] ?? null
      }
    }

    setBusy(true)
    setError(null)
    try {
      if (isNew) {
        const { result } = await api.post<RecordRow>(tableUrl(meta.name), payload)
        navigate(`/table/${meta.name}/${result.sys_id}`, { replace: true })
      } else {
        // sys_mod_count from the loaded record makes the update optimistic:
        // a concurrent change returns 409 instead of silently losing it.
        payload.sys_mod_count = values.sys_mod_count
        await api.patch<RecordRow>(`/api/now/table/${meta.name}/${sysId}`, payload)
        navigate(`/table/${meta.name}`)
      }
    } catch (e) {
      setError(e instanceof ApiError ? `${e.message}${e.detail ? ` — ${e.detail}` : ''}` : 'Save failed')
    } finally {
      setBusy(false)
    }
  }

  async function remove() {
    if (!meta || isNew || !window.confirm('Delete this record?')) return
    setBusy(true)
    try {
      await api.delete(`/api/now/table/${meta.name}/${sysId}`)
      navigate(`/table/${meta.name}`)
    } catch (e) {
      setError(e instanceof ApiError ? e.message : 'Delete failed')
      setBusy(false)
    }
  }

  if (!meta) return error ? <div className="error-banner">{error}</div> : null

  return (
    <div>
      <div className="page-header">
        <h1>
          {meta.label}: {isNew ? 'New record' : displayText(record?.[meta.display_field]) || sysId}
        </h1>
        <span className="spacer" />
        <button className="btn secondary" onClick={() => navigate(`/table/${meta.name}`)}>
          Back to list
        </button>
      </div>

      {error && <div className="error-banner">{error}</div>}

      <div className="panel">
        <div className="form-grid">
          {visibleFields.map((f) => (
            <FieldRow
              key={f.element}
              missing={missing.has(f.element)}
              label={f.label}
              mandatory={f.mandatory}
              element={f.element}
              inherited={f.declared_on !== meta.name ? f.declared_on : null}
            >
              <FieldInput
                field={f}
                value={values[f.element] ?? null}
                refDisplay={refDisplays[f.element]}
                disabled={!editable || !f.can_write || busy}
                onChange={(v, d) => setField(f.element, v, d)}
              />
              {!f.can_write && <div className="field-note">read-only (ACL)</div>}
            </FieldRow>
          ))}
        </div>

        <div className="form-actions">
          {editable && (
            <button className="btn" onClick={save} disabled={busy}>
              {isNew ? 'Create' : 'Save'}
            </button>
          )}
          {!isNew && meta.access.can_delete && (
            <button className="btn danger" onClick={remove} disabled={busy}>
              Delete
            </button>
          )}
        </div>

        {!isNew && record && (
          <div className="sys-info">
            sys_id {String(rawValue(record.sys_id))} · class {displayText(record.sys_class_name)} · created{' '}
            {displayText(record.sys_created_on)} by {displayText(record.sys_created_by)} · updated{' '}
            {displayText(record.sys_updated_on)} by {displayText(record.sys_updated_by)} · mod count{' '}
            {displayText(record.sys_mod_count)}
          </div>
        )}
      </div>
    </div>
  )
}

function FieldRow({
  label,
  mandatory,
  element,
  inherited,
  missing,
  children,
}: {
  label: string
  mandatory: boolean
  element: string
  inherited: string | null
  missing: boolean
  children: React.ReactNode
}) {
  return (
    <>
      <label htmlFor={`f_${element}`} style={missing ? { color: 'var(--danger)' } : undefined}>
        {label}
        {inherited && <span className="tag">{inherited}</span>}
        {mandatory && <span className="req">*</span>}
      </label>
      <div>{children}</div>
    </>
  )
}

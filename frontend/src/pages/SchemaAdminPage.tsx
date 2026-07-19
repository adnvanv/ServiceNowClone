import { useCallback, useEffect, useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { api, ApiError } from '../api/client'
import type { TableListItem, TableMeta } from '../api/types'

/**
 * The screen that makes the demo land: define a table and its fields at
 * runtime, then immediately open its list/form — no code, no deploy.
 */

interface FieldDraft {
  element: string
  label: string
  type: string
  maxLength: string
  referenceTable: string
  mandatory: boolean
  display: boolean
  defaultValue: string
  choicesText: string // one "value=Label" per line
}

const EMPTY_FIELD: FieldDraft = {
  element: '',
  label: '',
  type: 'string',
  maxLength: '',
  referenceTable: '',
  mandatory: false,
  display: false,
  defaultValue: '',
  choicesText: '',
}

const TYPES = ['string', 'text', 'integer', 'decimal', 'boolean', 'datetime', 'choice', 'reference']

function toFieldRequest(d: FieldDraft) {
  const choices = d.choicesText
    .split('\n')
    .map((line) => line.trim())
    .filter(Boolean)
    .map((line, i) => {
      const eq = line.indexOf('=')
      const value = eq >= 0 ? line.slice(0, eq).trim() : line
      const label = eq >= 0 ? line.slice(eq + 1).trim() : line
      return { value, label, sequence: i + 1 }
    })
  return {
    element: d.element.trim(),
    label: d.label.trim() || d.element.trim(),
    type: d.type,
    maxLength: d.type === 'string' && d.maxLength ? Number(d.maxLength) : null,
    referenceTable: d.type === 'reference' ? d.referenceTable : null,
    mandatory: d.mandatory,
    display: d.display,
    defaultValue: d.defaultValue || null,
    choices: d.type === 'choice' && choices.length > 0 ? choices : null,
  }
}

function FieldEditor({ draft, tables, onChange, onRemove }: {
  draft: FieldDraft
  tables: TableListItem[]
  onChange: (d: FieldDraft) => void
  onRemove?: () => void
}) {
  return (
    <div>
      <div className="schema-field-row">
        <input
          placeholder="element (e.g. priority)"
          value={draft.element}
          onChange={(e) => onChange({ ...draft, element: e.target.value })}
        />
        <input
          placeholder="Label"
          value={draft.label}
          onChange={(e) => onChange({ ...draft, label: e.target.value })}
        />
        <select value={draft.type} onChange={(e) => onChange({ ...draft, type: e.target.value })}>
          {TYPES.map((t) => (
            <option key={t}>{t}</option>
          ))}
        </select>
        {draft.type === 'string' ? (
          <input
            placeholder="max length"
            type="number"
            value={draft.maxLength}
            onChange={(e) => onChange({ ...draft, maxLength: e.target.value })}
          />
        ) : draft.type === 'reference' ? (
          <select
            value={draft.referenceTable}
            onChange={(e) => onChange({ ...draft, referenceTable: e.target.value })}
          >
            <option value="">— references —</option>
            {tables.map((t) => (
              <option key={t.name} value={t.name}>
                {t.label} ({t.name})
              </option>
            ))}
          </select>
        ) : (
          <input
            placeholder="default value"
            value={draft.defaultValue}
            onChange={(e) => onChange({ ...draft, defaultValue: e.target.value })}
          />
        )}
        <span className="check">
          <input
            type="checkbox"
            checked={draft.mandatory}
            onChange={(e) => onChange({ ...draft, mandatory: e.target.checked })}
          />
          mandatory
        </span>
        <span className="check">
          <input
            type="checkbox"
            checked={draft.display}
            onChange={(e) => onChange({ ...draft, display: e.target.checked })}
          />
          display
        </span>
        {onRemove ? (
          <button className="link-btn" onClick={onRemove}>
            ✕
          </button>
        ) : (
          <span />
        )}
      </div>
      {draft.type === 'choice' && (
        <div style={{ margin: '0 0 8px 0' }}>
          <textarea
            placeholder={'Choices, one per line as value=Label\n1=New\n2=In Progress'}
            value={draft.choicesText}
            onChange={(e) => onChange({ ...draft, choicesText: e.target.value })}
            style={{ width: '100%', minHeight: 54, fontFamily: 'var(--mono)', fontSize: 12 }}
          />
        </div>
      )}
    </div>
  )
}

export default function SchemaAdminPage() {
  const navigate = useNavigate()
  const [tables, setTables] = useState<TableListItem[]>([])
  const [error, setError] = useState<string | null>(null)
  const [info, setInfo] = useState<string | null>(null)

  // create table state
  const [name, setName] = useState('')
  const [label, setLabel] = useState('')
  const [extendsTable, setExtendsTable] = useState('')
  const [extendable, setExtendable] = useState(false)
  const [fields, setFields] = useState<FieldDraft[]>([{ ...EMPTY_FIELD }])
  const [busy, setBusy] = useState(false)

  // add-field state
  const [targetTable, setTargetTable] = useState('')
  const [newField, setNewField] = useState<FieldDraft>({ ...EMPTY_FIELD })
  const [targetMeta, setTargetMeta] = useState<TableMeta | null>(null)

  const loadTables = useCallback(() => {
    api.get<TableListItem[]>('/api/now/meta/tables').then(({ result }) => setTables(result))
  }, [])

  useEffect(loadTables, [loadTables])

  useEffect(() => {
    if (!targetTable) {
      setTargetMeta(null)
      return
    }
    api.get<TableMeta>(`/api/now/meta/table/${targetTable}`).then(({ result }) => setTargetMeta(result))
  }, [targetTable])

  function fail(e: unknown, fallback: string) {
    setInfo(null)
    setError(e instanceof ApiError ? `${e.message}${e.detail ? ` — ${e.detail}` : ''}` : fallback)
  }

  async function createTable() {
    setBusy(true)
    setError(null)
    try {
      await api.post('/api/now/meta/table', {
        name: name.trim(),
        label: label.trim() || name.trim(),
        extendsTable: extendsTable || null,
        extendable,
        fields: fields.filter((f) => f.element.trim()).map(toFieldRequest),
      })
      setInfo(`Table '${name.trim()}' created — it is live right now.`)
      loadTables()
      navigate(`/table/${name.trim()}`)
    } catch (e) {
      fail(e, 'Create failed')
    } finally {
      setBusy(false)
    }
  }

  async function addField() {
    setBusy(true)
    setError(null)
    try {
      await api.post(`/api/now/meta/table/${targetTable}/field`, toFieldRequest(newField))
      setInfo(`Field '${newField.element}' added to '${targetTable}'.`)
      setNewField({ ...EMPTY_FIELD })
      setTargetTable('')
    } catch (e) {
      fail(e, 'Add field failed')
    } finally {
      setBusy(false)
    }
  }

  const extendables = tables.filter((t) => t.extendable)

  return (
    <div>
      <div className="page-header">
        <h1>Schema admin</h1>
        <span className="sub">runtime DDL — tables are usable the moment they are created</span>
      </div>

      {error && <div className="error-banner">{error}</div>}
      {info && <div className="info-banner">{info}</div>}

      <div className="panel">
        <h2>Create table</h2>
        <div className="filter-row">
          <input placeholder="name (e.g. problem)" value={name} onChange={(e) => setName(e.target.value)} />
          <input placeholder="Label" value={label} onChange={(e) => setLabel(e.target.value)} />
          <select value={extendsTable} onChange={(e) => setExtendsTable(e.target.value)}>
            <option value="">— does not extend —</option>
            {extendables.map((t) => (
              <option key={t.name} value={t.name}>
                extends {t.label} ({t.name})
              </option>
            ))}
          </select>
          <span className="check" style={{ display: 'flex', alignItems: 'center', gap: 4 }}>
            <input type="checkbox" checked={extendable} onChange={(e) => setExtendable(e.target.checked)} />
            extendable
          </span>
        </div>

        <h2 style={{ marginTop: 12 }}>Fields</h2>
        {fields.map((f, i) => (
          <FieldEditor
            key={i}
            draft={f}
            tables={tables}
            onChange={(d) => setFields(fields.map((x, idx) => (idx === i ? d : x)))}
            onRemove={fields.length > 1 ? () => setFields(fields.filter((_, idx) => idx !== i)) : undefined}
          />
        ))}
        <button className="link-btn" onClick={() => setFields([...fields, { ...EMPTY_FIELD }])}>
          + Add field
        </button>

        <div className="form-actions">
          <button className="btn" onClick={createTable} disabled={busy || !name.trim()}>
            Create table
          </button>
        </div>
      </div>

      <div className="panel">
        <h2>Add field to existing table</h2>
        <div className="filter-row">
          <select value={targetTable} onChange={(e) => setTargetTable(e.target.value)}>
            <option value="">— table —</option>
            {tables.map((t) => (
              <option key={t.name} value={t.name}>
                {t.label} ({t.name})
              </option>
            ))}
          </select>
          {targetMeta && (
            <span style={{ color: 'var(--text-muted)', fontSize: 12 }}>
              current fields: {targetMeta.fields.map((f) => f.element).join(', ') || 'none'}
            </span>
          )}
        </div>
        {targetTable && (
          <>
            <FieldEditor draft={newField} tables={tables} onChange={setNewField} />
            <div className="form-actions">
              <button className="btn" onClick={addField} disabled={busy || !newField.element.trim()}>
                Add field
              </button>
            </div>
          </>
        )}
      </div>
    </div>
  )
}

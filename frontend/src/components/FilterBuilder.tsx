import { useEffect, useState } from 'react'
import type { FieldMeta, TableMeta } from '../api/types'

/**
 * Composes an encoded query from [field, operator, value] rows joined by
 * AND/OR. The raw encoded string is always shown in an editable box and is
 * the single source of truth on Run — editing it by hand is fully supported.
 */

interface FilterRow {
  connector: 'AND' | 'OR'
  field: string
  operator: string
  value: string
}

const OPERATORS: { token: string; label: string; unary?: boolean }[] = [
  { token: '=', label: 'is' },
  { token: '!=', label: 'is not' },
  { token: 'LIKE', label: 'contains' },
  { token: 'STARTSWITH', label: 'starts with' },
  { token: 'ENDSWITH', label: 'ends with' },
  { token: '>', label: 'greater than' },
  { token: '<', label: 'less than' },
  { token: '>=', label: 'at least' },
  { token: '<=', label: 'at most' },
  { token: 'IN', label: 'is one of' },
  { token: 'NOT IN', label: 'is not one of' },
  { token: 'ISEMPTY', label: 'is empty', unary: true },
  { token: 'ISNOTEMPTY', label: 'is not empty', unary: true },
  { token: 'BETWEEN', label: 'between (a@b)' },
]

function compose(rows: FilterRow[]): string {
  let encoded = ''
  rows.forEach((row, i) => {
    if (!row.field || !row.operator) return
    const op = OPERATORS.find((o) => o.token === row.operator)
    const condition = `${row.field}${row.operator}${op?.unary ? '' : row.value}`
    if (i === 0 || encoded === '') encoded = condition
    else encoded += (row.connector === 'OR' ? '^OR' : '^') + condition
  })
  return encoded
}

interface Props {
  meta: TableMeta
  query: string
  onRun: (encoded: string) => void
}

export default function FilterBuilder({ meta, query, onRun }: Props) {
  const [rows, setRows] = useState<FilterRow[]>([])
  const [raw, setRaw] = useState(query)

  useEffect(() => setRaw(query), [query])

  const queryableFields: (FieldMeta | { element: string; label: string })[] = [
    ...meta.fields.filter((f) => f.can_read),
    ...meta.base_columns.map((c) => ({ element: c, label: c })),
  ]

  function updateRow(i: number, patch: Partial<FilterRow>) {
    const next = rows.map((r, idx) => (idx === i ? { ...r, ...patch } : r))
    setRows(next)
    setRaw(compose(next))
  }

  function addRow() {
    setRows([...rows, { connector: 'AND', field: '', operator: '=', value: '' }])
  }

  function removeRow(i: number) {
    const next = rows.filter((_, idx) => idx !== i)
    setRows(next)
    setRaw(compose(next))
  }

  return (
    <div className="panel">
      {rows.map((row, i) => {
        const op = OPERATORS.find((o) => o.token === row.operator)
        return (
          <div className="filter-row" key={i}>
            {i > 0 ? (
              <select
                className="conn"
                value={row.connector}
                onChange={(e) => updateRow(i, { connector: e.target.value as 'AND' | 'OR' })}
              >
                <option>AND</option>
                <option>OR</option>
              </select>
            ) : (
              <span className="conn" style={{ textAlign: 'center', color: 'var(--text-muted)' }}>
                where
              </span>
            )}
            <select value={row.field} onChange={(e) => updateRow(i, { field: e.target.value })}>
              <option value="">— field —</option>
              {queryableFields.map((f) => (
                <option key={f.element} value={f.element}>
                  {f.label} ({f.element})
                </option>
              ))}
            </select>
            <select value={row.operator} onChange={(e) => updateRow(i, { operator: e.target.value })}>
              {OPERATORS.map((o) => (
                <option key={o.token} value={o.token}>
                  {o.label}
                </option>
              ))}
            </select>
            {!op?.unary && (
              <input
                placeholder="value"
                value={row.value}
                onChange={(e) => updateRow(i, { value: e.target.value })}
                onKeyDown={(e) => e.key === 'Enter' && onRun(raw)}
              />
            )}
            <button className="link-btn" onClick={() => removeRow(i)} title="Remove condition">
              ✕
            </button>
          </div>
        )
      })}
      <button className="link-btn" onClick={addRow}>
        + Add condition
      </button>
      <div className="encoded-query">
        <span style={{ color: 'var(--text-muted)', fontSize: 12 }}>Encoded query</span>
        <input
          value={raw}
          onChange={(e) => setRaw(e.target.value)}
          onKeyDown={(e) => e.key === 'Enter' && onRun(raw)}
          placeholder="e.g. active=true^priority=1^ORpriority=2"
          spellCheck={false}
        />
        <button className="btn" onClick={() => onRun(raw)}>
          Run
        </button>
        <button
          className="btn secondary"
          onClick={() => {
            setRows([])
            setRaw('')
            onRun('')
          }}
        >
          Clear
        </button>
      </div>
    </div>
  )
}

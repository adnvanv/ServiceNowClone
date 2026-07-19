import { useEffect, useRef, useState } from 'react'
import { api, tableUrl } from '../api/client'
import type { FieldMeta, RecordRow } from '../api/types'
import { displayText } from '../api/types'

interface Props {
  field: FieldMeta
  value: string | null
  displayValue: string | null
  disabled?: boolean
  onChange: (sysId: string | null, display: string | null) => void
}

/**
 * Typeahead over the referenced table, searching its display field with a
 * LIKE query. Selecting a row stores the target's sys_id.
 */
export default function ReferencePicker({ field, value, displayValue, disabled, onChange }: Props) {
  const ref = field.reference!
  const [text, setText] = useState(displayValue ?? '')
  const [options, setOptions] = useState<RecordRow[] | null>(null)
  const [open, setOpen] = useState(false)
  const debounce = useRef<number>(undefined)
  const wrapper = useRef<HTMLDivElement>(null)

  useEffect(() => setText(displayValue ?? ''), [displayValue])

  useEffect(() => {
    function onDocClick(e: MouseEvent) {
      if (wrapper.current && !wrapper.current.contains(e.target as Node)) setOpen(false)
    }
    document.addEventListener('mousedown', onDocClick)
    return () => document.removeEventListener('mousedown', onDocClick)
  }, [])

  function search(term: string) {
    setText(term)
    setOpen(true)
    if (term === '') {
      // Clearing the input clears the reference.
      onChange(null, null)
    }
    window.clearTimeout(debounce.current)
    debounce.current = window.setTimeout(() => {
      const query = term ? `${ref.display_field}LIKE${term}` : ''
      api
        .get<RecordRow[]>(tableUrl(ref.table, { query, limit: 10, orderBy: ref.display_field }))
        .then(({ result }) => setOptions(result))
        .catch(() => setOptions([]))
    }, 200)
  }

  function pick(row: RecordRow) {
    const display = displayText(row[ref.display_field]) || String(row.sys_id)
    onChange(String(row.sys_id), display)
    setText(display)
    setOpen(false)
  }

  return (
    <div className="ref-picker" ref={wrapper}>
      <input
        type="text"
        value={text}
        disabled={disabled}
        placeholder={`Search ${ref.label}…`}
        onChange={(e) => search(e.target.value)}
        onFocus={() => {
          if (!disabled) search(text)
        }}
      />
      {open && !disabled && options !== null && (
        <div className="menu">
          {options.map((row) => (
            <div key={String(row.sys_id)} onMouseDown={() => pick(row)}>
              {displayText(row[ref.display_field]) || String(row.sys_id)}
            </div>
          ))}
          {options.length === 0 && <div className="muted">No matches in {ref.label}</div>}
        </div>
      )}
      {value && <div className="field-note">→ {ref.table} {value}</div>}
    </div>
  )
}

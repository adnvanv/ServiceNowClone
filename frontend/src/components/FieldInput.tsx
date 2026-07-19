import type { FieldMeta } from '../api/types'
import ReferencePicker from './ReferencePicker'

interface Props {
  field: FieldMeta
  value: unknown
  /** Display text for the current reference value, when known. */
  refDisplay?: string | null
  disabled?: boolean
  onChange: (value: unknown, refDisplay?: string | null) => void
}

/**
 * Renders exactly one input for a field, chosen by internal_type. This is
 * the whole form "engine" — there is no per-table form anywhere.
 */
export default function FieldInput({ field, value, refDisplay, disabled, onChange }: Props) {
  const common = { disabled, id: `f_${field.element}` }

  switch (field.type) {
    case 'text':
      return (
        <textarea
          {...common}
          value={value === null || value === undefined ? '' : String(value)}
          onChange={(e) => onChange(e.target.value)}
        />
      )
    case 'integer':
    case 'decimal':
      return (
        <input
          {...common}
          type="number"
          step={field.type === 'decimal' ? 'any' : 1}
          value={value === null || value === undefined ? '' : String(value)}
          onChange={(e) => onChange(e.target.value === '' ? null : Number(e.target.value))}
        />
      )
    case 'boolean':
      return (
        <input
          {...common}
          type="checkbox"
          checked={value === true}
          onChange={(e) => onChange(e.target.checked)}
          style={{ width: 16, height: 16, marginTop: 5 }}
        />
      )
    case 'datetime': {
      // The API speaks ISO-8601 (UTC); datetime-local wants yyyy-MM-ddTHH:mm.
      const text = typeof value === 'string' && value ? value.substring(0, 16) : ''
      return (
        <input
          {...common}
          type="datetime-local"
          value={text}
          onChange={(e) => onChange(e.target.value ? `${e.target.value}:00Z` : null)}
        />
      )
    }
    case 'choice':
      return (
        <select
          {...common}
          value={value === null || value === undefined ? '' : String(value)}
          onChange={(e) => onChange(e.target.value === '' ? null : e.target.value)}
        >
          <option value="">— none —</option>
          {(field.choices ?? []).map((c) => (
            <option key={c.value} value={c.value}>
              {c.label}
            </option>
          ))}
        </select>
      )
    case 'reference':
      return (
        <ReferencePicker
          field={field}
          value={value === null || value === undefined ? null : String(value)}
          displayValue={refDisplay ?? null}
          disabled={disabled}
          onChange={(sysId, display) => onChange(sysId, display)}
        />
      )
    default:
      return (
        <input
          {...common}
          type="text"
          maxLength={field.max_length ?? undefined}
          value={value === null || value === undefined ? '' : String(value)}
          onChange={(e) => onChange(e.target.value)}
        />
      )
  }
}

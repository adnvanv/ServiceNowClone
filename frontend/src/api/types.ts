// Shapes served by /api/now/meta — the single source the whole UI renders from.

export interface TableListItem {
  name: string
  label: string
  extendable: boolean
  super_class: string | null
}

export interface ChoiceMeta {
  value: string
  label: string
  sequence: number
}

export interface ReferenceMeta {
  table: string
  label: string
  display_field: string
}

export interface FieldMeta {
  element: string
  label: string
  type: 'string' | 'text' | 'integer' | 'decimal' | 'boolean' | 'datetime' | 'choice' | 'reference'
  max_length: number | null
  mandatory: boolean
  default_value: string | null
  display: boolean
  declared_on: string
  reference?: ReferenceMeta
  choices?: ChoiceMeta[]
  can_read: boolean
  can_write: boolean
}

export interface TableAccess {
  can_read: boolean
  can_create: boolean
  can_write: boolean
  can_delete: boolean
}

export interface TableMeta {
  name: string
  label: string
  extendable: boolean
  super_class: string | null
  chain: string[]
  display_field: string
  base_columns: string[]
  access: TableAccess
  fields: FieldMeta[]
}

/** A reference/choice value when sysparm_display_value=true. */
export interface DisplayValue {
  value: unknown
  display_value: string | null
}

export type RecordRow = Record<string, unknown>

export interface UserInfo {
  sys_id: string
  user_name: string
  first_name: string | null
  last_name: string | null
  email: string | null
}

export interface LoginResult {
  token: string
  user: UserInfo
  roles: string[]
}

export function isDisplayValue(v: unknown): v is DisplayValue {
  return typeof v === 'object' && v !== null && 'value' in v && 'display_value' in v
}

/** Raw value of a cell regardless of display_value shaping. */
export function rawValue(v: unknown): unknown {
  return isDisplayValue(v) ? v.value : v
}

/** Human-readable form of a cell. */
export function displayText(v: unknown): string {
  if (v === null || v === undefined) return ''
  if (isDisplayValue(v)) return v.display_value ?? (v.value === null ? '' : String(v.value))
  if (typeof v === 'boolean') return v ? 'true' : 'false'
  return String(v)
}

// Thin fetch wrapper: token injection, the result/error envelope, and typed
// helpers for the table + meta APIs. No table-specific code lives here or
// anywhere else in the frontend.

export class ApiError extends Error {
  status: number
  detail: string

  constructor(status: number, message: string, detail = '') {
    super(message)
    this.status = status
    this.detail = detail
  }
}

let authToken: string | null = localStorage.getItem('platform.token')
let onUnauthorized: (() => void) | null = null

export function setToken(token: string | null) {
  authToken = token
  if (token) localStorage.setItem('platform.token', token)
  else localStorage.removeItem('platform.token')
}

export function getToken() {
  return authToken
}

export function setUnauthorizedHandler(handler: () => void) {
  onUnauthorized = handler
}

interface RequestOptions {
  method?: string
  body?: unknown
}

async function request<T>(url: string, options: RequestOptions = {}): Promise<{ result: T; totalCount: number | null }> {
  const response = await fetch(url, {
    method: options.method ?? 'GET',
    headers: {
      'Content-Type': 'application/json',
      ...(authToken ? { Authorization: `Bearer ${authToken}` } : {}),
    },
    body: options.body !== undefined ? JSON.stringify(options.body) : undefined,
  })

  if (response.status === 401 && onUnauthorized) onUnauthorized()

  if (response.status === 204) {
    return { result: undefined as T, totalCount: null }
  }

  const payload = await response.json().catch(() => null)
  if (!response.ok) {
    const error = payload?.error
    throw new ApiError(response.status, error?.message ?? `Request failed (${response.status})`, error?.detail ?? '')
  }
  const totalHeader = response.headers.get('X-Total-Count')
  return { result: payload.result as T, totalCount: totalHeader !== null ? Number(totalHeader) : null }
}

export const api = {
  get: <T>(url: string) => request<T>(url),
  post: <T>(url: string, body: unknown) => request<T>(url, { method: 'POST', body }),
  patch: <T>(url: string, body: unknown) => request<T>(url, { method: 'PATCH', body }),
  delete: <T>(url: string) => request<T>(url, { method: 'DELETE' }),
}

export function tableUrl(
  table: string,
  params: {
    query?: string
    fields?: string[]
    limit?: number
    offset?: number
    orderBy?: string
    displayValue?: boolean
  } = {},
): string {
  const search = new URLSearchParams()
  if (params.query) search.set('sysparm_query', params.query)
  if (params.fields?.length) search.set('sysparm_fields', params.fields.join(','))
  if (params.limit !== undefined) search.set('sysparm_limit', String(params.limit))
  if (params.offset) search.set('sysparm_offset', String(params.offset))
  if (params.orderBy) search.set('sysparm_order_by', params.orderBy)
  if (params.displayValue) search.set('sysparm_display_value', 'true')
  const qs = search.toString()
  return `/api/now/table/${table}${qs ? `?${qs}` : ''}`
}

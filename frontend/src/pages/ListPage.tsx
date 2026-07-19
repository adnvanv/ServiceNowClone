import { useCallback, useEffect, useMemo, useState } from 'react'
import { useNavigate, useParams, useSearchParams } from 'react-router-dom'
import { api, ApiError, tableUrl } from '../api/client'
import type { RecordRow, TableMeta } from '../api/types'
import { displayText } from '../api/types'
import FilterBuilder from '../components/FilterBuilder'

const PAGE_SIZE = 20

/**
 * The generic list view: columns come from the dictionary, sorting from
 * clicking headers, filtering from the FilterBuilder's encoded query. Works
 * identically for any table that exists or will ever exist.
 */
export default function ListPage() {
  const { table } = useParams<{ table: string }>()
  const navigate = useNavigate()
  const [searchParams, setSearchParams] = useSearchParams()

  const [meta, setMeta] = useState<TableMeta | null>(null)
  const [rows, setRows] = useState<RecordRow[]>([])
  const [total, setTotal] = useState(0)
  const [error, setError] = useState<string | null>(null)

  const query = searchParams.get('query') ?? ''
  const page = Number(searchParams.get('page') ?? '0')
  const sort = searchParams.get('sort') ?? ''

  // Visible columns: every readable dictionary field, long-text types last.
  const columns = useMemo(() => {
    if (!meta) return []
    const fields = meta.fields.filter((f) => f.can_read)
    return [...fields.filter((f) => f.type !== 'text'), ...fields.filter((f) => f.type === 'text')].slice(0, 9)
  }, [meta])

  useEffect(() => {
    setMeta(null)
    setError(null)
    api
      .get<TableMeta>(`/api/now/meta/table/${table}`)
      .then(({ result }) => setMeta(result))
      .catch((e) => setError(e instanceof ApiError ? e.message : 'Failed to load metadata'))
  }, [table])

  const load = useCallback(() => {
    if (!meta) return
    setError(null)
    api
      .get<RecordRow[]>(
        tableUrl(meta.name, {
          query,
          limit: PAGE_SIZE,
          offset: page * PAGE_SIZE,
          orderBy: sort || undefined,
          displayValue: true,
        }),
      )
      .then(({ result, totalCount }) => {
        setRows(result)
        setTotal(totalCount ?? result.length)
      })
      .catch((e) => {
        setRows([])
        setTotal(0)
        setError(e instanceof ApiError ? `${e.message}${e.detail ? ` — ${e.detail}` : ''}` : 'Query failed')
      })
  }, [meta, query, page, sort])

  useEffect(load, [load])

  function setParam(patch: Record<string, string | null>) {
    const next = new URLSearchParams(searchParams)
    for (const [k, v] of Object.entries(patch)) {
      if (v === null || v === '') next.delete(k)
      else next.set(k, v)
    }
    setSearchParams(next)
  }

  function toggleSort(element: string) {
    if (sort === element) setParam({ sort: `-${element}`, page: null })
    else if (sort === `-${element}`) setParam({ sort: null, page: null })
    else setParam({ sort: element, page: null })
  }

  if (!meta) {
    return error ? <div className="error-banner">{error}</div> : null
  }

  const pages = Math.max(1, Math.ceil(total / PAGE_SIZE))

  return (
    <div>
      <div className="page-header">
        <h1>{meta.label}</h1>
        <span className="sub">{meta.name}</span>
        {meta.super_class && <span className="tag">extends {meta.super_class}</span>}
        <span className="spacer" />
        {meta.access.can_create && (
          <button className="btn" onClick={() => navigate(`/table/${meta.name}/new`)}>
            New
          </button>
        )}
      </div>

      <FilterBuilder meta={meta} query={query} onRun={(encoded) => setParam({ query: encoded, page: null })} />

      {error && <div className="error-banner">{error}</div>}

      <table className="data-table">
        <thead>
          <tr>
            {columns.map((c) => (
              <th key={c.element} onClick={() => toggleSort(c.element)} title={c.element}>
                {c.label}
                {sort === c.element && ' ▲'}
                {sort === `-${c.element}` && ' ▼'}
              </th>
            ))}
          </tr>
        </thead>
        <tbody>
          {rows.map((row) => (
            <tr key={String(row.sys_id)} onClick={() => navigate(`/table/${meta.name}/${row.sys_id}`)}>
              {columns.map((c) => (
                <td key={c.element}>{displayText(row[c.element])}</td>
              ))}
            </tr>
          ))}
          {rows.length === 0 && (
            <tr>
              <td className="empty" colSpan={columns.length || 1}>
                No records
              </td>
            </tr>
          )}
        </tbody>
      </table>

      <div className="pagination">
        <button disabled={page === 0} onClick={() => setParam({ page: String(page - 1) })}>
          ‹ Prev
        </button>
        <span>
          Page {page + 1} of {pages} — {total} record{total === 1 ? '' : 's'}
        </span>
        <button disabled={page + 1 >= pages} onClick={() => setParam({ page: String(page + 1) })}>
          Next ›
        </button>
      </div>
    </div>
  )
}

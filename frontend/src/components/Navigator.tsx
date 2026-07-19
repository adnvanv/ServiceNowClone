import { useEffect, useState } from 'react'
import { NavLink } from 'react-router-dom'
import { api } from '../api/client'
import type { TableListItem } from '../api/types'

/** Sidebar listing every table the current user can read. */
export default function Navigator() {
  const [tables, setTables] = useState<TableListItem[]>([])

  useEffect(() => {
    api
      .get<TableListItem[]>('/api/now/meta/tables')
      .then(({ result }) => setTables(result))
      .catch(() => setTables([]))
  }, [])

  return (
    <nav className="sidebar">
      <h3>Tables</h3>
      {tables.map((t) => (
        <NavLink key={t.name} to={`/table/${t.name}`}>
          {t.label} <span className="table-name">{t.name}</span>
        </NavLink>
      ))}
      {tables.length === 0 && <div style={{ padding: '5px 14px', color: 'var(--text-muted)' }}>No readable tables</div>}
    </nav>
  )
}

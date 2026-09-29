import { NavLink, Outlet } from 'react-router-dom'

export function Layout() {
  return (
    <div className="app">
      <header className="app-header">
        <NavLink to="/" className="brand">
          Resume Screening
        </NavLink>
        <nav>
          <NavLink to="/" end>
            Jobs
          </NavLink>
        </nav>
      </header>
      <main className="app-main">
        <Outlet />
      </main>
    </div>
  )
}

import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { StrictMode } from 'react'
import { createRoot } from 'react-dom/client'
import { BrowserRouter, Route, Routes } from 'react-router-dom'
import { Layout } from './components/Layout'
import { EditJobPage } from './pages/EditJobPage'
import { JobsPage } from './pages/JobsPage'
import { ResultsPage } from './pages/ResultsPage'
import { ScreenPage } from './pages/ScreenPage'
import { ResultsProvider } from './results'
import './styles.css'

const queryClient = new QueryClient({
  defaultOptions: { queries: { retry: false, refetchOnWindowFocus: false } },
})

createRoot(document.getElementById('root')!).render(
  <StrictMode>
    <QueryClientProvider client={queryClient}>
      <ResultsProvider>
        <BrowserRouter>
          <Routes>
            <Route element={<Layout />}>
              <Route index element={<JobsPage />} />
              <Route path="jobs/new" element={<EditJobPage />} />
              <Route path="jobs/:jobId/edit" element={<EditJobPage />} />
              <Route path="jobs/:jobId/screen" element={<ScreenPage />} />
              <Route path="jobs/:jobId/results" element={<ResultsPage />} />
              <Route path="*" element={<p>Page not found.</p>} />
            </Route>
          </Routes>
        </BrowserRouter>
      </ResultsProvider>
    </QueryClientProvider>
  </StrictMode>,
)

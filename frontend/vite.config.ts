import react from '@vitejs/plugin-react'
import { defineConfig } from 'vitest/config'

// The build goes straight into Spring Boot's static resources, so the jar serves the app.
export default defineConfig({
  plugins: [react()],
  build: {
    outDir: '../target/classes/static',
    emptyOutDir: true,
  },
  server: {
    // `npm run dev` on :5173 talks to the Spring app on :8080.
    proxy: { '/api': 'http://localhost:8080' },
  },
  test: {
    environment: 'jsdom',
    setupFiles: './src/test-setup.ts',
  },
})

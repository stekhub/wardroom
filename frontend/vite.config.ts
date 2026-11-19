import path from 'node:path'
import tailwindcss from '@tailwindcss/vite'
import react from '@vitejs/plugin-react'
import { defineConfig } from 'vitest/config'

// During development the backend runs on port 8119 and Vite forwards the API to it,
// so frontend and API share one origin as they do in the built application.
const backend = 'http://localhost:8119'

export default defineConfig({
  plugins: [react(), tailwindcss()],
  resolve: {
    alias: { '@': path.resolve(import.meta.dirname, 'src') },
  },
  build: {
    // Spring Boot serves the frontend from the classpath; Maven packages this directory into the jar.
    outDir: '../target/classes/static',
    emptyOutDir: true,
  },
  server: {
    proxy: {
      '/api': backend,
      '/actuator': backend,
    },
  },
  test: {
    environment: 'jsdom',
    setupFiles: ['src/test/setup.ts'],
    css: false,
  },
})

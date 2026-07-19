import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'

// /api is proxied to the backend so dev and the nginx-served container
// build use the same relative URLs.
export default defineConfig({
  plugins: [react()],
  server: {
    proxy: {
      '/api': 'http://localhost:8080',
    },
  },
})

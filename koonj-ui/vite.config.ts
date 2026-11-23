import { defineConfig } from 'vite'
import tailwindcss from '@tailwindcss/vite'

export default defineConfig({
  server: { proxy: {
    '/api/v1': 'http://127.0.0.1:8080',
    '/swagger-ui': 'http://127.0.0.1:8080',
    '/v3/api-docs': 'http://127.0.0.1:8080',
  } },
  plugins: [tailwindcss()],
})

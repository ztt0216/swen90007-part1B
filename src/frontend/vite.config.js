import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'

// ←←← 这里改成你们“后端在 Render 上的域名”（不带最后的 /api）
const BACKEND = 'https://null-pointers-or0u.onrender.com'

export default defineConfig({
  plugins: [react()],
  server: {
    proxy: {
      '/api': {
        target: BACKEND,
        changeOrigin: true,
        rewrite: (path) => path, // /api 保持 /api
        secure: true,
      }
    }
  }
})

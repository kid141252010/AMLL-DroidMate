import { defineConfig } from 'vite'
import { resolve } from 'node:path'
import react from '@vitejs/plugin-react'
import wasm from 'vite-plugin-wasm'

export default defineConfig({
  plugins: [react(), wasm()],

  base: './',
  define: {
    global: 'globalThis',
    'process.env.NODE_ENV': JSON.stringify('development'),
  },
  build: {
    outDir: 'dist',
    emptyOutDir: true,
    sourcemap: true,
    cssCodeSplit: false,
    minify: false,
    lib: {
      entry: resolve(__dirname, 'src/main.tsx'),
      name: 'AMLLBundle',
      formats: ['iife'],
      fileName: () => 'amll.bundle.js',
    },
  },
  resolve: {
    // 强制去重，确保运行时只有一个 React / React DOM 副本
    dedupe: ['react', 'react-dom', 'jotai'],
  },
  server: {
    port: 5173,
  },
})

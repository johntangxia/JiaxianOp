import { defineConfig } from "vitest/config";
import vue from "@vitejs/plugin-vue";
import AutoImport from "unplugin-auto-import/vite";
import Components from "unplugin-vue-components/vite";
import { ElementPlusResolver } from "unplugin-vue-components/resolvers";

// 开发时把 /api 代理到后端；后端端口通过 VITE_API_TARGET 指定（默认 8080）
const apiTarget = process.env.VITE_API_TARGET ?? "http://localhost:8080";

export default defineConfig({
  plugins: [
    vue(),
    AutoImport({ resolvers: [ElementPlusResolver()], dts: "src/auto-imports.d.ts" }),
    Components({ resolvers: [ElementPlusResolver()], dts: "src/components.d.ts" }),
  ],
  server: {
    port: 5173,
    proxy: {
      "/api": apiTarget,
    },
  },
  build: {
    chunkSizeWarningLimit: 1500,
  },
  test: {
    environment: "node",
    include: ["src/**/*.test.ts"],
  },
});

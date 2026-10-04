# 稼先社区运营分析看板（前端）

Vue 3 + TypeScript + Vite + Element Plus + ECharts。目前包含：总览看板（PV 趋势、环比同比、预测、Top N）和积分排行榜（月榜/年榜）。

## 开发

```bash
cd frontend
npm install
VITE_API_TARGET=http://localhost:8080 npm run dev   # 默认就是 8080，后端换了端口就改这里
```

开发服务器在 http://localhost:5173，`/api` 会被代理到后端。

## 常用命令

```bash
npm test         # Vitest 单元测试（纯逻辑和图表配置）
npm run build    # vue-tsc 类型检查 + 生产构建（输出到 dist/）
```

## 演示数据

看板需要后端有数据。启动后端后依次调用：

```bash
curl -X POST localhost:8080/api/admin/simulator/backfill -H 'Content-Type: application/json' \
  -d '{"batchId":"demo","employeeCount":50,"accountCount":20,"teamAccountRatio":0.25,"contentCount":300,"historyStart":"2024-10-01","historyEnd":"2026-03-01","randomSeed":7,"personas":true}'
curl -X POST localhost:8080/api/admin/analytics/pv/rebuild                 # PV 汇总
curl -X POST localhost:8080/api/admin/analytics/leaderboard/snapshot       # 榜单快照
```

## 目录

```
src/api         后端接口封装（带类型）
src/utils       纯逻辑：格式化、日期范围、指标卡、明细表行
src/charts      ECharts 配置生成函数（纯函数）
src/composables useAsync（加载/错误/过期响应）
src/components  公共组件（图表、异步区块、远程搜索下拉）
src/views       页面
```

`src/components.d.ts` 和 `src/auto-imports.d.ts` 由 `unplugin-*` 自动生成，需要提交到仓库。

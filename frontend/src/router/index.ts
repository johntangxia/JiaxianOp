import { createRouter, createWebHistory } from "vue-router";
import { demoRoutes } from "../demo/routes";

export default createRouter({
  history: createWebHistory(),
  routes: import.meta.env.VITE_STATIC_DEMO === "1" ? demoRoutes : [
    { path: "/visit-flow", name: "visit-flow", component: () => import("../views/VisitFlowView.vue"), meta: { title: "访问流向" } },
    { path: "/", name: "overview", component: () => import("../views/OverviewView.vue"), meta: { title: "总览看板" } },
    {
      path: "/channels",
      name: "channels",
      component: () => import("../views/channels/ChannelCockpitView.vue"),
      meta: { title: "频道驾驶舱" },
    },
    {
      path: "/channels/:id",
      name: "channel-detail",
      component: () => import("../views/channels/ChannelDetailView.vue"),
      meta: { title: "频道详情" },
    },
    {
      path: "/readers",
      name: "readers",
      component: () => import("../views/readers/ReadersView.vue"),
      meta: { title: "读者分析" },
    },
    {
      path: "/creators",
      name: "creators",
      component: () => import("../views/creators/CreatorsView.vue"),
      meta: { title: "创作者分析" },
    },
    {
      path: "/users",
      name: "users",
      component: () => import("../views/users/UsersView.vue"),
      meta: { title: "用户分析" },
    },
    {
      path: "/users/:id",
      name: "user-detail",
      component: () => import("../views/users/UserDetailView.vue"),
      meta: { title: "用户 360" },
    },
    {
      path: "/growth",
      name: "growth",
      component: () => import("../views/growth/GrowthView.vue"),
      meta: { title: "增长闭环" },
    },
    {
      path: "/accounts/:id",
      name: "account-detail",
      component: () => import("../views/drill/AccountView.vue"),
      meta: { title: "账号详情" },
    },
    {
      path: "/bloggers/:id",
      name: "blogger-detail",
      component: () => import("../views/drill/BloggerView.vue"),
      meta: { title: "博主详情（作者视角）" },
    },
    {
      path: "/contents/:id",
      name: "content-detail",
      component: () => import("../views/drill/ContentView.vue"),
      meta: { title: "内容详情" },
    },
    {
      path: "/compare",
      name: "compare",
      component: () => import("../views/drill/CompareView.vue"),
      meta: { title: "对比分析" },
    },
    {
      path: "/contest",
      name: "contest",
      component: () => import("../views/contest/ContestView.vue"),
      meta: { title: "频道竞赛榜" },
    },
    {
      path: "/leaderboard",
      name: "leaderboard",
      component: () => import("../views/LeaderboardView.vue"),
      meta: { title: "积分排行榜" },
    },
    { path: "/kol", name: "kol", component: () => import("../views/kol/KolListView.vue"), meta: { title: "KOL 管理" } },
    {
      path: "/kol/:id",
      name: "kol-detail",
      component: () => import("../views/kol/KolDetailView.vue"),
      meta: { title: "博主详情" },
    },
    {
      path: "/admin/simulator",
      name: "simulator",
      component: () => import("../views/admin/SimulatorView.vue"),
      meta: { title: "数据模拟器" },
    },
    {
      path: "/admin/points-config",
      name: "points-config",
      component: () => import("../views/admin/PointsConfigView.vue"),
      meta: { title: "积分权重" },
    },
    {
      path: "/admin/playbook",
      name: "playbook",
      component: () => import("../views/admin/PlaybookView.vue"),
      meta: { title: "建议文案库" },
    },
    { path: "/:pathMatch(.*)*", redirect: "/" },
  ],
});

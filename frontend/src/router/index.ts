import { createRouter, createWebHistory } from "vue-router";

export default createRouter({
  history: createWebHistory(),
  routes: [
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

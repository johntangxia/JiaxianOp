import { createRouter, createWebHistory } from "vue-router";

export default createRouter({
  history: createWebHistory(),
  routes: [
    {
      path: "/",
      name: "overview",
      component: () => import("../views/OverviewView.vue"),
      meta: { title: "总览看板" },
    },
    {
      path: "/leaderboard",
      name: "leaderboard",
      component: () => import("../views/LeaderboardView.vue"),
      meta: { title: "积分排行榜" },
    },
    { path: "/:pathMatch(.*)*", redirect: "/" },
  ],
});

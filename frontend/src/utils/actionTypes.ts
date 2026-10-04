const ACTION_LABELS: Record<string, string> = {
  POST: "发帖",
  LOGIN: "登录",
  COMMENT: "发表评论",
  SHARE: "分享",
  FAVORITE: "收藏",
  LIKE: "点赞",
  VIEW: "浏览",
  EDITORIAL_PICK: "入选思想简报",
  CONTENT_COMMENTED: "内容获得评论",
  CONTENT_SHARED: "内容被分享",
  COMMENT_REPLIED: "评论获得回复",
  CONTENT_FAVORITED: "内容被收藏",
  CONTENT_LIKED: "内容被点赞",
  COMMENT_LIKED: "评论获赞",
  CONTENT_VIEWED: "内容被浏览",
};

/** 积分动作类型的中文名；不认识的原样返回代码。 */
export function actionLabel(actionType: string): string {
  return ACTION_LABELS[actionType] ?? actionType;
}

export function categoryLabel(category: "ACTIVE" | "PASSIVE"): string {
  return category === "ACTIVE" ? "主动行为" : "被动互动";
}

const EVENT_LABELS: Record<string, string> = { POST: "发帖", COMMENT: "评论", LOGIN: "登录" };

export function eventLabel(type: string): string {
  return EVENT_LABELS[type] ?? type;
}

export function alertStatusLabel(status: "NO_ALERT" | "ALERT"): string {
  return status === "ALERT" ? "触发预警" : "无预警";
}

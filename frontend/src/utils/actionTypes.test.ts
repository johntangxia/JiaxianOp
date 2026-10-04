import { describe, expect, it } from "vitest";
import { actionLabel, alertStatusLabel, categoryLabel, eventLabel } from "./actionTypes";

describe("dictionaries", () => {
  it("labels all fifteen points action types in Chinese", () => {
    const codes = ["POST", "LOGIN", "COMMENT", "SHARE", "FAVORITE", "LIKE", "VIEW", "EDITORIAL_PICK",
      "CONTENT_COMMENTED", "CONTENT_SHARED", "COMMENT_REPLIED", "CONTENT_FAVORITED", "CONTENT_LIKED",
      "COMMENT_LIKED", "CONTENT_VIEWED"];

    for (const code of codes) {
      expect(actionLabel(code)).not.toBe(code);
    }
    expect(actionLabel("POST")).toBe("发帖");
    expect(actionLabel("EDITORIAL_PICK")).toBe("入选思想简报");
  });

  it("falls back to the code for unknown action types", () => {
    expect(actionLabel("SOMETHING_NEW")).toBe("SOMETHING_NEW");
  });

  it("labels categories, event types and alert statuses", () => {
    expect(categoryLabel("ACTIVE")).toBe("主动行为");
    expect(categoryLabel("PASSIVE")).toBe("被动互动");
    expect(eventLabel("LOGIN")).toBe("登录");
    expect(eventLabel("COMMENT")).toBe("评论");
    expect(eventLabel("POST")).toBe("发帖");
    expect(alertStatusLabel("NO_ALERT")).toBe("无预警");
    expect(alertStatusLabel("ALERT")).toBe("触发预警");
  });
});

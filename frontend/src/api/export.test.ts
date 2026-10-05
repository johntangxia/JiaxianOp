import { describe, expect, it } from "vitest";
import { creatorRankingExportUrl, userListExportUrl } from "./people";
import { lurkersExportUrl } from "./readers";

describe("export urls", () => {
  it("builds the user list export url with the active filters and no paging", () => {
    expect(userListExportUrl({ asOf: "2026-09-04", stage: "ACTIVE", value: undefined, dept: "战略", q: "员工", sort: "VIEWS" }))
      .toBe("/api/users/export?asOf=2026-09-04&stage=ACTIVE&dept=%E6%88%98%E7%95%A5&q=%E5%91%98%E5%B7%A5&sort=VIEWS");
  });

  it("skips empty filters", () => {
    expect(userListExportUrl({ asOf: "2026-09-04", sort: "LAST_ACTIVE" })).toBe("/api/users/export?asOf=2026-09-04&sort=LAST_ACTIVE");
  });

  it("builds the creator ranking and lurker export urls", () => {
    expect(creatorRankingExportUrl("2026-03-09", "2026-09-04", "POSTS", undefined))
      .toBe("/api/creators/ranking/export?from=2026-03-09&to=2026-09-04&sort=POSTS");
    expect(lurkersExportUrl("2026-03-09", "2026-09-04", "光"))
      .toBe("/api/readers/lurkers/export?from=2026-03-09&to=2026-09-04&dept=%E5%85%89");
  });
});

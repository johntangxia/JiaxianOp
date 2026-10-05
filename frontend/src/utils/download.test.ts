import { afterEach, describe, expect, it, vi } from "vitest";
import { downloadFile } from "./download";

describe("downloadFile", () => {
  afterEach(() => vi.unstubAllGlobals());

  it("clicks a temporary link to the url and removes it again", () => {
    const link = { href: "", rel: "", click: vi.fn() };
    const body = { appendChild: vi.fn(), removeChild: vi.fn() };
    vi.stubGlobal("document", { createElement: vi.fn(() => link), body });

    downloadFile("/api/users/export?asOf=2026-09-04");

    expect(link.href).toBe("/api/users/export?asOf=2026-09-04");
    expect(link.rel).toBe("noopener");
    expect(body.appendChild).toHaveBeenCalledWith(link);
    expect(link.click).toHaveBeenCalledTimes(1);
    expect(body.removeChild).toHaveBeenCalledWith(link);
  });
});

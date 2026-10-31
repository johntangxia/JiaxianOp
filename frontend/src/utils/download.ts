/** 触发浏览器下载一个地址（后端用 Content-Disposition: attachment 返回，不会离开当前页面）。 */
export function downloadFile(url: string): void {
  if (import.meta.env.VITE_STATIC_DEMO === "1") {
    void import("../demo/export").then(async ({ demoExportCsv }) => {
      const content = await demoExportCsv(url);
      const objectUrl = URL.createObjectURL(new Blob([content], { type: "text/csv;charset=utf-8" }));
      const link = document.createElement("a");
      link.href = objectUrl;
      link.download = "jiaxian-static-demo.csv";
      document.body.appendChild(link);
      link.click();
      link.remove();
      setTimeout(() => URL.revokeObjectURL(objectUrl), 0);
    }).catch((error: unknown) => { window.alert(error instanceof Error ? error.message : String(error)); });
    return;
  }
  const link = document.createElement("a");
  link.href = url;
  link.rel = "noopener";
  document.body.appendChild(link);
  link.click();
  document.body.removeChild(link);
}

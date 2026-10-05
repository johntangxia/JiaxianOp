/** 触发浏览器下载一个地址（后端用 Content-Disposition: attachment 返回，不会离开当前页面）。 */
export function downloadFile(url: string): void {
  const link = document.createElement("a");
  link.href = url;
  link.rel = "noopener";
  document.body.appendChild(link);
  link.click();
  document.body.removeChild(link);
}

export async function fetchHealth(): Promise<string> {
  const response = await fetch("/api/health");
  if (!response.ok) {
    throw new Error(`Health check failed with status ${response.status}`);
  }
  return response.text();
}

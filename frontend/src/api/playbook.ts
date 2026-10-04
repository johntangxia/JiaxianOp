import { getJson, sendJson } from "./http";
import type { PlaybookEntry, PlaybookRequest } from "./types";

export function fetchPlaybook(): Promise<PlaybookEntry[]> {
  return getJson<PlaybookEntry[]>("/api/admin/playbook");
}

export function createPlaybook(request: PlaybookRequest): Promise<PlaybookEntry> {
  return sendJson<PlaybookEntry>("POST", "/api/admin/playbook", { body: request });
}

export function updatePlaybook(id: number, request: PlaybookRequest): Promise<PlaybookEntry> {
  return sendJson<PlaybookEntry>("PUT", `/api/admin/playbook/${id}`, { body: request });
}

export function deletePlaybook(id: number): Promise<void> {
  return sendJson<void>("DELETE", `/api/admin/playbook/${id}`);
}

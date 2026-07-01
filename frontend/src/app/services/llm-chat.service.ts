import { Injectable, inject } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';

export interface ChatTurn {
  streamId: string;
  role: string;
  content: string;
  ts?: number;
  msgId?: string;
  model?: string;
}

export interface GroupInfo {
  name: string;
  consumers: number;
  pending: number;
  lag?: number;
  lastDeliveredId?: string;
}

export interface GroupsInfo {
  stream: string;
  length: number;
  tokenStream: string;
  tokenStreamLength: number;
  groups: GroupInfo[];
}

export interface MessagePosted {
  cid: string;
  msgId: string;
  streamId: string;
}

/**
 * HTTP client for the LLM Chat pattern (#12) backend (`/api/llm-chat`).
 */
@Injectable({
  providedIn: 'root'
})
export class LlmChatService {
  private http = inject(HttpClient);
  private baseUrl = 'http://localhost:8080/api/llm-chat';

  postMessage(cid: string, content: string): Observable<MessagePosted> {
    return this.http.post<MessagePosted>(`${this.baseUrl}/${cid}/message`, { content });
  }

  history(cid: string): Observable<ChatTurn[]> {
    return this.http.get<ChatTurn[]>(`${this.baseUrl}/${cid}/history`);
  }

  groups(cid: string): Observable<GroupsInfo> {
    return this.http.get<GroupsInfo>(`${this.baseUrl}/${cid}/groups`);
  }

  reset(cid: string): Observable<void> {
    return this.http.post<void>(`${this.baseUrl}/${cid}/reset`, {});
  }
}

import {
  Component, OnDestroy, OnInit, signal, computed, effect, inject,
  ElementRef, ViewChild, AfterViewChecked
} from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { Subscription } from 'rxjs';
import { WebSocketService } from '../../services/websocket.service';
import { ChatTurn, GroupsInfo, LlmChatService } from '../../services/llm-chat.service';
import { MermaidDiagramComponent } from '../mermaid-diagram/mermaid-diagram.component';
import { DiagramDefinitionsService } from '../../services/diagram-definitions.service';

interface ChatMessage {
  role: 'user' | 'assistant';
  content: string;
  complete: boolean;
  msgId?: string;
  streamId?: string;
}

/** Shape of the WebSocket events the backend broadcasts for pattern #12. */
interface LlmChatWsEvent {
  eventType: 'TOKEN' | 'ASSISTANT_MESSAGE' | 'USER_MESSAGE' | 'CONVERSATION_RESET';
  conversationId?: string;
  msgId?: string;
  value?: string;
  streamId?: string;
}

/**
 * LLM Chat (pattern #12) page.
 *
 * <p>The conversation stream (`chat:{cid}`) is the source of truth: it's loaded/polled via REST so
 * the chat always reflects Redis even if the WebSocket hiccups. The WebSocket adds the live
 * token-by-token animation on top. Events are broadcast filtered by our (random, unguessable) cid.
 */
@Component({
  selector: 'app-llm-chat',
  standalone: true,
  imports: [CommonModule, FormsModule, MermaidDiagramComponent],
  templateUrl: './llm-chat.component.html',
  styleUrls: ['./llm-chat.component.scss']
})
export class LlmChatComponent implements OnInit, OnDestroy, AfterViewChecked {
  private ws = inject(WebSocketService);
  private api = inject(LlmChatService);
  readonly diagrams = inject(DiagramDefinitionsService);

  @ViewChild('messagesEl') private messagesEl?: ElementRef<HTMLDivElement>;

  /**
   * Conversation identity, keyed as {@code companyId:userId} (colon-separated) so the Redis stream
   * reads like a real multi-tenant key: {@code chat:acme-corp:u-<random>}. The userId keeps a random
   * suffix so the cid also acts as an unguessable capability (no auth — ADR-0008): the server only
   * delivers a conversation's events to sessions that subscribed with its exact cid.
   */
  readonly companyId = 'acme-corp';
  readonly userId = 'u-' + crypto.randomUUID().slice(0, 8);
  readonly cid = `${this.companyId}:${this.userId}`;

  /** Source of truth: completed turns from Redis (via REST). */
  private readonly historyTurns = signal<ChatTurn[]>([]);
  /** In-flight assistant reply being streamed over WebSocket, overlaid until history catches up. */
  private readonly live = signal<{ msgId: string; content: string } | null>(null);
  /** Optimistically shown user message until the next history poll includes it. */
  private readonly pendingUser = signal<string | null>(null);

  readonly connected = signal(false);
  readonly showInternals = signal(true);
  readonly groups = signal<GroupsInfo | null>(null);
  draft = '';

  /** Rendered chat = history turns + optimistic user + live streaming bubble (deduped). */
  readonly messages = computed<ChatMessage[]>(() => {
    const turns = this.historyTurns();
    const out: ChatMessage[] = turns.map(t => ({
      role: t.role === 'assistant' ? 'assistant' : 'user',
      content: t.content ?? '',
      complete: true,
      msgId: t.msgId,
      streamId: t.streamId
    }));
    const pu = this.pendingUser();
    if (pu && !turns.some(t => t.role === 'user' && t.content === pu)) {
      out.push({ role: 'user', content: pu, complete: true });
    }
    const lt = this.live();
    if (lt && !turns.some(t => t.msgId === lt.msgId)) {
      out.push({ role: 'assistant', content: lt.content, complete: false, msgId: lt.msgId });
    }
    return out;
  });

  /** Raw conversation-stream entries (with their Redis ids) for the internals panel. */
  readonly streamEntries = computed(() => this.historyTurns());

  private subs: Subscription[] = [];
  private pollTimer?: ReturnType<typeof setInterval>;
  private shouldScroll = false;

  constructor() {
    // Auto-scroll the transcript to the bottom whenever the message list changes.
    effect(() => {
      this.messages();
      this.shouldScroll = true;
    });
  }

  ngOnInit(): void {
    this.ws.connect();
    this.subs.push(
      this.ws.getEvents().subscribe(e => this.handleEvent(e as unknown as LlmChatWsEvent)),
      this.ws.getConnectionStatus().subscribe(status => {
        this.connected.set(status);
        // (Re)subscribe on every (re)connection so the server delivers only this cid's events.
        if (status) {
          this.ws.send({ type: 'subscribe', cid: this.cid });
        }
      })
    );
    this.refresh();
    // REST is the safety net: keep the transcript + internals in sync even if the socket drops.
    this.pollTimer = setInterval(() => this.refresh(), 1500);
  }

  ngAfterViewChecked(): void {
    if (this.shouldScroll && this.messagesEl) {
      this.messagesEl.nativeElement.scrollTop = this.messagesEl.nativeElement.scrollHeight;
      this.shouldScroll = false;
    }
  }

  ngOnDestroy(): void {
    this.subs.forEach(s => s.unsubscribe());
    if (this.pollTimer) {
      clearInterval(this.pollTimer);
    }
  }

  handleEvent(event: LlmChatWsEvent): void {
    if (event.conversationId !== this.cid) {
      return;
    }
    switch (event.eventType) {
      case 'TOKEN': {
        const lt = this.live();
        const value = event.value ?? '';
        this.live.set(lt && lt.msgId === event.msgId
          ? { msgId: lt.msgId, content: lt.content + value }
          : { msgId: event.msgId ?? '', content: value });
        break;
      }
      case 'ASSISTANT_MESSAGE':
        this.live.set({ msgId: event.msgId ?? '', content: event.value ?? '' });
        this.refresh(); // pull the now-complete assistant turn into history
        break;
      case 'CONVERSATION_RESET':
        this.clearView();
        break;
      default:
        break;
    }
  }

  send(): void {
    const content = this.draft.trim();
    if (!content) {
      return;
    }
    this.pendingUser.set(content);
    this.draft = '';
    this.api.postMessage(this.cid, content).subscribe({
      next: () => setTimeout(() => this.refresh(), 300),
      error: err => console.error('postMessage failed', err)
    });
  }

  reset(): void {
    this.api.reset(this.cid).subscribe({
      next: () => this.clearView(),
      error: err => console.error('reset failed', err)
    });
  }

  killWorker(): void {
    this.api.killWorker(this.cid).subscribe({
      next: () => this.refresh(),
      error: err => console.error('kill-worker failed', err)
    });
  }

  refresh(): void {
    this.loadHistory();
    this.refreshGroups();
  }

  toggleInternals(): void {
    this.showInternals.update(v => !v);
  }

  private loadHistory(): void {
    this.api.history(this.cid).subscribe({
      next: turns => {
        this.historyTurns.set(turns);
        const pu = this.pendingUser();
        if (pu && turns.some(t => t.role === 'user' && t.content === pu)) {
          this.pendingUser.set(null);
        }
        const lt = this.live();
        if (lt && turns.some(t => t.msgId === lt.msgId)) {
          this.live.set(null);
        }
      },
      error: () => { /* transient; next poll retries */ }
    });
  }

  private refreshGroups(): void {
    this.api.groups(this.cid).subscribe({
      next: g => this.groups.set(g),
      error: () => this.groups.set(null)
    });
  }

  private clearView(): void {
    this.historyTurns.set([]);
    this.live.set(null);
    this.pendingUser.set(null);
    this.refreshGroups();
  }
}

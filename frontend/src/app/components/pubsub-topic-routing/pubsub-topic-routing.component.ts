import { Component, OnInit, OnDestroy, signal, inject, ChangeDetectionStrategy, ChangeDetectorRef } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { HttpClient } from '@angular/common/http';
import { WebSocketService } from '../../services/websocket.service';
import { Subscription } from 'rxjs';

interface PatternSubscriber {
  name: string;
  pattern: string;
  messages: ReceivedMessage[];
}

interface ReceivedMessage {
  channel: string;
  payload: Record<string, string>;
  timestamp: string;
  subscriber: string;
  pattern: string;
}

@Component({
  selector: 'app-pubsub-topic-routing',
  standalone: true,
  imports: [CommonModule, FormsModule],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './pubsub-topic-routing.component.html',
  styleUrl: './pubsub-topic-routing.component.scss'
})
export class PubsubTopicRoutingComponent implements OnInit, OnDestroy {
  private http = inject(HttpClient);
  private wsService = inject(WebSocketService);
  private cdr = inject(ChangeDetectorRef);
  private apiUrl = 'http://localhost:8080/api/pubsub-topic-routing';
  private subscription?: Subscription;
  private wsStatusSub?: Subscription;

  selectedRoutingKey = 'order.eu.created';
  fields = signal([
    { key: 'orderId', value: '12345' },
    { key: 'amount', value: '99.99' }
  ]);
  isPublishing = signal(false);
  publishMessage$ = signal('');
  isSuccess = signal(false);
  wsConnected = signal(false);

  subscribers = signal<PatternSubscriber[]>([
    { name: 'EU Compliance', pattern: 'order.eu.*', messages: [] },
    { name: 'Order Audit', pattern: 'order.*.created', messages: [] },
    { name: 'US Orders', pattern: 'order.us.*', messages: [] }
  ]);

  ngOnInit(): void {
    this.wsService.connect();
    this.wsStatusSub = this.wsService.getConnectionStatus().subscribe(status => {
      this.wsConnected.set(status);
      this.cdr.markForCheck();
    });
    this.subscription = this.wsService.getEvents().subscribe((event: any) => {
      if (event.eventType === 'MESSAGE_RECEIVED' && event.payload?._subscriber) {
        const subscriberName = event.payload._subscriber;
        this.subscribers.update(subs => subs.map(sub => {
          if (sub.name === subscriberName) {
            const newMsg: ReceivedMessage = {
              channel: event.channel,
              payload: event.payload,
              timestamp: event.timestamp,
              subscriber: subscriberName,
              pattern: event.payload._pattern
            };
            return { ...sub, messages: [newMsg, ...sub.messages].slice(0, 20) };
          }
          return sub;
        }));
        this.cdr.markForCheck();
      }
    });
  }

  ngOnDestroy(): void {
    this.subscription?.unsubscribe();
    this.wsStatusSub?.unsubscribe();
  }

  addField(): void {
    this.fields.update(f => [...f, { key: '', value: '' }]);
  }

  removeField(index: number): void {
    this.fields.update(f => f.filter((_, i) => i !== index));
  }

  publishMessage(): void {
    this.isPublishing.set(true);
    this.publishMessage$.set('');
    const payload: Record<string, string> = {};
    this.fields().forEach(f => { if (f.key && f.value) payload[f.key] = f.value; });

    this.http.post<any>(`${this.apiUrl}/publish`, {
      routingKey: this.selectedRoutingKey,
      payload
    }).subscribe({
      next: (res) => {
        this.isSuccess.set(true);
        this.publishMessage$.set(`✅ Routed to ${res.subscriberCount} pattern subscribers`);
        this.isPublishing.set(false);
        this.incrementOrderId();
        this.cdr.markForCheck();
        setTimeout(() => { this.publishMessage$.set(''); this.cdr.markForCheck(); }, 3000);
      },
      error: (err) => {
        this.isSuccess.set(false);
        this.publishMessage$.set(`❌ Error: ${err.error?.error || err.message}`);
        this.isPublishing.set(false);
        this.cdr.markForCheck();
      }
    });
  }

  private incrementOrderId(): void {
    this.fields.update(f => f.map(field => {
      if (field.key === 'orderId') {
        const id = parseInt(field.value, 10);
        if (!isNaN(id)) return { ...field, value: (id + 1).toString() };
      }
      return field;
    }));
  }

  getPayloadFields(payload: Record<string, string>): Array<{key: string, value: string}> {
    return Object.entries(payload)
      .filter(([k]) => !k.startsWith('_'))
      .map(([key, value]) => ({ key, value }));
  }

  formatTime(timestamp: string): string {
    return new Date(timestamp).toLocaleTimeString();
  }
}


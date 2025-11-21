import { Component, Input, OnInit, OnDestroy, inject, ChangeDetectorRef } from '@angular/core';
import { CommonModule } from '@angular/common';
import { WebSocketService, DLQEvent } from '../../services/websocket.service';
import { RedisApiService } from '../../services/redis-api.service';
import { Subscription } from 'rxjs';

export interface StreamMessage {
  id: string;
  fields: Record<string, string>;
  timestamp: string;
}

/**
 * Reusable component to display Redis Stream messages with real-time updates via WebSocket.
 * 
 * Features:
 * - Displays messages in reverse chronological order (oldest unread at bottom)
 * - Real-time updates via WebSocket
 * - Pagination support
 * - Shows stream name header
 * - "More messages..." indicator when applicable
 */
@Component({
  selector: 'app-stream-viewer',
  standalone: true,
  imports: [CommonModule],
  template: `
    <div class="stream-viewer">
      <div class="stream-header">
        <h3 class="stream-name">{{ stream }}</h3>
        <div class="connection-status" [class.connected]="isConnected" [class.disconnected]="!isConnected">
          <span class="status-dot"></span>
          <span class="status-text">{{ isConnected ? 'Connected' : 'Disconnected' }}</span>
        </div>
      </div>

      <div class="message-table-container">
        <table class="message-table">
          <thead>
            <tr>
              <th>Message ID</th>
              <th>Content</th>
            </tr>
          </thead>
          <tbody>
            <!-- "More messages..." indicator at top -->
            <tr *ngIf="hasMoreMessages" class="more-messages-row">
              <td colspan="2" class="more-messages-cell">
                <span class="more-messages-text">... {{ totalMessages - pageSize }} more messages ...</span>
              </td>
            </tr>

            <!-- Messages in reverse chronological order (newest first, oldest at bottom) -->
            <tr *ngFor="let message of displayedMessages" class="message-row">
              <td class="message-id">{{ message.id }}</td>
              <td class="message-content">
                <div class="message-fields">
                  <div *ngFor="let field of getFields(message.fields)" class="field-item">
                    <span class="field-key">{{ field.key }}:</span>
                    <span class="field-value">{{ field.value }}</span>
                  </div>
                </div>
              </td>
            </tr>

            <!-- Empty state -->
            <tr *ngIf="displayedMessages.length === 0 && !isLoading" class="empty-row">
              <td colspan="2" class="empty-cell">
                <span class="empty-text">No messages in stream</span>
              </td>
            </tr>

            <!-- Loading state -->
            <tr *ngIf="isLoading" class="loading-row">
              <td colspan="2" class="loading-cell">
                <span class="loading-text">Loading messages...</span>
              </td>
            </tr>
          </tbody>
        </table>
      </div>

      <div class="stream-footer">
        <span class="message-count">{{ displayedMessages.length }} of {{ totalMessages }} messages</span>
      </div>
    </div>
  `,
  styles: [`
    .stream-viewer {
      background: white;
      border-radius: 8px;
      border: 1px solid #e2e8f0;
      overflow: hidden;
    }

    .stream-header {
      padding: 16px 20px;
      background: linear-gradient(135deg, #f8fafc 0%, #f1f5f9 100%);
      border-bottom: 1px solid #e2e8f0;
      display: flex;
      justify-content: space-between;
      align-items: center;
    }

    .stream-name {
      margin: 0;
      font-size: 16px;
      font-weight: 600;
      color: #1e293b;
    }

    .connection-status {
      display: flex;
      align-items: center;
      gap: 6px;
      font-size: 12px;
      padding: 4px 10px;
      border-radius: 12px;
      background: #f1f5f9;
    }

    .connection-status.connected {
      background: #dcfce7;
      color: #166534;
    }

    .connection-status.disconnected {
      background: #fee2e2;
      color: #991b1b;
    }

    .status-dot {
      width: 6px;
      height: 6px;
      border-radius: 50%;
      background: currentColor;
    }

    .status-text {
      font-weight: 500;
    }

    .message-table-container {
      max-height: 500px;
      overflow-y: auto;
    }

    .message-table {
      width: 100%;
      border-collapse: collapse;
    }

    .message-table thead {
      position: sticky;
      top: 0;
      background: #f8fafc;
      z-index: 10;
    }

    .message-table th {
      padding: 12px 16px;
      text-align: left;
      font-size: 12px;
      font-weight: 600;
      color: #64748b;
      text-transform: uppercase;
      letter-spacing: 0.05em;
      border-bottom: 2px solid #e2e8f0;
    }

    .message-table th:first-child {
      width: 200px;
    }

    .message-row {
      border-bottom: 1px solid #f1f5f9;
      transition: background-color 0.15s ease;
    }

    .message-row:hover {
      background-color: #f8fafc;
    }

    .message-row td {
      padding: 12px 16px;
      vertical-align: top;
    }

    .message-id {
      font-family: 'Courier New', monospace;
      font-size: 11px;
      color: #475569;
      word-break: break-all;
    }

    .message-content {
      font-size: 13px;
    }

    .message-fields {
      display: flex;
      flex-direction: column;
      gap: 6px;
    }

    .field-item {
      display: flex;
      gap: 8px;
    }

    .field-key {
      font-weight: 600;
      color: #475569;
      min-width: 80px;
    }

    .field-value {
      color: #1e293b;
      word-break: break-word;
    }

    .more-messages-row {
      background: #fef3c7;
    }

    .more-messages-cell {
      padding: 12px 16px;
      text-align: center;
      font-style: italic;
      color: #92400e;
      border-bottom: 1px solid #fde68a;
    }

    .more-messages-text {
      font-size: 13px;
    }

    .empty-row, .loading-row {
      height: 200px;
    }

    .empty-cell, .loading-cell {
      text-align: center;
      color: #94a3b8;
      font-size: 14px;
      padding: 40px;
    }

    .stream-footer {
      padding: 12px 20px;
      background: #f8fafc;
      border-top: 1px solid #e2e8f0;
      display: flex;
      justify-content: space-between;
      align-items: center;
    }

    .message-count {
      font-size: 12px;
      color: #64748b;
      font-weight: 500;
    }

    @media (max-width: 768px) {
      .message-table th:first-child {
        width: 120px;
      }

      .field-item {
        flex-direction: column;
        gap: 2px;
      }

      .field-key {
        min-width: auto;
      }
    }
  `]
})
export class StreamViewerComponent implements OnInit, OnDestroy {
  @Input() stream = '';
  @Input() group = '';
  @Input() consumer = '';
  @Input() pageSize = 10;

  private wsService = inject(WebSocketService);
  private apiService = inject(RedisApiService);
  private cdr = inject(ChangeDetectorRef);
  private eventSubscription?: Subscription;
  private statusSubscription?: Subscription;

  displayedMessages: StreamMessage[] = [];
  totalMessages = 0;
  hasMoreMessages = false;
  isConnected = false;
  isLoading = true;

  ngOnInit(): void {
    // Only connect if stream name is provided
    if (this.stream && this.group && this.consumer) {
      this.loadInitialData();
      this.connectWebSocket();
    } else {
      console.warn('StreamViewerComponent: Missing required parameters (stream, group, consumer)');
      this.isLoading = false;
    }
  }

  ngOnDestroy(): void {
    this.eventSubscription?.unsubscribe();
    this.statusSubscription?.unsubscribe();
  }

  private loadInitialData(): void {
    this.isLoading = true;

    console.log(`StreamViewer [${this.stream}]: Loading initial data (pageSize: ${this.pageSize})`);

    // Load existing messages from the stream
    this.apiService.getMessages(this.stream, this.pageSize).subscribe({
      next: (response) => {
        console.log(`StreamViewer [${this.stream}]: Received response:`, response);

        if (response.success && response.messages) {
          // Convert API response to StreamMessage format
          this.displayedMessages = response.messages.map(msg => ({
            id: msg.id,
            fields: msg.fields,
            timestamp: new Date().toISOString() // Timestamp not provided by API
          }));

          this.totalMessages = response.count;
          this.hasMoreMessages = false; // We don't know total count yet

          console.log(`StreamViewer [${this.stream}]: Loaded ${this.displayedMessages.length} messages`, this.displayedMessages);
        } else {
          console.warn(`StreamViewer [${this.stream}]: Response not successful or no messages`, response);
        }
        this.isLoading = false;
        this.cdr.detectChanges(); // Force change detection
      },
      error: (error) => {
        console.error(`StreamViewer [${this.stream}]: Failed to load messages`, error);
        console.error(`StreamViewer [${this.stream}]: Error details:`, {
          status: error.status,
          statusText: error.statusText,
          message: error.message,
          url: error.url
        });
        this.isLoading = false;
      }
    });
  }

  private connectWebSocket(): void {
    // Connect to WebSocket (or get existing connection)
    this.wsService.connect();

    // Subscribe to connection status
    this.statusSubscription = this.wsService.getConnectionStatus().subscribe(
      status => {
        console.log(`StreamViewer [${this.stream}]: Connection status changed to ${status}`);
        this.isConnected = status;
        this.cdr.detectChanges(); // Force change detection
      }
    );

    // Subscribe to events
    this.eventSubscription = this.wsService.getEvents().subscribe(
      event => this.handleWebSocketEvent(event)
    );

    // Check if already connected
    if (this.wsService.isConnected()) {
      this.isConnected = true;
    }
  }

  private handleWebSocketEvent(event: DLQEvent): void {
    console.log(`StreamViewer [${this.stream}]: Received WebSocket event:`, event);

    // Filter events for this stream
    if (event.streamName !== this.stream) {
      console.log(`StreamViewer [${this.stream}]: Event ignored (different stream: ${event.streamName})`);
      return;
    }

    // Handle message deletion
    if (event.eventType === 'MESSAGE_DELETED' && event.messageId) {
      console.log(`StreamViewer [${this.stream}]: Deleting message:`, event.messageId);

      const initialLength = this.displayedMessages.length;
      this.displayedMessages = this.displayedMessages.filter(msg => msg.id !== event.messageId);

      if (this.displayedMessages.length < initialLength) {
        this.totalMessages--;
        console.log(`StreamViewer [${this.stream}]: Message deleted. New count: ${this.totalMessages}`);
      }

      this.cdr.detectChanges();
      return;
    }

    // Add new message to the list
    if (event.messageId && event.payload) {
      const newMessage: StreamMessage = {
        id: event.messageId,
        fields: event.payload,
        timestamp: event.timestamp || new Date().toISOString()
      };

      console.log(`StreamViewer [${this.stream}]: Adding new message:`, newMessage);

      // Add to beginning (newest first)
      this.displayedMessages.unshift(newMessage);
      this.totalMessages++;

      // Keep only pageSize messages
      if (this.displayedMessages.length > this.pageSize) {
        this.displayedMessages = this.displayedMessages.slice(0, this.pageSize);
        this.hasMoreMessages = true;
      }

      this.cdr.detectChanges(); // Force change detection
    }
  }

  getFields(fields: Record<string, string>): {key: string; value: string}[] {
    return Object.entries(fields).map(([key, value]) => ({ key, value }));
  }
}


import { Injectable } from '@angular/core';
import { Subject, Observable } from 'rxjs';
import SockJS from 'sockjs-client';

export interface StreamMessage {
  id: string;
  fields: Record<string, string>;
  timestamp?: string;
}

export interface DLQEvent {
  eventType: string;
  messageId?: string;
  payload?: Record<string, string>;
  streamName?: string;
  consumer?: string;
  details?: string;
  timestamp?: string;
}

/**
 * WebSocket service for real-time communication with Spring Boot backend.
 * Uses SockJS for WebSocket connection with fallback support.
 */
@Injectable({
  providedIn: 'root'
})
export class WebSocketService {
  private socket: any = null; // SockJS doesn't implement WebSocket interface fully
  private eventSubject = new Subject<DLQEvent>();
  private connectionStatus = new Subject<boolean>();
  private reconnectAttempts = 0;
  private maxReconnectAttempts = 5;
  private reconnectDelay = 3000;
  private isConnecting = false;
  private connected = false; // Track connection state manually

  /**
   * Connect to the WebSocket endpoint
   * @param endpoint WebSocket endpoint path (default: '/ws/dlq-events')
   */
  connect(endpoint = '/ws/dlq-events'): void {
    if (this.connected && this.socket) {
      console.log('WebSocket already connected');
      // Emit current connection status for new subscribers
      this.connectionStatus.next(true);
      return;
    }

    if (this.isConnecting) {
      console.log('WebSocket connection in progress');
      return;
    }

    this.isConnecting = true;
    // Spring Boot context path is /api, so WebSocket endpoint is /api/ws/dlq-events
    const url = `http://localhost:8080/api${endpoint}`;

    try {
      // Use SockJS for better compatibility
      this.socket = new SockJS(url);

      this.socket.onopen = () => {
        console.log('WebSocket connection established');
        this.isConnecting = false;
        this.connected = true;
        this.reconnectAttempts = 0;
        this.connectionStatus.next(true);
      };

      this.socket.onmessage = (event: MessageEvent) => {
        try {
          const data = JSON.parse(event.data);
          this.eventSubject.next(data);
        } catch (error) {
          console.error('Failed to parse WebSocket message:', error);
        }
      };

      this.socket.onerror = (error: Event) => {
        console.error('WebSocket error:', error);
        this.isConnecting = false;
        this.connected = false;
        this.connectionStatus.next(false);
      };

      this.socket.onclose = () => {
        console.log('WebSocket connection closed');
        this.isConnecting = false;
        this.connected = false;
        this.connectionStatus.next(false);
        this.attemptReconnect(endpoint);
      };
    } catch (error) {
      console.error('Failed to create WebSocket connection:', error);
      this.isConnecting = false;
      this.connected = false;
      this.connectionStatus.next(false);
    }
  }

  /**
   * Attempt to reconnect to the WebSocket
   */
  private attemptReconnect(endpoint: string): void {
    if (this.reconnectAttempts < this.maxReconnectAttempts) {
      this.reconnectAttempts++;
      console.log(`Attempting to reconnect (${this.reconnectAttempts}/${this.maxReconnectAttempts})...`);
      
      setTimeout(() => {
        this.connect(endpoint);
      }, this.reconnectDelay);
    } else {
      console.error('Max reconnection attempts reached');
    }
  }

  /**
   * Disconnect from the WebSocket
   */
  disconnect(): void {
    if (this.socket) {
      this.socket.close();
      this.socket = null;
    }
  }

  /**
   * Get observable for WebSocket events
   */
  getEvents(): Observable<DLQEvent> {
    return this.eventSubject.asObservable();
  }

  /**
   * Get observable for connection status
   */
  getConnectionStatus(): Observable<boolean> {
    return this.connectionStatus.asObservable();
  }

  /**
   * Check if WebSocket is currently connected
   */
  isConnected(): boolean {
    return this.connected;
  }
}


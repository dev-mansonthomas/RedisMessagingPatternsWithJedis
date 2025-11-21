import { Component } from '@angular/core';
import { CommonModule } from '@angular/common';

@Component({
  selector: 'app-request-reply',
  standalone: true,
  imports: [CommonModule],
  template: `
    <div class="request-reply-container">
      <div class="page-header">
        <h1 class="page-title">Request/Reply Pattern</h1>
        <p class="page-description">
          Synchronous communication pattern where a client sends a request and waits for a response.
        </p>
      </div>

      <div class="content-grid">
        <div class="card">
          <div class="card-header">
            <h2 class="card-title">Send Request</h2>
          </div>
          <div class="card-content">
            <p>Request/Reply component content will be implemented here.</p>
          </div>
        </div>

        <div class="card">
          <div class="card-header">
            <h2 class="card-title">Response</h2>
          </div>
          <div class="card-content">
            <p>Response handling will be displayed here.</p>
          </div>
        </div>
      </div>
    </div>
  `,
  styles: [`
    .request-reply-container {
      max-width: 1400px;
      margin: 0 auto;
    }

    .page-header {
      margin-bottom: 32px;
    }

    .page-title {
      font-size: 32px;
      font-weight: 700;
      color: #1e293b;
      margin-bottom: 8px;
    }

    .page-description {
      font-size: 16px;
      color: #64748b;
      margin: 0;
    }

    .content-grid {
      display: grid;
      grid-template-columns: repeat(auto-fit, minmax(400px, 1fr));
      gap: 24px;
    }

    .card {
      background: white;
      border-radius: 12px;
      box-shadow: 0 1px 3px rgba(0, 0, 0, 0.1);
      border: 1px solid #e2e8f0;
      overflow: hidden;
      transition: box-shadow 0.2s ease;
    }

    .card:hover {
      box-shadow: 0 4px 6px rgba(0, 0, 0, 0.1);
    }

    .card-header {
      padding: 20px 24px 16px;
      border-bottom: 1px solid #e2e8f0;
      background: linear-gradient(135deg, #f8fafc 0%, #f1f5f9 100%);
    }

    .card-title {
      font-size: 18px;
      font-weight: 600;
      color: #1e293b;
      margin: 0;
    }

    .card-content {
      padding: 24px;
    }

    @media (max-width: 768px) {
      .content-grid {
        grid-template-columns: 1fr;
        gap: 16px;
      }
      
      .page-title {
        font-size: 24px;
      }
    }
  `]
})
export class RequestReplyComponent {}


import { Component } from '@angular/core';
import { CommonModule } from '@angular/common';
import { StreamViewerComponent } from '../stream-viewer/stream-viewer.component';

@Component({
  selector: 'app-dlq',
  standalone: true,
  imports: [CommonModule, StreamViewerComponent],
  template: `
    <div class="dlq-container">
      <div class="page-header">
        <h1 class="page-title">Dead Letter Queue (DLQ)</h1>
        <p class="page-description">
          Demonstrate Redis DLQ messaging patterns with real-time monitoring and testing capabilities.
        </p>
      </div>

      <div class="content-grid">
        <div class="card">
          <div class="card-header">
            <h2 class="card-title">DLQ Configuration</h2>
          </div>
          <div class="card-content">
            <p>Configure DLQ parameters and settings here.</p>
            <!-- DLQ configuration form will be added here -->
          </div>
        </div>

        <div class="card full-width">
          <div class="card-header">
            <h2 class="card-title">Main Stream - Real-time Messages</h2>
          </div>
          <div class="card-content">
            <app-stream-viewer
              stream="test-stream"
              group="test-group"
              consumer="consumer-1"
              [pageSize]="10">
            </app-stream-viewer>
          </div>
        </div>

        <div class="card full-width">
          <div class="card-header">
            <h2 class="card-title">DLQ Stream - Failed Messages</h2>
          </div>
          <div class="card-content">
            <app-stream-viewer
              stream="test-stream:dlq"
              group="dlq-group"
              consumer="dlq-consumer"
              [pageSize]="10">
            </app-stream-viewer>
          </div>
        </div>

        <div class="card">
          <div class="card-header">
            <h2 class="card-title">Statistics</h2>
          </div>
          <div class="card-content">
            <p>View DLQ statistics and metrics.</p>
            <!-- Statistics panel will be added here -->
          </div>
        </div>

        <div class="card">
          <div class="card-header">
            <h2 class="card-title">Test Controls</h2>
          </div>
          <div class="card-content">
            <p>Start and control DLQ test scenarios.</p>
            <!-- Test controls will be added here -->
          </div>
        </div>
      </div>
    </div>
  `,
  styles: [`
    .dlq-container {
      max-width: 1200px;
      margin: 0 auto;
    }

    .page-header {
      margin-bottom: 32px;
    }

    .page-title {
      font-size: 28px;
      font-weight: 700;
      color: #1e293b;
      margin: 0 0 8px 0;
    }

    .page-description {
      font-size: 16px;
      color: #64748b;
      margin: 0;
      line-height: 1.6;
    }

    .content-grid {
      display: grid;
      grid-template-columns: repeat(auto-fit, minmax(300px, 1fr));
      gap: 24px;
    }

    .card.full-width {
      grid-column: 1 / -1;
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
export class DlqComponent {}
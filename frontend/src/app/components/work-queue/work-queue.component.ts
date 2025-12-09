import { Component, OnInit, OnDestroy, inject, ChangeDetectionStrategy, ChangeDetectorRef } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { HttpClient } from '@angular/common/http';
import { StreamViewerComponent } from '../stream-viewer/stream-viewer.component';
import { StreamRefreshService } from '../../services/stream-refresh.service';

interface SleepOption {
  label: string;
  value: number;
}

/**
 * Work Queue / Competing Consumers pattern demonstration.
 * 
 * Features:
 * - Start/Stop job production buttons
 * - Configurable sleep interval between jobs
 * - 4 worker-done stream viewers
 * - DLQ stream viewer
 */
@Component({
  selector: 'app-work-queue',
  standalone: true,
  imports: [CommonModule, FormsModule, StreamViewerComponent],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="work-queue-container">
      <div class="page-header">
        <h2>Work Queue / Competing Consumers</h2>
        <p class="description">
          Fire &amp; Forget pattern: 4 workers process jobs in parallel. 
          1 in 10 jobs fails and goes to DLQ after 2 retries.
        </p>
      </div>

      <!-- Controls Section -->
      <div class="controls-section">
        <div class="controls-row">
          <button
            class="btn btn-start"
            [disabled]="isProducing"
            (click)="startProducing()">
            ▶ Start Producing Jobs
          </button>

          <button
            class="btn btn-stop"
            [disabled]="!isProducing"
            (click)="stopProducing()">
            ⏹ Stop Producing Jobs
          </button>

          <button
            class="btn btn-clear"
            [disabled]="isProducing"
            (click)="clearAllStreams()">
            🗑 Clear All
          </button>

          <div class="sleep-selector">
            <label>Sleep between jobs:</label>
            <select [(ngModel)]="selectedSleep" [disabled]="isProducing">
              <option *ngFor="let opt of sleepOptions" [ngValue]="opt.value">
                {{ opt.label }}
              </option>
            </select>
          </div>

          <div class="job-counter" *ngIf="jobsProduced > 0">
            Jobs produced: <strong>{{ jobsProduced }}</strong>
          </div>
        </div>
      </div>

      <!-- Job Stream (input) -->
      <div class="stream-section">
        <h3>📥 Job Stream (Input)</h3>
        <div class="stream-row single">
          <app-stream-viewer
            [stream]="'jobs.imageProcessing.v1'"
            [group]="'jobs-group'"
            [consumer]="'viewer'"
            [pageSize]="10">
          </app-stream-viewer>
        </div>
      </div>

      <!-- Workers Done Streams -->
      <div class="stream-section">
        <h3>✅ Workers Done Streams</h3>
        <div class="stream-row workers">
          <app-stream-viewer
            *ngFor="let w of [1,2,3,4]"
            [stream]="'jobs.done.worker-' + w"
            [group]="'jobs-group'"
            [consumer]="'viewer'"
            [pageSize]="10">
          </app-stream-viewer>
        </div>
      </div>

      <!-- DLQ Stream -->
      <div class="stream-section">
        <h3>❌ Dead Letter Queue</h3>
        <div class="stream-row single">
          <app-stream-viewer
            [stream]="'jobs.imageProcessing.v1:dlq'"
            [group]="'jobs-group'"
            [consumer]="'viewer'"
            [pageSize]="10">
          </app-stream-viewer>
        </div>
      </div>

      <!-- How it Works Section -->
      <div class="info-box">
        <div class="info-header">
          <span class="info-icon">ℹ️</span>
          <h3>How Work Queue / Competing Consumers Works</h3>
        </div>
        <div class="info-content">
          <div class="info-section">
            <h4>📤 Job Production</h4>
            <ol>
              <li><strong>Jobs are generated</strong> in the Angular frontend and sent via REST API to Spring Boot</li>
              <li><strong>Spring adds job</strong> to <code>jobs.imageProcessing.v1</code> stream via <code>XADD</code></li>
              <li><strong>Job payload</strong> contains: <code>jobId</code>, <code>processingType</code> (OK or Error), <code>createdAt</code></li>
              <li><strong>1 in 10 jobs</strong> is marked as <code>Error</code> to simulate failures</li>
            </ol>
          </div>
          <div class="info-section">
            <h4>👷 Worker Processing (4 Virtual Threads)</h4>
            <ol>
              <li><strong>4 workers start</strong> automatically on Spring Boot startup</li>
              <li><strong>Each worker polls</strong> every 100ms using <code>read_claim_or_dlq</code> Lua function</li>
              <li><strong>Consumer Group</strong> <code>jobs-group</code> ensures no duplicate processing</li>
              <li><strong>On success (OK)</strong>: Job copied to <code>jobs.done.worker-X</code> stream + <code>XACK</code></li>
              <li><strong>On failure (Error)</strong>: No <code>XACK</code> → message stays in PENDING</li>
            </ol>
          </div>
          <div class="info-section">
            <h4>🔄 Retry & DLQ Logic</h4>
            <ol>
              <li><strong>Failed jobs</strong> remain in PENDING entries (no ACK)</li>
              <li><strong>After 100ms idle</strong>, another worker can claim the job via <code>XREADGROUP CLAIM</code></li>
              <li><strong>Max 2 delivery attempts</strong>: After 2 failures, job is routed to DLQ</li>
              <li><strong>DLQ routing</strong>: <code>XCLAIM</code> + <code>XADD</code> to DLQ + <code>XACK</code> (atomic via Lua)</li>
            </ol>
          </div>
          <div class="info-section">
            <h4>🔧 Technical Details</h4>
            <ul>
              <li><strong>Lua Function</strong>: <code>read_claim_or_dlq</code> handles read + claim + DLQ atomically</li>
              <li><strong>Virtual Threads</strong>: Java 21 lightweight threads for efficient blocking I/O</li>
              <li><strong>Streams</strong>:
                <code>jobs.imageProcessing.v1</code> (input),
                <code>jobs.done.worker-1..4</code> (output),
                <code>jobs.imageProcessing.v1:dlq</code> (failures)
              </li>
              <li><strong>WebSocket</strong>: Real-time UI updates via <code>MESSAGE_PRODUCED</code> / <code>MESSAGE_DELETED</code> events</li>
            </ul>
          </div>
          <div class="info-section">
            <h4>📈 Horizontal Scalability</h4>
            <ul>
              <li><strong>Add more workers</strong>: Consumer Groups distribute load automatically</li>
              <li><strong>No coordination needed</strong>: Redis handles message distribution</li>
              <li><strong>At-least-once delivery</strong>: Each message is processed at least once (idempotency recommended)</li>
            </ul>
          </div>
        </div>
      </div>
    </div>
  `,
  styles: [`
    .work-queue-container {
      padding: 20px;
      max-width: 1400px;
      margin: 0 auto;
    }

    .page-header {
      margin-bottom: 24px;
    }

    .page-header h2 {
      margin: 0 0 8px 0;
      color: #1e293b;
    }

    .description {
      color: #64748b;
      margin: 0;
    }

    .controls-section {
      background: white;
      border-radius: 8px;
      padding: 16px;
      margin-bottom: 24px;
      border: 1px solid #e2e8f0;
    }

    .controls-row {
      display: flex;
      align-items: center;
      gap: 16px;
      flex-wrap: wrap;
    }

    .btn {
      padding: 10px 20px;
      border: none;
      border-radius: 6px;
      font-weight: 600;
      cursor: pointer;
      transition: all 0.2s;
    }

    .btn:disabled {
      opacity: 0.5;
      cursor: not-allowed;
    }

    .btn-start {
      background: #16a34a;
      color: white;
    }

    .btn-start:hover:not(:disabled) {
      background: #15803d;
    }

    .btn-stop {
      background: #dc2626;
      color: white;
    }

    .btn-stop:hover:not(:disabled) {
      background: #b91c1c;
    }

    .btn-clear {
      background: #6b7280;
      color: white;
    }

    .btn-clear:hover:not(:disabled) {
      background: #4b5563;
    }

    .sleep-selector {
      display: flex;
      align-items: center;
      gap: 8px;
    }

    .sleep-selector label {
      color: #64748b;
      font-size: 14px;
    }

    .sleep-selector select {
      padding: 8px 12px;
      border: 1px solid #e2e8f0;
      border-radius: 6px;
      background: white;
      font-size: 14px;
    }

    .job-counter {
      color: #64748b;
      font-size: 14px;
      padding: 8px 12px;
      background: #f1f5f9;
      border-radius: 6px;
    }

    .stream-section {
      margin-bottom: 24px;
    }

    .stream-section h3 {
      margin: 0 0 12px 0;
      color: #1e293b;
      font-size: 16px;
    }

    .stream-row {
      display: grid;
      gap: 16px;
    }

    .stream-row.single {
      grid-template-columns: 1fr;
    }

    .stream-row.workers {
      grid-template-columns: repeat(4, 1fr);
    }

    @media (max-width: 1200px) {
      .stream-row.workers {
        grid-template-columns: repeat(2, 1fr);
      }
    }

    @media (max-width: 768px) {
      .stream-row.workers {
        grid-template-columns: 1fr;
      }
    }

    /* Info Box Styles */
    .info-box {
      margin-top: 24px;
      background: linear-gradient(135deg, #f8fafc 0%, #f1f5f9 100%);
      border: 1px solid #cbd5e1;
      border-radius: 12px;
      padding: 24px;
      box-shadow: 0 2px 4px rgba(0, 0, 0, 0.05);
    }

    .info-header {
      display: flex;
      align-items: center;
      gap: 12px;
      margin-bottom: 20px;
      padding-bottom: 16px;
      border-bottom: 1px solid #e2e8f0;
    }

    .info-icon {
      font-size: 24px;
    }

    .info-header h3 {
      margin: 0;
      font-size: 18px;
      font-weight: 600;
      color: #1e293b;
    }

    .info-content {
      display: grid;
      grid-template-columns: repeat(auto-fit, minmax(280px, 1fr));
      gap: 20px;
    }

    .info-section {
      background: white;
      border-radius: 8px;
      padding: 16px;
      border: 1px solid #e2e8f0;
    }

    .info-section h4 {
      margin: 0 0 12px 0;
      font-size: 14px;
      font-weight: 600;
      color: #334155;
    }

    .info-section ol,
    .info-section ul {
      margin: 0;
      padding-left: 20px;
      font-size: 13px;
      color: #475569;
      line-height: 1.8;
    }

    .info-section li {
      margin-bottom: 4px;
    }

    .info-section code {
      background: #e2e8f0;
      padding: 2px 6px;
      border-radius: 4px;
      font-family: 'Monaco', 'Menlo', monospace;
      font-size: 12px;
      color: #0f172a;
    }

    .info-section strong {
      color: #1e293b;
    }
  `]
})
export class WorkQueueComponent implements OnInit, OnDestroy {
  private http = inject(HttpClient);
  private refreshService = inject(StreamRefreshService);
  private cdr = inject(ChangeDetectorRef);
  private apiUrl = 'http://localhost:8080/api/work-queue';

  // Production state
  isProducing = false;
  jobsProduced = 0;
  private jobCounter = 0;
  private productionInterval: any = null;

  // Sleep options
  sleepOptions: SleepOption[] = [
    { label: '0.1s', value: 100 },
    { label: '0.5s', value: 500 },
    { label: '1s', value: 1000 },
    { label: '2s', value: 2000 }
  ];
  selectedSleep = 500; // Default 0.5s

  ngOnInit(): void {
    // Component initialization
  }

  ngOnDestroy(): void {
    this.stopProducing();
  }

  startProducing(): void {
    if (this.isProducing) return;

    this.isProducing = true;
    this.produceNextJob();
  }

  stopProducing(): void {
    this.isProducing = false;
    if (this.productionInterval) {
      clearTimeout(this.productionInterval);
      this.productionInterval = null;
    }
  }

  private produceNextJob(): void {
    if (!this.isProducing) return;

    this.jobCounter++;
    // 1 in 10 jobs is an Error
    const processingType = (this.jobCounter % 10 === 0) ? 'Error' : 'OK';

    this.http.post<any>(`${this.apiUrl}/produce`, null, {
      params: { processingType }
    }).subscribe({
      next: (response) => {
        if (response.success) {
          this.jobsProduced++;
          this.cdr.markForCheck();
        }
        // Schedule next job
        this.productionInterval = setTimeout(() => this.produceNextJob(), this.selectedSleep);
      },
      error: (error) => {
        console.error('Failed to produce job:', error);
        // Retry after delay
        this.productionInterval = setTimeout(() => this.produceNextJob(), this.selectedSleep);
      }
    });
  }

  clearAllStreams(): void {
    // Call dedicated endpoint that clears streams and recreates consumer group
    this.http.delete(`${this.apiUrl}/clear`).subscribe({
      next: () => {
        console.log('All work queue streams cleared');
        this.jobsProduced = 0;
        this.jobCounter = 0;
        this.cdr.markForCheck();

        // Refresh all stream viewers
        setTimeout(() => {
          this.refreshService.triggerRefresh();
        }, 200);
      },
      error: (err) => console.error('Failed to clear streams:', err)
    });
  }
}


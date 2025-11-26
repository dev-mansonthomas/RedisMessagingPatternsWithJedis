import { Injectable } from '@angular/core';
import { Subject } from 'rxjs';

/**
 * Service to coordinate stream refresh events across components.
 * Used when streams are cleared or need to be reloaded.
 */
@Injectable({
  providedIn: 'root'
})
export class StreamRefreshService {
  private refreshSubject = new Subject<void>();
  
  /**
   * Observable that emits when streams should be refreshed.
   */
  refresh$ = this.refreshSubject.asObservable();
  
  /**
   * Trigger a refresh of all stream viewers.
   */
  triggerRefresh(): void {
    console.log('StreamRefreshService: Triggering refresh for all streams');
    this.refreshSubject.next();
  }
}

